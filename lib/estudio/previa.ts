import { clipeNoTempo, duracaoTotal, type Projeto } from "@/packages/converter-core/estudio.mjs";

import { carregarFontes, desenharQuadro } from "./desenho";
import type { ArquivosDoProjeto } from "./exportar";
import { MidiasDasCamadas, type ImagemDaCamada } from "./midias-das-camadas";
import { prepararRecortes } from "./recorte";

/**
 * A prévia do estúdio numa página: um canvas na proporção do projeto, desenhado com o mesmo
 * código da exportação. Parado, cada instante pede o quadro ao <video> do clipe (seek); tocando,
 * os vídeos e as músicas tocam de verdade e o canvas acompanha a cada quadro da tela.
 */
export class PreviaDoEstudio {
  private readonly camadas = new MidiasDasCamadas();
  private readonly videos = new Map<string, HTMLVideoElement>();
  private readonly fotos = new Map<string, Promise<ImageBitmap>>();
  private readonly musicas = new Map<string, HTMLAudioElement>();
  private readonly enderecos: string[] = [];
  private pedido = 0;
  private parar: (() => void) | null = null;

  constructor(private readonly canvas: HTMLCanvasElement) {}

  /** Desenha o instante `t`. Pedidos antigos que terminam depois de um novo são descartados. */
  async desenhar(projeto: Projeto, arquivos: ArquivosDoProjeto, t: number): Promise<void> {
    const pedido = ++this.pedido;
    await carregarFontes();
    const achado = clipeNoTempo(projeto, t);
    let imagem: (CanvasImageSource & { width: number; height: number }) | null = null;
    if (achado) {
      const arquivo = arquivos.get(achado.clipe.arquivo);
      if (arquivo && achado.clipe.tipo === "imagem") imagem = await this.foto(achado.clipe.arquivo, arquivo);
      else if (arquivo) {
        const video = this.video(achado.clipe.arquivo, arquivo);
        await buscar(video, achado.tempoNoArquivo);
        imagem = comTamanho(video);
      }
    }
    const extras = await this.camadas.quadros(projeto, arquivos, t);
    if (pedido !== this.pedido) return;
    const recortes = await prepararRecortes(projeto, t, imagem, extras, () => pedido === this.pedido);
    if (pedido !== this.pedido) return;
    this.pintar(projeto, t, recortes.principal, achado?.clipe.ajuste ?? null, recortes.extras);
  }

  /** Toca a partir de `de`; `aoAvancar` recebe o instante a cada quadro. Devolve a função de parar. */
  tocar(projeto: Projeto, arquivos: ArquivosDoProjeto, de: number, aoAvancar: (t: number) => void, aoAcabar: () => void, aoFalhar: (erro: unknown) => void): () => void {
    this.parar?.();
    this.pedido++;
    const total = duracaoTotal(projeto);
    const comeco = performance.now() - de * 1000;
    let atual: HTMLVideoElement | null = null;
    let indiceAtual = -1;
    let quadro = 0;
    let vivo = true;

    const musicas = projeto.audios.map((audio) => {
      const arquivo = arquivos.get(audio.arquivo);
      return arquivo ? { audio, elemento: this.musica(audio.arquivo, arquivo), tocando: false } : null;
    }).filter((m) => m !== null);

    const passo = async () => {
      if (!vivo) return;
      const t = (performance.now() - comeco) / 1000;
      if (t >= total) {
        encerrar();
        aoAvancar(total);
        aoAcabar();
        return;
      }
      const achado = clipeNoTempo(projeto, t);
      let imagem: (CanvasImageSource & { width: number; height: number }) | null = null;
      if (achado) {
        const arquivo = arquivos.get(achado.clipe.arquivo);
        if (achado.indice !== indiceAtual) {
          atual?.pause();
          atual = null;
          indiceAtual = achado.indice;
          if (arquivo && achado.clipe.tipo === "video") {
            atual = this.video(achado.clipe.arquivo, arquivo);
            atual.currentTime = achado.tempoNoArquivo;
            atual.muted = achado.clipe.mudo === true;
            atual.volume = Math.min(1, achado.clipe.volume ?? 1);
            void atual.play().catch(() => {});
          }
        }
        if (achado.clipe.tipo === "imagem") {
          const pronta = this.fotosProntas.get(achado.clipe.arquivo);
          if (pronta) imagem = pronta;
          else if (arquivo) void this.foto(achado.clipe.arquivo, arquivo);
        } else if (atual) imagem = comTamanho(atual);
      }
      for (const m of musicas) {
        const { audio, elemento } = m;
        const entrada = audio.entrada ?? 0;
        const dentro = t >= entrada && t < entrada + (audio.fim - audio.inicio);
        if (dentro && !m.tocando) {
          elemento.currentTime = audio.inicio + (t - entrada);
          elemento.volume = Math.min(1, audio.volume ?? 1);
          void elemento.play().catch(() => {});
          m.tocando = true;
        } else if (!dentro && m.tocando) {
          elemento.pause();
          m.tocando = false;
        }
      }
      let extras: Map<string, ImagemDaCamada>;
      try { extras = await this.camadas.quadros(projeto, arquivos, t); }
      catch (erro) { if (vivo) aoFalhar(erro); encerrar(); aoAcabar(); return; }
      if (!vivo) return;
      let recortes;
      try { recortes = await prepararRecortes(projeto, t, imagem, extras, () => vivo); }
      catch (erro) { if (vivo) aoFalhar(erro); encerrar(); aoAcabar(); return; }
      if (!vivo) return;
      this.pintar(projeto, t, recortes.principal, achado?.clipe.ajuste ?? null, recortes.extras);
      aoAvancar(t);
      quadro = requestAnimationFrame(() => { void passo(); });
    };

    const encerrar = () => {
      vivo = false;
      cancelAnimationFrame(quadro);
      atual?.pause();
      for (const m of musicas) m.elemento.pause();
      if (this.parar === encerrar) this.parar = null;
    };
    this.parar = encerrar;
    void carregarFontes().then(() => {
      if (vivo) quadro = requestAnimationFrame(() => { void passo(); });
    }).catch(erro => { if (vivo) { aoFalhar(erro); encerrar(); aoAcabar(); } });
    return encerrar;
  }

