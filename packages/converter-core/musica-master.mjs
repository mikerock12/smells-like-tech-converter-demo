/**
 * Master e exportação da criação musical: loudness integrado (ITU-R BS.1770-4, com as portas
 * de −70 LUFS e −10 LU), pico de amostra, pico real estimado com sobreamostragem 4×, presets
 * de masterização por finalidade e o WAV PCM 16/24 bits (com dither TPDF) ou float32 (sem
 * dither). Espelhos: MusicMaster.cs e MasterDaMusica.kt, conferidos por musica-casos/.
 */

import { Biquad } from "./musica-dsp.mjs";

/** Nenhum alvo é imposto: "Sem alvo" só protege contra clipping. */
export const PRESETS_DE_MASTER = [
  { id: "sem-alvo", rotulo: "Sem alvo (só proteção contra clipping)", alvoLufs: null, tetoDbtp: -1 },
  { id: "streaming", rotulo: "Streaming (−14 LUFS)", alvoLufs: -14, tetoDbtp: -1 },
  { id: "podcast", rotulo: "Podcast e voz (−16 LUFS)", alvoLufs: -16, tetoDbtp: -1 },
  { id: "video", rotulo: "Vídeo para redes (−14 LUFS, teto −2 dBTP)", alvoLufs: -14, tetoDbtp: -2 },
  { id: "alto", rotulo: "Alto, estilo CD (−9 LUFS)", alvoLufs: -9, tetoDbtp: -0.5 },
];

export const FORMATOS_DE_EXPORTACAO = [
  { id: "wav-24", rotulo: "WAV 24 bits", extensao: "wav", bits: 24 },
  { id: "wav-16", rotulo: "WAV 16 bits (CD)", extensao: "wav", bits: 16 },
  { id: "wav-32f", rotulo: "WAV 32 bits float", extensao: "wav", bits: 32 },
  { id: "flac", rotulo: "FLAC 24 bits", extensao: "flac", bits: 24 },
  { id: "mp3", rotulo: "MP3 320 kbps", extensao: "mp3", bits: 16 },
  { id: "aac", rotulo: "AAC 256 kbps (M4A)", extensao: "m4a", bits: 16 },
  { id: "opus", rotulo: "Opus 192 kbps", extensao: "opus", bits: 16 },
];

/** Os dois estágios da pré-filtragem K, para qualquer taxa (mesmas fórmulas do pyloudnorm). */
export function coeficientesK(taxa) {
  const prateleira = (() => {
    const G = 3.999843853973347;
    const Q = 0.7071752369554196;
    const fc = 1681.974450955533;
    const K = Math.tan((Math.PI * fc) / taxa);
    const Vh = Math.pow(10, G / 20);
    const Vb = Math.pow(Vh, 0.4996667741545416);
    const a0 = 1 + K / Q + K * K;
    return [(Vh + (Vb * K) / Q + K * K) / a0, (2 * (K * K - Vh)) / a0, (Vh - (Vb * K) / Q + K * K) / a0, (2 * (K * K - 1)) / a0, (1 - K / Q + K * K) / a0];
  })();
  const passaAlta = (() => {
    const Q = 0.5003270373238773;
    const fc = 38.13547087602444;
    const K = Math.tan((Math.PI * fc) / taxa);
    const a0 = 1 + K / Q + K * K;
    return [1, -2, 1, (2 * (K * K - 1)) / a0, (1 - K / Q + K * K) / a0];
  })();
  return [prateleira, passaAlta];
}

/** Loudness integrado em LUFS (−Infinity para silêncio). */
export function loudnessIntegrado(esq, dir, taxa) {
  const [c1, c2] = coeficientesK(taxa);
  const f1 = new Biquad(c1);
  const f2 = new Biquad(c2);
  const n = esq.length;
  const tamanho = Math.round(taxa * 0.4);
  const passo = Math.round(taxa * 0.1);
  if (n < tamanho) return -Infinity;
  // Soma dos quadrados em janelas de 100 ms; cada bloco de 400 ms junta quatro.
  const partes = Math.floor(n / passo);
  const energia = new Float64Array(partes);
  for (let i = 0; i < partes * passo; i += 1) {
    const e = f2.amostra(f1.amostra(esq[i], 0), 0);
    const d = f2.amostra(f1.amostra(dir[i], 1), 1);
    energia[Math.floor(i / passo)] += e * e + d * d;
  }
  const blocos = [];
  for (let b = 0; b + 4 <= partes; b += 1) blocos.push((energia[b] + energia[b + 1] + energia[b + 2] + energia[b + 3]) / tamanho);
  const lufs = (z) => -0.691 + 10 * Math.log10(z);
  const absolutos = blocos.filter((z) => z > 0 && lufs(z) > -70);
  if (absolutos.length === 0) return -Infinity;
  const media = (lista) => lista.reduce((s, z) => s + z, 0) / lista.length;
  const relativo = lufs(media(absolutos)) - 10;
  const finais = absolutos.filter((z) => lufs(z) > relativo);
  return finais.length ? lufs(media(finais)) : -Infinity;
}

