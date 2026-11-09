/**
 * Efeitos da criação musical: o catálogo (parâmetros, faixas e padrões) e o processamento
 * de cada um, amostra por amostra, sem alocar nada depois de criado. É o mesmo código que
 * toca em tempo real e que exporta, nos três núcleos (MusicDsp.cs, DspDaMusica.kt).
 *
 * Contas em double; os buffers são Float32. Nenhum efeito tem lookahead, então a latência
 * de todos é zero e não há compensação de atraso de plugin a fazer.
 */

const DOIS_PI = Math.PI * 2;
const limitar = (valor, minimo, maximo) => Math.min(maximo, Math.max(minimo, valor));
/** Zera valores minúsculos nos laços de realimentação (evita números subnormais lentos). */
const anti = (valor) => (Math.abs(valor) < 1e-25 ? 0 : valor);
const coeficiente = (ms, taxa) => (ms <= 0 ? 0 : Math.exp(-1 / ((taxa * ms) / 1000)));
const db = (valor) => Math.pow(10, valor / 20);

const p = (id, rotulo, min, max, padrao, unidade) => ({ id, rotulo, min, max, padrao, unidade });

export const EFEITOS_DA_MUSICA = [
  { tipo: "passa-alta", rotulo: "Filtro passa-alta", grupo: "Filtros", parametros: [p("frequencia", "Corte", 20, 2000, 80, "Hz")] },
  { tipo: "passa-baixa", rotulo: "Filtro passa-baixa", grupo: "Filtros", parametros: [p("frequencia", "Corte", 500, 20000, 12000, "Hz")] },
  {
    tipo: "equalizador",
    rotulo: "Equalizador",
    grupo: "Filtros",
    parametros: [
      p("gravesDb", "Graves (100 Hz)", -15, 15, 0, "dB"),
      p("mediosFrequencia", "Médios: frequência", 150, 2000, 500, "Hz"),
      p("mediosDb", "Médios: ganho", -15, 15, 0, "dB"),
      p("mediosQ", "Médios: largura (Q)", 0.3, 8, 1, ""),
      p("presencaFrequencia", "Presença: frequência", 1500, 8000, 3000, "Hz"),
      p("presencaDb", "Presença: ganho", -15, 15, 0, "dB"),
      p("agudosDb", "Agudos (8 kHz)", -15, 15, 0, "dB"),
    ],
  },
  {
    tipo: "compressor",
    rotulo: "Compressor",
    grupo: "Dinâmica",
    parametros: [
      p("limiarDb", "Limiar", -60, 0, -18, "dB"),
      p("razao", "Razão (x:1)", 1, 20, 3, ""),
      p("ataqueMs", "Ataque", 0.1, 100, 10, "ms"),
      p("liberacaoMs", "Liberação", 10, 1000, 120, "ms"),
      p("joelhoDb", "Joelho", 0, 12, 6, "dB"),
      p("ganhoDb", "Ganho de compensação", 0, 24, 0, "dB"),
    ],
  },
  {
    tipo: "porta",
    rotulo: "Gate / expansor",
    grupo: "Dinâmica",
    parametros: [
      p("limiarDb", "Limiar", -80, 0, -50, "dB"),
      p("faixaDb", "Redução quando fechado", -80, 0, -60, "dB"),
      p("ataqueMs", "Abertura", 0.1, 50, 1, "ms"),
      p("liberacaoMs", "Fechamento", 10, 1000, 100, "ms"),
    ],
  },
  {
    tipo: "limitador",
    rotulo: "Limitador",
    grupo: "Dinâmica",
    parametros: [p("tetoDb", "Teto", -12, 0, -1, "dB"), p("liberacaoMs", "Liberação", 1, 500, 50, "ms")],
  },
  {
    tipo: "desesser",
    rotulo: "De-esser",
    grupo: "Dinâmica",
    parametros: [p("frequencia", "Frequência", 3000, 10000, 6500, "Hz"), p("limiarDb", "Limiar", -60, 0, -30, "dB"), p("reducaoMaxDb", "Redução máxima", 0, 24, 10, "dB")],
  },
  {
    tipo: "saturacao",
    rotulo: "Saturação",
    grupo: "Cor",
    parametros: [p("driveDb", "Drive", 0, 24, 6, "dB"), p("mistura", "Mistura", 0, 1, 1, ""), p("saidaDb", "Saída", -24, 6, 0, "dB")],
  },
  {
    tipo: "atraso",
    rotulo: "Delay",
    grupo: "Espaço",
    parametros: [p("tempoMs", "Tempo", 1, 2000, 350, "ms"), p("retorno", "Realimentação", 0, 0.95, 0.35, ""), p("mistura", "Mistura", 0, 1, 0.25, "")],
  },
  {
    tipo: "reverberacao",
    rotulo: "Reverb",
    grupo: "Espaço",
    parametros: [p("tamanho", "Tamanho da sala", 0, 1, 0.6, ""), p("amortecimento", "Amortecimento", 0, 1, 0.4, ""), p("largura", "Largura", 0, 1, 1, ""), p("mistura", "Mistura", 0, 1, 0.25, "")],
  },
  {
    tipo: "coro",
    rotulo: "Chorus",
    grupo: "Modulação",
    parametros: [p("taxaHz", "Velocidade", 0.1, 5, 0.8, "Hz"), p("profundidadeMs", "Profundidade", 0, 10, 3, "ms"), p("mistura", "Mistura", 0, 1, 0.35, "")],
  },
  {
    tipo: "tremolo",
    rotulo: "Tremolo",
    grupo: "Modulação",
    parametros: [p("taxaHz", "Velocidade", 0.5, 15, 5, "Hz"), p("profundidade", "Profundidade", 0, 1, 0.5, "")],
  },
  { tipo: "largura", rotulo: "Largura estéreo", grupo: "Espaço", parametros: [p("largura", "Largura", 0, 2, 1, "×")] },
  { tipo: "ganho", rotulo: "Ganho", grupo: "Utilidades", parametros: [p("ganhoDb", "Ganho", -24, 24, 0, "dB")] },
];

