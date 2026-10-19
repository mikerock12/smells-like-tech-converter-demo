import {
  ALL_FORMATS,
  AudioBufferSource,
  BlobSource,
  BufferTarget,
  CanvasSink,
  CanvasSource,
  Input,
  Mp4OutputFormat,
  Output,
  QUALITY_HIGH,
  getFirstEncodableAudioCodec,
  getFirstEncodableVideoCodec,
} from "mediabunny";
import { GIFEncoder, applyPalette, quantize } from "gifenc";

import {
  FPS_DO_GIF,
  clipeNoTempo,
  duracaoDoClipe,
  duracaoTotal,
  problemas,
  segmentos,
  tamanhoDoGif,
  type Clipe,
  type Projeto,
} from "@/packages/converter-core/estudio.mjs";

import { codificarMp3 } from "@/lib/converter/motores/audio-codificar";

import { carregarFontes, desenharQuadro } from "./desenho";

/**
 * Exporta um projeto do estúdio no próprio navegador: WebCodecs pelo mediabunny para o vídeo,
 * Web Audio para a mistura do som, gifenc para o GIF e lamejs para o MP3. Nada sai do
 * computador. Os quadros de cada clipe são lidos em sequência (CanvasSink.canvases), que
 * funciona até em vídeos sem índice. A transcrição (saída texto) só existe pelo plugin.
 */

export type ArquivosDoProjeto = ReadonlyMap<string, File>;

export type Exportado = { nome: string; mime: string; bytes: ArrayBuffer };

export type Andamento = (etapa: string, fracao: number) => void;

const TAXA = 48_000;

export class ExportacaoCancelada extends Error {}

export async function exportarNoNavegador(
  projeto: Projeto,
  arquivos: ArquivosDoProjeto,
  andamento: Andamento,
  cancelado: () => boolean,
): Promise<Exportado> {
  const erros = problemas(projeto);
  if (erros.length) throw new Error(erros.join(" "));
  if (projeto.saida === "texto") throw new Error("A transcrição do estúdio é feita pelo plugin.");
  await carregarFontes();
  const nome = `estudio-${projeto.destino}-${carimbo()}`;

  switch (projeto.saida) {
    case "imagem": {
      andamento("Desenhando a imagem", 0.2);
      const tela = new OffscreenCanvas(projeto.largura, projeto.altura);
      const ctx = contexto(tela);
      const achado = clipeNoTempo(projeto, projeto.quadro);
      const imagem = achado ? await quadroDoClipe(achado.clipe, achado.tempoNoArquivo, arquivos) : null;
      desenharQuadro(ctx, projeto, projeto.quadro, imagem, achado?.clipe.ajuste ?? null);
      const blob = await tela.convertToBlob({ type: "image/png" });
      return { nome: `${nome}.png`, mime: "image/png", bytes: await blob.arrayBuffer() };
    }
    case "audio": {
      const mistura = await misturarAudio(projeto, arquivos, (f) => andamento("Mixando o áudio", f * 0.5));
      const canais = [mistura.getChannelData(0), mistura.getChannelData(1)];
      const mp3 = codificarMp3(canais, mistura.sampleRate, 192, (f) => andamento("Gravando o MP3", 0.5 + f * 0.5), cancelado);
      if (!mp3) throw new ExportacaoCancelada();
      return { nome: `${nome}.mp3`, mime: "audio/mpeg", bytes: mp3 };
    }
    case "gif":
      return { nome: `${nome}.gif`, mime: "image/gif", bytes: await gif(projeto, arquivos, andamento, cancelado) };
    default:
      return { nome: `${nome}.mp4`, mime: "video/mp4", bytes: await video(projeto, arquivos, andamento, cancelado) };
  }
}

// ==================== vídeo ====================

