/// <reference lib="webworker" />
/**
 * Separação de faixas no navegador, fora da página e longe da thread de áudio: o modelo
 * Spleeter 4stems (ONNX Runtime Web, WASM) roda trecho a trecho; a matemática é a do núcleo
 * (musica-separacao.mjs), igual à dos apps. Nenhum áudio sai do aparelho.
 */

import { MODELO_DE_SEPARACAO, separar } from "@/packages/converter-core/musica-separacao.mjs";

type Pedido = { tipo: "separar"; canais: Float32Array[]; modo: string } | { tipo: "cancelar" };

let cancelado = false;

async function modelo(): Promise<ArrayBuffer> {
  const raiz = await navigator.storage.getDirectory();
  const pasta = await raiz.getDirectoryHandle("modelos", { create: true });
  const arquivo = await (await pasta.getFileHandle(MODELO_DE_SEPARACAO.arquivo)).getFile();
  return arquivo.arrayBuffer();
}

self.onmessage = async (evento: MessageEvent<Pedido>) => {
  const p = evento.data;
  if (p.tipo === "cancelar") {
    cancelado = true;
    return;
  }
  cancelado = false;
  try {
    const ort = await import("onnxruntime-web/wasm");
    ort.env.wasm.wasmPaths = new URL("/motores/recorte/", self.location.href).href;
    ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1)) : 1;
    const sessao = await ort.InferenceSession.create(new Uint8Array(await modelo()), { executionProviders: ["wasm"], graphOptimizationLevel: "all" });
    const m = MODELO_DE_SEPARACAO;
    const comprimento = p.canais[0].length;
    const faixas: Float32Array[][] = [];
    const definicao = await separar({
      comprimento,
      canais: p.canais.length,
      modo: p.modo,
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
    });
    await sessao.release();
    const buffers = faixas.flatMap((f) => f.map((c) => c.buffer));
    self.postMessage({ tipo: "pronto", faixas: definicao.map((d, i) => ({ id: d.id, rotulo: d.rotulo, canais: faixas[i] })) }, buffers);
  } catch (erro) {
    self.postMessage({ tipo: "erro", mensagem: cancelado ? "Separação cancelada." : erro instanceof Error ? erro.message : String(erro) });
  }
};