export function definicaoDoEfeito(tipo) {
  return EFEITOS_DA_MUSICA.find((efeito) => efeito.tipo === tipo) ?? null;
}

/** Efeito novo com os padrões do catálogo. */
export function novoEfeito(tipo, id) {
  const definicao = definicaoDoEfeito(tipo);
  if (!definicao) throw new Error(`Efeito desconhecido: ${tipo}`);
  const efeito = { id, tipo, ligado: true };
  for (const parametro of definicao.parametros) efeito[parametro.id] = parametro.padrao;
  return efeito;
}

/** O valor de um parâmetro, com o padrão quando falta. */
export function parametro(efeito, id) {
  const definicao = definicaoDoEfeito(efeito.tipo);
  const valor = efeito[id];
  return typeof valor === "number" ? valor : definicao.parametros.find((item) => item.id === id).padrao;
}

/** O que está errado num efeito, ou null. */
export function validarEfeito(efeito) {
  const definicao = definicaoDoEfeito(efeito?.tipo);
  if (!definicao) return `efeito desconhecido (${efeito?.tipo}).`;
  if (typeof efeito.ligado !== "boolean") return `${definicao.rotulo}: falta dizer se está ligado.`;
  for (const item of definicao.parametros) {
    const valor = efeito[item.id];
    if (valor === undefined) continue;
    if (typeof valor !== "number" || !Number.isFinite(valor) || valor < item.min || valor > item.max)
      return `${definicao.rotulo}: ${item.rotulo.toLowerCase()} vai de ${item.min} a ${item.max}${item.unidade ? ` ${item.unidade}` : ""}.`;
  }
  return null;
}

/** Quanto o efeito continua soando depois que o sinal acaba, em segundos (para exportar a cauda). */
export function caudaDoEfeito(efeito) {
  if (!efeito.ligado) return 0;
  if (efeito.tipo === "reverberacao") return 1 + 5 * parametro(efeito, "tamanho");
  if (efeito.tipo === "atraso") {
    const retorno = parametro(efeito, "retorno");
    const tempo = parametro(efeito, "tempoMs") / 1000;
    return retorno <= 0 ? tempo : Math.min(10, tempo * (Math.log(0.001) / Math.log(retorno)) + tempo);
  }
  if (efeito.tipo === "coro") return 0.05;
  return 0;
}

/* ------------------------------------------------------------------ filtros */

