/**
 * Tempos do corte como as pessoas digitam. No celular o teclado numérico não tem ":", então
 * "1-05" e "1 05" valem o mesmo que "1:05". A casa decimal dos segundos pode ser vírgula ou
 * ponto ("1:05,5"). Também aceita só segundos ("90") e horas ("1:02:03").
 */
export function lerTempoEmSegundos(texto: string | number | boolean | undefined): number | null {
  const valor = String(texto ?? "").trim();
  if (!valor) return null;
  const partes = valor.split(/[-:\s]+/);
  if (partes.length > 3 || partes.some((parte) => parte === "")) return null;
  let segundos = 0;
  for (let indice = 0; indice < partes.length; indice++) {
    const ultima = indice === partes.length - 1;
    const parte = ultima ? partes[indice].replace(",", ".") : partes[indice];
    if (!/^\d+(\.\d+)?$/.test(parte) || (!ultima && parte.includes("."))) return null;
    const numero = Number(parte);
    if (indice > 0 && numero >= 60) return null;
    segundos = segundos * 60 + numero;
  }
  return segundos;
}

/** "m:ss" ou "h:mm:ss", com os milissegundos quando houver ("1:05,5", "1:05,135"). */
export function escreverTempo(segundos: number): string {
  const milesimos = Math.round(Math.max(0, segundos) * 1000);
  const inteiros = Math.floor(milesimos / 1000);
  const horas = Math.floor(inteiros / 3600);
  const minutos = Math.floor((inteiros % 3600) / 60);
  const resto = String(inteiros % 60).padStart(2, "0");
  const base = horas > 0 ? `${horas}:${String(minutos).padStart(2, "0")}:${resto}` : `${minutos}:${resto}`;
  const fracao = String(milesimos % 1000).padStart(3, "0").replace(/0+$/, "");
  return fracao ? `${base},${fracao}` : base;
}

/** A posição na linha do tempo do player, sempre completa: "00:01:05,135". */
export function escreverTempoCompleto(segundos: number): string {
  const milesimos = Math.round(Math.max(0, segundos) * 1000);
  const inteiros = Math.floor(milesimos / 1000);
  const dois = (valor: number) => String(valor).padStart(2, "0");
  return `${dois(Math.floor(inteiros / 3600))}:${dois(Math.floor((inteiros % 3600) / 60))}:${dois(inteiros % 60)},${String(milesimos % 1000).padStart(3, "0")}`;
}

/** Arredonda para o milissegundo, a precisão das marcações. */
export function milesimo(segundos: number): number {
  return Math.round(segundos * 1000) / 1000;
}
