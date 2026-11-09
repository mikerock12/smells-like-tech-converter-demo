/**
 * Separação de faixas (stems) e redução de ruído, fora do tempo real.
 *
 * O modelo (Spleeter 4stems da Deezer, MIT, convertido por scripts/musica-modelo-separacao.py)
 * recebe a magnitude da STFT e devolve uma estimativa por instrumento; aqui ficam a STFT, a
 * máscara de Wiener (expoente 2), a extensão da máscara acima de 11 kHz e a reconstrução por
 * sobreposição e soma. Tudo em trechos de 512 quadros (~12 s), para uma música longa não
 * precisar caber inteira na memória do celular. A soma das faixas é a mistura original.
 *
 * Espelhos: MusicSeparation.cs e SeparacaoDaMusica.kt, conferidos por musica-casos/.
 */

export const MODELO_DE_SEPARACAO = {
  id: "spleeter-4stems-v1",
  rotulo: "Spleeter 4 faixas (Deezer)",
  arquivo: "spleeter-4stems.onnx",
  url: "https://converter.smellsliketech.com.br/modelos/spleeter-4stems-v1.onnx",
  bytes: 78614199,
  sha256: "c5fae1215e5636a911ef4ced6eb056b129562d2661a186fddfa321afc2dc95a1",
  licenca: "MIT — github.com/deezer/spleeter (código e modelos pré-treinados)",
  liberadoParaUsoComercial: true,
  taxa: 44100,
  janela: 4096,
  passo: 1024,
  quadrosPorTrecho: 512,
  bandas: 1024,
  instrumentos: ["vozes", "bateria", "baixo", "outros"],
  entrada: "magnitude",
  saida: "estimativas",
  memoriaMb: 700,
};

export const MODOS_DE_SEPARACAO = [
  { id: "2", rotulo: "Voz e instrumental", faixas: [{ id: "vozes", rotulo: "Voz", de: ["vozes"] }, { id: "instrumental", rotulo: "Instrumental", de: ["bateria", "baixo", "outros"] }] },
  {
    id: "4",
    rotulo: "Voz, bateria, baixo e outros",
    faixas: [
      { id: "vozes", rotulo: "Voz", de: ["vozes"] },
      { id: "bateria", rotulo: "Bateria", de: ["bateria"] },
      { id: "baixo", rotulo: "Baixo", de: ["baixo"] },
      { id: "outros", rotulo: "Outros", de: ["outros"] },
    ],
  },
];

/** O que o modelo de hoje não faz, dito na tela em vez de prometido. */
export const AVISO_DA_SEPARACAO =
  "A separação por IA nunca é perfeita: pode sobrar um pouco de um instrumento no outro, principalmente quando tocam juntos na mesma região. " +
  "Guitarra, piano, segunda voz ou backing vocal separados não estão disponíveis neste modelo: eles ficam em “outros” ou junto da voz principal. " +
  "A faixa original continua no projeto, para comparar.";

export function modoDeSeparacao(id) {
  return MODOS_DE_SEPARACAO.find((modo) => modo.id === id) ?? null;
}

/* ------------------------------------------------------------------ FFT */

/** FFT complexa radix-2 no lugar (tamanho potência de 2), com tabelas prontas. */
export class Fft {
  constructor(n) {
    this.n = n;
    this.cos = new Float64Array(n / 2);
    this.sin = new Float64Array(n / 2);
    for (let i = 0; i < n / 2; i += 1) {
      this.cos[i] = Math.cos((2 * Math.PI * i) / n);
      this.sin[i] = Math.sin((2 * Math.PI * i) / n);
    }
    this.inverso = new Uint32Array(n);
    const bits = Math.log2(n);
    for (let i = 0; i < n; i += 1) {
      let r = 0;
      for (let b = 0; b < bits; b += 1) r |= ((i >> b) & 1) << (bits - 1 - b);
      this.inverso[i] = r;
    }
  }

  /** `sinal` = −1 para a direta, +1 para a inversa (sem dividir por n). */
  transformar(re, im, sinal) {
    const n = this.n;
    for (let i = 0; i < n; i += 1) {
      const j = this.inverso[i];
      if (j > i) {
        let t = re[i];
        re[i] = re[j];
        re[j] = t;
        t = im[i];
        im[i] = im[j];
        im[j] = t;
      }
    }
    for (let tamanho = 2; tamanho <= n; tamanho *= 2) {
      const metade = tamanho / 2;
      const passo = n / tamanho;
      for (let inicio = 0; inicio < n; inicio += tamanho) {
        for (let k = 0; k < metade; k += 1) {
          const wr = this.cos[k * passo];
          const wi = sinal * this.sin[k * passo];
          const a = inicio + k;
          const b = a + metade;
          const tr = re[b] * wr - im[b] * wi;
          const ti = re[b] * wi + im[b] * wr;
          re[b] = re[a] - tr;
          im[b] = im[a] - ti;
          re[a] += tr;
          im[a] += ti;
        }
      }
    }
  }
}

