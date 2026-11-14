/// <reference lib="webworker" />
/**
 * Separação de faixas no navegador, fora da página e longe da thread de áudio: o modelo
 * Spleeter 4stems (ONNX Runtime Web, WASM) roda trecho a trecho; a matemática é a do núcleo
 * (musica-separacao.mjs), igual à dos apps. Nenhum áudio sai do aparelho.
 */

import { MODELO_DE_SEPARACAO, separar } from "@/packages/converter-core/musica-separacao.mjs";
import { carregarNucleoNativo } from "@/packages/converter-core/nativo.mjs";
import { ROFORMER_COMPACTO } from "@/packages/converter-core/musica-modelos.mjs";
import { separarRoFormer } from "./roformer";

type Pedido = { tipo: "separar"; canais: Float32Array[]; modo: string; nativo: boolean; mascara: "classica" | "refinada"; gpu?: boolean }
  | { tipo: "restaurar"; canais: Float32Array[]; taxa: number; perfil: string; intensidade: number }
  | { tipo: "cancelar" };

let cancelado = false;

/** O runtime do ONNX com WebGPU, no R2 (passa do limite de arquivo do Workers); a versão casa com o package.json. */
const RUNTIME_WEBGPU = "ort-1.30.0-simd-threaded.asyncify.wasm";

async function carregarWasm() {
  const resposta = await fetch("/motores/nativo/slt_native.wasm");
  if (!resposta.ok) throw new Error("Não foi possível carregar o núcleo de áudio.");
  return carregarNucleoNativo(await resposta.arrayBuffer());
}

async function modelo(roformer: boolean): Promise<ArrayBuffer> {
  const dados = roformer ? ROFORMER_COMPACTO : MODELO_DE_SEPARACAO;
  const raiz = await navigator.storage.getDirectory();
  const pasta = await raiz.getDirectoryHandle("modelos", { create: true });
  const arquivo = await (await pasta.getFileHandle(dados.arquivo)).getFile();
  if (arquivo.size !== dados.bytes) throw new Error("O tamanho do modelo de separação não confere.");
  const bytes = await arquivo.arrayBuffer();
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  if (Array.from(hash, (b) => b.toString(16).padStart(2, "0")).join("") !== dados.sha256) {
    throw new Error("O modelo de separação não confere. Apague o modelo salvo e tente novamente.");
  }
  return bytes;
}