/** Interpolador de sobreamostragem 4×: sinc com janela de Hann, 12 coeficientes por fase. */
export function fasesDoPicoReal() {
  const fases = [];
  const meio = 6;
  for (let f = 0; f < 4; f += 1) {
    const coeficientes = new Float64Array(12);
    for (let k = 0; k < 12; k += 1) {
      const x = k - meio + 1 - f / 4;
      const sinc = x === 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
      const janela = 0.5 + 0.5 * Math.cos((Math.PI * x) / (meio + 0.5));
      coeficientes[k] = sinc * janela;
    }
    fases.push(coeficientes);
  }
  return fases;
}

/** Pico de amostra e pico real (dBFS / dBTP). */
export function picos(esq, dir) {
  const fases = fasesDoPicoReal();
  let amostra = 0;
  let real = 0;
  for (const canal of [esq, dir]) {
    for (let i = 0; i < canal.length; i += 1) {
      const v = Math.abs(canal[i]);
      if (v > amostra) amostra = v;
      for (let f = 1; f < 4; f += 1) {
        const c = fases[f];
        let soma = 0;
        for (let k = 0; k < 12; k += 1) {
          const j = i + k - 5;
          if (j >= 0 && j < canal.length) soma += canal[j] * c[k];
        }
        if (Math.abs(soma) > real) real = Math.abs(soma);
      }
    }
  }
  real = Math.max(real, amostra);
  const dbfs = (v) => (v > 0 ? 20 * Math.log10(v) : -Infinity);
  return { amostraDb: dbfs(amostra), realDb: dbfs(real) };
}

export function medirMaster(esq, dir, taxa) {
  return { lufs: loudnessIntegrado(esq, dir, taxa), ...picos(esq, dir) };
}

/**
 * Aplica um preset: ganho até o alvo (se houver) e limitador de pico real com 0,3 dB de folga.
 * Devolve os canais novos e as medidas de antes e depois. O ganho nunca passa de +24 dB.
 */
export function masterizar(esq, dir, taxa, presetId) {
  const preset = PRESETS_DE_MASTER.find((p) => p.id === presetId) ?? PRESETS_DE_MASTER[0];
  const antes = medirMaster(esq, dir, taxa);
  const ganhoDb = preset.alvoLufs === null || !Number.isFinite(antes.lufs) ? 0 : Math.min(24, preset.alvoLufs - antes.lufs);
  const g = Math.pow(10, ganhoDb / 20);
  const teto = Math.pow(10, (preset.tetoDbtp - 0.3) / 20);
  const liberacao = Math.exp(-1 / (taxa * 0.05));
  const e = new Float32Array(esq.length);
  const d = new Float32Array(dir.length);
  let ganho = 1;
  for (let i = 0; i < esq.length; i += 1) {
    const a = esq[i] * g;
    const b = dir[i] * g;
    const pico = Math.max(Math.abs(a), Math.abs(b));
    const alvo = pico > teto ? teto / pico : 1;
    ganho = alvo < ganho ? alvo : liberacao * ganho + (1 - liberacao) * alvo;
    e[i] = a * ganho;
    d[i] = b * ganho;
  }
  // O limitador olha a amostra; entre amostras o sinal ainda pode passar do teto. Se o pico real
  // (4×) passar, a mistura inteira desce o que falta: o teto vale sempre, à custa de um pouco
  // de volume, que a medida mostra.
  let depois = medirMaster(e, d, taxa);
  if (depois.realDb > preset.tetoDbtp) {
    const corte = Math.pow(10, (preset.tetoDbtp - depois.realDb - 0.05) / 20);
    for (let i = 0; i < e.length; i += 1) {
      e[i] *= corte;
      d[i] *= corte;
    }
    depois = medirMaster(e, d, taxa);
  }
  return { esq: e, dir: d, ganhoDb, antes, depois };
}

/* ------------------------------------------------------------------ WAV */

/** Gerador de ruído do dither: xorshift32, a mesma sequência nos três núcleos. */
export function criarRuido(semente = 0x12345678) {
  let estado = semente >>> 0 || 1;
  return () => {
    estado ^= estado << 13;
    estado >>>= 0;
    estado ^= estado >>> 17;
    estado ^= estado << 5;
    estado >>>= 0;
    return estado / 4294967296;
  };
}

