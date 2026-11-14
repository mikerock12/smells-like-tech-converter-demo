"use client";

import { ALL_FORMATS, AudioSample, AudioSampleSink, BlobSource, BufferTarget, Conversion, Input, MkvOutputFormat, Mp4OutputFormat, Output, WebMOutputFormat } from "mediabunny";

import { caudaEmQuadros, fonteDaMemoria, Misturador, type FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { FORMATOS_DE_EXPORTACAO, masterizar } from "@/packages/converter-core/musica-master.mjs";
import { fracaoDoPacote, grupoDoVideo, lerPedidoDoPacote, modeloDoPacote, nomesDosArquivos, projetoDoArquivo, type PedidoDoPacote } from "@/packages/converter-core/musica-pacote.mjs";
import type { ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import { codificar } from "@/lib/musica/exportar";
import { SeparadorNoNavegador, garantirModelo, type FaixaSeparada } from "@/lib/musica/separacao";

import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { decodificarAudio } from "./audio";
import { empacotar } from "../motores/zip";

/**
 * Separar faixas no conversor do site (experimental), tudo neste aparelho:
 *
 * - áudio → .zip: separa (Spleeter 4 ou RoFormer 6), mistura cada arquivo pedido com os
 *   efeitos de cada faixa (o mesmo mixer do estúdio musical), masteriza e empacota;
 * - vídeo → vídeo: tira o áudio, separa, fica com a voz ou com o resto (com efeitos) e
 *   devolve o vídeo com o áudio novo. A imagem é copiada, sem recodificar.
 */

const pausa = () => new Promise((r) => setTimeout(r, 0));

type Etapa = Parameters<typeof fracaoDoPacote>[0];

function avisos(contexto: ContextoDoMotor) {
  return (etapa: Etapa, texto: string, fracao: number) => contexto.progresso(texto, fracaoDoPacote(etapa, fracao));
}

async function separarAqui(contexto: ContextoDoMotor, pedido: PedidoDoPacote, canais: Float32Array[], taxa: number): Promise<FaixaSeparada[]> {
  const avisar = avisos(contexto);
  const modelo = modeloDoPacote(pedido.modelo)!;
  const roformer = modelo.id === "roformer";
  const controle = new AbortController();
  const separador = new SeparadorNoNavegador();
  const vigia = setInterval(() => {
    if (!contexto.cancelado()) return;
    controle.abort();
    separador.cancelar();
  }, 300);
  try {
    avisar("modelo", roformer ? "Conferindo o RoFormer (97 MB na primeira vez)" : "Conferindo o modelo (78 MB na primeira vez)", 0);
    await garantirModelo((f) => avisar("modelo", "Baixando o modelo de separação", f), controle.signal, roformer);
    const estereo = canais.length === 1 ? [canais[0], canais[0].slice()] : canais.slice(0, 2);
    return await separador.separar(estereo.map((c) => c.slice()), taxa, modelo.modo, (etapa, f) => avisar("separando", etapa === "Separando as faixas" ? `Separando com o ${modelo.rotulo}` : etapa, f));
  } catch (erro) {
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    throw erro;
  } finally {
    clearInterval(vigia);
  }
}

/** A mistura de um arquivo do pacote, em pedaços, com pausas para a página não travar. */
async function misturar(projeto: ProjetoMusical, fontes: Map<string, FonteDeAudio>, quadros: number, progresso: (f: number) => void, cancelado: () => boolean) {
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
    if (cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    const k = Math.min(passo, quadros - feito);
    m.processar(feito, k, be, bd, { metronomo: false });
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

function fontesDas(faixas: FaixaSeparada[]) {
  return new Map(faixas.map((f) => [f.id, fonteDaMemoria(f.canais)] as const));
}

const base = (nome: string) => nome.replace(/\.[^.]+$/, "") || "musica";

/** Áudio → .zip com os arquivos pedidos. */
export async function separarEmPacote(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const avisar = avisos(contexto);
  const pedido = lerPedidoDoPacote(String(contexto.opcoes.pacote || "{}"), { modelo: "spleeter" });
  const entrada = contexto.entradas[0];
  avisar("lendo", "Lendo o áudio", 0);
  const pcm = await decodificarAudio(entrada.arquivo, entrada.formato);
  if (pcm.canais[0].length > pcm.sampleRate * 60 * 30) throw new ConversionError("longo_demais", "No navegador, a separação aceita até 30 minutos. Para áudios maiores, use o plugin ou o aplicativo.");
  const faixas = await separarAqui(contexto, pedido, pcm.canais, pcm.sampleRate);
  const fontes = fontesDas(faixas);
  const quadros = faixas[0]?.canais[0].length ?? 0;
  if (quadros === 0) throw new ConversionError("sem_audio", "A separação não devolveu áudio. Tente de novo ou use o plugin.");
  const configuracao = { ...pedido.configuracao, taxa: pcm.sampleRate };
  const formato = FORMATOS_DE_EXPORTACAO.find((f) => f.id === pedido.formato) ?? FORMATOS_DE_EXPORTACAO[0];
  const nomes = nomesDosArquivos(base(entrada.nome), pedido.grupos, formato.extensao);
  const arquivos: Saida[] = [];
  for (const [i, grupo] of pedido.grupos.entries()) {
    const parte = (f: number) => avisar("mixando", `Mixando “${grupo.nome}” (${i + 1} de ${pedido.grupos.length})`, (i + f) / pedido.grupos.length);
    parte(0);
    const projeto = projetoDoArquivo(configuracao, grupo, quadros);
    const total = quadros + caudaEmQuadros(projeto);
    const mistura = await misturar(projeto, fontes, total, (f) => parte(f * 0.8), contexto.cancelado);
    parte(0.85);
    await pausa();
    const master = masterizar(mistura.esq, mistura.dir, pcm.sampleRate, pedido.master);
    parte(0.9);
    await pausa();
    const blob = await codificar(formato.id, master.esq, master.dir, pcm.sampleRate);
    arquivos.push({ nome: nomes[i], mime: blob.type || "application/octet-stream", bytes: await blob.arrayBuffer() });
  }
  avisar("juntando", "Juntando no .zip", 0.5);
  const zip = empacotar(arquivos, `${base(entrada.nome)}-faixas.zip`);
  const modelo = modeloDoPacote(pedido.modelo)!;
  return { saidas: [zip], nota: `${arquivos.length} arquivo(s) · ${modelo.rotulo} · experimental` };
}

/** Lê a trilha do vídeo inteira, alinhada pelo tempo de cada pedaço. */
async function lerTrilha(arquivo: Blob, contexto: ContextoDoMotor) {
  const input = new Input({ source: new BlobSource(arquivo), formats: ALL_FORMATS });
  try {
    const trilha = await input.getPrimaryAudioTrack();
    if (!trilha) throw new ConversionError("sem_audio", "Este vídeo não tem áudio para separar.");
    const taxa = trilha.sampleRate;
    const duracao = await trilha.computeDuration();
    const quadros = Math.ceil(duracao * taxa) + taxa;
    if (duracao > 60 * 30) throw new ConversionError("longo_demais", "No navegador, a separação aceita vídeos de até 30 minutos. Para vídeos maiores, use o plugin ou o aplicativo.");
    const canais = [new Float32Array(quadros), new Float32Array(quadros)];
    let inicio: number | null = null;
    let fim = 0;
    for await (const amostra of new AudioSampleSink(trilha).samples()) {
      try {
        if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
        inicio ??= Math.round(amostra.timestamp * taxa);
        const pos = Math.round(amostra.timestamp * taxa) - inicio;
        const n = Math.min(amostra.numberOfFrames, quadros - pos);
        if (pos < 0 || n <= 0) continue;
        const temp = new Float32Array(amostra.numberOfFrames);
        for (let c = 0; c < 2; c += 1) {
          amostra.copyTo(temp, { planeIndex: Math.min(c, amostra.numberOfChannels - 1), format: "f32-planar" });
          canais[c].set(temp.subarray(0, n), pos);
        }
        fim = Math.max(fim, pos + n);
        contexto.progresso("Lendo o áudio do vídeo", fracaoDoPacote("lendo", fim / quadros));
      } finally {
        amostra.close();
      }
    }
    return { canais: canais.map((c) => c.subarray(0, fim)), taxa, inicio: inicio ?? 0 };
  } finally {
    input.dispose?.();
  }
}

/** Vídeo → o mesmo vídeo, com o áudio só de voz ou sem a voz. */
export async function vozDoVideo(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const avisar = avisos(contexto);
  const entrada = contexto.entradas[0];
  const bruto = lerPedidoDoPacote(String(contexto.opcoes.pacote || "{}"), { modelo: "spleeter", manter: "resto" });
  const manter = bruto.manter ?? "resto";
  const pedido = { ...bruto, grupos: [grupoDoVideo(bruto.modelo, manter)] };
  avisar("lendo", "Lendo o áudio do vídeo", 0);
  const trilha = await lerTrilha(entrada.arquivo, contexto);
  const faixas = await separarAqui(contexto, pedido, trilha.canais, trilha.taxa);
  const quadros = trilha.canais[0].length;
  const projeto = projetoDoArquivo({ ...pedido.configuracao, taxa: trilha.taxa }, pedido.grupos[0], quadros);
  const mistura = await misturar(projeto, fontesDas(faixas), quadros, (f) => avisar("mixando", manter === "voz" ? "Aplicando os efeitos na voz" : "Aplicando os efeitos no áudio sem voz", f * 0.6), contexto.cancelado);
  const final = masterizar(mistura.esq, mistura.dir, trilha.taxa, pedido.master);

  const extensao = entrada.formato === "webm" ? "webm" : entrada.formato === "mkv" ? "mkv" : "mp4";
  const formato = extensao === "webm" ? new WebMOutputFormat() : extensao === "mkv" ? new MkvOutputFormat() : new Mp4OutputFormat();
  const input = new Input({ source: new BlobSource(entrada.arquivo), formats: ALL_FORMATS });
  const output = new Output({ format: formato, target: new BufferTarget() });
  try {
    const conversao = await Conversion.init({
      input,
      output,
      audio: {
        forceTranscode: true,
        numberOfChannels: 2,
        sampleRate: trilha.taxa,
        codec: extensao === "webm" ? "opus" : "aac",
        bitrate: 192_000,
        process: (amostra: AudioSample) => {
          const n = amostra.numberOfFrames;
          const pos = Math.round(amostra.timestamp * trilha.taxa) - trilha.inicio;
          const dados = new Float32Array(n * 2);
          for (let i = 0; i < n; i += 1) {
            const k = pos + i;
            if (k < 0 || k >= quadros) continue;
            dados[i] = final.esq[k];
            dados[n + i] = final.dir[k];
          }
          const nova = new AudioSample({ data: dados, format: "f32-planar", numberOfChannels: 2, sampleRate: trilha.taxa, timestamp: amostra.timestamp });
          amostra.close();
          return nova;
        },
      },
    });
    if (!conversao.isValid) throw new ConversionError("sem_suporte", "Este navegador não consegue gravar este vídeo de volta com o áudio novo. Tente pelo plugin ou pelo aplicativo.");
    conversao.onProgress = (f) => avisar("juntando", "Juntando o áudio novo ao vídeo", f);
    const vigia = setInterval(() => { if (contexto.cancelado()) void conversao.cancel(); }, 300);
    try { await conversao.execute(); } finally { clearInterval(vigia); }
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    const bytes = output.target.buffer;
    if (!bytes) throw new ConversionError("sem_saida", "O vídeo não foi gravado.");
    const sufixo = manter === "voz" ? "so-voz" : "sem-voz";
    return {
      saidas: [{ nome: `${base(entrada.nome)}-${sufixo}.${extensao}`, mime: extensao === "webm" ? "video/webm" : extensao === "mkv" ? "video/x-matroska" : "video/mp4", bytes, duracao: quadros / trilha.taxa }],
      nota: `${manter === "voz" ? "Só a voz" : "Sem a voz"} · ${modeloDoPacote(pedido.modelo)!.rotulo} · experimental`,
    };
  } finally {
    input.dispose?.();
  }
}
