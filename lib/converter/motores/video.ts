/// <reference lib="webworker" />

import {
  ALL_FORMATS,
  AudioSampleSink,
  BlobSource,
  BufferTarget,
  CanvasSink,
  CanvasSource,
  Conversion,
  Input,
  MkvOutputFormat,
  MovOutputFormat,
  Mp4OutputFormat,
  Output,
  QUALITY_HIGH,
  QUALITY_LOW,
  QUALITY_MEDIUM,
  QUALITY_VERY_HIGH,
  QUALITY_VERY_LOW,
  WebMOutputFormat,
  getFirstEncodableVideoCodec,
  type InputVideoTrack,
  type OutputFormat,
  type Quality,
  type Rotation,
} from "mediabunny";
import { GIFEncoder, applyPalette, quantize } from "gifenc";

import { GifInvalido, lerGif, quadrosDoGif } from "@/packages/converter-core/gif.mjs";
import { getFormat, outputFileName } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";

import { lerTempoEmSegundos, type Opcoes } from "../ferramentas";
import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { codificarMp3, codificarWav } from "./audio-codificar";
import { SAIDAS_PARA_ZIP, empacotar } from "./zip";

/**
 * Vídeo no navegador, sem ffmpeg.wasm.
 *
 * O mediabunny lê e grava os containers (MP4, WEBM, MKV, MOV) e usa o WebCodecs para
 * decodificar e codificar — ou seja, os codecs do próprio navegador, com a placa de
 * vídeo quando existe. Quando o navegador não tem WebCodecs, o erro diz isso e aponta
 * para o plugin; não há caminho lento escondido.
 */

const conversoesEmAndamento = new Map<string, Conversion>();

export function cancelarVideo(jobId: string): void {
  const conversao = conversoesEmAndamento.get(jobId);
  if (conversao) void conversao.cancel();
}

function exigirWebCodecs(): void {
  if (typeof VideoDecoder === "undefined" || typeof VideoEncoder === "undefined") {
    throw new ConversionError(
      "sem_webcodecs",
      "Este navegador não tem os codecs de vídeo abertos para páginas (WebCodecs). Use um Chrome, Edge, Firefox ou Safari recentes — ou o plugin, que converte com o FFmpeg da sua máquina.",
    );
  }
}

async function abrir(arquivo: Blob): Promise<Input> {
  return new Input({ formats: ALL_FORMATS, source: new BlobSource(arquivo) });
}

function formatoDeSaida(formato: FormatId): OutputFormat {
  switch (formato) {
    case "webm":
      return new WebMOutputFormat();
    case "mkv":
      return new MkvOutputFormat();
    case "mov":
      return new MovOutputFormat({ fastStart: "in-memory" });
    default:
      return new Mp4OutputFormat({ fastStart: "in-memory" });
  }
}

const QUALIDADES: Record<string, Quality> = {
  "muito-alta": QUALITY_VERY_HIGH,
  alta: QUALITY_HIGH,
  media: QUALITY_MEDIUM,
  baixa: QUALITY_LOW,
  "muito-baixa": QUALITY_VERY_LOW,
};

function explicarDescartes(conversao: Conversion): never {
  const motivos = conversao.discardedTracks.map((descarte) => descarte.reason);
  if (motivos.includes("undecodable_source_codec") || motivos.includes("unknown_source_codec")) {
    throw new ConversionError(
      "codec_nao_suportado",
      "Este navegador não decodifica o codec deste vídeo. O plugin converte com o FFmpeg, que lê praticamente tudo.",
    );
  }
  if (motivos.includes("no_encodable_target_codec")) {
    throw new ConversionError(
      "sem_codificador",
      "Este navegador não sabe gravar vídeo neste formato. Tente MP4 ou WEBM, ou use o plugin.",
    );
  }
  throw new ConversionError("conversao_invalida", `O vídeo não pôde ser convertido (${motivos.join(", ") || "sem faixas"}).`);
}

// ==================== converter / comprimir / cortar / silenciar ====================

