import { converterLegenda } from "@/packages/converter-core/legendas.mjs";
import { getFormat, outputFileName } from "@/packages/converter-core/index.mjs";

import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";

/** SRT ↔ VTT ↔ TXT, com atraso. Não precisa de motor nenhum: é texto. */
export async function motorDeLegenda(contexto: ContextoDoMotor): Promise<{ saidas: Saida[] }> {
  const formato = String(contexto.opcoes.formato ?? "vtt") as "srt" | "vtt" | "txt";
  const atraso = Number(contexto.opcoes.atraso ?? 0) || 0;
  const saidas: Saida[] = [];

  for (const entrada of contexto.entradas) {
    const conteudo = await entrada.arquivo.text();
    let convertido: string;
    try {
      convertido = converterLegenda(conteudo, formato, atraso);
    } catch (erro) {
      throw new ConversionError("legenda_invalida", erro instanceof Error ? erro.message : "Legenda inválida.");
    }
    const bytes = new TextEncoder().encode(convertido);
    saidas.push({
      nome: outputFileName(entrada.nome, formato),
      mime: getFormat(formato)?.mime ?? "text/plain",
      bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
    });
  }

  return { saidas };
}