async function video(projeto: Projeto, arquivos: ArquivosDoProjeto, andamento: Andamento, cancelado: () => boolean): Promise<ArrayBuffer> {
  const { largura, altura, fps } = projeto;
  const formato = new Mp4OutputFormat({ fastStart: "in-memory" });
  const codec = await getFirstEncodableVideoCodec(["avc", ...formato.getSupportedVideoCodecs().filter((c) => c !== "avc")], { width: largura, height: altura });
  if (!codec) throw new Error(`Este navegador não grava vídeo em ${largura}×${altura}. Use o plugin, ou um Chrome/Edge atualizado.`);
  const codecDoAudio = await getFirstEncodableAudioCodec(["aac", "opus"], { numberOfChannels: 2, sampleRate: TAXA });

  const mistura = codecDoAudio ? await misturarAudio(projeto, arquivos, (f) => andamento("Mixando o áudio", f * 0.1)) : null;
  const tela = new OffscreenCanvas(largura, altura);
  const ctx = contexto(tela);
  const alvo = new BufferTarget();
  const saida = new Output({ format: formato, target: alvo });
  const fonte = new CanvasSource(tela, { codec, bitrate: QUALITY_HIGH });
  saida.addVideoTrack(fonte, { frameRate: fps });
  const som = codecDoAudio && mistura ? new AudioBufferSource({ codec: codecDoAudio, quality: QUALITY_HIGH }) : null;
  if (som) saida.addAudioTrack(som);
  await saida.start();
  try {
    if (som && mistura) await som.add(mistura);
    const total = Math.max(1, Math.round(duracaoTotal(projeto) * fps));
    await percorrerQuadros(projeto, arquivos, async (indice, t, imagem, ajuste) => {
      if (cancelado()) throw new ExportacaoCancelada();
      desenharQuadro(ctx, projeto, t, imagem, ajuste);
      await fonte.add(indice / fps, 1 / fps);
      andamento("Montando o vídeo", 0.1 + 0.9 * (indice + 1) / total);
    });
    await saida.finalize();
  } catch (erro) {
    await saida.cancel().catch(() => {});
    throw erro;
  }
  if (!alvo.buffer) throw new Error("A exportação terminou sem produzir arquivo.");
  return alvo.buffer;
}

// ==================== GIF ====================

async function gif(projeto: Projeto, arquivos: ArquivosDoProjeto, andamento: Andamento, cancelado: () => boolean): Promise<ArrayBuffer> {
  const { largura, altura } = tamanhoDoGif(projeto.largura, projeto.altura);
  const tela = new OffscreenCanvas(projeto.largura, projeto.altura);
  const ctx = contexto(tela);
  const pequena = new OffscreenCanvas(largura, altura);
  const pincel = pequena.getContext("2d", { willReadFrequently: true });
  if (!pincel) throw new Error("O navegador não disponibilizou o canvas.");
  const codificador = GIFEncoder();
  const doGif = { ...projeto, fps: FPS_DO_GIF };
  const total = Math.max(1, Math.round(duracaoTotal(projeto) * FPS_DO_GIF));
  await percorrerQuadros(doGif, arquivos, async (indice, t, imagem, ajuste) => {
    if (cancelado()) throw new ExportacaoCancelada();
    desenharQuadro(ctx, projeto, t, imagem, ajuste);
    pincel.drawImage(tela, 0, 0, largura, altura);
    const { data } = pincel.getImageData(0, 0, largura, altura);
    const paleta = quantize(data, 256);
    // O GIF guarda o atraso em centésimos: alternar 6 e 7 mantém o total igual ao do vídeo.
    const atraso = (Math.round(((indice + 1) * 100) / FPS_DO_GIF) - Math.round((indice * 100) / FPS_DO_GIF)) * 10;
    codificador.writeFrame(applyPalette(data, paleta), largura, altura, { palette: paleta, delay: atraso });
    andamento("Montando o GIF", (indice + 1) / total);
  });
  codificador.finish();
  return codificador.bytes().slice().buffer;
}

// ==================== quadros ====================