/** Coeficientes RBJ normalizados (a0 = 1): [b0, b1, b2, a1, a2]. */
export function coeficientesBiquad(tipo, frequencia, taxa, q = Math.SQRT1_2, ganhoDb = 0) {
  const f = limitar(frequencia, 10, taxa * 0.45);
  const w0 = (DOIS_PI * f) / taxa;
  const cosw = Math.cos(w0);
  const sinw = Math.sin(w0);
  const A = Math.pow(10, ganhoDb / 40);
  let b0, b1, b2, a0, a1, a2;
  if (tipo === "passa-baixa" || tipo === "passa-alta") {
    const alpha = sinw / (2 * q);
    const s = tipo === "passa-baixa" ? 1 - cosw : 1 + cosw;
    b0 = s / 2;
    b1 = tipo === "passa-baixa" ? s : -s;
    b2 = s / 2;
    a0 = 1 + alpha;
    a1 = -2 * cosw;
    a2 = 1 - alpha;
  } else if (tipo === "pico") {
    const alpha = sinw / (2 * q);
    b0 = 1 + alpha * A;
    b1 = -2 * cosw;
    b2 = 1 - alpha * A;
    a0 = 1 + alpha / A;
    a1 = -2 * cosw;
    a2 = 1 - alpha / A;
  } else {
    // Prateleiras com inclinação S = 1.
    const alpha = (sinw / 2) * Math.SQRT2;
    const raiz = 2 * Math.sqrt(A) * alpha;
    if (tipo === "prateleira-grave") {
      b0 = A * (A + 1 - (A - 1) * cosw + raiz);
      b1 = 2 * A * (A - 1 - (A + 1) * cosw);
      b2 = A * (A + 1 - (A - 1) * cosw - raiz);
      a0 = A + 1 + (A - 1) * cosw + raiz;
      a1 = -2 * (A - 1 + (A + 1) * cosw);
      a2 = A + 1 + (A - 1) * cosw - raiz;
    } else {
      b0 = A * (A + 1 + (A - 1) * cosw + raiz);
      b1 = -2 * A * (A - 1 + (A + 1) * cosw);
      b2 = A * (A + 1 + (A - 1) * cosw - raiz);
      a0 = A + 1 - (A - 1) * cosw + raiz;
      a1 = 2 * (A - 1 - (A + 1) * cosw);
      a2 = A + 1 - (A - 1) * cosw - raiz;
    }
  }
  return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0];
}

/** Um biquad estéreo (estado separado por canal). */
export class Biquad {
  constructor(coeficientes) {
    this.e = new Float64Array(8); // x1 x2 y1 y2 de cada canal
    this.definir(coeficientes);
  }

  definir([b0, b1, b2, a1, a2]) {
    this.b0 = b0;
    this.b1 = b1;
    this.b2 = b2;
    this.a1 = a1;
    this.a2 = a2;
  }

  amostra(x, canal) {
    const e = this.e;
    const k = canal * 4;
    const y = anti(this.b0 * x + this.b1 * e[k] + this.b2 * e[k + 1] - this.a1 * e[k + 2] - this.a2 * e[k + 3]);
    e[k + 1] = e[k];
    e[k] = x;
    e[k + 3] = e[k + 2];
    e[k + 2] = y;
    return y;
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      esq[i] = this.amostra(esq[i], 0);
      dir[i] = this.amostra(dir[i], 1);
    }
  }
}

class Filtro {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.efeito = efeito;
    const c = coeficientesBiquad(efeito.tipo, parametro(efeito, "frequencia"), this.taxa);
    if (this.biquad) this.biquad.definir(c);
    else this.biquad = new Biquad(c);
  }

  processar(esq, dir, n) {
    this.biquad.processar(esq, dir, n);
  }
}

class Equalizador {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.bandas = [];
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    const t = this.taxa;
    const coeficientes = [
      coeficientesBiquad("prateleira-grave", 100, t, Math.SQRT1_2, parametro(efeito, "gravesDb")),
      coeficientesBiquad("pico", parametro(efeito, "mediosFrequencia"), t, parametro(efeito, "mediosQ"), parametro(efeito, "mediosDb")),
      coeficientesBiquad("pico", parametro(efeito, "presencaFrequencia"), t, 1, parametro(efeito, "presencaDb")),
      coeficientesBiquad("prateleira-aguda", 8000, t, Math.SQRT1_2, parametro(efeito, "agudosDb")),
    ];
    this.ativas = [parametro(efeito, "gravesDb"), parametro(efeito, "mediosDb"), parametro(efeito, "presencaDb"), parametro(efeito, "agudosDb")].map((g) => g !== 0);
    coeficientes.forEach((c, i) => {
      if (this.bandas[i]) this.bandas[i].definir(c);
      else this.bandas[i] = new Biquad(c);
    });
  }

  processar(esq, dir, n) {
    for (let b = 0; b < 4; b += 1) if (this.ativas[b]) this.bandas[b].processar(esq, dir, n);
  }
}

