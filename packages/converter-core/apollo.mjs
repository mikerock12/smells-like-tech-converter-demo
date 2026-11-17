/**
 * Reparo de compressão por IA (Apollo, JusperLee/Apollo, CC BY-SA 4.0: código e pesos). O
 * modelo recebe blocos estéreo de 5 s a 44,1 kHz e devolve o bloco restaurado; abaixo do
 * corte o grafo mantém o original (o Apollo recria o espectro inteiro, e o MP3 preserva bem
 * os graves) e acima mistura o Apollo. Os blocos se sobrepõem pela metade, com transição
 * linear de 0,5 s, como no RoFormer. Esta é a referência; o C# e o Kotlin seguem igual.
 */

export const APOLLO = Object.freeze({
  id: "apollo-jusperlee-v1-onnx-fp32",
  arquivo: "apollo-restauracao-fp32-v1.onnx",
  url: "https://converter.smellsliketech.com.br/modelos/apollo-restauracao-fp32-v1.onnx",
  bytes: 73947536,
  sha256: "bd0e9aeb69c84056715e7bfc6386cbb8cf17e7e980add13b87177afceec2d893",
  licenca: "CC-BY-SA-4.0",
  fonte: "https://github.com/JusperLee/Apollo",
  pesos: "https://huggingface.co/JusperLee/Apollo",
  atribuicao: "Apollo, de Kai Li e Yi Luo (JusperLee/Apollo), CC BY-SA 4.0. Convertido para ONNX pela Smells Like Tech, sob a mesma licença.",
  taxa: 44100,
  bloco: 220500,
});

const BLOCO = APOLLO.bloco;
const PASSO = BLOCO / 2;
const TRANSICAO = BLOCO / 10;

/** Onde começa cada bloco (o último termina no fim do áudio, ou é o único). */
export function inicioDosBlocos(comprimento) {
  const inicios = [];
  for (let inicio = 0; inicio < Math.max(1, comprimento - PASSO); inicio += PASSO) inicios.push(inicio);
  return inicios;
}

/**
 * Restaura um áudio estéreo (ou mono, repetido nos dois canais) a 44,1 kHz.
 * `executar(bloco: Float32Array de 2×BLOCO, planar) → Float32Array` do mesmo tamanho.
 * Devolve os canais na quantidade da entrada.
 */
export async function restaurarComApollo(canais, executar, progresso = () => {}, cancelado = () => false) {
  const n = canais[0].length;
  if (!n) throw new Error("O áudio está vazio.");
  const saida = [new Float32Array(n), new Float32Array(n)];
  const pesos = new Float32Array(n);
  const entrada = new Float32Array(2 * BLOCO);
  const inicios = inicioDosBlocos(n);
  for (let b = 0; b < inicios.length; b++) {
    if (cancelado()) throw new Error("Restauração cancelada.");
    const inicio = inicios[b];
    const fim = Math.min(n, inicio + BLOCO);
    const validos = fim - inicio;
    entrada.fill(0);
    entrada.set(canais[0].subarray(inicio, fim), 0);
    entrada.set((canais[1] ?? canais[0]).subarray(inicio, fim), BLOCO);
    const y = await executar(entrada);
    if (!y || y.length !== 2 * BLOCO) throw new Error("O formato de saída do Apollo não confere.");
    for (let i = 0; i < validos; i++) {
      let p = 1;
      if (inicio > 0 && i < TRANSICAO) p = i / (TRANSICAO - 1);
      if (fim < n && i >= validos - TRANSICAO) p = Math.min(p, (validos - 1 - i) / (TRANSICAO - 1));
      saida[0][inicio + i] += y[i] * p;
      saida[1][inicio + i] += y[BLOCO + i] * p;
      pesos[inicio + i] += p;
    }
    progresso((b + 1) / inicios.length);
  }
  for (let i = 0; i < n; i++) {
    const w = pesos[i] > 1e-9 ? pesos[i] : 1;
    saida[0][i] /= w;
    saida[1][i] /= w;
    if (!Number.isFinite(saida[0][i]) || !Number.isFinite(saida[1][i])) throw new Error("O Apollo devolveu uma amostra inválida.");
  }
  if (canais.length === 1) {
    for (let i = 0; i < n; i++) saida[0][i] = 0.5 * (saida[0][i] + saida[1][i]);
    return [saida[0]];
  }
  return saida;
}