/** Converte para inteiro com dither TPDF (±1 LSB, triangular) e arredondamento para cima no meio. */
export function quantizar(valor, bits, ruido) {
  const escala = bits === 16 ? 32767 : 8388607;
  const dither = ruido ? ruido() - ruido() : 0;
  const v = Math.floor(valor * escala + dither + 0.5);
  return Math.max(-escala - 1, Math.min(escala, v));
}

/** WAV (RIFF) intercalado: 16 ou 24 bits PCM com dither, ou 32 bits float sem dither. */
export function codificarWav(canais, taxa, bits = 24, { dither = true, semente = 0x12345678 } = {}) {
  const n = canais[0].length;
  const c = canais.length;
  const bytesPorAmostra = bits / 8;
  const dados = n * c * bytesPorAmostra;
  const buffer = new ArrayBuffer(44 + dados);
  const v = new DataView(buffer);
  const texto = (posicao, s) => {
    for (let i = 0; i < s.length; i += 1) v.setUint8(posicao + i, s.charCodeAt(i));
  };
  texto(0, "RIFF");
  v.setUint32(4, 36 + dados, true);
  texto(8, "WAVE");
  texto(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, bits === 32 ? 3 : 1, true);
  v.setUint16(22, c, true);
  v.setUint32(24, taxa, true);
  v.setUint32(28, taxa * c * bytesPorAmostra, true);
  v.setUint16(32, c * bytesPorAmostra, true);
  v.setUint16(34, bits, true);
  texto(36, "data");
  v.setUint32(40, dados, true);
  const ruido = bits !== 32 && dither ? criarRuido(semente) : null;
  let p = 44;
  for (let i = 0; i < n; i += 1) {
    for (let k = 0; k < c; k += 1) {
      const x = canais[k][i];
      if (bits === 32) v.setFloat32(p, x, true);
      else {
        const q = quantizar(x, bits, ruido);
        if (bits === 16) v.setInt16(p, q, true);
        else {
          v.setUint8(p, q & 0xff);
          v.setUint8(p + 1, (q >> 8) & 0xff);
          v.setUint8(p + 2, (q >> 16) & 0xff);
        }
      }
      p += bytesPorAmostra;
    }
  }
  return new Uint8Array(buffer);
}

/**
 * Lê WAV PCM 16/24/32 ou float32 (o que os nossos clientes gravam). Recupera arquivo de
 * gravação interrompida: se o tamanho do "data" estiver zerado ou passar do arquivo, usa o
 * que existe de fato.
 */
export function lerWav(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const texto = (p) => String.fromCharCode(bytes[p], bytes[p + 1], bytes[p + 2], bytes[p + 3]);
  if (bytes.length < 12 || texto(0) !== "RIFF" || texto(8) !== "WAVE") throw new Error("Não é um WAV.");
  let p = 12;
  let formato = null;
  while (p + 8 <= bytes.length) {
    const id = texto(p);
    let tamanho = v.getUint32(p + 4, true);
    if (id === "fmt ") {
      let tipo = v.getUint16(p + 8, true);
      // WAVE_FORMAT_EXTENSIBLE: o tipo de verdade está no começo do GUID do subformato.
      if (tipo === 0xfffe && tamanho >= 26) tipo = v.getUint16(p + 32, true);
      formato = { tipo, canais: v.getUint16(p + 10, true), taxa: v.getUint32(p + 12, true), bits: v.getUint16(p + 22, true) };
    } else if (id === "data") {
      if (!formato) throw new Error("WAV sem formato.");
      const disponivel = bytes.length - (p + 8);
      if (tamanho === 0 || tamanho > disponivel) tamanho = disponivel;
      const bytesPorAmostra = formato.bits / 8;
      const n = Math.floor(tamanho / (bytesPorAmostra * formato.canais));
      const canais = Array.from({ length: formato.canais }, () => new Float32Array(n));
      let q = p + 8;
      const flutuante = formato.tipo === 3;
      for (let i = 0; i < n; i += 1) {
        for (let c = 0; c < formato.canais; c += 1) {
          let x;
          if (flutuante) x = v.getFloat32(q, true);
          else if (formato.bits === 16) x = v.getInt16(q, true) / 32768;
          else if (formato.bits === 24) x = ((bytes[q] | (bytes[q + 1] << 8) | (bytes[q + 2] << 16)) << 8 >> 8) / 8388608;
          else if (formato.bits === 32) x = v.getInt32(q, true) / 2147483648;
          else throw new Error("Profundidade não suportada.");
          canais[c][i] = x;
          q += bytesPorAmostra;
        }
      }
      return { taxa: formato.taxa, canais, bits: formato.bits };
    }
    p += 8 + tamanho + (tamanho & 1);
  }
  throw new Error("WAV sem dados.");
}
