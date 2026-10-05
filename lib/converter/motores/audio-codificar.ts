/// <reference lib="webworker" />

import { Mp3Encoder } from "@breezystack/lamejs";

/**
 * Codificação de áudio a partir de PCM: MP3 (lamejs) e WAV (PCM de 16 bits).
 *
 * Aqui só entra PCM: quem decodifica é a página, com os codecs que o navegador já tem.
 */

const AMOSTRAS_POR_BLOCO = 1152 * 20;

function paraInteiros16(amostras: Float32Array): Int16Array {
  const saida = new Int16Array(amostras.length);
  for (let indice = 0; indice < amostras.length; indice += 1) {
    const valor = Math.max(-1, Math.min(1, amostras[indice]));
    saida[indice] = valor < 0 ? valor * 0x8000 : valor * 0x7fff;
  }
  return saida;
}

function juntar(pedacos: readonly Uint8Array[]): Uint8Array {
  const tamanho = pedacos.reduce((soma, pedaco) => soma + pedaco.length, 0);
  const resultado = new Uint8Array(tamanho);
  let posicao = 0;
  for (const pedaco of pedacos) {
    resultado.set(pedaco, posicao);
    posicao += pedaco.length;
  }
  return resultado;
}

export function codificarMp3(
  canais: readonly Float32Array[],
  sampleRate: number,
  bitrate: number,
  progresso: (fracao: number) => void,
  cancelado: () => boolean,
): ArrayBuffer | null {
  if (canais.length === 0 || canais[0].length === 0) {
    throw new Error("Este arquivo não tem áudio para converter.");
  }

  // O lamejs aceita mono ou estéreo. Mais que dois canais viram estéreo.
  const numeroDeCanais = canais.length === 1 ? 1 : 2;
  const esquerdo = paraInteiros16(canais[0]);
  const direito = numeroDeCanais === 2 ? paraInteiros16(canais[1] ?? canais[0]) : null;

  const encoder = new Mp3Encoder(numeroDeCanais, sampleRate, bitrate);
  const pedacos: Uint8Array[] = [];
  const total = esquerdo.length;

  for (let inicio = 0; inicio < total; inicio += AMOSTRAS_POR_BLOCO) {
    if (cancelado()) return null;
    const fim = Math.min(inicio + AMOSTRAS_POR_BLOCO, total);
    const bloco = direito
      ? encoder.encodeBuffer(esquerdo.subarray(inicio, fim), direito.subarray(inicio, fim))
      : encoder.encodeBuffer(esquerdo.subarray(inicio, fim));
    if (bloco.length > 0) pedacos.push(bloco);
    progresso(fim / total);
  }

  const resto = encoder.flush();
  if (resto.length > 0) pedacos.push(resto);

  return juntar(pedacos).buffer as ArrayBuffer;
}

/** WAV PCM 16 bits, intercalado. Simples e universal. */
export function codificarWav(canais: readonly Float32Array[], sampleRate: number): ArrayBuffer {
  if (canais.length === 0 || canais[0].length === 0) {
    throw new Error("Este arquivo não tem áudio para converter.");
  }

  const numeroDeCanais = Math.min(canais.length, 2);
  const quadros = canais[0].length;
  const tamanhoDosDados = quadros * numeroDeCanais * 2;
  const buffer = new ArrayBuffer(44 + tamanhoDosDados);
  const view = new DataView(buffer);

  const texto = (posicao: number, valor: string) => {
    for (let indice = 0; indice < valor.length; indice += 1) view.setUint8(posicao + indice, valor.charCodeAt(indice));
  };

  texto(0, "RIFF");
  view.setUint32(4, 36 + tamanhoDosDados, true);
  texto(8, "WAVE");
  texto(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, numeroDeCanais, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * numeroDeCanais * 2, true);
  view.setUint16(32, numeroDeCanais * 2, true);
  view.setUint16(34, 16, true);
  texto(36, "data");
  view.setUint32(40, tamanhoDosDados, true);

  let posicao = 44;
  for (let quadro = 0; quadro < quadros; quadro += 1) {
    for (let canal = 0; canal < numeroDeCanais; canal += 1) {
      const valor = Math.max(-1, Math.min(1, canais[canal][quadro]));
      view.setInt16(posicao, valor < 0 ? valor * 0x8000 : valor * 0x7fff, true);
      posicao += 2;
    }
  }

  return buffer;
}