async function converter(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota?: string }> {
  exigirWebCodecs();
  const { entradas, opcoes, ferramenta, progresso } = contexto;
  const saidas: Saida[] = [];

  for (const [indice, entrada] of entradas.entries()) {
    const input = await abrir(entrada.arquivo);
    try {
      const formato = escolherFormato(ferramenta, opcoes, entrada.formato);
      const duracao = await input.computeDuration();
      const faixa = await input.getPrimaryVideoTrack();
      if (!faixa) throw new ConversionError("sem_video", "Este arquivo não tem imagem, só som. Use “Converter áudio”.");

      const target = new BufferTarget();
      const output = new Output({ format: formatoDeSaida(formato), target });

      const inicio = lerTempoEmSegundos(opcoes.inicio);
      const fim = lerTempoEmSegundos(opcoes.fim);
      const trim = inicio !== null || fim !== null ? { start: inicio ?? undefined, end: fim ?? undefined } : undefined;
      if (trim && trim.start !== undefined && trim.end !== undefined && trim.end <= trim.start) {
        throw new ConversionError("corte_invalido", "O fim do corte precisa vir depois do início.");
      }

      const video = await opcoesDeVideo(ferramenta, opcoes, faixa, duracao, trim);
      const semAudio = ferramenta === "video.silenciar" || opcoes.semAudio === true;

      const conversao = await Conversion.init({
        input,
        output,
        video,
        audio: semAudio ? { discard: true } : ferramenta === "video.comprimir" ? { bitrate: 96_000 } : undefined,
        trim,
        showWarnings: false,
      });

      if (!conversao.isValid) explicarDescartes(conversao);

      conversoesEmAndamento.set(contexto.jobId, conversao);
      const base = indice / entradas.length;
      conversao.onProgress = (fracao) => progresso("convertendo", base + fracao / entradas.length);
      try {
        await conversao.execute();
      } catch (erro) {
        if (contexto.cancelado()) return { saidas: [] };
        throw traduzirErro(erro);
      } finally {
        conversoesEmAndamento.delete(contexto.jobId);
      }

      const bytes = target.buffer;
      if (!bytes) throw new ConversionError("saida_vazia", "A conversão terminou sem produzir arquivo.");

      const sufixo = ferramenta === "video.cortar" ? "-cortado" : ferramenta === "video.silenciar" ? "-sem-audio" : ferramenta === "video.comprimir" ? "-leve" : "";
      const nome = outputFileName(entrada.nome, formato).replace(/(\.[^.]+)$/, `${sufixo}$1`);
      saidas.push({
        nome,
        mime: getFormat(formato)?.mime ?? "video/mp4",
        bytes,
        largura: faixa.displayWidth,
        altura: faixa.displayHeight,
        duracao: (fim ?? duracao) - (inicio ?? 0),
      });
    } finally {
      input.dispose();
    }
  }

  return { saidas };
}

function escolherFormato(ferramenta: string, opcoes: Opcoes, deEntrada: FormatId | null): FormatId {
  const pedido = String(opcoes.formato ?? "");
  const aceitos: FormatId[] = ["mp4", "webm", "mkv", "mov"];
  if (aceitos.includes(pedido as FormatId)) return pedido as FormatId;
  // Cortar e silenciar preferem manter o container de origem.
  if ((ferramenta === "video.cortar" || ferramenta === "video.silenciar") && deEntrada && aceitos.includes(deEntrada)) return deEntrada;
  return "mp4";
}