type Imagem = CanvasImageSource & { readonly width: number; readonly height: number };
type AoQuadro = (indice: number, t: number, imagem: Imagem | null, ajuste: Clipe["ajuste"]) => Promise<void>;

/**
 * Chama `aoQuadro` para cada quadro da saída, em ordem, com a imagem do clipe naquele
 * instante. Fotos são abertas uma vez (já endireitadas pelo navegador); vídeos são lidos em
 * sequência do início ao fim do trecho.
 */
async function percorrerQuadros(projeto: Projeto, arquivos: ArquivosDoProjeto, aoQuadro: AoQuadro): Promise<void> {
  const { fps } = projeto;
  for (const segmento of segmentos(projeto)) {
    const clipe = projeto.clipes[segmento.indice];
    const primeiro = Math.round(segmento.inicio * fps);
    const ultimo = Math.round(segmento.fim * fps);
    if (ultimo <= primeiro) continue;
    const arquivo = arquivoDe(arquivos, clipe.arquivo);

    if (clipe.tipo === "imagem") {
      const foto = await createImageBitmap(arquivo, { imageOrientation: "from-image" });
      try {
        for (let i = primeiro; i < ultimo; i += 1) await aoQuadro(i, i / fps, foto, clipe.ajuste);
      } finally {
        foto.close();
      }
      continue;
    }

    const entrada = new Input({ formats: ALL_FORMATS, source: new BlobSource(arquivo) });
    try {
      const faixa = await entrada.getPrimaryVideoTrack();
      if (!faixa || !(await faixa.canDecode())) throw new Error(`Este navegador não lê o vídeo “${arquivo.name}”. Use o plugin.`);
      const inicio = clipe.inicio ?? 0;
      const fim = inicio + duracaoDoClipe(clipe);
      const sink = new CanvasSink(faixa, { poolSize: 2 });
      let i = primeiro;
      let ultimoQuadro: Imagem | null = null;
      for await (const embrulho of sink.canvases(inicio, fim)) {
        ultimoQuadro = embrulho.canvas as Imagem;
        // Um quadro do arquivo cobre os quadros da saída até o fim da sua duração.
        while (i < ultimo && inicio + (i - primeiro) / fps < embrulho.timestamp + embrulho.duration) {
          await aoQuadro(i, i / fps, ultimoQuadro, clipe.ajuste);
          i += 1;
        }
        if (i >= ultimo) break;
      }
      // O arquivo acabou antes do trecho: o último quadro fica na tela até o fim do clipe.
      for (; i < ultimo; i += 1) await aoQuadro(i, i / fps, ultimoQuadro, clipe.ajuste);
    } finally {
      entrada.dispose();
    }
  }
}

/** Um quadro de um clipe num ponto do arquivo (para a imagem e para a prévia). */
export async function quadroDoClipe(clipe: Clipe, tempoNoArquivo: number, arquivos: ArquivosDoProjeto): Promise<Imagem | null> {
  const arquivo = arquivoDe(arquivos, clipe.arquivo);
  if (clipe.tipo === "imagem") return createImageBitmap(arquivo, { imageOrientation: "from-image" });
  const entrada = new Input({ formats: ALL_FORMATS, source: new BlobSource(arquivo) });
  try {
    const faixa = await entrada.getPrimaryVideoTrack();
    if (!faixa || !(await faixa.canDecode())) return null;
    const sink = new CanvasSink(faixa, { poolSize: 1 });
    const direto = await sink.getCanvas(tempoNoArquivo).catch(() => null);
    if (direto) return copiar(direto.canvas as Imagem);
    for await (const embrulho of sink.canvases(tempoNoArquivo, tempoNoArquivo + 1)) return copiar(embrulho.canvas as Imagem);
    return null;
  } finally {
    entrada.dispose();
  }
}

function copiar(imagem: Imagem): OffscreenCanvas {
  const copia = new OffscreenCanvas(imagem.width, imagem.height);
  copia.getContext("2d")?.drawImage(imagem, 0, 0);
  return copia;
}