/* ------------------------------------------------------------------ STFT em trechos */

/**
 * Análise e síntese em trechos. O sinal ganha `janela` zeros nos dois lados (como no Spleeter),
 * cada quadro é a FFT de `janela` amostras com Hann periódica, a cada `passo` amostras.
 * `sintetizar` aplica uma máscara real por canal e devolve as amostras já prontas (as que
 * nenhum quadro futuro vai mais tocar) pelo `emitir(canal, inicio, amostras, n)`.
 */
export class ProcessadorEspectral {
  constructor({ comprimento, janela = 4096, passo = 1024, quadrosPorTrecho = 512, saidas = 1 }) {
    this.comprimento = comprimento;
    this.N = janela;
    this.H = passo;
    this.T = quadrosPorTrecho;
    this.B = janela / 2 + 1;
    this.saidas = saidas;
    this.quadros = Math.floor((comprimento + janela) / passo) + 1;
    this.trechos = Math.ceil(this.quadros / quadrosPorTrecho);
    this.fft = new Fft(janela);
    this.w = new Float64Array(janela);
    let energia = 0;
    for (let i = 0; i < janela; i += 1) {
      this.w[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / janela);
      energia += this.w[i] * this.w[i];
    }
    this.normal = energia / passo;
    const tamanho = quadrosPorTrecho * this.B;
    this.re = [new Float32Array(tamanho), new Float32Array(tamanho)];
    this.im = [new Float32Array(tamanho), new Float32Array(tamanho)];
    this.amostras = new Float32Array((quadrosPorTrecho - 1) * passo + janela);
    this.br = new Float64Array(janela);
    this.bi = new Float64Array(janela);
    // Acumuladores da sobreposição e soma, um por saída e canal.
    this.acumulado = Array.from({ length: saidas }, () => [new Float64Array(quadrosPorTrecho * passo + janela), new Float64Array(quadrosPorTrecho * passo + janela)]);
    this.emitidos = new Array(saidas).fill(0);
    this.base = new Array(saidas).fill(0);
  }

  quadrosDoTrecho(s) {
    return Math.min(this.T, this.quadros - s * this.T);
  }

  /** STFT dos quadros do trecho `s`. `ler(canal, inicio, n, destino)` lê o sinal original (zeros fora dele). */
  analisar(s, ler, canais) {
    const { N, H, B } = this;
    const q = this.quadrosDoTrecho(s);
    const f0 = s * this.T;
    const inicio = f0 * H - N; // em amostras do sinal original
    const n = (q - 1) * H + N;
    for (let c = 0; c < 2; c += 1) {
      lerComZeros(ler, Math.min(c, canais - 1), inicio, n, this.amostras, this.comprimento);
      const re = this.re[c];
      const im = this.im[c];
      for (let f = 0; f < q; f += 1) {
        for (let i = 0; i < N; i += 1) {
          this.br[i] = this.amostras[f * H + i] * this.w[i];
          this.bi[i] = 0;
        }
        this.fft.transformar(this.br, this.bi, -1);
        for (let k = 0; k < B; k += 1) {
          re[f * B + k] = this.br[k];
          im[f * B + k] = this.bi[k];
        }
      }
    }
    return q;
  }

  /** Magnitude das primeiras `bandas` frequências, no formato do modelo [2, T, bandas] (zeros após o fim). */
  magnitude(q, bandas, destino) {
    const { B, T } = this;
    destino.fill(0);
    for (let c = 0; c < 2; c += 1) {
      for (let f = 0; f < q; f += 1) {
        for (let k = 0; k < bandas; k += 1) {
          const i = f * B + k;
          destino[(c * T + f) * bandas + k] = Math.hypot(this.re[c][i], this.im[c][i]);
        }
      }
    }
    return destino;
  }

