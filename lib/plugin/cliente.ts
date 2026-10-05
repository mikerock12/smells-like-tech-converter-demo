import {
  CABECALHO,
  ENDERECO_DO_PLUGIN,
  PROTOCOLO_ESPERADO,
  TEMPO_DE_BUSCA_MS,
  type Apresentacao,
  type ArquivoEscolhido,
  type EstadoDoPlugin,
  type Trabalho,
} from "./protocolo";

/**
 * Conversa do site com o plugin.
 *
 * A regra que governa este arquivo: **quem não tem o plugin não pode perceber que ele
 * existe**. A busca falha em silêncio, rápido, e o site segue funcionando com o
 * conversor do navegador. Nada de erro no console, nada de aviso vermelho.
 */
export class PluginClient {
  constructor(private readonly base: string = ENDERECO_DO_PLUGIN) {}

  /**
   * Procura o plugin nesta máquina.
   *
   * Sem plugin instalado, a conexão é recusada de imediato e cai no catch — por isso o
   * tempo limite é curto: ninguém deve esperar por algo que provavelmente não está lá.
   */
  async procurar(): Promise<EstadoDoPlugin> {
    try {
      const resposta = await this.buscar("/v1/ola", { method: "GET" }, TEMPO_DE_BUSCA_MS);
      if (!resposta.ok) return { situacao: "ausente" };

      const apresentacao = (await resposta.json()) as Apresentacao;
      if (apresentacao.papel !== "plugin") return { situacao: "ausente" };
      if (apresentacao.protocolo !== PROTOCOLO_ESPERADO) {
        return { situacao: "desatualizado", protocolo: apresentacao.protocolo };
      }

      return { situacao: "ligado", apresentacao };
    } catch {
      return { situacao: "ausente" };
    }
  }

  /** Abre a caixa de seleção do Windows. Devolve null quando a pessoa desiste. */
  async escolherArquivo(): Promise<ArquivoEscolhido | null> {
    const resposta = await this.buscar("/v1/escolher", { method: "POST" });
    if (resposta.status === 204) return null;
    if (!resposta.ok) throw await this.erro(resposta);
    return (await resposta.json()) as ArquivoEscolhido;
  }

  /**
   * Converte um arquivo que já está no disco da máquina.
   *
   * É o caminho rápido: o arquivo não passa pelo navegador, então um vídeo de dez
   * gigabytes entra na fila instantaneamente.
   */
  async converterCaminho(
    caminho: string,
    opcoes: Record<string, unknown>,
    nomeDeSaida?: string,
  ): Promise<Trabalho> {
    const resposta = await this.buscar("/v1/trabalhos/caminho", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ caminho, opcoes, nomeDeSaida }),
    });

    if (!resposta.ok) throw await this.erro(resposta);
    return (await resposta.json()) as Trabalho;
  }

  /** Converte um arquivo que o usuário arrastou para a página. */
  async converterArquivo(
    arquivo: File,
    opcoes: Record<string, unknown>,
    nomeDeSaida?: string,
  ): Promise<Trabalho> {
    const corpo = new FormData();
    corpo.append("arquivo", arquivo, arquivo.name);
    corpo.append("opcoes", JSON.stringify(opcoes));
    if (nomeDeSaida) corpo.append("nomeDeSaida", nomeDeSaida);

    const resposta = await this.buscar("/v1/trabalhos", { method: "POST", body: corpo });
    if (!resposta.ok) throw await this.erro(resposta);
    return (await resposta.json()) as Trabalho;
  }

  async consultar(id: string): Promise<Trabalho> {
    const resposta = await this.buscar(`/v1/trabalhos/${id}`, { method: "GET" });
    if (!resposta.ok) throw await this.erro(resposta);
    return (await resposta.json()) as Trabalho;
  }

  async cancelar(id: string): Promise<void> {
    await this.buscar(`/v1/trabalhos/${id}/cancelar`, { method: "POST" });
  }

  async abrirPasta(): Promise<void> {
    await this.buscar("/v1/abrir-pasta", { method: "POST" });
  }

  /** Endereço para baixar um arquivo produzido. */
  enderecoDoArquivo(id: string, indice: number): string {
    return `${this.base}/v1/trabalhos/${id}/arquivo/${indice}`;
  }

  /**
   * Acompanha um trabalho até o fim.
   *
   * Usa fluxo de eventos quando dá, porque é assim que a barra do site anda junto com a
   * do FFmpeg. Se o navegador não colaborar, cai para consulta periódica — mais pobre,
   * mas continua funcionando.
   */
  acompanhar(id: string, aoMudar: (trabalho: Trabalho) => void): () => void {
    let encerrado = false;

    const fonte = new EventSource(`${this.base}/v1/trabalhos/${id}/eventos`);

    fonte.onmessage = (evento) => {
      try {
        const trabalho = JSON.parse(evento.data) as Trabalho;
        aoMudar(trabalho);
        if (trabalho.encerrado) {
          encerrado = true;
          fonte.close();
        }
      } catch {
        // Linha malformada: ignora e espera a próxima.
      }
    };

    fonte.onerror = () => {
      if (encerrado) return;
      fonte.close();
      void this.consultarAteOFim(id, aoMudar, () => encerrado);
    };

    return () => {
      encerrado = true;
      fonte.close();
    };
  }

  private async consultarAteOFim(
    id: string,
    aoMudar: (trabalho: Trabalho) => void,
    desistiu: () => boolean,
  ): Promise<void> {
    while (!desistiu()) {
      await new Promise((resolva) => setTimeout(resolva, 1_000));
      try {
        const trabalho = await this.consultar(id);
        aoMudar(trabalho);
        if (trabalho.encerrado) return;
      } catch {
        return;
      }
    }
  }

  private buscar(caminho: string, inicial: RequestInit, tempoLimiteMs?: number): Promise<Response> {
    const cabecalhos = new Headers(inicial.headers);
    cabecalhos.set(CABECALHO, "1");

    return fetch(`${this.base}${caminho}`, {
      ...inicial,
      headers: cabecalhos,
      mode: "cors",
      // Sem cookies: o plugin não tem sessão nem precisa saber quem é o usuário.
      credentials: "omit",
      cache: "no-store",
      signal: tempoLimiteMs ? AbortSignal.timeout(tempoLimiteMs) : inicial.signal,
    });
  }

  private async erro(resposta: Response): Promise<Error> {
    try {
      const corpo = (await resposta.json()) as { mensagem?: string };
      return new Error(corpo.mensagem ?? "O plugin recusou o pedido.");
    } catch {
      return new Error(`O plugin respondeu ${resposta.status}.`);
    }
  }
}
