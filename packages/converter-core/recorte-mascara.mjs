/**
 * Limpeza da máscara da remoção de fundo. O modelo devolve uma confiança de 0 a 1 em cada
 * ponto, e aplicada direto ela deixa resquícios: névoa onde a confiança é baixa, um halo do
 * fundo em volta da figura e manchas soltas longe dela. Espelho: StudioMask.cs e
 * RefinoDaMascara.kt, conferidos por recorte-casos.json.
 */

/** Daqui para cima um ponto conta como figura ao procurar as manchas soltas. */
export const CORTE_DA_ILHA = 0.5;
/** Uma mancha menor que esta fração da maior não é figura: sai inteira. */
export const ILHA_MINIMA = 0.02;
/** Abaixo de BAIXO a confiança vira fundo; acima de ALTO, figura sólida. */
export const MASCARA_BAIXO = 0.45;
export const MASCARA_ALTO = 0.85;

/** Aperta a borda: a transição fica mais curta e cai para dentro da figura, longe do fundo. */
export function curvaDaMascara(valor) {
  const t = Math.min(1, Math.max(0, (valor - MASCARA_BAIXO) / (MASCARA_ALTO - MASCARA_BAIXO)));
  return t * t * (3 - 2 * t);
}

/** Tira as manchas soltas, no lugar. Devolve quantas saíram. */
export function limparIlhas(mascara, largura, altura) {
  const rotulos = new Int32Array(largura * altura);
  const tamanhos = [0];
  const pilha = new Int32Array(largura * altura);
  for (let inicio = 0; inicio < mascara.length; inicio++) {
    if (rotulos[inicio] !== 0 || mascara[inicio] < CORTE_DA_ILHA) continue;
    const rotulo = tamanhos.length;
    let topo = 0, tamanho = 0;
    pilha[topo++] = inicio;
    rotulos[inicio] = rotulo;
    while (topo > 0) {
      const ponto = pilha[--topo];
      tamanho++;
      const x = ponto % largura;
      const vizinhos = [x > 0 ? ponto - 1 : -1, x < largura - 1 ? ponto + 1 : -1, ponto - largura, ponto + largura];
      for (const vizinho of vizinhos) {
        if (vizinho < 0 || vizinho >= mascara.length || rotulos[vizinho] !== 0 || mascara[vizinho] < CORTE_DA_ILHA) continue;
        rotulos[vizinho] = rotulo;
        pilha[topo++] = vizinho;
      }
    }
    tamanhos.push(tamanho);
  }
  const limite = Math.max(...tamanhos) * ILHA_MINIMA;
  for (let i = 0; i < mascara.length; i++) if (rotulos[i] !== 0 && tamanhos[rotulos[i]] < limite) mascara[i] = 0;
  return tamanhos.slice(1).filter((tamanho) => tamanho < limite).length;
}
