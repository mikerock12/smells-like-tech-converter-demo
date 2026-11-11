"use client";

const CHAVE = "criacao-musical:salvar-automaticamente";

export function salvarAutomaticamente(): boolean {
  try {
    return typeof localStorage !== "undefined" && localStorage.getItem(CHAVE) === "1";
  } catch {
    return false;
  }
}

export function guardarSalvamentoAutomatico(ligado: boolean): void {
  try {
    localStorage.setItem(CHAVE, ligado ? "1" : "0");
  } catch {
    // A preferência volta ao modo de perguntar se o navegador bloquear o armazenamento.
  }
}
