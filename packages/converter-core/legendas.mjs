/**
 * Legendas: SRT e VTT, de ida e de volta, e texto limpo.
 *
 * Puro de propósito: nada de DOM. As duas gramáticas são pequenas o bastante para um
 * leitor tolerante, que aceita BOM, CRLF, cabeçalhos do VTT e blocos sem número.
 */

/** Um trecho de legenda com tempos em milissegundos. */
function bloco(inicio, fim, texto) {
  return { inicio, fim, texto };
}

/** "00:01:02,345" ou "00:01:02.345" ou "01:02.345" → milissegundos. */
export function lerTempo(texto) {
  const partes = String(texto).trim().replace(",", ".").split(":");
  if (partes.length < 2 || partes.length > 3) return null;
  const segundos = Number(partes.pop());
  const minutos = Number(partes.pop());
  const horas = partes.length ? Number(partes.pop()) : 0;
  if ([segundos, minutos, horas].some((valor) => !Number.isFinite(valor))) return null;
  return Math.round(((horas * 60 + minutos) * 60 + segundos) * 1000);
}

function doisDigitos(valor) {
  return String(valor).padStart(2, "0");
}

/** Milissegundos → "00:01:02,345" (SRT) ou "00:01:02.345" (VTT). */
export function escreverTempo(ms, separador = ",") {
  const total = Math.max(0, Math.round(ms));
  const horas = Math.floor(total / 3_600_000);
  const minutos = Math.floor((total % 3_600_000) / 60_000);
  const segundos = Math.floor((total % 60_000) / 1000);
  const milis = total % 1000;
  return `${doisDigitos(horas)}:${doisDigitos(minutos)}:${doisDigitos(segundos)}${separador}${String(milis).padStart(3, "0")}`;
}

/**
 * Lê SRT ou VTT. Blocos sem tempo válido são ignorados; o resto sobrevive a quase
 * qualquer formatação.
 */
export function lerLegenda(conteudo) {
  const texto = String(conteudo ?? "").replace(/^\ufeff/, "").replace(/\r\n?/g, "\n");
  const blocos = [];

  for (const trecho of texto.split(/\n{2,}/)) {
    const linhas = trecho.split("\n").map((linha) => linha.trimEnd());
    const indiceDoTempo = linhas.findIndex((linha) => linha.includes("-->"));
    if (indiceDoTempo < 0) continue;

    const [de, ate] = linhas[indiceDoTempo].split("-->").map((parte) => parte.trim().split(/\s+/)[0]);
    const inicio = lerTempo(de);
    const fim = lerTempo(ate);
    if (inicio === null || fim === null) continue;

    const corpo = linhas
      .slice(indiceDoTempo + 1)
      .join("\n")
      .trim();
    if (!corpo) continue;

    blocos.push(bloco(inicio, fim, corpo));
  }

  return blocos;
}

export function escreverSrt(blocos) {
  return blocos
    .map((item, indice) => `${indice + 1}\n${escreverTempo(item.inicio, ",")} --> ${escreverTempo(item.fim, ",")}\n${item.texto}`)
    .join("\n\n")
    .concat("\n");
}

export function escreverVtt(blocos) {
  const corpo = blocos
    .map((item) => `${escreverTempo(item.inicio, ".")} --> ${escreverTempo(item.fim, ".")}\n${item.texto}`)
    .join("\n\n");
  return `WEBVTT\n\n${corpo}\n`;
}

/** Só o texto, sem tempos e sem marcação, um parágrafo por bloco. */
export function escreverTexto(blocos) {
  return blocos
    .map((item) => item.texto.replace(/<[^>]+>/g, "").replace(/\{\\[^}]+\}/g, "").trim())
    .filter(Boolean)
    .join("\n")
    .concat("\n");
}

/** Desloca todos os tempos. Negativo adianta; nada fica abaixo de zero. */
export function deslocar(blocos, milissegundos) {
  const delta = Math.round(Number(milissegundos) || 0);
  return blocos.map((item) => bloco(Math.max(0, item.inicio + delta), Math.max(0, item.fim + delta), item.texto));
}

/** Converte de um formato para outro, com deslocamento opcional em segundos. */
export function converterLegenda(conteudo, formatoDeSaida, atrasoSegundos = 0) {
  const blocos = deslocar(lerLegenda(conteudo), atrasoSegundos * 1000);
  if (blocos.length === 0) {
    throw new Error("Não encontrei nenhuma legenda com tempo neste arquivo.");
  }

  switch (formatoDeSaida) {
    case "srt":
      return escreverSrt(blocos);
    case "vtt":
      return escreverVtt(blocos);
    case "txt":
      return escreverTexto(blocos);
    default:
      throw new Error(`Não sei gravar legenda em ${formatoDeSaida}.`);
  }
}
