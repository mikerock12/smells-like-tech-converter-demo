/**
 * O que o plugin fala.
 *
 * Este arquivo é o espelho de desktop/src/SmellsLikeTech.Converter.Bridge/Contracts.
 * Se um lado mudar, o outro muda junto — e o número do protocolo sobe.
 */

/** Versão de contrato que este site sabe conversar. */
export const PROTOCOLO_ESPERADO = 1;

/** Onde o plugin escuta. Fixo de propósito: o site precisa saber onde procurar. */
export const ENDERECO_DO_PLUGIN = "http://127.0.0.1:5199";

/**
 * Cabeçalho exigido nos pedidos que mudam algo.
 *
 * Não é senha: serve para obrigar o navegador a pedir permissão antes (verificação
 * prévia), que é onde o plugin confere a origem. Um POST de formulário passaria direto
 * sem isso.
 */
export const CABECALHO = "X-Smells-Like-Tech";

/** Quanto tempo esperar antes de concluir que não existe plugin nesta máquina. */
export const TEMPO_DE_BUSCA_MS = 1_200;

export type Apresentacao = {
  produto: string;
  papel: string;
  versao: string;
  protocolo: number;
  maquina: Maquina;
  operacoes: Operacao[];
  pastaDeSaida: string;
};

export type Maquina = {
  processador: string;
  nucleos: number;
  placaDeVideo: string;
  aceleradoresDeVideo: string[];
  ffmpegPresente: boolean;
  modelosDeTranscricao: string[];
  vozesInstaladas: string[];
  /** IDs técnicos a partir do plugin 0.4.0; os nomes acima continuam compatíveis. */
  idsDasVozes?: string[];
};

export type Operacao = {
  id: string;
  titulo: string;
  descricao: string;
  entradas: string[];
  saidas: string[];
  disponivel: boolean;
  impedimento?: string;
};

export type Trabalho = {
  id: string;
  operacao: string;
  nome: string;
  estado: string;
  etapa: string;
  encerrado: boolean;
  progresso?: number;
  mensagem?: string;
  erro?: string;
  codigoDoErro?: string;
  segundosRestantes?: number;
  velocidade?: number;
  bytesDeEntrada: number;
  bytesDeSaida: number;
  arquivos: ArquivoProduzido[];
};

export type ArquivoProduzido = {
  indice: number;
  nome: string;
  bytes: number;
};

export type ArquivoEscolhido = {
  caminho: string;
  nome: string;
  bytes: number;
};

/** Como o site enxerga o plugin em cada momento. */
export type EstadoDoPlugin =
  | { situacao: "procurando" }
  | { situacao: "ausente" }
  | { situacao: "desatualizado"; protocolo: number }
  | { situacao: "ligado"; apresentacao: Apresentacao };

/**
 * Descreve em uma linha o que a máquina traz de vantagem.
 *
 * O plugin existe para usar o computador de quem está do outro lado; dizer qual
 * processador e qual placa foram encontrados é o que torna isso concreto.
 */
export function resumirMaquina(maquina: Maquina): string {
  const partes = [maquina.processador, `${maquina.nucleos} núcleos`];
  if (maquina.aceleradoresDeVideo.length > 0) {
    partes.push(`${maquina.placaDeVideo} (aceleração por hardware)`);
  } else if (maquina.placaDeVideo !== "não identificada") {
    partes.push(maquina.placaDeVideo);
  }
  return partes.join(" · ");
}

/** Só as operações que esta máquina realmente consegue executar agora. */
export function operacoesDisponiveis(apresentacao: Apresentacao): Operacao[] {
  return apresentacao.operacoes.filter((operacao) => operacao.disponivel);
}

const EXTENSOES: Record<string, string> = {
  mp4: "video", mkv: "video", mov: "video", avi: "video", webm: "video", mpeg: "video",
  mpg: "video", ts: "video", m4v: "video", wmv: "video", flv: "video",
  mp3: "audio", wav: "audio", aac: "audio", flac: "audio", ogg: "audio", m4a: "audio",
  opus: "audio", wma: "audio",
  jpg: "image", jpeg: "image", png: "image", webp: "image", avif: "image", gif: "image",
  bmp: "image", ico: "image", heic: "image", heif: "image", tif: "image", tiff: "image",
  pdf: "pdf",
  txt: "text", md: "text",
};

/** Que tipo de mídia é este arquivo, pelo nome. O plugin confere de novo pelo conteúdo. */
export function tipoDoArquivo(nome: string): string | null {
  const extensao = nome.includes(".") ? nome.split(".").pop()?.toLowerCase() : null;
  return extensao ? EXTENSOES[extensao] ?? null : null;
}

/** Operações que fazem sentido para este arquivo, na ordem em que o plugin as descreve. */
export function operacoesPara(apresentacao: Apresentacao, nomeDoArquivo: string): Operacao[] {
  const tipo = tipoDoArquivo(nomeDoArquivo);
  if (!tipo) return [];
  return apresentacao.operacoes.filter(
    (operacao) => operacao.disponivel && operacao.entradas.includes(tipo),
  );
}

/** Tempo restante em texto curto, do jeito que se fala. */
export function tempoRestante(segundos: number | undefined): string | null {
  if (segundos === undefined || !Number.isFinite(segundos) || segundos <= 0) return null;
  if (segundos < 60) return `faltam ${Math.round(segundos)}s`;
  const minutos = Math.floor(segundos / 60);
  const resto = Math.round(segundos % 60);
  if (minutos < 60) return `faltam ${minutos}min${resto > 0 ? ` ${resto}s` : ""}`;
  const horas = Math.floor(minutos / 60);
  return `faltam ${horas}h ${minutos % 60}min`;
}