  /**
   * Aplica `mascara` ([2][T*B], a mesma disposição da análise) ao trecho analisado, para a
   * saída `saida`, e emite as amostras que ficaram completas.
   */
  sintetizar(s, saida, mascara, emitir) {
    const { N, H, B } = this;
    const q = this.quadrosDoTrecho(s);
    const f0 = s * this.T;
    const ultimo = s === this.trechos - 1;
    for (let c = 0; c < 2; c += 1) {
      const acc = this.acumulado[saida][c];
      for (let f = 0; f < q; f += 1) {
        const m = mascara[c];
        for (let k = 0; k < B; k += 1) {
          const i = f * B + k;
          this.br[k] = this.re[c][i] * m[i];
          this.bi[k] = this.im[c][i] * m[i];
        }
        for (let k = 1; k < N / 2; k += 1) {
          this.br[N - k] = this.br[k];
          this.bi[N - k] = -this.bi[k];
        }
        this.fft.transformar(this.br, this.bi, 1);
        const posicao = (f0 + f) * H - this.base[saida];
        for (let i = 0; i < N; i += 1) acc[posicao + i] += ((this.br[i] / N) * this.w[i]) / this.normal;
      }
    }
    // Amostras prontas: até o começo do próximo quadro (no sinal com zeros), ou tudo no fim.
    // O `emitir` precisa copiar o pedaço na hora: o buffer é reaproveitado.
    const prontoAte = Math.min(this.comprimento, ultimo ? this.comprimento : (f0 + q) * H - N);
    const de = this.emitidos[saida];
    if (prontoAte > de) {
      const n = prontoAte - de;
      for (let c = 0; c < 2; c += 1) {
        const acc = this.acumulado[saida][c];
        const desloc = de + N - this.base[saida];
        for (let i = 0; i < n; i += 1) this.amostras[i] = acc[desloc + i];
        emitir(c, de, this.amostras, n);
      }
      this.emitidos[saida] = prontoAte;
    }
    // Só a cauda dos últimos quadros continua valendo: o resto já saiu.
    const novaBase = (f0 + q) * H;
    const desloc = novaBase - this.base[saida];
    for (const acc of this.acumulado[saida]) {
      acc.copyWithin(0, desloc, desloc + N);
      acc.fill(0, N);
    }
    this.base[saida] = novaBase;
  }
}

function lerComZeros(ler, canal, inicio, n, destino, comprimento) {
  destino.fill(0, 0, n);
  const a = Math.max(0, inicio);
  const b = Math.min(comprimento, inicio + n);
  if (b <= a) return;
  const parte = destino.subarray(a - inicio, b - inicio);
  ler(canal, a, b - a, parte);
}

/* ------------------------------------------------------------------ máscaras */

/**
 * Máscaras de Wiener (expoente 2) a partir das estimativas do modelo, para uma faixa do modo
 * escolhido (a soma das máscaras dos instrumentos dela). Acima das `bandas` do modelo, a
 * máscara de cada quadro é a média dela nas bandas de baixo, para não perder os agudos.
 * `estimativas`: [instrumentos, 2, T, bandas]. `destino`: [2][T*B].
 */
export function mascaraDaFaixa(estimativas, instrumentos, indices, q, T, bandas, B, destino) {
  const eps = 1e-10;
  const plano = 2 * T * bandas;
  for (let c = 0; c < 2; c += 1) {
    const m = destino[c];
    for (let f = 0; f < q; f += 1) {
      let somaDaLinha = 0;
      const base = (c * T + f) * bandas;
      for (let k = 0; k < bandas; k += 1) {
        let total = eps;
        let parte = 0;
        for (let i = 0; i < instrumentos; i += 1) {
          const e = estimativas[i * plano + base + k];
          total += e * e;
        }
        for (const i of indices) {
          const e = estimativas[i * plano + base + k];
          parte += e * e + eps / instrumentos;
        }
        const valor = parte / total;
        m[f * B + k] = valor;
        somaDaLinha += valor;
      }
      const media = somaDaLinha / bandas;
      for (let k = bandas; k < B; k += 1) m[f * B + k] = media;
    }
  }
}

/**
 * Separação completa com o modelo injetado: `executar(tensor [1,2,T,bandas]) → Promise<Float32Array [1,4,2,T,bandas]>`.
 * `ler(canal, inicio, n, destino)` lê o original a 44,1 kHz; `emitir(faixa, canal, inicio, amostras, n)`
 * recebe o resultado em pedaços, na ordem. `progresso(fracao)` e `cancelado()` opcionais.
 */
