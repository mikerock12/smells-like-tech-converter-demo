/** Quadros por coluna dos picos guardados (a forma de onda desenha a partir deles). */
export const QUADROS_POR_PICO = 256;

/** Mínimo e máximo de cada grupo de 256 quadros, dos dois canais juntos: [min0, max0, min1, max1...]. */
export function calcularPicos(canais: Float32Array[]): Float32Array {
  const n = canais[0]?.length ?? 0;
  const colunas = Math.ceil(n / QUADROS_POR_PICO);
  const picos = new Float32Array(colunas * 2);
  for (let k = 0; k < colunas; k += 1) {
    let min = 0;
    let max = 0;
    const fim = Math.min(n, (k + 1) * QUADROS_POR_PICO);
    for (const canal of canais) {
      for (let i = k * QUADROS_POR_PICO; i < fim; i += 1) {
        const v = canal[i];
        if (v < min) min = v;
        if (v > max) max = v;
      }
    }
    picos[k * 2] = min;
    picos[k * 2 + 1] = max;
  }
  return picos;
}
