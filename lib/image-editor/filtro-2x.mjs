/** Ampliador subpixel criado pela SLTI. As quatro fases seguem a ordem do treino.
 * A convolução usa RGB pré-multiplicado para que cores invisíveis não vazem nas bordas. */
export function ampliarRgba(origem, largura, altura, kernels) {
  const destino = new Uint8ClampedArray(largura * altura * 16);
  const vizinhos = new Int32Array(25);
  for (let y = 0; y < altura; y++) {
    for (let x = 0; x < largura; x++) {
      let i = 0;
      for (let dy = -2; dy <= 2; dy++) {
        const linha = Math.min(altura - 1, Math.max(0, y + dy)) * largura;
        for (let dx = -2; dx <= 2; dx++)
          vizinhos[i++] = (linha + Math.min(largura - 1, Math.max(0, x + dx))) * 4;
      }
      for (let fase = 0; fase < 4; fase++) {
        const pesos = kernels[fase];
        let a = 0, r = 0, g = 0, b = 0;
        for (let k = 0; k < 25; k++) {
          const p = vizinhos[k];
          const peso = origem[p + 3] * pesos[k];
          a += peso;
          r += origem[p] * peso;
          g += origem[p + 1] * peso;
          b += origem[p + 2] * peso;
        }
        const p = ((2 * y + (fase >> 1)) * largura * 2 + 2 * x + (fase & 1)) * 4;
        destino[p + 3] = a;
        if (destino[p + 3]) {
          destino[p] = r / a;
          destino[p + 1] = g / a;
          destino[p + 2] = b / a;
        }
      }
    }
  }
  return destino;
}
