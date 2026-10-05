import { limite as limiteDoPlano } from "@/packages/converter-core/precos.mjs";
import type { Ferramenta } from "@/packages/converter-core/catalogo.mjs";

import { restanteHoje } from "@/lib/plano/contadores";

/**
 * Os limites do plano grátis, aplicados antes de converter.
 *
 * São limites de volume: arquivos por vez, minutos de vídeo, páginas de PDF, e os
 * contadores diários das ferramentas pesadas. Nunca de qualidade. Cada verificação
 * devolve nulo (pode seguir) ou a frase que explica o que travou e o que o Pro muda.
 */

export type Plano = "gratis" | "pro";

export interface ItemParaConverter {
  readonly nome: string;
  readonly bytes: number;
  readonly arquivo?: File;
  /** Só quando veio pelo seletor do plugin: nada foi copiado. */
  readonly caminho?: string;
}

/**
 * O que travou. `mensagem` diz só o fato ("você escolheu 8; no grátis são 5"): a oferta
 * do Pro fica com o aviso de limite, que mostra preço e botão.
 */
export interface Bloqueio {
  readonly mensagem: string;
  readonly contador: string;
}

/** Duração de um vídeo ou áudio pelo próprio navegador, sem decodificar. */
export function duracaoDaMidia(arquivo: File): Promise<number | null> {
  return new Promise((resolva) => {
    const url = URL.createObjectURL(arquivo);
    const elemento = document.createElement(arquivo.type.startsWith("audio/") ? "audio" : "video");
    const encerrar = (valor: number | null) => {
      URL.revokeObjectURL(url);
      elemento.removeAttribute("src");
      resolva(valor);
    };
    elemento.preload = "metadata";
    elemento.onloadedmetadata = () => encerrar(Number.isFinite(elemento.duration) ? elemento.duration : null);
    elemento.onerror = () => encerrar(null);
    setTimeout(() => encerrar(null), 8000);
    elemento.src = url;
  });
}

function frase(plural: string, quantidade: number, singular = plural.replace(/s$/, "")): string {
  return `${quantidade} ${quantidade === 1 ? singular : plural}`;
}

/**
 * Confere um lote antes de entrar na fila. Faz as medições que precisam de arquivo
 * (duração, páginas) só quando o limite existe.
 */
export async function conferirLimites(
  plano: Plano,
  ferramenta: Ferramenta,
  itens: readonly ItemParaConverter[],
  extras: { caracteres?: number } = {},
): Promise<Bloqueio | null> {
  if (plano === "pro") return null;

  if (ferramenta.plano === "pro" && ferramenta.limite === "pesado") {
    return { contador: "pesado", mensagem: `No grátis, “${ferramenta.titulo}” não está incluído. As outras ferramentas continuam liberadas.` };
  }

  const lote = limiteDoPlano(plano, "lote");
  if (lote !== null && itens.length > lote) {
    return {
      contador: "lote",
      mensagem: `No grátis são ${lote} arquivos por vez, e você escolheu ${itens.length}. Dá para converter em partes, ou todos de uma vez no Pro.`,
    };
  }

  switch (ferramenta.limite) {
    case "video": {
      const teto = limiteDoPlano(plano, "video");
      if (teto === null) return null;
      for (const item of itens) {
        if (!item.arquivo) continue;
        const segundos = await duracaoDaMidia(item.arquivo);
        if (segundos !== null && segundos > teto * 60) {
          return {
            contador: "video",
            mensagem: `“${item.nome}” tem ${Math.round(segundos / 60)} minutos. No grátis, vídeo vai até ${teto} minutos por arquivo.`,
          };
        }
      }
      return null;
    }
    case "pdf": {
      const teto = limiteDoPlano(plano, "pdf");
      if (teto === null) return null;
      const { contarPaginas } = await import("./pagina/pdfjs");
      for (const item of itens) {
        if (!item.arquivo || !/\.pdf$/i.test(item.nome)) continue;
        const paginas = await contarPaginas(item.arquivo).catch(() => 0);
        if (paginas > teto) {
          return {
            contador: "pdf",
            mensagem: `“${item.nome}” tem ${paginas} páginas. No grátis, PDF vai até ${teto} páginas por arquivo.`,
          };
        }
      }
      return null;
    }
    case "pesado": {
      const restante = restanteHoje(plano, "pesado");
      if (restante !== null && itens.length > restante) {
        return {
          contador: "pesado",
          mensagem:
            restante === 0
              ? `As ${limiteDoPlano(plano, "pesado")} conversões pelo plugin de hoje já foram usadas. Amanhã elas voltam.`
              : `Hoje ainda cabem ${frase("conversões", restante, "conversão")} pelo plugin no grátis; você escolheu ${itens.length}.`,
        };
      }
      return null;
    }
    case "transcricao": {
      const restante = restanteHoje(plano, "transcricao");
      if (restante === null) return null;
      let total = 0;
      for (const item of itens) {
        if (!item.arquivo) continue;
        total += ((await duracaoDaMidia(item.arquivo)) ?? 0) / 60;
      }
      if (total > restante) {
        return {
          contador: "transcricao",
          mensagem: `Isso dá ${Math.ceil(total)} minutos de transcrição, e no grátis cabem ${restante} por dia, para experimentar.`,
        };
      }
      return null;
    }
    case "narracao": {
      const restante = restanteHoje(plano, "narracao");
      const caracteres = extras.caracteres ?? 0;
      if (restante !== null && caracteres > restante) {
        return {
          contador: "narracao",
          mensagem: `Seu texto tem ${caracteres.toLocaleString("pt-BR")} caracteres, e no grátis cabem ${restante.toLocaleString("pt-BR")} por dia.`,
        };
      }
      return null;
    }
    case "ocr": {
      const restante = restanteHoje(plano, "ocr");
      if (restante !== null && itens.length > restante) {
        return {
          contador: "ocr",
          mensagem:
            restante === 0
              ? `As ${limiteDoPlano(plano, "ocr")} páginas de reconhecimento de texto de hoje já foram. Amanhã elas voltam.`
              : `Hoje ainda cabem ${frase("páginas", restante)} de reconhecimento no grátis; você escolheu ${itens.length}.`,
        };
      }
      return null;
    }
    default:
      return null;
  }
}
