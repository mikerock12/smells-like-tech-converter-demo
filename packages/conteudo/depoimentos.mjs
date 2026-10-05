/**
 * Depoimentos de quem usa, para a home e para as páginas de PDF e transcrição.
 *
 * Só entram depoimentos reais, com a autorização de quem falou, e com profissão e
 * cidade (o nome é opcional). A home esconde o bloco enquanto a lista estiver vazia.
 * Depoimento inventado é publicidade enganosa (Código de Defesa do Consumidor, art. 37)
 * e derruba exatamente a confiança que o produto vende.
 *
 * Formato de cada item:
 *   { texto: "Uso para PDF de cliente. Nada sobe.", autor: "Contadora", local: "Porto Alegre/RS", tema: "pdf" }
 * `tema` ("pdf", "transcricao", "imagem", "video", "geral") decide onde mais ele aparece.
 */
export const DEPOIMENTOS = Object.freeze([]);

export function depoimentosDoTema(tema) {
  return DEPOIMENTOS.filter((item) => item.tema === tema || item.tema === "geral");
}