  /** Solta os vídeos, as fotos e os endereços temporários. */
  liberar(): void {
    this.pedido++;
    this.camadas.liberar();
    this.parar?.();
    for (const video of this.videos.values()) video.removeAttribute("src");
    for (const musica of this.musicas.values()) musica.removeAttribute("src");
    for (const foto of this.fotosProntas.values()) foto.close();
    for (const endereco of this.enderecos) URL.revokeObjectURL(endereco);
    this.videos.clear();
    this.musicas.clear();
    this.fotos.clear();
    this.fotosProntas.clear();
    this.enderecos.length = 0;
  }

  private readonly fotosProntas = new Map<string, ImageBitmap>();

  private pintar(projeto: Projeto, t: number, imagem: (CanvasImageSource & { width: number; height: number }) | null, ajuste: Projeto["clipes"][number]["ajuste"] | null, extras: ReadonlyMap<string, ImagemDaCamada>) {
    const ctx = this.canvas.getContext("2d");
    if (!ctx) return;
    const escala = this.canvas.width / projeto.largura;
    ctx.save();
    ctx.setTransform(escala, 0, 0, this.canvas.height / projeto.altura, 0, 0);
    desenharQuadro(ctx, projeto, t, imagem, ajuste, extras);
    ctx.restore();
  }

  private video(chave: string, arquivo: File): HTMLVideoElement {
    let video = this.videos.get(chave);
    if (!video) {
      video = document.createElement("video");
      video.preload = "auto";
      video.playsInline = true;
      video.src = this.endereco(arquivo);
      this.videos.set(chave, video);
    }
    return video;
  }

  private musica(chave: string, arquivo: File): HTMLAudioElement {
    let musica = this.musicas.get(chave);
    if (!musica) {
      musica = new Audio(this.endereco(arquivo));
      musica.preload = "auto";
      this.musicas.set(chave, musica);
    }
    return musica;
  }

  private foto(chave: string, arquivo: File): Promise<ImageBitmap> {
    let promessa = this.fotos.get(chave);
    if (!promessa) {
      promessa = createImageBitmap(arquivo, { imageOrientation: "from-image" }).then((foto) => {
        this.fotosProntas.set(chave, foto);
        return foto;
      });
      this.fotos.set(chave, promessa);
    }
    return promessa;
  }

  private endereco(arquivo: File): string {
    const endereco = URL.createObjectURL(arquivo);
    this.enderecos.push(endereco);
    return endereco;
  }
}

/** Leva o vídeo a um instante e espera o quadro chegar (com limite, para não travar a prévia). */
function buscar(video: HTMLVideoElement, segundos: number): Promise<void> {
  return new Promise((resolva) => {
    const pronto = () => {
      clearTimeout(limite);
      video.removeEventListener("seeked", pronto);
      video.removeEventListener("loadeddata", aoCarregar);
      resolva();
    };
    const aoCarregar = () => {
      video.currentTime = segundos;
    };
    const limite = setTimeout(pronto, 3000);
    video.addEventListener("seeked", pronto);
    if (video.readyState >= 1) video.currentTime = segundos;
    else video.addEventListener("loadeddata", aoCarregar, { once: true });
  });
}

/** O <video> como imagem com largura e altura (as do quadro, não as do elemento). */
function comTamanho(video: HTMLVideoElement): CanvasImageSource & { width: number; height: number } {
  return Object.assign(video, { width: video.videoWidth || 1, height: video.videoHeight || 1 }) as HTMLVideoElement & { width: number; height: number };
}