/* ------------------------------------------------------------------ dinâmica */

class Compressor {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.envelope = 0; // redução em dB (≤ 0)
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.limiar = parametro(efeito, "limiarDb");
    this.razao = parametro(efeito, "razao");
    this.joelho = parametro(efeito, "joelhoDb");
    this.compensacao = parametro(efeito, "ganhoDb");
    this.ataque = coeficiente(parametro(efeito, "ataqueMs"), this.taxa);
    this.liberacao = coeficiente(parametro(efeito, "liberacaoMs"), this.taxa);
  }

  reducao(nivelDb) {
    const acima = nivelDb - this.limiar;
    const inclinacao = 1 / this.razao - 1;
    const w = this.joelho;
    if (w > 0 && 2 * Math.abs(acima) <= w) return (inclinacao * (acima + w / 2) * (acima + w / 2)) / (2 * w);
    return acima > 0 ? inclinacao * acima : 0;
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const nivel = Math.max(Math.abs(esq[i]), Math.abs(dir[i]));
      const alvo = this.reducao(20 * Math.log10(nivel + 1e-12));
      const c = alvo < this.envelope ? this.ataque : this.liberacao;
      this.envelope = c * this.envelope + (1 - c) * alvo;
      const g = Math.pow(10, (this.envelope + this.compensacao) / 20);
      esq[i] *= g;
      dir[i] *= g;
    }
  }
}

class Porta {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.detector = 0;
    this.ganho = 1;
    this.queda = coeficiente(10, taxa);
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.limiar = parametro(efeito, "limiarDb");
    this.fechado = db(parametro(efeito, "faixaDb"));
    this.ataque = coeficiente(parametro(efeito, "ataqueMs"), this.taxa);
    this.liberacao = coeficiente(parametro(efeito, "liberacaoMs"), this.taxa);
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const nivel = Math.max(Math.abs(esq[i]), Math.abs(dir[i]));
      this.detector = Math.max(nivel, this.detector * this.queda);
      const alvo = 20 * Math.log10(this.detector + 1e-12) > this.limiar ? 1 : this.fechado;
      const c = alvo > this.ganho ? this.ataque : this.liberacao;
      this.ganho = anti(c * this.ganho + (1 - c) * alvo);
      esq[i] *= this.ganho;
      dir[i] *= this.ganho;
    }
  }
}

/** Limitador de pico ligado nos dois canais: ataque instantâneo, então nunca passa do teto. */
class Limitador {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.ganho = 1;
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.teto = db(parametro(efeito, "tetoDb"));
    this.liberacao = coeficiente(parametro(efeito, "liberacaoMs"), this.taxa);
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const pico = Math.max(Math.abs(esq[i]), Math.abs(dir[i]));
      const alvo = pico > this.teto ? this.teto / pico : 1;
      this.ganho = alvo < this.ganho ? alvo : this.liberacao * this.ganho + (1 - this.liberacao) * alvo;
      esq[i] *= this.ganho;
      dir[i] *= this.ganho;
    }
  }
}

class Desesser {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.envelope = 0;
    this.ataque = coeficiente(1, taxa);
    this.liberacao = coeficiente(60, taxa);
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    const c = coeficientesBiquad("passa-alta", parametro(efeito, "frequencia"), this.taxa);
    if (this.filtro) this.filtro.definir(c);
    else this.filtro = new Biquad(c);
    this.limiar = parametro(efeito, "limiarDb");
    this.maximo = parametro(efeito, "reducaoMaxDb");
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const he = this.filtro.amostra(esq[i], 0);
      const hd = this.filtro.amostra(dir[i], 1);
      const nivel = Math.max(Math.abs(he), Math.abs(hd));
      const c = nivel > this.envelope ? this.ataque : this.liberacao;
      this.envelope = anti(c * this.envelope + (1 - c) * nivel);
      const reducao = Math.min(this.maximo, Math.max(0, 20 * Math.log10(this.envelope + 1e-12) - this.limiar));
      const g = Math.pow(10, -reducao / 20);
      esq[i] = esq[i] - he + he * g;
      dir[i] = dir[i] - hd + hd * g;
    }
  }
}

/* ------------------------------------------------------------------ cor e utilidades */

class Saturacao {
  constructor(efeito) {
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.drive = db(parametro(efeito, "driveDb"));
    this.normal = Math.tanh(this.drive);
    this.mistura = parametro(efeito, "mistura");
    this.saida = db(parametro(efeito, "saidaDb"));
  }

