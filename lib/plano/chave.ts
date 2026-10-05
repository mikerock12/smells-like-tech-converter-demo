import { CHAVE_PUBLICA_DAS_LICENCAS } from "@/packages/licenca/chave-publica.mjs";
import { importarPublica, verificarLicenca, type CargaDaLicenca } from "@/packages/licenca/index.mjs";
import { planoDoSite } from "@/packages/converter-core/precos.mjs";

/**
 * A chave de acesso guardada neste navegador.
 *
 * Fica no localStorage, e só nele: o site não tem conta, sessão nem cookie. A chave se
 * prova sozinha pela assinatura, conferida com a chave pública embutida no site. A
 * variável NEXT_PUBLIC_LICENCA_CHAVE_PUBLICA, quando definida no build, substitui a
 * embutida — é o caminho para trocar de par sem mexer no código.
 */

const ARMAZENAMENTO = "slt.chave";

export const CHAVE_PUBLICA = process.env.NEXT_PUBLIC_LICENCA_CHAVE_PUBLICA || CHAVE_PUBLICA_DAS_LICENCAS;

export type EstadoDaChave =
  | { situacao: "sem-chave" }
  | { situacao: "invalida"; motivo: "formato" | "assinatura" | "sem-chave-publica" }
  | { situacao: "valida"; carga: CargaDaLicenca; plano: "gratis" | "pro"; vencida: boolean };

let publica: Promise<CryptoKey | null> | null = null;

function chavePublica(): Promise<CryptoKey | null> {
  publica ??= CHAVE_PUBLICA ? importarPublica(CHAVE_PUBLICA).catch(() => null) : Promise.resolve(null);
  return publica;
}

export function lerChaveGuardada(): string | null {
  try {
    return localStorage.getItem(ARMAZENAMENTO);
  } catch {
    return null;
  }
}

export function guardarChave(chave: string | null): void {
  try {
    if (chave) localStorage.setItem(ARMAZENAMENTO, chave.trim());
    else localStorage.removeItem(ARMAZENAMENTO);
  } catch {
    // Navegação privada ou armazenamento bloqueado: a chave vale só nesta visita.
  }
}

/** Confere uma chave qualquer, sem guardar. */
export async function avaliarChave(chave: string | null): Promise<EstadoDaChave> {
  if (!chave || !chave.trim()) return { situacao: "sem-chave" };

  const verificadora = await chavePublica();
  if (!verificadora) return { situacao: "invalida", motivo: "sem-chave-publica" };

  const resultado = await verificarLicenca(chave, verificadora);
  if (!resultado.ok) return { situacao: "invalida", motivo: resultado.motivo };

  const plano = planoDoSite(resultado.carga);
  const vencida = resultado.carga.expira !== null && Date.parse(resultado.carga.expira) < Date.now();
  return { situacao: "valida", carga: resultado.carga, plano, vencida };
}
