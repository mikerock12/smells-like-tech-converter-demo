"use client";

import { conferirCriacaoMusical, semanaDeSaoPaulo } from "@/packages/converter-core/musica-plano.mjs";

/**
 * Projetos musicais criados na semana (segunda a domingo, horário de Brasília), guardados no
 * navegador por id. Reabrir não conta; limpar os dados do site zera (limite de conveniência).
 */

const PREFIXO = "slt.uso.musica.";

export function criadosNaSemana(): string[] {
  try {
    const lista = JSON.parse(localStorage.getItem(PREFIXO + semanaDeSaoPaulo()) ?? "[]");
    return Array.isArray(lista) ? lista.filter((id) => typeof id === "string") : [];
  } catch {
    return [];
  }
}

export function podeCriar(plano: "gratis" | "pro", projetoId: string) {
  return conferirCriacaoMusical(plano, { projetoId, criadosNaSemana: criadosNaSemana() });
}

export function registrarCriacao(projetoId: string): void {
  try {
    const semana = semanaDeSaoPaulo();
    const lista = criadosNaSemana();
    if (!lista.includes(projetoId)) localStorage.setItem(PREFIXO + semana, JSON.stringify([...lista, projetoId]));
    for (let i = localStorage.length - 1; i >= 0; i -= 1) {
      const chave = localStorage.key(i);
      if (chave?.startsWith(PREFIXO) && chave !== PREFIXO + semana) localStorage.removeItem(chave);
    }
  } catch {
    // Sem armazenamento, sem contador.
  }
}