export async function separar({ comprimento, canais, ler, executar, emitir, modo = "4", progresso = () => {}, cancelado = () => false }) {
  const definicao = modoDeSeparacao(modo);
  if (!definicao) throw new Error(`Modo de separação desconhecido: ${modo}`);
  const m = MODELO_DE_SEPARACAO;
  const processador = new ProcessadorEspectral({ comprimento, janela: m.janela, passo: m.passo, quadrosPorTrecho: m.quadrosPorTrecho, saidas: definicao.faixas.length });
  const entrada = new Float32Array(2 * m.quadrosPorTrecho * m.bandas);
  const mascara = [new Float32Array(m.quadrosPorTrecho * processador.B), new Float32Array(m.quadrosPorTrecho * processador.B)];
  const indices = definicao.faixas.map((faixa) => faixa.de.map((nome) => m.instrumentos.indexOf(nome)));
  for (let s = 0; s < processador.trechos; s += 1) {
    if (cancelado()) throw new Error("Separação cancelada.");
    const q = processador.analisar(s, ler, canais);
    processador.magnitude(q, m.bandas, entrada);
    const estimativas = await executar(entrada);
    for (let f = 0; f < definicao.faixas.length; f += 1) {
      mascaraDaFaixa(estimativas, m.instrumentos.length, indices[f], q, m.quadrosPorTrecho, m.bandas, processador.B, mascara);
      processador.sintetizar(s, f, mascara, (canal, inicio, amostras, n) => emitir(f, canal, inicio, amostras, n));
    }
    progresso((s + 1) / processador.trechos);
  }
  return definicao.faixas;
}

/* ------------------------------------------------------------------ redução de ruído */

/**
 * Redução de ruído por porta espectral, fora do tempo real: o perfil do ruído é a média dos
 * espectros dos 10% de quadros mais quietos; a máscara tira `intensidade` vezes esse perfil
 * de cada banda, com piso de −20 dB para não soar metálico. Janela de 2048 a 48 kHz.
 */
export function reduzirRuido({ comprimento, canais, ler, emitir, intensidade = 1.5, piso = 0.1 }) {
  const opcoes = { comprimento, janela: 2048, passo: 512, quadrosPorTrecho: 256, saidas: 1 };
  const analise = new ProcessadorEspectral(opcoes);
  const B = analise.B;
  const energias = [];
  for (let s = 0; s < analise.trechos; s += 1) {
    const q = analise.analisar(s, ler, canais);
    for (let f = 0; f < q; f += 1) {
      let e = 0;
      for (let c = 0; c < 2; c += 1) for (let k = 0; k < B; k += 1) e += analise.re[c][f * B + k] ** 2 + analise.im[c][f * B + k] ** 2;
      energias.push(e);
    }
  }
  const ordenadas = [...energias].sort((a, b) => a - b);
  const limiar = ordenadas[Math.max(0, Math.floor(ordenadas.length * 0.1) - 1)] ?? 0;
  const perfil = [new Float64Array(B), new Float64Array(B)];
  let contados = 0;
  for (let s = 0; s < analise.trechos; s += 1) {
    const q = analise.analisar(s, ler, canais);
    for (let f = 0; f < q; f += 1) {
      if (energias[s * opcoes.quadrosPorTrecho + f] > limiar) continue;
      contados += 1;
      for (let c = 0; c < 2; c += 1) for (let k = 0; k < B; k += 1) perfil[c][k] += Math.hypot(analise.re[c][f * B + k], analise.im[c][f * B + k]);
    }
  }
  for (let c = 0; c < 2; c += 1) for (let k = 0; k < B; k += 1) perfil[c][k] /= Math.max(1, contados);
  const sintese = new ProcessadorEspectral(opcoes);
  const mascara = [new Float32Array(opcoes.quadrosPorTrecho * B), new Float32Array(opcoes.quadrosPorTrecho * B)];
  for (let s = 0; s < sintese.trechos; s += 1) {
    const q = sintese.analisar(s, ler, canais);
    for (let c = 0; c < 2; c += 1) {
      for (let f = 0; f < q; f += 1) {
        for (let k = 0; k < B; k += 1) {
          const i = f * B + k;
          const magnitude = Math.hypot(sintese.re[c][i], sintese.im[c][i]);
          mascara[c][i] = Math.max(piso, magnitude > 0 ? 1 - (intensidade * perfil[c][k]) / magnitude : 0);
        }
      }
    }
    sintese.sintetizar(s, 0, mascara, emitir);
  }
}
