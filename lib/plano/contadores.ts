import { limite as limiteDoPlano } from "@/packages/converter-core/precos.mjs";
import type { Contador } from "@/packages/converter-core/catalogo.mjs";

/**
 * Contadores diários do plano grátis, no próprio navegador.
 *
 * Não existe servidor contando nada: os limites são de conveniência e ficam no
 * localStorage de quem usa, zerando à meia-noite local. Quem limpar o armazenamento
 * zera o contador — e tudo bem. O objetivo não é policiar, é tornar o Pro a escolha
 * óbvia para quem usa todo dia.
 */

const PREFIXO = "slt.uso.";

function chaveDeHoje(contador: Contador): string {
  const hoje = new Date();
  const dia = `${hoje.getFullYear()}-${String(hoje.getMonth() + 1).padStart(2, "0")}-${String(hoje.getDate()).padStart(2, "0")}`;
  return `${PREFIXO}${contador}.${dia}`;
}

export function usoDeHoje(contador: Contador): number {
  try {
    return Number(localStorage.getItem(chaveDeHoje(contador)) ?? 0) || 0;
  } catch {
    return 0;
  }
}

export function registrarUso(contador: Contador, quantidade = 1): void {
  try {
    localStorage.setItem(chaveDeHoje(contador), String(usoDeHoje(contador) + quantidade));
    // Contadores de dias passados não servem para nada.
    for (let indice = localStorage.length - 1; indice >= 0; indice -= 1) {
      const chave = localStorage.key(indice);
      if (chave?.startsWith(PREFIXO) && !chave.endsWith(chaveDeHoje(contador).slice(-10)) && chave.includes(`${contador}.`)) {
        localStorage.removeItem(chave);
      }
    }
  } catch {
    // Sem armazenamento, sem contador: a conversão continua.
  }
}

/**
 * Quanto ainda cabe hoje num contador diário, ou null quando não há limite.
 */
export function restanteHoje(plano: "gratis" | "pro", contador: Contador): number | null {
  const teto = limiteDoPlano(plano, contador);
  if (teto === null) return null;
  return Math.max(0, teto - usoDeHoje(contador));
}
