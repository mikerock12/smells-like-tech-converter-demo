"use client";

import { lerOpcoesDoRestaurador, type DiagnosticoDoAudio, type PlanoDaRestauracao } from "@/packages/converter-core/restaurador.mjs";
import { APOLLO } from "@/packages/converter-core/apollo.mjs";

/**
 * Restaurar áudio com IA (experimental), no navegador: diagnóstico, plano e reparo rodam no
 * núcleo nativo em WebAssembly, num worker, sem enviar nada. A entrada não muda (é copiada).
 * Com o reparo de compressão por IA ligado, o Apollo (ONNX, 74 MB, baixado uma vez e conferido
 * pelo SHA-256) roda antes do reparo, a 44,1 kHz; o áudio volta à taxa original no fim.
 */

/** O plano pode chamar o Apollo? (a opção ligada ou a etapa dosada no Avançado). */
export function podeUsarApollo(opcoes: unknown): boolean {
  const o = lerOpcoesDoRestaurador(typeof opcoes === "string" ? opcoes : JSON.stringify(opcoes ?? {}));
  return o.perfil === "manual" ? (o.etapas.compressao ?? 0) > 0 : o.ia;
}

export type Restaurado = {
  canais: Float32Array[];
  diagnostico: DiagnosticoDoAudio;
  plano: PlanoDaRestauracao;
  estatisticas: { estalos: number; recortes: number; reducaoDb: number; limitadorDb: number } | null;
};

export async function restaurarAudio(canais: Float32Array[], taxa: number, opcoes: unknown,
  progresso: (etapa: string, fracao: number) => void = () => {}, sinal?: AbortSignal): Promise<Restaurado> {
  const comApollo = podeUsarApollo(opcoes);
  let taxaDeTrabalho = taxa;
  let entrada: Float32Array[] = canais.map((c) => c.slice());
  if (comApollo) {
    const { garantirModelo, reamostrar } = await import("./separacao");
    await garantirModelo((f) => progresso("Baixando o modelo Apollo", f), sinal, "apollo");
    if (taxa !== APOLLO.taxa) { entrada = await reamostrar(entrada, taxa, APOLLO.taxa); taxaDeTrabalho = APOLLO.taxa; }
  }
  const { default: Fabrica } = await import("./separacao.worker?worker");
  const worker = new Fabrica({ name: "restaurador-de-audio" });
  let etapa = "Analisando a qualidade";
  try {
    const pronto = await new Promise<Restaurado>((resolver, rejeitar) => {
      const parar = () => { worker.postMessage({ tipo: "cancelar" }); rejeitar(new Error("Restauração cancelada.")); };
      sinal?.addEventListener("abort", parar, { once: true });
      worker.onmessage = (evento) => {
        const m = evento.data;
        if (m.tipo === "progresso") progresso(m.etapa ?? etapa, m.fracao ?? 0);
        else if (m.tipo === "diagnostico") etapa = m.plano?.semMudanca ? "Sem defeitos para tratar" : "Restaurando";
        else if (m.tipo === "restauradoAudio") resolver({ canais: m.canais, diagnostico: m.diagnostico, plano: m.plano, estatisticas: m.estatisticas });
        else if (m.tipo === "erro") rejeitar(new Error(m.mensagem));
      };
      worker.onerror = (e) => rejeitar(new Error(e.message || "A restauração parou com um erro."));
      worker.postMessage({ tipo: "restaurarAudio", canais: entrada, taxa: taxaDeTrabalho, opcoes }, entrada.map((c) => c.buffer));
    });
    if (taxaDeTrabalho !== taxa) {
      const { reamostrar } = await import("./separacao");
      const voltou = await reamostrar(pronto.canais, taxaDeTrabalho, taxa);
      // A conta da taxa arredonda para cima: corta na duração original.
      pronto.canais = voltou.map((c) => c.length > canais[0].length ? c.slice(0, canais[0].length) : c);
    }
    if (!pronto.plano.semMudanca && (pronto.plano.parametros.entradaDb ?? 0) < 0) normalizarPico(pronto.canais);
    return pronto;
  } finally {
    worker.terminate();
  }
}

/** Só o diagnóstico e o plano (o botão "Analisar qualidade"), sem baixar nem processar. */
export async function diagnosticarAudio(canais: Float32Array[], taxa: number, opcoes: unknown): Promise<Pick<Restaurado, "diagnostico" | "plano">> {
  const { default: Fabrica } = await import("./separacao.worker?worker");
  const worker = new Fabrica({ name: "diagnostico-de-audio" });
  const entrada = canais.map((c) => c.slice());
  try {
    return await new Promise((resolver, rejeitar) => {
      worker.onmessage = (evento) => {
        const m = evento.data;
        if (m.tipo === "diagnostico") resolver({ diagnostico: m.diagnostico, plano: m.plano });
        else if (m.tipo === "erro") rejeitar(new Error(m.mensagem));
      };
      worker.onerror = (e) => rejeitar(new Error(e.message || "O diagnóstico parou com um erro."));
      worker.postMessage({ tipo: "diagnosticarAudio", canais: entrada, taxa, opcoes }, entrada.map((c) => c.buffer));
    });
  } finally {
    worker.terminate();
  }
}

function picoDe(canais: Float32Array[]): number {
  let pico = 0;
  for (const c of canais) for (let i = 0; i < c.length; i += 1) { const a = Math.abs(c[i]); if (a > pico) pico = a; }
  return pico;
}

/** O pico máximo da saída (−1 dBFS): o volume volta até ele sem limitar de novo. */
const TETO_DA_SAIDA = 10 ** (-1 / 20);

/**
 * Quando o plano baixou a entrada (margem para os picos que voltam), o resultado sobe até o
 * pico de −1 dBFS (no máximo +12 dB): mais baixo que o original esmagado, com a dinâmica de volta.
 */
export function normalizarPico(canais: Float32Array[]): number {
  const pico = picoDe(canais);
  if (!(pico > 0)) return 1;
  const ganho = Math.min(4, TETO_DA_SAIDA / pico);
  if (ganho > 1.0001) for (const c of canais) for (let i = 0; i < c.length; i += 1) c[i] *= ganho;
  return ganho > 1.0001 ? ganho : 1;
}

/**
 * Ganho que iguala o RMS do restaurado ao do original (de −6 a +12 dB) sem passar de −1 dBFS
 * de pico: o A/B fica nivelado e o restaurado não clipa. Com a dinâmica de volta, ele pode
 * ficar um pouco mais baixo que o original esmagado.
 */
export function ganhoDeNivelamento(original: Float32Array[], restaurado: Float32Array[]): number {
  const energia = (cs: Float32Array[]) => {
    let s = 0;
    let n = 0;
    for (const c of cs) { for (let i = 0; i < c.length; i += 3) s += c[i] * c[i]; n += Math.ceil(c.length / 3); }
    return n ? s / n : 0;
  };
  const a = energia(original);
  const b = energia(restaurado);
  if (!(a > 0) || !(b > 0)) return 1;
  const ganho = Math.min(4, Math.max(0.5, Math.sqrt(a / b)));
  const pico = picoDe(restaurado);
  return ganho > 1 && pico > 0 ? Math.max(1, Math.min(ganho, TETO_DA_SAIDA / pico)) : ganho;
}
