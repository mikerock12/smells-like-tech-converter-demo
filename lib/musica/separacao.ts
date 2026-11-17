"use client";

import { MODELO_DE_SEPARACAO } from "@/packages/converter-core/musica-separacao.mjs";
import { ROFORMER_COMPACTO } from "@/packages/converter-core/musica-modelos.mjs";
import { APOLLO } from "@/packages/converter-core/apollo.mjs";

/** Qual modelo: Spleeter (false), RoFormer (true) ou o Apollo do restaurador. */
export type QualModelo = boolean | "apollo";

export type PerfilDeMascara = "classica" | "refinada";

/**
 * Separação de faixas no site: baixa o modelo uma vez (78 MB, conferido pelo SHA-256 e
 * guardado no navegador), converte o áudio para 44,1 kHz, separa no worker e devolve as
 * faixas na taxa do projeto. O áudio nunca sai do aparelho; só o modelo vem da rede.
 */

export type FaixaSeparada = { id: string; rotulo: string; canais: Float32Array[] };

export async function restaurarFaixa(canais: Float32Array[], taxa: number, perfil: string, intensidade: number) {
  const { default: Fabrica } = await import("./separacao.worker?worker");
  const worker = new Fabrica({ name: "restauracao-de-faixa" });
  const entrada = canais.map((c) => c.slice());
  try {
    return await new Promise<{ canais: Float32Array[]; ganho: number; versao: number }>((resolver, rejeitar) => {
      worker.onmessage = (evento) => {
        if (evento.data.tipo === "restaurado") resolver(evento.data);
        if (evento.data.tipo === "erro") rejeitar(new Error(evento.data.mensagem));
      };
      worker.onerror = (evento) => rejeitar(new Error(evento.message || "A restauração parou com um erro."));
      worker.postMessage({ tipo: "restaurar", canais: entrada, taxa, perfil, intensidade }, entrada.map((c) => c.buffer));
    });
  } finally {
    worker.terminate();
  }
}

const dadosDoModelo = (qual: QualModelo) => qual === "apollo" ? APOLLO : qual ? ROFORMER_COMPACTO : MODELO_DE_SEPARACAO;

async function pastaDosModelos() {
  return (await navigator.storage.getDirectory()).getDirectoryHandle("modelos", { create: true });
}

export async function modeloBaixado(roformer: QualModelo = false, verificar = false): Promise<boolean> {
  const modelo = dadosDoModelo(roformer);
  try {
    const arquivo = await (await (await pastaDosModelos()).getFileHandle(modelo.arquivo)).getFile();
    return arquivo.size === modelo.bytes &&
      (!verificar || hex(await crypto.subtle.digest("SHA-256", await arquivo.arrayBuffer())) === modelo.sha256);
  } catch {
    return false;
  }
}

export async function apagarModelo(roformer: QualModelo = false): Promise<void> {
  try { await (await pastaDosModelos()).removeEntry(dadosDoModelo(roformer).arquivo); }
  catch (erro) { if (!(erro instanceof DOMException) || erro.name !== "NotFoundError") throw erro; }
}