async function opcoesDeVideo(
  ferramenta: string,
  opcoes: Opcoes,
  faixa: InputVideoTrack,
  duracao: number,
  trim: { start?: number; end?: number } | undefined,
) {
  const rotacao = (Number(opcoes.rotacao ?? 0) || 0) as Rotation;
  const alturaOriginal = faixa.displayHeight;

  if (ferramenta === "video.cortar" || ferramenta === "video.silenciar") {
    // Sem reprocessar quando dá: o mediabunny copia os pacotes originais.
    return {};
  }

  if (ferramenta === "video.comprimir") {
    const alvo = String(opcoes.alvo ?? "whatsapp");
    const segundos = Math.max(1, (trim?.end ?? duracao) - (trim?.start ?? 0));
    const stats = await faixa.computePacketStats(200).catch(() => null);
    const bitrateAtual = stats?.averageBitrate ?? 4_000_000;

    let bitrate: number;
    let altura: number | undefined;
    if (alvo === "whatsapp") {
      bitrate = Math.min((16 * 1024 * 1024 * 8 * 0.9) / segundos - 96_000, bitrateAtual);
      altura = Math.min(alturaOriginal, 720);
    } else if (alvo === "email") {
      bitrate = Math.min((25 * 1024 * 1024 * 8 * 0.9) / segundos - 96_000, bitrateAtual);
      altura = Math.min(alturaOriginal, 1080);
    } else if (alvo === "quarto") {
      bitrate = bitrateAtual / 4;
    } else {
      bitrate = bitrateAtual / 2;
    }

    return {
      bitrate: Math.max(150_000, Math.round(bitrate)),
      height: altura && altura < alturaOriginal ? altura : undefined,
      forceTranscode: true,
    };
  }

  const resolucao = String(opcoes.resolucao ?? "original");
  const altura = resolucao === "original" ? undefined : Math.min(Number(resolucao), alturaOriginal);
  const fps = String(opcoes.fps ?? "original");

  return {
    height: altura && altura < alturaOriginal ? altura : undefined,
    bitrate: QUALIDADES[String(opcoes.qualidadeDoVideo ?? "media")] ?? QUALITY_MEDIUM,
    frameRate: fps === "original" ? undefined : Number(fps),
    rotate: rotacao,
    forceTranscode: true,
  };
}

function traduzirErro(erro: unknown): ConversionError {
  if (erro instanceof ConversionError) return erro;
  const mensagem = erro instanceof Error ? erro.message : String(erro);
  if (/encod/i.test(mensagem) && /support|config/i.test(mensagem)) {
    return new ConversionError("sem_codificador", "Este navegador não conseguiu gravar o vídeo neste formato. Tente MP4, ou use o plugin.");
  }
  return new ConversionError("video_falhou", `A conversão falhou: ${mensagem}`);
}

// ==================== vídeo → áudio ====================

async function extrairAudio(contexto: ContextoDoMotor): Promise<{ saidas: Saida[] }> {
  const { entradas, opcoes, progresso } = contexto;
  const saidas: Saida[] = [];

  for (const entrada of entradas) {
    const input = await abrir(entrada.arquivo);
    try {
      const faixa = await input.getPrimaryAudioTrack();
      if (!faixa) throw new ConversionError("sem_audio", "Este vídeo não tem trilha de áudio.");
      if (!(await faixa.canDecode())) {
        throw new ConversionError("codec_nao_suportado", "Este navegador não decodifica o áudio deste vídeo. O plugin extrai com o FFmpeg.");
      }

      const duracao = await input.computeDuration();
      const inicio = lerTempoEmSegundos(opcoes.inicio) ?? 0;
      const fim = lerTempoEmSegundos(opcoes.fim) ?? duracao;

      const sink = new AudioSampleSink(faixa);
      const pedacos: Float32Array[][] = [];
      const numeroDeCanais = Math.min(faixa.numberOfChannels, 2);
      let quadros = 0;

      for await (const amostra of sink.samples(inicio, fim)) {
        if (contexto.cancelado()) {
          amostra.close();
          return { saidas: [] };
        }
        const planos: Float32Array[] = [];
        for (let canal = 0; canal < numeroDeCanais; canal += 1) {
          const plano = new Float32Array(amostra.numberOfFrames);
          amostra.copyTo(plano, { planeIndex: canal, format: "f32-planar" });
          planos.push(plano);
        }
        pedacos.push(planos);
        quadros += amostra.numberOfFrames;
        amostra.close();
        progresso("lendo", Math.min(0.5, ((amostra.timestamp - inicio) / Math.max(1, fim - inicio)) * 0.5));
      }

      const canais = Array.from({ length: numeroDeCanais }, (_, canal) => {
        const junto = new Float32Array(quadros);
        let posicao = 0;
        for (const pedaco of pedacos) {
          junto.set(pedaco[canal], posicao);
          posicao += pedaco[canal].length;
        }
        return junto;
      });

      const formato = String(opcoes.formato ?? "mp3") === "wav" ? "wav" : "mp3";
      const bytes =
        formato === "wav"
          ? codificarWav(canais, faixa.sampleRate)
          : codificarMp3(canais, faixa.sampleRate, Number(opcoes.bitrate ?? 192), (fracao) => progresso("gravando", 0.5 + fracao * 0.5), contexto.cancelado);
      if (!bytes) return { saidas: [] };

      saidas.push({
        nome: outputFileName(entrada.nome, formato),
        mime: formato === "wav" ? "audio/wav" : "audio/mpeg",
        bytes,
        duracao: quadros / faixa.sampleRate,
      });
    } finally {
      input.dispose();
    }
  }

  return { saidas };
}

