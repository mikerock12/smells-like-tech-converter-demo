import type { Projeto } from "@/packages/converter-core/estudio.mjs";

import type { ArquivosDoProjeto } from "./exportar";

/**
 * O projeto do estúdio no dialeto do plugin (StudioExportOptions do aplicativo): os arquivos
 * vão em ordem no envio e o projeto passa a citá-los como "#0", "#1"…, que é o que o
 * StudioEngine aceita — ele nunca lê arquivo que não veio no trabalho.
 */
export function pedidoParaOPlugin(
  projeto: Projeto,
  arquivos: ArquivosDoProjeto,
  modeloDeTranscricao = "base",
): { arquivos: File[]; opcoes: Record<string, unknown> } {
  const ordem: string[] = [];
  const indice = (chave: string): string => {
    if (!arquivos.has(chave)) throw new Error("Um arquivo do projeto não está mais aberto nesta página. Adicione-o de novo.");
    if (!ordem.includes(chave)) ordem.push(chave);
    return `#${ordem.indexOf(chave)}`;
  };
  const projetoDoPlugin: Projeto = {
    ...projeto,
    clipes: projeto.clipes.map((clipe) => ({ ...clipe, arquivo: indice(clipe.arquivo) })),
    audios: projeto.audios.map((audio) => ({ ...audio, arquivo: indice(audio.arquivo) })),
  };
  return {
    arquivos: ordem.map((chave) => arquivos.get(chave)!),
    opcoes: {
      operation: "studio.export",
      Project: projetoDoPlugin,
      Quality: 80,
      TranscriptionModel: modeloDeTranscricao,
      TranscriptionLanguage: "auto",
    },
  };
}
