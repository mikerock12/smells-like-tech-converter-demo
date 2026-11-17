/// <reference lib="webworker" />
/**
 * Separação de faixas no navegador, fora da página e longe da thread de áudio: o modelo
 * Spleeter 4stems (ONNX Runtime Web, WASM) roda trecho a trecho; a matemática é a do núcleo
 * (musica-separacao.mjs), igual à dos apps. Nenhum áudio sai do aparelho.
 */

import { MODELO_DE_SEPARACAO, separar } from "@/packages/converter-core/musica-separacao.mjs";
import { carregarNucleoNativo } from "@/packages/converter-core/nativo.mjs";
import { ROFORMER_COMPACTO } from "@/packages/converter-core/musica-modelos.mjs";
import { planejarRestauracao } from "@/packages/converter-core/restaurador.mjs";
import { APOLLO, restaurarComApollo } from "@/packages/converter-core/apollo.mjs";
import { separarRoFormer } from "./roformer";

type Pedido = { tipo: "separar"; canais: Float32Array[]; modo: string; nativo: boolean; mascara: "classica" | "refinada"; gpu?: boolean; refinarAgudos?: boolean; recuperarBateria?: boolean }
  | { tipo: "restaurar"; canais: Float32Array[]; taxa: number; perfil: string; intensidade: number }
  | { tipo: "restaurarAudio"; canais: Float32Array[]; taxa: number; opcoes: unknown }
  | { tipo: "diagnosticarAudio"; canais: Float32Array[]; taxa: number; opcoes: unknown }
  | { tipo: "cancelar" };

let cancelado = false;

/** O runtime do ONNX com WebGPU, no R2 (passa do limite de arquivo do Workers); a versão casa com o package.json. */
const RUNTIME_WEBGPU = "ort-1.30.0-simd-threaded.asyncify.wasm";

async function carregarWasm() {
  const resposta = await fetch("/motores/nativo/slt_native.wasm");
  if (!resposta.ok) throw new Error("Não foi possível carregar o núcleo de áudio.");
  return carregarNucleoNativo(await resposta.arrayBuffer());
}