// ==================== GIF e quadros ====================

async function paraGif(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  exigirWebCodecs();
  const { entradas, opcoes, progresso } = contexto;
  const saidas: Saida[] = [];

  for (const entrada of entradas) {
    const input = await abrir(entrada.arquivo);
    try {
      const faixa = await input.getPrimaryVideoTrack();
      if (!faixa) throw new ConversionError("sem_video", "Este arquivo não tem imagem.");
      if (!(await faixa.canDecode())) throw new ConversionError("codec_nao_suportado", "Este navegador não decodifica este vídeo. Use o plugin.");

      const duracaoTotal = await input.computeDuration();
      const inicio = Math.min(lerTempoEmSegundos(opcoes.inicio) ?? 0, Math.max(0, duracaoTotal - 0.1));
      const duracao = Math.min(Number(opcoes.duracao ?? 5) || 5, 30, duracaoTotal - inicio);
      const fps = Math.max(1, Math.min(20, Number(opcoes.fps ?? 12) || 12));
      const largura = Math.max(16, Math.min(640, Number(opcoes.largura ?? 480) || 480));

      // Leitura sequencial de propósito: pedir "o quadro no instante X" falha em vídeos
      // sem índice (gravações de tela, WebM do MediaRecorder). Percorrer o trecho e
      // guardar um quadro a cada 1/fps funciona em qualquer arquivo.
      const sink = new CanvasSink(faixa, { width: largura, poolSize: 2 });
      const passo = 1 / fps;
      const total = Math.max(1, Math.round(duracao * fps));
      const gif = GIFEncoder();
      let quadros = 0;
      let alturaFinal = 0;
      let proximo = inicio;

      for await (const embrulho of sink.canvases(inicio, inicio + duracao)) {
        if (contexto.cancelado()) return { saidas: [], nota: "" };
        if (embrulho.timestamp + embrulho.duration < proximo) continue;
        const canvas = embrulho.canvas as OffscreenCanvas;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (!ctx) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
        const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height);
        alturaFinal = height;
        const paleta = quantize(data, 256);
        const indices = applyPalette(data, paleta);
        // Um quadro do vídeo pode cobrir mais de um do GIF (vídeo a 10 fps, GIF a 15).
        while (proximo <= embrulho.timestamp + embrulho.duration && quadros < total) {
          gif.writeFrame(indices, width, height, { palette: paleta, delay: Math.round(1000 / fps) });
          quadros += 1;
          proximo += passo;
        }
        progresso("desenhando", Math.min(1, quadros / total));
        if (quadros >= total) break;
      }

      if (quadros === 0) throw new ConversionError("sem_quadros", "Não consegui ler nenhum quadro nesse trecho.");
      gif.finish();
      const bytes = gif.bytes();

      saidas.push({
        nome: outputFileName(entrada.nome, "gif"),
        mime: "image/gif",
        bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
        largura,
        altura: alturaFinal,
        duracao: quadros / fps,
      });
    } finally {
      input.dispose();
    }
  }

  return { saidas, nota: "" };
}