  processar(esq, dir, n) {
    const { drive, normal, mistura, saida } = this;
    for (let i = 0; i < n; i += 1) {
      esq[i] = (esq[i] * (1 - mistura) + (Math.tanh(drive * esq[i]) / normal) * mistura) * saida;
      dir[i] = (dir[i] * (1 - mistura) + (Math.tanh(drive * dir[i]) / normal) * mistura) * saida;
    }
  }
}

class Ganho {
  constructor(efeito) {
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.g = db(parametro(efeito, "ganhoDb"));
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      esq[i] *= this.g;
      dir[i] *= this.g;
    }
  }
}

class Largura {
  constructor(efeito) {
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.largura = parametro(efeito, "largura");
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const meio = (esq[i] + dir[i]) * 0.5;
      const lado = (esq[i] - dir[i]) * 0.5 * this.largura;
      esq[i] = meio + lado;
      dir[i] = meio - lado;
    }
  }
}

class Tremolo {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.fase = 0;
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.passo = (DOIS_PI * parametro(efeito, "taxaHz")) / this.taxa;
    this.profundidade = parametro(efeito, "profundidade");
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const g = 1 - this.profundidade * 0.5 * (1 + Math.sin(this.fase));
      esq[i] *= g;
      dir[i] *= g;
      this.fase += this.passo;
      if (this.fase >= DOIS_PI) this.fase -= DOIS_PI;
    }
  }
}

/* ------------------------------------------------------------------ espaço */

class Atraso {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.tamanho = Math.ceil(taxa * 2) + 1;
    this.buffer = [new Float32Array(this.tamanho), new Float32Array(this.tamanho)];
    this.escrita = 0;
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.atraso = limitar(Math.round((parametro(efeito, "tempoMs") * this.taxa) / 1000), 1, this.tamanho - 1);
    this.retorno = parametro(efeito, "retorno");
    this.mistura = parametro(efeito, "mistura");
  }

  processar(esq, dir, n) {
    const [be, bd] = this.buffer;
    for (let i = 0; i < n; i += 1) {
      let leitura = this.escrita - this.atraso;
      if (leitura < 0) leitura += this.tamanho;
      const de = be[leitura];
      const dd = bd[leitura];
      be[this.escrita] = anti(esq[i] + de * this.retorno);
      bd[this.escrita] = anti(dir[i] + dd * this.retorno);
      esq[i] = esq[i] * (1 - this.mistura) + de * this.mistura;
      dir[i] = dir[i] * (1 - this.mistura) + dd * this.mistura;
      this.escrita += 1;
      if (this.escrita === this.tamanho) this.escrita = 0;
    }
  }
}

class Coro {
  constructor(efeito, taxa) {
    this.taxa = taxa;
    this.tamanho = Math.ceil(taxa * 0.05) + 2;
    this.buffer = [new Float32Array(this.tamanho), new Float32Array(this.tamanho)];
    this.escrita = 0;
    this.fase = 0;
    this.base = taxa * 0.015;
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.passo = (DOIS_PI * parametro(efeito, "taxaHz")) / this.taxa;
    this.profundidade = (parametro(efeito, "profundidadeMs") * this.taxa) / 1000;
    this.mistura = parametro(efeito, "mistura");
  }

  ler(buffer, atraso) {
    let posicao = this.escrita - atraso;
    while (posicao < 0) posicao += this.tamanho;
    const i0 = Math.floor(posicao);
    const fracao = posicao - i0;
    const i1 = i0 + 1 === this.tamanho ? 0 : i0 + 1;
    return buffer[i0] + (buffer[i1] - buffer[i0]) * fracao;
  }

  processar(esq, dir, n) {
    const [be, bd] = this.buffer;
    for (let i = 0; i < n; i += 1) {
      be[this.escrita] = esq[i];
      bd[this.escrita] = dir[i];
      const ae = this.base + this.profundidade * Math.sin(this.fase);
      const ad = this.base + this.profundidade * Math.sin(this.fase + Math.PI / 2);
      const we = this.ler(be, ae);
      const wd = this.ler(bd, ad);
      esq[i] = esq[i] * (1 - this.mistura) + we * this.mistura;
      dir[i] = dir[i] * (1 - this.mistura) + wd * this.mistura;
      this.escrita += 1;
      if (this.escrita === this.tamanho) this.escrita = 0;
      this.fase += this.passo;
      if (this.fase >= DOIS_PI) this.fase -= DOIS_PI;
    }
  }
}

