"use client";

import { ALL_FORMATS, AudioSample, AudioSampleSink, AudioSampleSource, BlobSource, BufferSource, BufferTarget, EncodedPacketSink, EncodedVideoPacketSource, Input, MkvOutputFormat, Mp4OutputFormat, Output, WebMOutputFormat, type InputAudioTrack } from "mediabunny";

import { caudaEmQuadros, fonteDaMemoria, Misturador, type FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { FORMATOS_DE_EXPORTACAO, masterizar } from "@/packages/converter-core/musica-master.mjs";
import { fracaoDoPacote, grupoDoVideo, lerPedidoDoPacote, modeloDoPacote, nomesDosArquivos, projetoDoArquivo, type PedidoDoPacote } from "@/packages/converter-core/musica-pacote.mjs";
import type { ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import { MENSAGEM_DE_FALHA_DO_AUDIO, conferirSaida, correlacao, detalhesDosProblemas, picoDb, rmsDb } from "@/packages/converter-core/validacao-de-saida.mjs";
import { codificar } from "@/lib/musica/exportar";
import { garantirAac } from "../aac";
import { SeparadorNoNavegador, garantirModelo, restaurarFaixa, type FaixaSeparada } from "@/lib/musica/separacao";
import { reduzirRuido } from "@/packages/converter-core/musica-separacao.mjs";
import { perfilDeRestauracaoDaFaixa } from "@/packages/converter-core/nativo.mjs";

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
    const t = pedido.tratamento;
    const faixas = await separador.separar(estereo.map((c) => c.slice()), taxa, modelo.modo,
      (etapa, f) => avisar("separando", etapa === "Separando as faixas" ? `Separando com o ${modelo.rotulo}` : etapa, f),
      !roformer && t?.refinarAgudos ? "refinada" : "classica",
      { refinarAgudos: roformer && t?.refinarAgudos === true, recuperarBateria: t?.recuperarBateria === true, gpu: !roformer && t?.aceleracao === true });
    return await tratarFaixas(faixas, taxa, pedido, contexto);
  } catch (erro) {
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    throw erro;
  } finally {
    clearInterval(vigia);
  }
}

/**
 * Depois de separar, a mesma qualidade do estúdio musical: redução de ruído em cada faixa e
 * restauração por instrumento (a faixa restaurada já sai com o volume igualado ao da bruta).
 */
