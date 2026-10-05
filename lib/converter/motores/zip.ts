import { zipSync } from "fflate";

import type { Saida } from "../protocol";

/**
 * Muitas saídas viram um ZIP só. Sem compressão (level 0): imagem, PDF e vídeo já
 * vêm comprimidos, e comprimir de novo só gastaria tempo.
 */
export function empacotar(saidas: readonly Saida[], nomeDoZip: string): Saida {
  const arquivos: Record<string, Uint8Array> = {};
  const usados = new Set<string>();

  for (const saida of saidas) {
    let nome = saida.nome;
    let contador = 2;
    while (usados.has(nome)) {
      nome = saida.nome.replace(/(\.[^.]+)$/, ` (${contador})$1`);
      contador += 1;
    }
    usados.add(nome);
    arquivos[nome] = new Uint8Array(saida.bytes);
  }

  const zip = zipSync(arquivos, { level: 0 });
  return {
    nome: nomeDoZip,
    mime: "application/zip",
    bytes: zip.buffer.slice(zip.byteOffset, zip.byteOffset + zip.byteLength) as ArrayBuffer,
  };
}

/** A partir de quantas saídas vale mais um ZIP do que uma pilha de downloads. */
export const SAIDAS_PARA_ZIP = 4;
