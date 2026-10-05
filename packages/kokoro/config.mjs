export const VOZES = Object.freeze([
  Object.freeze({ id: "pf_dora", sid: 42, nome: "Dora" }),
  Object.freeze({ id: "pm_alex", sid: 43, nome: "Alex" }),
  Object.freeze({ id: "pm_santa", sid: 44, nome: "Santa" }),
]);
export const CACHE = "slt-kokoro-82m-v1-fp32";
export const MODELO_URL = "/api/motores/kokoro";
export function locutor(id = "pf_dora") {
  const voz = VOZES.find((item) => item.id === id);
  if (!voz) throw new Error("Escolha Dora, Alex ou Santa do Kokoro-82M.");
  return voz.sid;
}
export function velocidade(rate = 0) {
  if (!Number.isFinite(rate)) throw new Error("Velocidade inválida.");
  return 2 ** (Math.max(-10, Math.min(10, rate)) / 10);
}
/** Limita cada inferência, sem descartar o fim de documentos longos. */
export function dividirTexto(texto, limite = 200) {
  if (!Number.isInteger(limite) || limite < 2) throw new Error("Limite inválido.");
  const partes = [];
  let resto = texto.trim();
  while (resto.length > limite) {
    const janela = resto.slice(0, limite);
    let corte = Math.max(...[". ", "! ", "? ", "\n"].map((ponto) => janela.lastIndexOf(ponto)));
    corte = corte > limite / 3 ? corte + 1 : janela.lastIndexOf(" ");
    if (corte <= 0) corte = limite;
    // Não separe os pares UTF-16, mesmo dentro de palavras muito longas.
    if (/[\uD800-\uDBFF]/u.test(resto[corte - 1])) corte -= 1;
    partes.push(resto.slice(0, corte).trim()); resto = resto.slice(corte).trim();
  }
  if (resto) partes.push(resto);
  return partes;
}