async function tratarFaixas(faixas: FaixaSeparada[], taxa: number, pedido: PedidoDoPacote, contexto: ContextoDoMotor) {
  const t = pedido.tratamento;
  if (!t?.reduzirRuido && !(t?.restauracao && t.restauracao > 0)) return faixas;
  const avisar = avisos(contexto);
  for (const faixa of faixas) {
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    if (t.reduzirRuido) {
      avisar("mixando", `Reduzindo o ruído: ${faixa.rotulo}`, 0);
      await pausa();
      const n = faixa.canais[0].length;
      const limpos = [new Float32Array(n), new Float32Array(n)];
      reduzirRuido({ comprimento: n, canais: faixa.canais.length, ler: (c, i, q, d) => d.set(faixa.canais[Math.min(c, faixa.canais.length - 1)].subarray(i, i + q)), emitir: (c, i, a, q) => limpos[c].set(a.subarray(0, q), i) });
      faixa.canais = limpos;
    }
    if (t.restauracao > 0) {
      avisar("mixando", `Restaurando ${faixa.rotulo}`, 0);
      faixa.canais = (await restaurarFaixa(faixa.canais, taxa, perfilDeRestauracaoDaFaixa(faixa.id), t.restauracao)).canais;
    }
  }
  return faixas;
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

/**
 * Roda uma etapa do vídeo e, se ela falhar, registra no console (etapa + detalhes) e devolve
 * o erro amigável com a mensagem técnica no "Detalhes". Nada de engolir a exceção.
 */
async function etapa<T>(nome: string, contexto: ContextoDoMotor, fazer: () => Promise<T>): Promise<T> {
  try {
    return await fazer();
  } catch (erro) {
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    console.error(`[video.voz] a etapa “${nome}” falhou:`, erro);
    if (erro instanceof ConversionError && erro.code === "longo_demais") throw erro;
    const tecnica = erro instanceof Error ? `${erro.name}: ${erro.message}` : String(erro);
    const anterior = erro instanceof ConversionError && erro.detalhes ? `\n${erro.detalhes}` : "";
    throw new ConversionError(erro instanceof ConversionError ? erro.code : "etapa_falhou", MENSAGEM_DE_FALHA_DO_AUDIO, `${nome}: ${tecnica}${anterior}`);
  }
}

type Trilha = { canais: Float32Array[]; taxa: number; inicio: number; decodificador: string };

/** Pelo WebCodecs (AudioDecoder), alinhado pelo tempo de cada pedaço. */
async function lerPeloWebCodecs(trilha: InputAudioTrack, contexto: ContextoDoMotor, duracao: number): Promise<Trilha | null> {
  if (!(await trilha.canDecode().catch(() => false))) return null;
  const taxa = trilha.sampleRate;
  const quadros = Math.ceil(duracao * taxa) + taxa;
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
  if (fim === 0) return null;
  return { canais: canais.map((c) => c.subarray(0, fim)), taxa, inicio: (inicio ?? 0) / taxa, decodificador: "WebCodecs" };
}

/** Pelo decodeAudioData do Web Audio, que lê a trilha de MP4/WebM com os codecs de tocar som. */
async function lerPeloWebAudio(arquivo: Blob, taxaDesejada: number, inicio: number): Promise<Trilha> {
  const Contexto = window.OfflineAudioContext ?? (window as unknown as { webkitOfflineAudioContext?: typeof OfflineAudioContext }).webkitOfflineAudioContext;
  if (!Contexto) throw new Error("sem OfflineAudioContext");
  let contexto: OfflineAudioContext;
  try { contexto = new Contexto(1, 1, taxaDesejada); } catch { contexto = new Contexto(1, 1, 48_000); }
  const buffer = await contexto.decodeAudioData(await arquivo.arrayBuffer());
  const canais = Array.from({ length: Math.min(2, buffer.numberOfChannels) }, (_, i) => new Float32Array(buffer.getChannelData(i)));
  if (canais.length === 1) canais.push(canais[0].slice());
  return { canais, taxa: buffer.sampleRate, inicio, decodificador: "Web Audio" };
}

/**
 * Lê a trilha do vídeo inteira. O WebCodecs vem primeiro; se ele não decodifica o codec
 * (Chromium sem codecs proprietários, alguns Linux) ou não devolve nada, o Web Audio lê.
 */
async function lerTrilha(arquivo: Blob, contexto: ContextoDoMotor): Promise<Trilha> {
  const input = new Input({ source: new BlobSource(arquivo), formats: ALL_FORMATS });
  try {
    const trilha = await input.getPrimaryAudioTrack();
    if (!trilha) throw new ConversionError("sem_audio", "Este vídeo não tem áudio para separar.");
    const duracao = await trilha.computeDuration();
    if (duracao > 60 * 30) throw new ConversionError("longo_demais", "No navegador, a separação aceita vídeos de até 30 minutos. Para vídeos maiores, use o plugin ou o aplicativo.");
    const primeiro = await trilha.getFirstTimestamp().catch(() => 0);
    let motivo = "o WebCodecs não decodifica este áudio";
    try {
      const lido = await lerPeloWebCodecs(trilha, contexto, duracao);
      if (lido) return lido;
      motivo = "o WebCodecs não devolveu amostras";
    } catch (erro) {
      if (contexto.cancelado()) throw erro;
      motivo = `o WebCodecs falhou (${erro instanceof Error ? erro.message : String(erro)})`;
      console.warn("[video.voz] leitura pelo WebCodecs falhou; tentando o Web Audio:", erro);
    }
    try {
      return await lerPeloWebAudio(arquivo, trilha.sampleRate, primeiro);
    } catch (erro) {
      throw new ConversionError("audio_ilegivel", MENSAGEM_DE_FALHA_DO_AUDIO, `${motivo}; o Web Audio também não leu (${erro instanceof Error ? erro.message : String(erro)}), codec ${trilha.codec ?? "desconhecido"}`);
    }
  } finally {
    input.dispose?.();
  }
}

/**
 * Grava o vídeo de volta com o áudio novo: os pacotes de imagem são copiados sem recodificar
 * e o áudio é codificado aqui (AAC no MP4/MKV, Opus no WebM). Não depende de o navegador
 * decodificar o áudio original, e o AAC tem recurso em WebAssembly.
 */
async function juntar(arquivo: Blob, extensao: "mp4" | "mkv" | "webm", esq: Float32Array, dir: Float32Array, taxa: number, inicio: number,
                      progresso: (f: number) => void, cancelado: () => boolean) {
  const codecDoAudio = extensao === "webm" ? "opus" as const : "aac" as const;
  const wasm = codecDoAudio === "aac" ? await garantirAac({ numberOfChannels: 2, sampleRate: taxa, bitrate: 192_000 }) : false;
  const input = new Input({ source: new BlobSource(arquivo), formats: ALL_FORMATS });
  try {
    const video = await input.getPrimaryVideoTrack();
    if (!video) throw new Error("o arquivo não tem trilha de vídeo");
    if (!video.codec) throw new Error("codec de vídeo desconhecido");
    const formato = extensao === "webm" ? new WebMOutputFormat() : extensao === "mkv" ? new MkvOutputFormat() : new Mp4OutputFormat();
    const output = new Output({ format: formato, target: new BufferTarget() });
    const imagem = new EncodedVideoPacketSource(video.codec);
    output.addVideoTrack(imagem, { rotation: video.rotation });
    const som = new AudioSampleSource({ codec: codecDoAudio, bitrate: 192_000 });
    output.addAudioTrack(som);
    await output.start();
    const configuracao = await video.getDecoderConfig();
    const duracao = Math.max(1e-3, await video.computeDuration());
    const quadros = esq.length;
    const bloco = 4096;
    let feito = 0;
    const somAte = async (segundos: number) => {
      while (feito < quadros && inicio + feito / taxa < segundos) {
        const n = Math.min(bloco, quadros - feito);
        const dados = new Float32Array(n * 2);
        dados.set(esq.subarray(feito, feito + n), 0);
        dados.set(dir.subarray(feito, feito + n), n);
        const amostra = new AudioSample({ data: dados, format: "f32-planar", numberOfChannels: 2, sampleRate: taxa, timestamp: inicio + feito / taxa });
        try { await som.add(amostra); } finally { amostra.close(); }
        feito += n;
      }
    };
    let primeiro = true;
    for await (const pacote of new EncodedPacketSink(video).packets()) {
      if (cancelado()) throw new ConversionError("cancelado", "Cancelado.");
      await somAte(pacote.timestamp + 0.5);
      await imagem.add(pacote, primeiro && configuracao ? { decoderConfig: configuracao } : undefined);
      primeiro = false;
      progresso(Math.min(0.99, pacote.timestamp / duracao));
    }
    await somAte(Infinity);
    await output.finalize();
    const bytes = output.target.buffer;
    if (!bytes) throw new Error("o vídeo não foi gravado");
    return { bytes, wasm };
  } finally {
    input.dispose?.();
  }
}

/** Abre o arquivo pronto e mede o que ele tem: trilhas e durações (sem decodificar a imagem). */
async function medirSaida(bytes: ArrayBuffer) {
  const input = new Input({ source: new BufferSource(bytes), formats: ALL_FORMATS });
  try {
    const videos = await input.getVideoTracks();
    const audios = await input.getAudioTracks();
    return {
      faixasDeVideo: videos.length,
      faixasDeAudio: audios.length,
      duracaoDoVideo: videos[0] ? await videos[0].computeDuration() : null,
      duracaoDoAudio: audios[0] ? await audios[0].computeDuration() : null,
      codecDoAudio: audios[0]?.codec ?? null,
    };
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
  const trilha = await etapa("decodificar o áudio", contexto, () => lerTrilha(entrada.arquivo, contexto));
  const faixas = await etapa("separar", contexto, async () => {
    const prontas = await separarAqui(contexto, pedido, trilha.canais, trilha.taxa);
    if (!prontas.length || !(prontas[0]?.canais[0]?.length > 0)) throw new Error("a separação não devolveu faixas");
    return prontas;
  });
  const quadros = trilha.canais[0].length;
  const final = await etapa("somar as faixas", contexto, async () => {
    const projeto = projetoDoArquivo({ ...pedido.configuracao, taxa: trilha.taxa }, pedido.grupos[0], quadros);
    const mistura = await misturar(projeto, fontesDas(faixas), quadros, (f) => avisar("mixando", manter === "voz" ? "Aplicando os efeitos na voz" : "Aplicando os efeitos no áudio sem voz", f * 0.6), contexto.cancelado);
    const pronto = masterizar(mistura.esq, mistura.dir, trilha.taxa, pedido.master);
    for (const canal of [pronto.esq, pronto.dir]) for (let i = 0; i < canal.length; i += 997) {
      if (!Number.isFinite(canal[i])) throw new Error("a mistura tem amostras inválidas (NaN/Inf)");
    }
    return pronto;
  });

  const extensao = entrada.formato === "webm" ? "webm" : entrada.formato === "mkv" ? "mkv" : "mp4";
  const gravado = await etapa(extensao === "webm" ? "codificar o Opus e juntar ao vídeo" : "codificar o AAC e juntar ao vídeo", contexto,
    () => juntar(entrada.arquivo, extensao, final.esq, final.dir, trilha.taxa, trilha.inicio, (f) => avisar("juntando", "Juntando o áudio novo ao vídeo", f), contexto.cancelado));
  if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");

  // Conferência antes do "pronto": trilhas, duração, som e diferença do original.
  avisar("juntando", "Conferindo o vídeo pronto", 0.995);
  await etapa("conferir o arquivo final", contexto, async () => {
    const medidas = await medirSaida(gravado.bytes);
    const saida = [final.esq, final.dir];
    const problemas = conferirSaida({
      esperaVideo: true,
      ...medidas,
      picoDaSaidaDb: picoDb(saida),
      picoDaEntradaDb: picoDb(trilha.canais),
      correlacaoComEntrada: correlacao(saida, trilha.canais, 7),
      diferencaDeNivelDb: rmsDb(saida) - rmsDb(trilha.canais),
      deveMudar: true,
    });
    if (problemas.length) throw new ConversionError("saida_invalida", MENSAGEM_DE_FALHA_DO_AUDIO, detalhesDosProblemas(problemas));
    console.info(`[video.voz] saída conferida: ${medidas.faixasDeVideo} vídeo + ${medidas.faixasDeAudio} áudio (${medidas.codecDoAudio}${gravado.wasm ? ", AAC em WebAssembly" : ""}), áudio ${medidas.duracaoDoAudio?.toFixed(2)} s, vídeo ${medidas.duracaoDoVideo?.toFixed(2)} s, leitura por ${trilha.decodificador}.`);
  });

  const sufixo = manter === "voz" ? "so-voz" : "sem-voz";
  return {
    saidas: [{ nome: `${base(entrada.nome)}-${sufixo}.${extensao}`, mime: extensao === "webm" ? "video/webm" : extensao === "mkv" ? "video/x-matroska" : "video/mp4", bytes: gravado.bytes, duracao: quadros / trilha.taxa }],
    nota: `${manter === "voz" ? "Só a voz" : "Sem a voz"} · ${modeloDoPacote(pedido.modelo)!.rotulo} · experimental`,
  };
}
