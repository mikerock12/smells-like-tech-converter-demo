/**
 * Quantas vezes uma animação toca: 0 é sem fim, o padrão de sempre. Espelho: AnimationRepeats.cs
 * e Repeticoes (Modelo.kt).
 */
export const REPETICOES = [
  { valor: 0, rotulo: "Sem fim" },
  { valor: 3, rotulo: "3 vezes" },
  { valor: 5, rotulo: "5 vezes" },
  { valor: 10, rotulo: "10 vezes" },
];

export function repeticoesValidas(valor) {
  return REPETICOES.some((opcao) => opcao.valor === valor);
}

/** O GIF guarda quantas vezes repete depois da primeira: tocar 3 vezes é gravar 2. Zero é sem fim. */
export function loopDoGif(repeticoes) {
  return repeticoes > 0 ? repeticoes - 1 : 0;
}
