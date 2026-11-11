import { Fft } from "./musica-separacao.mjs";

const NOTAS = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
const MAIOR = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MENOR = [6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

function mono(canais) {
  const n = canais[0]?.length ?? 0;
  const saida = new Float32Array(n);
  if (!canais.length) return saida;
  for (let i = 0; i < n; i += 1) {
    let v = 0;
    for (const canal of canais) v += canal[i] ?? 0;
    saida[i] = v / canais.length;
  }
  return saida;
}

function correlacao(a, b) {
  const ma = a.reduce((s, v) => s + v, 0) / a.length;
  const mb = b.reduce((s, v) => s + v, 0) / b.length;
  let ab = 0;
  let aa = 0;
  let bb = 0;
  for (let i = 0; i < a.length; i += 1) {
    const x = a[i] - ma;
    const y = b[i] - mb;
    ab += x * y;
    aa += x * x;
    bb += y * y;
  }
  return ab / Math.sqrt(Math.max(1e-12, aa * bb));
}

function detectarBpm(sinal, taxa) {
  const passo = Math.max(128, Math.round(taxa / 100));
  const janela = passo * 2;
  const quantos = Math.floor(sinal.length / passo);
  if (quantos < 16) return null;
  const energia = new Float64Array(quantos);
  for (let i = 0; i < quantos; i += 1) {
    let soma = 0;
    const inicio = i * passo;
    for (let j = 0; j < janela && inicio + j < sinal.length; j += 2) soma += sinal[inicio + j] * sinal[inicio + j];
    energia[i] = Math.sqrt(soma / (janela / 2));
  }
  const fluxo = new Float64Array(quantos);
  for (let i = 2; i < quantos; i += 1) fluxo[i] = Math.max(0, energia[i] - (energia[i - 1] + energia[i - 2]) / 2);
  const media = fluxo.reduce((s, v) => s + v, 0) / quantos;
  for (let i = 0; i < quantos; i += 1) fluxo[i] = Math.max(0, fluxo[i] - media);
  let melhorBpm = null;
  let melhor = 0;
  for (let bpm = 40; bpm <= 240; bpm += 1) {
    const atraso = Math.max(2, (60 * taxa) / (bpm * passo));
    const inteiro = Math.floor(atraso);
    const fracao = atraso - inteiro;
    let soma = 0;
    let peso = 0;
    for (let i = inteiro + 1; i < quantos; i += 1) {
      const anterior = fluxo[i - inteiro] * (1 - fracao) + fluxo[i - inteiro - 1] * fracao;
      soma += fluxo[i] * anterior;
      peso += 1;
    }
    let nota = peso ? soma / peso : 0;
    if (bpm >= 70 && bpm <= 180) nota *= 1.03;
    if (nota > melhor) { melhor = nota; melhorBpm = bpm; }
  }
  return melhorBpm;
}

function detectarTonalidade(sinal, taxa) {
  const n = 2048;
  if (sinal.length < n * 4) return null;
  const fft = new Fft(n);
  const re = new Float64Array(n);
  const im = new Float64Array(n);
  const croma = new Float64Array(12);
  const maximoQuadros = 2400;
  const salto = Math.max(n, Math.floor((sinal.length - n) / maximoQuadros));
  let usados = 0;
  for (let inicio = 0; inicio + n <= sinal.length; inicio += salto) {
    for (let i = 0; i < n; i += 1) {
      re[i] = sinal[inicio + i] * (0.5 - 0.5 * Math.cos((2 * Math.PI * i) / n));
      im[i] = 0;
    }
    fft.transformar(re, im, -1);
    for (let k = 2; k < n / 2; k += 1) {
      const frequencia = (k * taxa) / n;
      if (frequencia < 55 || frequencia > 4000) continue;
      const nota = Math.round(12 * Math.log2(frequencia / 440)) + 69;
      croma[((nota % 12) + 12) % 12] += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
    }
    usados += 1;
  }
  if (!usados || croma.every((v) => v < 1e-9)) return null;
  let melhor = -Infinity;
  let resultado = null;
  for (const [modo, perfil] of [["maior", MAIOR], ["menor", MENOR]]) {
    for (let nota = 0; nota < 12; nota += 1) {
      const girado = Array.from({ length: 12 }, (_, i) => croma[(i + nota) % 12]);
      const valor = correlacao(girado, perfil);
      if (valor > melhor) { melhor = valor; resultado = `${NOTAS[nota]} ${modo}`; }
    }
  }
  return { tonalidade: resultado, confianca: Math.max(0, Math.min(1, melhor)) };
}

/** Estimativas locais por energia/ataques e croma; não substituem conferir de ouvido. */
export function analisarMusica(canais, taxa) {
  const sinal = mono(canais);
  const tonalidade = detectarTonalidade(sinal, taxa);
  return { bpm: detectarBpm(sinal, taxa), tonalidade: tonalidade?.tonalidade ?? null, confiancaDaTonalidade: tonalidade?.confianca ?? 0 };
}