function hex(buffer: ArrayBuffer) {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Baixa o modelo se ainda não estiver aqui, conferindo o SHA-256 antes de guardar. */
export async function garantirModelo(progresso: (fracao: number) => void, sinal?: AbortSignal, roformer: QualModelo = false): Promise<void> {
  const modelo = dadosDoModelo(roformer);
  if (await modeloBaixado(roformer, true)) return;
  const resposta = await fetch(new URL(modelo.url).pathname, { signal: sinal });
  if (!resposta.ok || !resposta.body) throw new Error(`Não foi possível baixar o modelo ${roformer === "apollo" ? "de restauração" : "de separação"}. Confira a conexão e tente de novo.`);
  const total = Number(resposta.headers.get("Content-Length")) || modelo.bytes;
  const leitor = resposta.body.getReader();
  const pedacos: Uint8Array[] = [];
  let recebido = 0;
  for (;;) {
    const { done, value } = await leitor.read();
    if (done) break;
    pedacos.push(value);
    recebido += value.length;
    if (recebido > modelo.bytes) {
      await leitor.cancel();
      throw new Error("O modelo baixado é maior que o esperado.");
    }
    progresso(Math.min(1, recebido / total));
  }
  const bytes = new Uint8Array(recebido);
  let p = 0;
  for (const pedaco of pedacos) {
    bytes.set(pedaco, p);
    p += pedaco.length;
  }
  const soma = hex(await crypto.subtle.digest("SHA-256", bytes));
  if (recebido !== modelo.bytes || soma !== modelo.sha256) throw new Error("O modelo baixado não confere e foi descartado.");
  sinal?.throwIfAborted();
  const arquivo = await (await pastaDosModelos()).getFileHandle(modelo.arquivo, { create: true });
  const fluxo = await arquivo.createWritable();
  try {
    await fluxo.write(bytes);
    await fluxo.close();
  } catch (erro) {
    await fluxo.abort().catch(() => undefined);
    throw erro;
  }
}

/** Troca a taxa de amostragem com o reamostrador do próprio navegador. */
export async function reamostrar(canais: Float32Array[], de: number, para: number): Promise<Float32Array[]> {
  if (de === para) return canais;
  const quadros = Math.ceil((canais[0].length * para) / de);
  const contexto = new OfflineAudioContext(canais.length, quadros, para);
  const buffer = contexto.createBuffer(canais.length, canais[0].length, de);
  canais.forEach((c, i) => buffer.copyToChannel(c as Float32Array<ArrayBuffer>, i));
  const fonte = contexto.createBufferSource();
  fonte.buffer = buffer;
  fonte.connect(contexto.destination);
  fonte.start();
  const pronto = await contexto.startRendering();
  return Array.from({ length: pronto.numberOfChannels }, (_, i) => pronto.getChannelData(i).slice());
}

const CHAVE_DA_PLACA = "aceleracao:placa-de-video";

/**
 * A separação na placa de vídeo (WebGPU) é opcional e começa desligada: numa Intel HD 530
 * integrada o Spleeter levou 10,8 s contra 6,6 s no processador, com o mesmo resultado
 * (diferença máxima de 3,6e-7). Placas dedicadas tendem a ganhar, mas não foram medidas.
 * A volta para o processador é automática se o driver recusar ou o runtime não baixar.
 */
export function usarPlacaDeVideo(): boolean {
  try { return localStorage.getItem(CHAVE_DA_PLACA) === "1"; } catch { return false; }
}

/** Se este navegador oferece WebGPU (não garante que o driver aceite o modelo). */
export function temPlacaDeVideo(): boolean {
  return typeof navigator !== "undefined" && "gpu" in navigator;
}

export function definirPlacaDeVideo(ligada: boolean) {
  try { localStorage.setItem(CHAVE_DA_PLACA, ligada ? "1" : "0"); } catch { /* armazenamento bloqueado */ }
}

export class SeparadorNoNavegador {
  private worker: Worker | null = null;
  /** Se a última separação rodou na placa de vídeo (WebGPU). */
  naPlacaDeVideo = false;
  private abortar = new AbortController();

  /**
   * `extras.refinarAgudos`: no RoFormer, refino de Wiener acima de 8 kHz (no Spleeter, use a
   * máscara "refinada"). `extras.recuperarBateria`: segunda separação do resíduo (experimental).
   */
  async separar(canais: Float32Array[], taxa: number, modo: string, progresso: (etapa: string, fracao: number) => void,
                mascara: PerfilDeMascara = "classica", extras: { refinarAgudos?: boolean; recuperarBateria?: boolean; gpu?: boolean } = {}): Promise<FaixaSeparada[]> {
    progresso("Preparando o áudio", 0);
    // O comprimento sai antes: na taxa do modelo (44,1 kHz) a entrada é a própria `canais`,
    // e transferir para o worker esvazia esses buffers.
    const comprimento = canais[0].length;
    const entrada = await reamostrar(canais, taxa, MODELO_DE_SEPARACAO.taxa);
    const { default: Fabrica } = await import("./separacao.worker?worker");
    const sinal = this.abortar.signal;
    sinal.throwIfAborted();
    this.worker = new Fabrica({ name: "separacao-de-faixas" });
    let cancelar: (() => void) | undefined;
    let etapaAtual = "";
    const faixas = await new Promise<FaixaSeparada[]>((resolver, rejeitar) => {
      cancelar = () => rejeitar(new Error("Separação cancelada."));
      sinal.addEventListener("abort", cancelar, { once: true });
      this.worker!.onmessage = (evento: MessageEvent<{ tipo: string; fracao?: number; faixas?: FaixaSeparada[]; mensagem?: string }>) => {
        const m = evento.data;
        if (m.tipo === "acelerador") {
          const a = m as { gpu?: boolean; motivo?: string };
          this.naPlacaDeVideo = a.gpu === true;
          if (a.motivo) console.info("Separação no processador; a placa de vídeo não pôde ser usada:", a.motivo);
        }
        else if (m.tipo === "etapa") etapaAtual = (m as { etapa?: string }).etapa ?? "";
        else if (m.tipo === "progresso") progresso(etapaAtual || (this.naPlacaDeVideo ? "Separando as faixas na placa de vídeo" : "Separando as faixas"), m.fracao ?? 0);
        else if (m.tipo === "pronto") resolver(m.faixas ?? []);
        else if (m.tipo === "erro") rejeitar(new Error(m.mensagem));
      };
      this.worker!.onerror = (e) => rejeitar(new Error(e.message || "A separação parou com um erro."));
      let nativo = false;
      try { nativo = localStorage.getItem("criacao-musical:motor-wasm") === "1"; } catch { /* preferência indisponível */ }
      const { gpu, ...resto } = extras;
      this.worker!.postMessage({ tipo: "separar", canais: entrada, modo, mascara, nativo, gpu: gpu ?? usarPlacaDeVideo(), ...resto }, entrada.map((c) => c.buffer as ArrayBuffer));
    }).finally(() => {
      if (cancelar) sinal.removeEventListener("abort", cancelar);
      this.worker?.terminate();
      this.worker = null;
    });
    progresso("Voltando à taxa do projeto", 1);
    const resultado: FaixaSeparada[] = [];
    for (const faixa of faixas) resultado.push({ ...faixa, canais: (await reamostrar(faixa.canais, MODELO_DE_SEPARACAO.taxa, taxa)).map((c) => c.subarray(0, comprimento)) });
    return resultado;
  }

  cancelar() {
    this.abortar.abort(new Error("Separação cancelada."));
  }
}