async function modelo(roformer: boolean | "apollo"): Promise<ArrayBuffer> {
  const dados = roformer === "apollo" ? APOLLO : roformer ? ROFORMER_COMPACTO : MODELO_DE_SEPARACAO;
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
    if (p.tipo === "diagnosticarAudio") {
      const nucleo = await carregarWasm();
      const diagnostico = nucleo.diagnosticar({ canais: p.canais, taxa: p.taxa, progresso: (f: number) => self.postMessage({ tipo: "progresso", fracao: f }), cancelado: () => cancelado });
      self.postMessage({ tipo: "diagnostico", diagnostico: diagnostico.valores, plano: planejarRestauracao(diagnostico.valores, p.opcoes) });
      return;
    }
    if (p.tipo === "restaurarAudio") {
      // Restaurador: diagnóstico → plano → reparo, tudo no núcleo nativo, neste aparelho.
      const nucleo = await carregarWasm();
      const diagnostico = nucleo.diagnosticar({ canais: p.canais, taxa: p.taxa, progresso: (f: number) => self.postMessage({ tipo: "progresso", fracao: 0.25 * f }), cancelado: () => cancelado });
      const plano = planejarRestauracao(diagnostico.valores, p.opcoes);
      self.postMessage({ tipo: "diagnostico", diagnostico: diagnostico.valores, plano });
      let canais = p.canais;
      let estatisticas = null;
      let deReparo = 0.25;
      if (plano.ia) {
        // Reparo de compressão por IA (Apollo), antes dos filtros. A página já trouxe o áudio a 44,1 kHz.
        if (p.taxa !== APOLLO.taxa) throw new Error("O Apollo precisa do áudio a 44,1 kHz.");
        self.postMessage({ tipo: "progresso", etapa: "Reparando a compressão (Apollo)", fracao: 0.25 });
        const ort = await import("onnxruntime-web/wasm");
        ort.env.wasm.wasmPaths = new URL("/motores/recorte/", self.location.href).href;
        ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1)) : 1;
        const sessao = await ort.InferenceSession.create(new Uint8Array(await modelo("apollo")), { graphOptimizationLevel: "all", enableCpuMemArena: false, executionProviders: ["wasm"] });
        try {
          const corte = new ort.Tensor("float32", new Float32Array([plano.ia.corteHz]), [1]);
          const mistura = new ort.Tensor("float32", new Float32Array([plano.ia.mistura]), [1]);
          canais = await restaurarComApollo(p.canais, async (bloco) => {
            const audio = new ort.Tensor("float32", bloco, [1, 2, APOLLO.bloco]);
            try {
              const { restaurado } = await sessao.run({ audio, corte, mistura });
              try { return (restaurado.data as Float32Array).slice(); } finally { restaurado.dispose(); }
            } finally { audio.dispose(); }
          }, (f) => self.postMessage({ tipo: "progresso", etapa: "Reparando a compressão (Apollo)", fracao: 0.25 + 0.6 * f }), () => cancelado);
        } finally { await sessao.release(); }
        deReparo = 0.85;
      }
      if (!plano.semMudanca) {
        const pronto = nucleo.reparar({ canais, taxa: p.taxa, parametros: plano.parametros, perfil: diagnostico.perfil,
          progresso: (f: number) => self.postMessage({ tipo: "progresso", etapa: "Restaurando", fracao: deReparo + (1 - deReparo) * f }), cancelado: () => cancelado });
        canais = pronto.canais;
        estatisticas = pronto.estatisticas;
      }
      self.postMessage({ tipo: "restauradoAudio", canais, diagnostico: diagnostico.valores, plano, estatisticas }, canais === p.canais ? [] : canais.map((c: Float32Array) => c.buffer));
      return;
    }
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
    type Faixa = { id: string; rotulo: string; canais: Float32Array[] };
    const nucleoNativo = roformer || p.refinarAgudos || p.nativo ? await carregarWasm().catch(() => null) : null;
    const comRoFormer = async (canais: Float32Array[], progresso: (f: number) => void): Promise<Faixa[]> => {
      if (!nucleoNativo) throw new Error("Não foi possível carregar o núcleo de áudio.");
      return separarRoFormer(canais, nucleoNativo, async (espectro) => {
        if (cancelado) throw new Error("Separação cancelada.");
        const entrada = new ort.Tensor("float32", espectro, [1, 431, 4100]);
        try {
          const { mascaras } = await sessao.run({ espectro: entrada });
          try { return (mascaras.data as Float32Array).slice(); }
          finally { mascaras.dispose(); }
        } finally { entrada.dispose(); }
      }, progresso);
    };
    const comSpleeter = async (canais: Float32Array[], progresso: (f: number) => void): Promise<Faixa[]> => {
      const m = MODELO_DE_SEPARACAO;
      const comprimento = canais[0].length;
      const faixas: Float32Array[][] = [];
      const opcoes: Parameters<typeof separar>[0] = {
        comprimento,
        canais: canais.length,
        modo: p.modo,
        mascara: p.mascara,
        ler: (c, inicio, n, destino) => destino.set(canais[Math.min(c, canais.length - 1)].subarray(inicio, inicio + n)),
        executar: async (entrada) => {
          const tensor = new ort.Tensor("float32", entrada, [1, 2, m.quadrosPorTrecho, m.bandas]);
          const saida = await sessao.run({ [m.entrada]: tensor });
          return saida[m.saida].data as Float32Array;
        },
        emitir: (f, c, inicio, amostras, n) => {
          faixas[f] ??= [new Float32Array(comprimento), new Float32Array(comprimento)];
          faixas[f][c].set(amostras.subarray(0, n), inicio);
        },
        progresso,
        cancelado: () => cancelado,
      };
      const definicao = p.nativo && nucleoNativo?.tem("separador") ? await nucleoNativo.separar(opcoes) : await separar(opcoes);
      return definicao.map((d, i) => ({ id: d.id, rotulo: d.rotulo, canais: faixas[i] }));
    };
    const motor = roformer ? comRoFormer : comSpleeter;
    let faixas: Faixa[];
    try {
      // Recuperar a bateria (experimental): uma segunda separação, só do resíduo, devolve à
      // bateria o que o modelo deixou em "outros". Custa uma separação a mais.
      const recuperar = p.recuperarBateria === true;
      faixas = await motor(p.canais, (fracao) => self.postMessage({ tipo: "progresso", fracao: recuperar ? fracao / 2 : fracao }));
      const bateria = faixas.find((f) => f.id === "bateria");
      const residuo = faixas.find((f) => f.id === "outros");
      if (recuperar && bateria && residuo) {
        self.postMessage({ tipo: "etapa", etapa: "Recuperando a bateria dos resíduos" });
        const segunda = await motor(residuo.canais.map((c) => c.slice()), (fracao) => self.postMessage({ tipo: "progresso", fracao: 0.5 + fracao / 2 }));
        const recuperada = segunda.find((f) => f.id === "bateria");
        if (recuperada) for (let c = 0; c < 2; c += 1) {
          const b = bateria.canais[c], r = residuo.canais[c], x = recuperada.canais[c];
          for (let i = 0; i < b.length; i += 1) { b[i] += x[i]; r[i] -= x[i]; }
        }
      }
    } finally {
      await sessao.release();
    }
    // Refinar agudos: no Spleeter é a máscara refinada (já aplicada); no RoFormer, um Wiener
    // multicanal acima de 8 kHz, com a soma das faixas preservada.
    if (roformer && p.refinarAgudos && nucleoNativo?.tem("refinoDeAgudos")) {
      self.postMessage({ tipo: "etapa", etapa: "Refinando os agudos" });
      const prontas = nucleoNativo.refinarAgudos({ faixas: faixas.map((f) => f.canais), taxa: MODELO_DE_SEPARACAO.taxa, corte: 8000, quanto: 0.8, cancelado: () => cancelado });
      faixas = faixas.map((f, i) => ({ ...f, canais: prontas[i] }));
    }
    self.postMessage({ tipo: "pronto", faixas }, faixas.flatMap((f) => f.canais.map((c) => c.buffer)));
  } catch (erro) {
    self.postMessage({ tipo: "erro", mensagem: cancelado ? "Separação cancelada." : erro instanceof Error ? erro.message : String(erro) });
  }
};