// ==================== áudio ====================

/**
 * A mistura do som do projeto, em 48 kHz estéreo: o som de cada vídeo no lugar dele (com
 * volume, ou mudo) e as músicas com trecho, entrada, volume e fades — a mesma conta da
 * receita do FFmpeg, feita pelo Web Audio.
 */
export async function misturarAudio(projeto: Projeto, arquivos: ArquivosDoProjeto, andamento: (fracao: number) => void): Promise<AudioBuffer> {
  const total = Math.max(0.1, duracaoTotal(projeto));
  const mesa = new OfflineAudioContext(2, Math.ceil(total * TAXA), TAXA);
  const decodificados = new Map<File, AudioBuffer | null>();
  const decodificar = async (arquivo: File): Promise<AudioBuffer | null> => {
    if (!decodificados.has(arquivo)) {
      decodificados.set(arquivo, await mesa.decodeAudioData(await arquivo.arrayBuffer()).catch(() => null));
    }
    return decodificados.get(arquivo) ?? null;
  };

  const lista = segmentos(projeto);
  for (const [n, segmento] of lista.entries()) {
    const clipe = projeto.clipes[segmento.indice];
    andamento((n / Math.max(1, lista.length + projeto.audios.length)) * 0.8);
    if (clipe.tipo !== "video" || clipe.mudo || (clipe.volume ?? 1) <= 0) continue;
    const buffer = await decodificar(arquivoDe(arquivos, clipe.arquivo));
    if (!buffer) continue;
    const fonte = mesa.createBufferSource();
    fonte.buffer = buffer;
    const ganho = mesa.createGain();
    ganho.gain.value = clipe.volume ?? 1;
    fonte.connect(ganho).connect(mesa.destination);
    fonte.start(segmento.inicio, clipe.inicio ?? 0, segmento.duracao);
  }

  for (const [n, musica] of projeto.audios.entries()) {
    andamento(((lista.length + n) / Math.max(1, lista.length + projeto.audios.length)) * 0.8);
    const buffer = await decodificar(arquivoDe(arquivos, musica.arquivo));
    if (!buffer) throw new Error("Este navegador não lê uma das músicas. Tente MP3 ou M4A, ou use o plugin.");
    const comprimento = musica.fim - musica.inicio;
    const entrada = musica.entrada ?? 0;
    const volume = musica.volume ?? 1;
    const fonte = mesa.createBufferSource();
    fonte.buffer = buffer;
    const ganho = mesa.createGain();
    const fadeEntrada = musica.fadeEntrada ?? 0;
    const fadeSaida = musica.fadeSaida ?? 0;
    ganho.gain.setValueAtTime(fadeEntrada > 0 ? 0 : volume, entrada);
    if (fadeEntrada > 0) ganho.gain.linearRampToValueAtTime(volume, entrada + fadeEntrada);
    if (fadeSaida > 0) {
      ganho.gain.setValueAtTime(volume, entrada + Math.max(0, comprimento - fadeSaida));
      ganho.gain.linearRampToValueAtTime(0, entrada + comprimento);
    }
    fonte.connect(ganho).connect(mesa.destination);
    fonte.start(entrada, musica.inicio, comprimento);
  }

  const resultado = await mesa.startRendering();
  andamento(1);
  return resultado;
}

// ==================== peças ====================

function arquivoDe(arquivos: ArquivosDoProjeto, chave: string): File {
  const arquivo = arquivos.get(chave);
  if (!arquivo) throw new Error("Um arquivo do projeto não está mais aberto nesta página. Adicione-o de novo.");
  return arquivo;
}

function contexto(tela: OffscreenCanvas): OffscreenCanvasRenderingContext2D {
  const ctx = tela.getContext("2d");
  if (!ctx) throw new Error("O navegador não disponibilizou o canvas.");
  return ctx;
}

function carimbo(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}