async function paraQuadros(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  exigirWebCodecs();
  const { entradas, opcoes, progresso } = contexto;
  const formato = (["png", "jpg", "webp"].includes(String(opcoes.formato)) ? String(opcoes.formato) : "png") as FormatId;
  const mime = getFormat(formato)?.mime ?? "image/png";
  const saidas: Saida[] = [];

  for (const entrada of entradas) {
    const input = await abrir(entrada.arquivo);
    try {
      const faixa = await input.getPrimaryVideoTrack();
      if (!faixa) throw new ConversionError("sem_video", "Este arquivo não tem imagem.");
      if (!(await faixa.canDecode())) throw new ConversionError("codec_nao_suportado", "Este navegador não decodifica este vídeo. Use o plugin.");

      const duracao = await input.computeDuration();
      const instantes = instantesDosQuadros(opcoes, duracao);
      const sink = new CanvasSink(faixa, { poolSize: 2 });
      const base = outputFileName(entrada.nome, formato).replace(/\.[^.]+$/, "");
      let alvo = 0;

      // Sequencial, como no GIF: o primeiro quadro cujo fim passa do instante pedido.
      const fim = Math.min(duracao, instantes[instantes.length - 1] + 1);
      for await (const embrulho of sink.canvases(instantes[0], fim)) {
        if (contexto.cancelado()) return { saidas: [], nota: "" };
        if (alvo >= instantes.length) break;
        if (embrulho.timestamp + embrulho.duration < instantes[alvo]) continue;

        const canvas = embrulho.canvas as OffscreenCanvas;
        const blob = await canvas.convertToBlob({ type: mime, quality: 0.92 });
        const bytes = await blob.arrayBuffer();
        while (alvo < instantes.length && instantes[alvo] <= embrulho.timestamp + embrulho.duration) {
          const rotulo = instantes.length === 1 ? "" : `-${String(alvo + 1).padStart(3, "0")}`;
          saidas.push({
            nome: `${base}${rotulo}-${embrulho.timestamp.toFixed(2).replace(".", "s")}.${formato}`,
            mime,
            bytes: alvo === 0 ? bytes : bytes.slice(0),
            largura: canvas.width,
            altura: canvas.height,
          });
          alvo += 1;
        }
        progresso("extraindo", alvo / instantes.length);
      }
    } finally {
      input.dispose();
    }
  }

  if (saidas.length === 0) throw new ConversionError("sem_quadros", "Nenhum quadro foi extraído. Confira o instante escolhido.");
  const nota = `${saidas.length} quadro(s)`;
  if (saidas.length >= SAIDAS_PARA_ZIP) {
    return { saidas: [empacotar(saidas, `${outputFileName(entradas[0].nome, formato).replace(/\.[^.]+$/, "")}-quadros.zip`)], nota };
  }
  return { saidas, nota };
}

function instantesDosQuadros(opcoes: Opcoes, duracao: number): number[] {
  const modo = String(opcoes.modo ?? "instante");
  if (modo === "intervalo") {
    const passo = Math.max(0.5, Number(opcoes.intervalo ?? 5) || 5);
    const lista: number[] = [];
    for (let t = 0; t < duracao && lista.length < 200; t += passo) lista.push(t);
    return lista;
  }
  if (modo === "distribuidos") {
    const quantidade = Math.max(1, Math.min(200, Math.floor(Number(opcoes.quantidade ?? 10) || 10)));
    return Array.from({ length: quantidade }, (_, indice) => ((indice + 0.5) / quantidade) * duracao);
  }
  const instante = lerTempoEmSegundos(opcoes.instante) ?? 1;
  return [Math.min(Math.max(0, instante), Math.max(0, duracao - 0.05))];
}

// ==================== GIF → vídeo ====================

/**
 * O GIF animado vira MP4 ou WEBM. Cada quadro sai do leitor de GIF já composto
 * (transparência e descarte respeitados), é pintado sobre a cor de fundo — vídeo não
 * tem transparência — e vai para o codificador do navegador com o tempo que o GIF pedia.
 */
