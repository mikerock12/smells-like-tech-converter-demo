import type { InferenceSession } from "onnxruntime-web";
import { camadasNoTempo, clipeNoTempo, type Projeto } from "@/packages/converter-core/estudio.mjs";
import type { ImagemDaCamada } from "./midias-das-camadas";

let sessao: Promise<InferenceSession> | null = null;
let fila: Promise<unknown> = Promise.resolve();
const fotos = new WeakMap<ImageBitmap, Promise<OffscreenCanvas>>();

async function obterSessao(): Promise<InferenceSession> {
  if (!sessao) sessao = (async () => {
    const ort = await import("onnxruntime-web/wasm");
    ort.env.wasm.wasmPaths = new URL("/motores/recorte/", location.href).href;
    ort.env.wasm.numThreads = 1;
    return ort.InferenceSession.create("/motores/recorte/u2netp.onnx", { executionProviders: ["wasm"], graphOptimizationLevel: "all" });
  })().catch(() => { sessao = null; throw new Error("Não foi possível carregar a remoção de fundo. Confira a conexão e tente novamente."); });
  return sessao;
}

/** Copia o quadro antes de esperar pela inferência: decodificadores reutilizam seus canvases. */
async function executar(imagem: ImagemDaCamada, atual: () => boolean): Promise<OffscreenCanvas> {
  const saida = new OffscreenCanvas(imagem.width, imagem.height);
  const tela = saida.getContext("2d")!;
  tela.drawImage(imagem, 0, 0);
  const pequena = new OffscreenCanvas(320, 320);
  const ctx = pequena.getContext("2d", { willReadFrequently: true })!;
  ctx.drawImage(saida, 0, 0, 320, 320);
  const rgba = ctx.getImageData(0, 0, 320, 320).data;
  const trabalho = fila.catch(() => {}).then(async () => {
    if (!atual()) throw new DOMException("Prévia substituída.", "AbortError");
    const ort = await import("onnxruntime-web/wasm");
    const motor = await obterSessao();
    if (!atual()) throw new DOMException("Prévia substituída.", "AbortError");
    const n = 320 * 320, entrada = new Float32Array(n * 3);
    let max = 1;
    for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) max = Math.max(max, rgba[i * 4 + c]);
    const medias = [.485, .456, .406], desvios = [.229, .224, .225];
    for (let i = 0; i < n; i++) for (let c = 0; c < 3; c++) entrada[c * n + i] = (rgba[i * 4 + c] / max - medias[c]) / desvios[c];
    const tensor = new ort.Tensor("float32", entrada, [1, 3, 320, 320]);
    let resultados: Awaited<ReturnType<InferenceSession["run"]>> | undefined;
    try {
      resultados = await motor.run({ [motor.inputNames[0]]: tensor }, [motor.outputNames[0]]);
      const confianca = resultados[motor.outputNames[0]].data as Float32Array;
      let min = Infinity, max = -Infinity;
      for (const v of confianca) { min = Math.min(min, v); max = Math.max(max, v); }
      const amplitude = Math.max(1e-6, max - min);
      const mascara = ctx.createImageData(320, 320);
      for (let i = 0; i < n; i++) {
        mascara.data[i * 4] = mascara.data[i * 4 + 1] = mascara.data[i * 4 + 2] = 255;
        mascara.data[i * 4 + 3] = Math.round(Math.max(0, Math.min(1, (confianca[i] - min) / amplitude)) * 255);
      }
      ctx.putImageData(mascara, 0, 0);
      tela.globalCompositeOperation = "destination-in";
      tela.drawImage(pequena, 0, 0, saida.width, saida.height);
      tela.globalCompositeOperation = "source-over";
      return saida;
    } finally { tensor.dispose(); if (resultados) for (const resultado of Object.values(resultados)) resultado.dispose(); }
  });
  fila = trabalho;
  return trabalho;
}

export function recortar(imagem: ImagemDaCamada, atual: () => boolean = () => true): Promise<OffscreenCanvas> {
  if (!(imagem instanceof ImageBitmap)) return executar(imagem, atual);
  let recorte = fotos.get(imagem);
  if (!recorte) {
    // Fotos têm resultado reutilizável por todas as prévias do mesmo bitmap.
    recorte = executar(imagem, () => true).catch(erro => { fotos.delete(imagem); throw erro; });
    fotos.set(imagem, recorte);
  }
  return recorte;
}

export async function prepararRecortes(projeto: Projeto, t: number, imagem: ImagemDaCamada | null, midias: ReadonlyMap<string, ImagemDaCamada>, atual: () => boolean = () => true) {
  if (!atual()) throw new DOMException("Prévia substituída.", "AbortError");
  const principal = imagem && clipeNoTempo(projeto, t)?.clipe.transformacao?.removerFundo ? await recortar(imagem, atual) : imagem;
  const extras = new Map(midias);
  for (const camada of camadasNoTempo(projeto, t)) {
    if (!atual()) throw new DOMException("Prévia substituída.", "AbortError");
    const fonte = midias.get(camada.id);
    if (fonte && camada.transformacao?.removerFundo) extras.set(camada.id, await recortar(fonte, atual));
  }
  return { principal, extras };
}
