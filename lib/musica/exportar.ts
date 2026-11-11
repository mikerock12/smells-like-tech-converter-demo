"use client";

import { Mp3Encoder } from "@breezystack/lamejs";
import { AudioSample, AudioSampleSource, BufferTarget, FlacOutputFormat, Mp4OutputFormat, OggOutputFormat, Output, canEncodeAudio } from "mediabunny";

import { duracaoEmQuadros, type ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import { Misturador, caudaEmQuadros, type FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { FORMATOS_DE_EXPORTACAO, codificarWav, masterizar, type Medidas } from "@/packages/converter-core/musica-master.mjs";
import { transformarCanais } from "@/packages/converter-core/musica-transformacao.mjs";

/**
 * Exportação da criação musical no navegador: a mesma mistura que toca (musica-mixer.mjs),
 * em pedaços com pausas para a página não travar; master pelo preset escolhido; WAV com
 * dither (16/24) ou float32, MP3 pelo lamejs e FLAC/AAC/Opus pelo WebCodecs quando o
 * navegador tem o codificador. Nada é enviado: o arquivo nasce aqui.
 */

export type OpcoesDeExportacao = {
  formato: string;
  master: string;
  alcance: "projeto" | "loop";
  cadaFaixa: boolean;
  cauda: boolean;
  /** Exporta só esta faixa (sem master), como "Exportar só esta faixa" no menu dela. */
  faixa?: string | null;
};

export type Exportado = { nome: string; blob: Blob; medidas?: Medidas; ganhoDb?: number };

const CODECS: Record<string, { codec: "flac" | "aac" | "opus"; formato: () => FlacOutputFormat | Mp4OutputFormat | OggOutputFormat; mime: string; bitrate: number }> = {
  flac: { codec: "flac", formato: () => new FlacOutputFormat(), mime: "audio/flac", bitrate: 0 },
  aac: { codec: "aac", formato: () => new Mp4OutputFormat(), mime: "audio/mp4", bitrate: 256_000 },
  opus: { codec: "opus", formato: () => new OggOutputFormat(), mime: "audio/ogg", bitrate: 192_000 },
};

/** Quais formatos este navegador consegue gravar. */
export async function formatosDisponiveis(taxa: number): Promise<string[]> {
  const lista = ["wav-24", "wav-16", "wav-32f", "mp3"];
  for (const [id, c] of Object.entries(CODECS)) {
    try {
      if (await canEncodeAudio(c.codec, { numberOfChannels: 2, sampleRate: taxa, ...(c.bitrate ? { bitrate: c.bitrate } : {}) })) lista.push(id);
    } catch {
      // Sem o codificador: o formato fica de fora (o plugin e os apps fazem).
    }
  }
  return FORMATOS_DE_EXPORTACAO.map((f) => f.id).filter((id) => lista.includes(id));
}

const pausa = () => new Promise((r) => setTimeout(r, 0));

async function misturar(projeto: ProjetoMusical, fontes: Map<string, FonteDeAudio>, inicio: number, quadros: number, somente: string | null, progresso: (f: number) => void, cancelado: () => boolean) {
  const m = new Misturador(projeto.taxa);
  for (const [id, fonte] of fontes) m.definirFonte(id, fonte);
  m.definirProjeto(projeto);
  const esq = new Float32Array(quadros);
  const dir = new Float32Array(quadros);
  const passo = 8192;
  const be = new Float32Array(passo);
  const bd = new Float32Array(passo);
  let marca = performance.now();
  for (let feito = 0; feito < quadros; feito += passo) {
    if (cancelado()) throw new DOMException("Exportação cancelada.", "AbortError");
    const k = Math.min(passo, quadros - feito);
    m.processar(inicio + feito, k, be, bd, { somente, metronomo: projeto.metronomo.naExportacao });
    esq.set(be.subarray(0, k), feito);
    dir.set(bd.subarray(0, k), feito);
    if (performance.now() - marca > 40) {
      progresso(feito / quadros);
      await pausa();
      marca = performance.now();
    }
  }
  return { esq, dir };
}

function mp3(esq: Float32Array, dir: Float32Array, taxa: number): Blob {
  const para16 = (x: Float32Array) => {
    const s = new Int16Array(x.length);
    for (let i = 0; i < x.length; i += 1) s[i] = Math.max(-32768, Math.min(32767, Math.round(x[i] * 32767)));
    return s;
  };
  const e = para16(esq);
  const d = para16(dir);
  const codificador = new Mp3Encoder(2, taxa, 320);
  const partes: Uint8Array[] = [];
  for (let i = 0; i < e.length; i += 1152 * 20) {
    const bloco = codificador.encodeBuffer(e.subarray(i, i + 1152 * 20), d.subarray(i, i + 1152 * 20));
    if (bloco.length) partes.push(new Uint8Array(bloco));
  }
  const fim = codificador.flush();
  if (fim.length) partes.push(new Uint8Array(fim));
  return new Blob(partes as BlobPart[], { type: "audio/mpeg" });
}

async function webcodecs(id: string, esq: Float32Array, dir: Float32Array, taxa: number): Promise<Blob> {
  const c = CODECS[id];
  const saida = new Output({ format: c.formato(), target: new BufferTarget() });
  const fonte = new AudioSampleSource({ codec: c.codec, ...(c.bitrate ? { bitrate: c.bitrate } : {}) });
  saida.addAudioTrack(fonte);
  await saida.start();
  const passo = taxa;
  for (let i = 0; i < esq.length; i += passo) {
    const n = Math.min(passo, esq.length - i);
    const dados = new Float32Array(n * 2);
    for (let k = 0; k < n; k += 1) {
      dados[k * 2] = esq[i + k];
      dados[k * 2 + 1] = dir[i + k];
    }
    await fonte.add(new AudioSample({ data: dados, format: "f32", numberOfChannels: 2, sampleRate: taxa, timestamp: i / taxa }));
  }
  fonte.close();
  await saida.finalize();
  return new Blob([saida.target.buffer!], { type: c.mime });
}

export async function codificar(formato: string, esq: Float32Array, dir: Float32Array, taxa: number): Promise<Blob> {
  const definicao = FORMATOS_DE_EXPORTACAO.find((f) => f.id === formato);
  if (!definicao) throw new Error(`Formato desconhecido: ${formato}`);
  if (definicao.extensao === "wav") {
    const bytes = codificarWav([esq, dir], taxa, definicao.bits);
    return new Blob([bytes.buffer as ArrayBuffer], { type: "audio/wav" });
  }
  if (formato === "mp3") return mp3(esq, dir, taxa);
  return webcodecs(formato, esq, dir, taxa);
}

const nomeDoArquivo = (s: string) => s.normalize("NFD").replace(/[^\w\s-]/g, "").trim().replace(/\s+/g, "-").slice(0, 60) || "musica";

/** Exporta a mistura (com master) ou cada faixa (sem master, pós-fader, com os envios). */
export async function exportarMusica(projeto: ProjetoMusical, fontes: Map<string, FonteDeAudio>, opcoes: OpcoesDeExportacao, progresso: (etapa: string, fracao: number) => void, cancelado: () => boolean): Promise<Exportado[]> {
  const extensao = FORMATOS_DE_EXPORTACAO.find((f) => f.id === opcoes.formato)?.extensao ?? "wav";
  const usarLoop = opcoes.alcance === "loop" && projeto.loop.fim > projeto.loop.inicio;
  const inicio = usarLoop ? projeto.loop.inicio : 0;
  const fim = usarLoop ? projeto.loop.fim : duracaoEmQuadros(projeto);
  if (fim <= inicio) throw new Error("Não há nada para exportar: grave ou importe uma faixa primeiro.");
  const quadros = fim - inicio + (opcoes.cauda ? caudaEmQuadros(projeto) : 0);
  const titulo = nomeDoArquivo(projeto.titulo);
  const resultados: Exportado[] = [];
  const unica = opcoes.faixa ? projeto.faixas.find((f) => f.id === opcoes.faixa) : null;
  const alvos = unica ? [{ id: unica.id, nome: `${titulo}-${nomeDoArquivo(unica.nome)}` }] : opcoes.cadaFaixa ? projeto.faixas.filter((f) => f.clipes.some((c) => !c.mudo)).map((f) => ({ id: f.id, nome: `${titulo}-${nomeDoArquivo(f.nome)}` })) : [{ id: null, nome: titulo }];
  for (const [i, alvo] of alvos.entries()) {
    const parte = (f: number) => (i + f) / alvos.length;
    progresso(alvo.id ? `Mixando ${alvo.nome}` : "Mixando", parte(0));
    let { esq, dir }: { esq: Float32Array; dir: Float32Array } = await misturar(projeto, fontes, inicio, quadros, alvo.id, (f) => progresso(alvo.id ? `Mixando ${alvo.nome}` : "Mixando", parte(f * 0.8)), cancelado);
    let medidas: Medidas | undefined;
    let ganhoDb: number | undefined;
    if (!alvo.id) {
      progresso("Masterizando", parte(0.82));
      await pausa();
      if (cancelado()) throw new DOMException("Exportação cancelada.", "AbortError");
      if ((projeto.master.velocidade ?? 1) !== 1 || (projeto.master.semitons ?? 0) !== 0) {
        [esq, dir] = transformarCanais([esq, dir], projeto.taxa, projeto.master);
      }
      const m = masterizar(esq, dir, projeto.taxa, opcoes.master);
      esq = m.esq;
      dir = m.dir;
      medidas = m.depois;
      ganhoDb = m.ganhoDb;
    }
    progresso("Codificando", parte(0.9));
    await pausa();
    const blob = await codificar(opcoes.formato, esq, dir, projeto.taxa);
    resultados.push({ nome: `${alvo.nome}.${extensao}`, blob, medidas, ganhoDb });
  }
  progresso("Pronto", 1);
  return resultados;
}