/** Freeverb (Jezar, domínio público): 8 pentes e 4 passa-tudo por canal. */
const PENTES = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
const PASSA_TUDO = [556, 441, 341, 225];
const ESPALHAMENTO = 23;

class Reverberacao {
  constructor(efeito, taxa) {
    const escala = taxa / 44100;
    const criar = (tamanhos, extra) => tamanhos.map((t) => ({ b: new Float32Array(Math.max(1, Math.round((t + extra) * escala))), i: 0, f: 0 }));
    this.pentes = [criar(PENTES, 0), criar(PENTES, ESPALHAMENTO)];
    this.passaTudo = [criar(PASSA_TUDO, 0), criar(PASSA_TUDO, ESPALHAMENTO)];
    this.atualizar(efeito);
  }

  atualizar(efeito) {
    this.retorno = parametro(efeito, "tamanho") * 0.28 + 0.7;
    this.amortecimento = parametro(efeito, "amortecimento") * 0.4;
    const mistura = parametro(efeito, "mistura");
    const largura = parametro(efeito, "largura");
    const molhado = mistura * 3;
    this.molhado1 = molhado * (largura / 2 + 0.5);
    this.molhado2 = molhado * ((1 - largura) / 2);
    this.seco = 1 - mistura;
  }

  canal(entrada, c) {
    let saida = 0;
    const d1 = this.amortecimento;
    const d2 = 1 - d1;
    for (const pente of this.pentes[c]) {
      const valor = pente.b[pente.i];
      pente.f = anti(valor * d2 + pente.f * d1);
      pente.b[pente.i] = entrada + pente.f * this.retorno;
      pente.i += 1;
      if (pente.i === pente.b.length) pente.i = 0;
      saida += valor;
    }
    for (const filtro of this.passaTudo[c]) {
      const valor = filtro.b[filtro.i];
      filtro.b[filtro.i] = anti(saida + valor * 0.5);
      saida = valor - saida;
      filtro.i += 1;
      if (filtro.i === filtro.b.length) filtro.i = 0;
    }
    return saida;
  }

  processar(esq, dir, n) {
    for (let i = 0; i < n; i += 1) {
      const entrada = (esq[i] + dir[i]) * 0.015;
      const oe = this.canal(entrada, 0);
      const od = this.canal(entrada, 1);
      const le = esq[i];
      const ld = dir[i];
      esq[i] = oe * this.molhado1 + od * this.molhado2 + le * this.seco;
      dir[i] = od * this.molhado1 + oe * this.molhado2 + ld * this.seco;
    }
  }
}

const CLASSES = {
  "passa-alta": Filtro,
  "passa-baixa": Filtro,
  equalizador: Equalizador,
  compressor: Compressor,
  porta: Porta,
  limitador: Limitador,
  desesser: Desesser,
  saturacao: Saturacao,
  atraso: Atraso,
  reverberacao: Reverberacao,
  coro: Coro,
  tremolo: Tremolo,
  largura: Largura,
  ganho: Ganho,
};

/** O processador de um efeito: `processar(esq, dir, n)` no lugar e `atualizar(efeito)`. */
export function criarProcessador(efeito, taxa) {
  const Classe = CLASSES[efeito.tipo];
  if (!Classe) throw new Error(`Efeito desconhecido: ${efeito.tipo}`);
  return new Classe(efeito, taxa);
}

/**
 * Uma cadeia de efeitos que sobrevive a mudanças: o processador de cada efeito (pelo id) é
 * reaproveitado, só com os parâmetros novos, para o reverb e o delay não cortarem a cauda.
 */
export class Cadeia {
  constructor(taxa) {
    this.taxa = taxa;
    this.itens = [];
  }

  sincronizar(efeitos) {
    const anteriores = new Map(this.itens.map((item) => [item.id, item]));
    this.itens = (efeitos ?? []).map((efeito) => {
      const anterior = anteriores.get(efeito.id);
      if (anterior && anterior.tipo === efeito.tipo) {
        anterior.processador.atualizar(efeito);
        anterior.ligado = efeito.ligado;
        return anterior;
      }
      return { id: efeito.id, tipo: efeito.tipo, ligado: efeito.ligado, processador: criarProcessador(efeito, this.taxa) };
    });
  }

  processar(esq, dir, n) {
    for (const item of this.itens) if (item.ligado) item.processador.processar(esq, dir, n);
  }
}
