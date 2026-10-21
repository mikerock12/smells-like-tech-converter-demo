import { ALL_FORMATS, BlobSource, CanvasSink, Input } from "mediabunny";
import { lerGif, quadrosDoGif, duracaoDoGif, type Gif } from "@/packages/converter-core/gif.mjs";
import { camadasNoTempo, type Camada, type Projeto } from "@/packages/converter-core/estudio.mjs";
import type { ArquivosDoProjeto } from "./exportar";

export type ImagemDaCamada = CanvasImageSource & { readonly width: number; readonly height: number };
type Fonte = { quadro(tempo: number, repetir: boolean): Promise<ImagemDaCamada>; liberar(): void };

/** Guarda decodificadores, não todos os quadros. GIFs curtos repetem independentemente. */
export class MidiasDasCamadas {
  private readonly fontes = new Map<string, Promise<Fonte>>();
  private encerrado = false;
  async quadros(projeto: Projeto, arquivos: ArquivosDoProjeto, t: number): Promise<Map<string, ImagemDaCamada>> {
    const resultado = new Map<string, ImagemDaCamada>();
    for (const camada of camadasNoTempo(projeto, t)) {
      if (this.encerrado) return resultado;
      const chave = camada.id + ":" + camada.arquivo + ":" + camada.tipo;
      let promessa = this.fontes.get(chave);
      if (!promessa) {
        const arquivo = arquivos.get(camada.arquivo);
        if (!arquivo) throw new Error("Uma mídia da camada não está mais aberta. Adicione-a novamente.");
        promessa = abrir(arquivo, camada.tipo);
        this.fontes.set(chave, promessa);
      }
      const fonte = await promessa;
      if (this.encerrado) return resultado;
      resultado.set(camada.id, await fonte.quadro(Math.max(0, t - camada.inicio + (camada.deslocamento ?? 0)), camada.repetir !== false));
    }
    return resultado;
  }
  liberar(): void {
    this.encerrado = true;
    for (const promessa of this.fontes.values()) void promessa.then(f => f.liberar()).catch(() => {});
    this.fontes.clear();
  }
}

async function abrir(arquivo: File, tipo: Camada["tipo"]): Promise<Fonte> {
  if (tipo === "gif" || arquivo.type === "image/gif" || /\.gif$/i.test(arquivo.name)) return fonteGif(lerGif(await arquivo.arrayBuffer()));
  if (tipo === "imagem") {
    const imagem = await createImageBitmap(arquivo, { imageOrientation: "from-image" });
    return { quadro: async () => imagem, liberar: () => imagem.close() };
  }
  const entrada = new Input({ formats: ALL_FORMATS, source: new BlobSource(arquivo) });
  try {
    const faixa = await entrada.getPrimaryVideoTrack();
    if (!faixa || !(await faixa.canDecode())) throw new Error("Este navegador não lê um vídeo das camadas. Use o plugin.");
    const duracao = await entrada.computeDuration();
    const sink = new CanvasSink(faixa, { poolSize: 1 });
    return {
      quadro: async (tempo, repetir) => {
        const t = duracao > 0 ? (repetir ? tempo % duracao : Math.min(tempo, Math.max(0, duracao - .001))) : tempo;
        const quadro = await sink.getCanvas(t);
        if (!quadro) throw new Error("Não foi possível ler um quadro da camada.");
        return quadro.canvas as ImagemDaCamada;
      },
      liberar: () => entrada.dispose(),
    };
  } catch (erro) { entrada.dispose(); throw erro; }
}

function fonteGif(gif: Gif): Fonte {
  const tela = new OffscreenCanvas(gif.largura, gif.altura);
  const ctx = tela.getContext("2d")!;
  const duracao = duracaoDoGif(gif);
  let gerador = quadrosDoGif(gif);
  let inicio = 0, fim = 0, anterior = -1;
  return {
    quadro: async (tempo, repetir) => {
      const ms = duracao > 0 ? (repetir ? tempo * 1000 % duracao : Math.min(tempo * 1000, duracao - 1)) : 0;
      if (ms < anterior) { gerador = quadrosDoGif(gif); inicio = 0; fim = 0; }
      while (fim <= ms) {
        const proximo = gerador.next();
        if (proximo.done) break;
        inicio = fim; fim = inicio + proximo.value.atraso;
        ctx.putImageData(new ImageData(new Uint8ClampedArray(proximo.value.rgba), gif.largura, gif.altura), 0, 0);
      }
      anterior = ms;
      return tela;
    },
    liberar: () => { gerador.return(undefined); tela.width = 1; tela.height = 1; },
  };
}