self.onmessage = async (evento: MessageEvent<Pedido>) => {
  const p = evento.data;
  if (p.tipo === "cancelar") {
    cancelado = true;
    return;
  }
  cancelado = false;
  try {
    if (p.tipo === "restaurar") {
      const nucleo = await carregarWasm();
      const pronto = nucleo.restaurar(p);
      self.postMessage({ tipo: "restaurado", ...pronto }, pronto.canais.map((c: Float32Array) => c.buffer));
      return;
    }
    const roformer = p.modo === "6";
    // Placa de vídeo (WebGPU: NVIDIA, AMD, Intel, Adreno, Mali) para o Spleeter, que é float32
    // e convolucional. O RoFormer INT8 fica no WASM: o WebGPU não roda as operações quantizadas.
    const querGpu = p.gpu === true && !roformer && "gpu" in navigator && !!(await (navigator as Navigator & { gpu: { requestAdapter(): Promise<unknown> } }).gpu.requestAdapter().catch(() => null));
    const bytes = new Uint8Array(await modelo(roformer));
    const threads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1)) : 1;
    const opcoesDaSessao = { graphOptimizationLevel: "all" as const, ...(roformer ? { enableCpuMemArena: false, enableMemPattern: false } : {}) };
    let ort: typeof import("onnxruntime-web/wasm");
    let sessao: Awaited<ReturnType<typeof ort.InferenceSession.create>>;
    let naGpu = false;
    try {
      if (!querGpu) throw new Error("sem placa de vídeo");
      // Carregado em tempo de execução, fora do bundle: o .wasm dele não cabe no Workers.
      const gpu = (await import(/* @vite-ignore */ new URL("/motores/recorte/ort.webgpu.min.mjs", self.location.href).href)) as typeof import("onnxruntime-web/webgpu");
      gpu.env.wasm.wasmPaths = { mjs: new URL("/motores/recorte/ort-wasm-simd-threaded.asyncify.mjs", self.location.href).href, wasm: new URL(`/modelos/${RUNTIME_WEBGPU}`, self.location.href).href };
      gpu.env.wasm.numThreads = threads;
      sessao = await gpu.InferenceSession.create(bytes, { ...opcoesDaSessao, executionProviders: ["webgpu", "wasm"] });
      ort = gpu as unknown as typeof ort;
      naGpu = true;
    } catch (erro) {
      // Sem WebGPU, driver que recusou ou sem rede para o runtime: segue no processador, como antes.
      if (querGpu) self.postMessage({ tipo: "acelerador", gpu: false, motivo: erro instanceof Error ? erro.message : String(erro) });
      ort = await import("onnxruntime-web/wasm");
      ort.env.wasm.wasmPaths = new URL("/motores/recorte/", self.location.href).href;
      ort.env.wasm.numThreads = threads;
      sessao = await ort.InferenceSession.create(bytes, { ...opcoesDaSessao, executionProviders: ["wasm"] });
    }
    self.postMessage({ tipo: "acelerador", gpu: naGpu });
    if (roformer) {
      try {
        const faixas = await separarRoFormer(p.canais, await carregarWasm(), async (espectro) => {
          const entrada = new ort.Tensor("float32", espectro, [1, 431, 4100]);
          try {
            const { mascaras } = await sessao.run({ espectro: entrada });
            try { return (mascaras.data as Float32Array).slice(); }
            finally { mascaras.dispose(); }
          } finally { entrada.dispose(); }
        }, (fracao) => self.postMessage({ tipo: "progresso", fracao }));
        self.postMessage({ tipo: "pronto", faixas }, faixas.flatMap((f) => f.canais.map((c) => c.buffer)));
      } finally { await sessao.release(); }
      return;
    }
    const m = MODELO_DE_SEPARACAO;
    const comprimento = p.canais[0].length;
    const faixas: Float32Array[][] = [];
    const opcoes: Parameters<typeof separar>[0] = {
      comprimento,
      canais: p.canais.length,
      modo: p.modo,
      mascara: p.mascara,
      ler: (c, inicio, n, destino) => destino.set(p.canais[Math.min(c, p.canais.length - 1)].subarray(inicio, inicio + n)),
      executar: async (entrada) => {
        const tensor = new ort.Tensor("float32", entrada, [1, 2, m.quadrosPorTrecho, m.bandas]);
        const saida = await sessao.run({ [m.entrada]: tensor });
        return saida[m.saida].data as Float32Array;
      },
      emitir: (f, c, inicio, amostras, n) => {
        faixas[f] ??= [new Float32Array(comprimento), new Float32Array(comprimento)];
        faixas[f][c].set(amostras.subarray(0, n), inicio);
      },
      progresso: (fracao) => self.postMessage({ tipo: "progresso", fracao }),
      cancelado: () => cancelado,
    };
    const nucleo = p.nativo ? await carregarWasm().catch(() => null) : null;
    let definicao: Awaited<ReturnType<typeof separar>>;
    try {
      definicao = nucleo?.tem("separador") ? await nucleo.separar(opcoes) : await separar(opcoes);
    } finally {
      await sessao.release();
    }
    const buffers = faixas.flatMap((f) => f.map((c) => c.buffer));
    self.postMessage({ tipo: "pronto", faixas: definicao.map((d, i) => ({ id: d.id, rotulo: d.rotulo, canais: faixas[i] })) }, buffers);
  } catch (erro) {
    self.postMessage({ tipo: "erro", mensagem: cancelado ? "Separação cancelada." : erro instanceof Error ? erro.message : String(erro) });
  }
};