async function deGif(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  exigirWebCodecs();
  const { entradas, opcoes, progresso } = contexto;
  const formato: FormatId = opcoes.formato === "webm" ? "webm" : "mp4";
  const voltas = Math.max(1, Math.min(20, Math.floor(Number(opcoes.voltas ?? 1)) || 1));
  const fundo = /^#[0-9a-f]{6}$/i.test(String(opcoes.fundo)) ? String(opcoes.fundo) : "#ffffff";
  const saidas: Saida[] = [];
  let segundosTotais = 0;

  for (const [indice, entrada] of entradas.entries()) {
    let gif: ReturnType<typeof lerGif>;
    try {
      gif = lerGif(new Uint8Array(await entrada.arquivo.arrayBuffer()));
    } catch (erro) {
      throw new ConversionError("gif_invalido", erro instanceof GifInvalido ? `“${entrada.nome}”: ${erro.message}` : `“${entrada.nome}” não pôde ser lido como GIF.`);
    }

    // H.264 e VP9 pedem largura e altura pares; a sobra de um pixel fica com a cor de fundo.
    const largura = gif.largura + (gif.largura % 2);
    const altura = gif.altura + (gif.altura % 2);
    const tela = new OffscreenCanvas(largura, altura);
    const pincel = tela.getContext("2d");
    const camada = new OffscreenCanvas(gif.largura, gif.altura);
    const pincelDaCamada = camada.getContext("2d");
    if (!pincel || !pincelDaCamada) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
    const pixels = new ImageData(gif.largura, gif.altura);

    const saidaFormato = formatoDeSaida(formato);
    const codec = await getFirstEncodableVideoCodec(saidaFormato.getSupportedVideoCodecs(), { width: largura, height: altura });
    if (!codec) {
      throw new ConversionError("sem_codificador", `Este navegador não sabe gravar ${getFormat(formato)?.label ?? formato}. Tente o outro formato.`);
    }

    const target = new BufferTarget();
    const output = new Output({ format: saidaFormato, target });
    const fonte = new CanvasSource(tela, { codec, bitrate: QUALITY_HIGH });
    output.addVideoTrack(fonte);
    await output.start();

    const total = gif.quadros.length * voltas;
    let feitos = 0;
    let tempo = 0;
    try {
      for (let volta = 0; volta < voltas; volta += 1) {
        for (const { rgba, atraso } of quadrosDoGif(gif)) {
          if (contexto.cancelado()) {
            await output.cancel();
            return { saidas: [], nota: "" };
          }
          pixels.data.set(rgba);
          pincelDaCamada.putImageData(pixels, 0, 0);
          pincel.fillStyle = fundo;
          pincel.fillRect(0, 0, largura, altura);
          pincel.drawImage(camada, 0, 0);

          const duracao = atraso / 1000;
          await fonte.add(tempo, duracao);
          tempo += duracao;
          feitos += 1;
          progresso("gravando", (indice + feitos / total) / entradas.length);
        }
      }
      await output.finalize();
    } catch (erro) {
      if (contexto.cancelado()) return { saidas: [], nota: "" };
      throw traduzirErro(erro);
    }

    const bytes = target.buffer;
    if (!bytes) throw new ConversionError("saida_vazia", "A conversão terminou sem produzir arquivo.");
    segundosTotais += tempo;
    saidas.push({
      nome: outputFileName(entrada.nome, formato),
      mime: getFormat(formato)?.mime ?? "video/mp4",
      bytes,
      largura,
      altura,
      duracao: tempo,
    });
  }

  const nota = `${Math.round(segundosTotais * 10) / 10} s de vídeo${voltas > 1 ? `, ${voltas} voltas` : ""}`;
  return { saidas, nota };
}

export async function motorDeVideo(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota?: string }> {
  switch (contexto.ferramenta) {
    case "video.de-gif":
      return deGif(contexto);
    case "video.para-audio":
      return extrairAudio(contexto);
    case "video.gif":
      return paraGif(contexto);
    case "video.quadros":
      return paraQuadros(contexto);
    default:
      return converter(contexto);
  }
}
