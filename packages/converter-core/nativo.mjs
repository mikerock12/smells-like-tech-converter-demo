/**
 * O núcleo nativo (native/slt-media-engine, C++20) no navegador e no Node: carrega o
 * slt_native.wasm e expõe a C ABI com tipos de JavaScript. É o mesmo código que vira
 * slt_native.dll no Windows e libslt_native.so no Android.
 *
 * O módulo não tem libc: a matemática vem daqui (os mesmos Math.sin/Math.cos do núcleo em
 * JS) e a memória é dele. Os buffers passam por cópia para dentro e para fora, em blocos
 * grandes; nada atravessa a fronteira amostra por amostra.
 */

import { MODELO_DE_SEPARACAO, modoDeSeparacao } from "./musica-separacao.mjs";

export const VERSAO_MAIOR_DA_ABI = 1;
export const CAPACIDADES = { motor: 1, separador: 2, mascaraRefinada: 4, restaurador: 8, consistencia: 16, stftComplexa: 256, refinoDeAgudos: 512, diagnostico: 1024, reparo: 2048 };

/** Ordem dos valores do diagnóstico (SLT_DIAG_* em slt_media_api.h). */
export const CAMPOS_DO_DIAGNOSTICO = ["duracao", "picoDb", "rmsDb", "cristaDb", "dc", "recorte", "pisoDeRuidoDb", "zumbidoHz", "zumbidoDb", "bandaHz", "estalosPorMinuto", "correlacao", "silencio", "agudosDb", "dinamicaDb", "taxa", "planuraDoRuido",
  // ABI 1.3: platô do limitador e nível por oitava ÷ 1 kHz.
  "limiteDb", "pertoDoLimite", "oitava31", "oitava63", "oitava125", "oitava250", "oitava500", "oitava1k", "oitava2k", "oitava4k", "oitava8k", "oitava16k"];
/** Ordem dos parâmetros do reparo (SLT_REPAIR_* em slt_media_api.h). */
export const PARAMETROS_DO_REPARO = ["dc", "zumbidoHz", "zumbido", "estalos", "recorte", "nivelDoRecorte", "ruido", "arDb", "corteGraveHz", "tetoDb", "ganhoDb",
  // ABI 1.3.
  "entradaDb", "transientes", "eq31", "eq63", "eq125", "eq250", "eq500", "eq1k", "eq2k", "eq4k", "eq8k", "eq16k"];
const BINS_DO_PERFIL = 2049;
export const PERFIS_DE_RESTAURACAO = [
  { id: "voz", codigo: 0, rotulo: "Voz", descricao: "Tira o grave que vazou, devolve presença e ar e segura a aspereza só quando ela sobra." },
  { id: "bateria", codigo: 1, rotulo: "Bateria", descricao: "Corpo de bumbo e caixa, ataque mais firme e pratos seguros quando estridentes." },
  { id: "baixo", codigo: 2, rotulo: "Baixo", descricao: "Fundamental e definição de nota, menos vazamento de pratos e guitarra." },
  { id: "outros", codigo: 3, rotulo: "Outros (guitarra, teclados)", descricao: "Médios de volta, aspereza segura e menos vazamento de pratos." },
];
export const VERSAO_DA_RESTAURACAO = 1;

const ERROS = ["ok", "argumento inválido", "versão de estrutura não suportada", "sem memória", "buffer pequeno demais", "estado inválido"];

function conferir(codigo, onde) {
  if (codigo !== 0) throw new Error(`Núcleo nativo: ${ERROS[codigo] ?? "erro interno"} em ${onde}.`);
}

/** Perfil de restauração da faixa separada: pelo id da faixa do modo de separação. */
export function perfilDeRestauracaoDaFaixa(idDaFaixa) {
  if (idDaFaixa === "vozes" || idDaFaixa === "voz") return "voz";
  if (idDaFaixa === "bateria") return "bateria";
  if (idDaFaixa === "baixo") return "baixo";
  return "outros";
}

export class NucleoNativo {
  constructor(instancia) {
    this.x = instancia.exports;
    this.versao = this.x.slt_get_api_version() >>> 0;
    if (this.versao >>> 16 !== VERSAO_MAIOR_DA_ABI) throw new Error(`Núcleo nativo com ABI ${this.versao >>> 16}.${this.versao & 0xffff}; esperada ${VERSAO_MAIOR_DA_ABI}.x.`);
    this.capacidades = this.x.slt_get_capabilities() >>> 0;
  }

  tem(capacidade) {
    return (this.capacidades & CAPACIDADES[capacidade]) !== 0;
  }

  /* A memória pode crescer (e o ArrayBuffer trocar) a cada alocação: as vistas são sempre novas. */
  f32(ponteiro, n) {
    return new Float32Array(this.x.memory.buffer, ponteiro, n);
  }

  u32(ponteiro, n) {
    return new Uint32Array(this.x.memory.buffer, ponteiro, n);
  }

  alocar(bytes) {
    const p = this.x.slt_alloc(bytes) >>> 0;
    if (p === 0) throw new Error("Núcleo nativo: sem memória.");
    return p;
  }

  liberar(ponteiro) {
    if (ponteiro) this.x.slt_free(ponteiro);
  }

  /** Mantém o espectro no WASM enquanto o chamador calcula as máscaras de um bloco. */
  criarStftComplexa(janela, passo, comprimento) {
    if (!this.tem("stftComplexa")) throw new Error("STFT complexa nativa indisponível.");
    if (![janela, passo, comprimento].every((n) => Number.isInteger(n) && n > 0 && n <= 0xffffffff)) {
      throw new Error("Configuração de STFT complexa inválida.");
    }
    const x = this.x;
    const handle = x.slt_complex_stft_create(janela, passo, comprimento);
    if (!handle) throw new Error("Configuração de STFT complexa inválida.");
    const tamanho = x.slt_complex_stft_size(handle) >>> 0;
    let memoria = 0;
    try {
      memoria = this.alocar(8 * (comprimento + tamanho));
    } catch (erro) {
      x.slt_complex_stft_destroy(handle);
      throw erro;
    }
    const direita = memoria + comprimento * 4;
    const espectro = direita + comprimento * 4;
    const mascara = espectro + tamanho * 4;
    let analisado = false;
    const conferirAberto = () => {
      if (!memoria) throw new Error("STFT complexa encerrada.");
    };
    return {
      analisar: (esquerdo, direito) => {
        conferirAberto();
        if (esquerdo.length !== comprimento || direito.length !== comprimento) throw new Error("Bloco PCM com duração inválida.");
        this.f32(memoria, comprimento).set(esquerdo);
        this.f32(direita, comprimento).set(direito);
        conferir(x.slt_complex_stft_analyze(handle, memoria, direita, comprimento, espectro, tamanho), "STFT complexa");
        analisado = true;
        return this.f32(espectro, tamanho).slice();
      },
      sintetizar: (ganhos, zerarDc = false) => {
        conferirAberto();
        if (!analisado || (ganhos && ganhos.length !== tamanho)) throw new Error("Espectro ou máscara inválido.");
        if (ganhos) this.f32(mascara, tamanho).set(ganhos);
        conferir(x.slt_complex_stft_synthesize(handle, espectro, ganhos ? mascara : 0, tamanho, Number(zerarDc),
          memoria, direita, comprimento), "síntese espectral");
        return [this.f32(memoria, comprimento).slice(), this.f32(direita, comprimento).slice()];
      },
      fechar: () => {
        if (!memoria) return;
        this.liberar(memoria);
        x.slt_complex_stft_destroy(handle);
        memoria = 0;
      },
    };
  }

  /** Pico, RMS e amostras acima de 0 dBFS de um bloco intercalado. */
  medir(intercalado, canais) {
    const quadros = intercalado.length / canais;
    const p = this.alocar(intercalado.length * 4 + 12);
    try {
      this.f32(p, intercalado.length).set(intercalado);
      const saida = p + intercalado.length * 4;
      conferir(this.x.slt_measure(p, quadros, canais, saida), "slt_measure");
      return { pico: this.f32(saida, 2)[0], rms: this.f32(saida, 2)[1], cortadas: this.u32(saida + 8, 1)[0] };
    } finally {
      this.liberar(p);
    }
  }

  /**
   * Restaura uma faixa separada (cópia; o bruto não muda). `canais`: 1 ou 2 Float32Array.
   * Devolve os canais restaurados, já com o volume igualado ao do bruto (A/B nivelado), e
   * o ganho aplicado. `intensidade` de 0 a 100; `progresso(fracao)` e `cancelado()` opcionais.
   */
  restaurar({ canais, taxa, perfil, intensidade, progresso = () => {}, cancelado = () => false }) {
    const definicao = PERFIS_DE_RESTAURACAO.find((p) => p.id === perfil);
    if (!definicao) throw new Error(`Perfil de restauração desconhecido: ${perfil}`);
    const BLOCO = 1 << 16;
    const config = this.alocar(16);
    const buffer = this.alocar(BLOCO * 4 * 2);
    let restaurador = 0;
    try {
      this.u32(config, 3).set([16, taxa, definicao.codigo]);
      this.f32(config + 12, 1)[0] = intensidade;
      restaurador = this.x.slt_restorer_create(config) >>> 0;
      if (!restaurador) throw new Error("Núcleo nativo: restauração recusada (taxa, perfil ou intensidade inválidos).");
      const quadros = canais[0].length;
      const saida = canais.map(() => new Float32Array(quadros));
      for (let inicio = 0; inicio < quadros; inicio += BLOCO) {
        if (cancelado()) throw new Error("Restauração cancelada.");
        const n = Math.min(BLOCO, quadros - inicio);
        canais.forEach((c, i) => this.f32(buffer + i * BLOCO * 4, n).set(c.subarray(inicio, inicio + n)));
        conferir(this.x.slt_restorer_process(restaurador, buffer, canais.length > 1 ? buffer + BLOCO * 4 : 0, n), "slt_restorer_process");
        saida.forEach((c, i) => c.set(this.f32(buffer + i * BLOCO * 4, n), inicio));
        progresso(Math.min(1, (inicio + n) / quadros));
      }
      conferir(this.x.slt_restorer_matching_gain(restaurador, config), "slt_restorer_matching_gain");
      const ganho = this.f32(config, 1)[0];
      if (ganho !== 1) for (const c of saida) for (let i = 0; i < c.length; i += 1) c[i] *= ganho;
      return { canais: saida, ganho, ganhoDb: 20 * Math.log10(ganho), versao: VERSAO_DA_RESTAURACAO };
    } finally {
      if (restaurador) this.x.slt_restorer_destroy(restaurador);
      this.liberar(buffer);
      this.liberar(config);
    }
  }

  /**
   * Refina os agudos das faixas separadas (Wiener multicanal acima de `corte` Hz): devolve
   * cópias, com a soma igual à das entradas. `faixas`: lista de [esq, dir] do mesmo tamanho.
   * @param {{ faixas: Float32Array[][]; taxa: number; corte?: number; quanto?: number; progresso?: (f: number) => void; cancelado?: () => boolean }} pedido
   * @returns {Float32Array[][]}
   */
  refinarAgudos({ faixas, taxa, corte = 8000, quanto = 1, progresso = () => {}, cancelado = () => false }) {
    if (!this.tem("refinoDeAgudos")) throw new Error("Refino dos agudos indisponível neste núcleo.");
    const n = faixas.length;
    const quadros = faixas[0]?.[0]?.length ?? 0;
    const refinador = this.x.slt_stem_refiner_create(taxa, n, corte, quanto) >>> 0;
    if (!refinador) throw new Error("Refino dos agudos recusado (taxa, faixas ou corte inválidos).");
    const atraso = this.x.slt_stem_refiner_latency(refinador) >>> 0;
    const BLOCO = 1 << 14;
    const buffer = this.alocar(BLOCO * 4 * 2 * n);
    try {
      const saida = faixas.map(() => [new Float32Array(quadros), new Float32Array(quadros)]);
      const total = quadros + atraso;
      for (let inicio = 0; inicio < total; inicio += BLOCO) {
        if (cancelado()) throw new Error("Refino cancelado.");
        const k = Math.min(BLOCO, total - inicio);
        for (let f = 0; f < n; f += 1) for (let c = 0; c < 2; c += 1) {
          const destino = this.f32(buffer + (f * 2 + c) * k * 4, k);
          destino.fill(0);
          const fonte = faixas[f][Math.min(c, faixas[f].length - 1)];
          if (inicio < quadros) destino.set(fonte.subarray(inicio, Math.min(quadros, inicio + k)));
        }
        conferir(this.x.slt_stem_refiner_process(refinador, buffer, k), "slt_stem_refiner_process");
        // A saída vem atrasada: a amostra i do bloco é a posição inicio + i − atraso.
        for (let f = 0; f < n; f += 1) for (let c = 0; c < 2; c += 1) {
          const pronto = this.f32(buffer + (f * 2 + c) * k * 4, k);
          const de = inicio - atraso;
          const primeiro = Math.max(0, -de);
          const ultimo = Math.min(k, quadros - de);
          if (ultimo > primeiro) saida[f][c].set(pronto.subarray(primeiro, ultimo), de + primeiro);
        }
        progresso(Math.min(1, (inicio + k) / total));
      }
      return saida;
    } finally {
      this.x.slt_stem_refiner_destroy(refinador);
      this.liberar(buffer);
    }
  }

  /**
   * Diagnóstico de qualidade (só mede): { valores (por nome), perfil (ruído por bin) }.
   * @param {{ canais: Float32Array[]; taxa: number; progresso?: (f: number) => void; cancelado?: () => boolean }} pedido
   * @returns {{ valores: import("./restaurador.mjs").DiagnosticoDoAudio; perfil: Float32Array }}
   */
  diagnosticar({ canais, taxa, progresso = () => {}, cancelado = () => false }) {
    if (!this.tem("diagnostico")) throw new Error("Diagnóstico de áudio indisponível neste núcleo.");
    const analisador = this.x.slt_audio_analyzer_create(taxa) >>> 0;
    if (!analisador) throw new Error("Diagnóstico recusado (taxa inválida).");
    const BLOCO = 1 << 15;
    const buffer = this.alocar(BLOCO * 4 * 2 + BINS_DO_PERFIL * 4);
    try {
      const quadros = canais[0].length;
      for (let inicio = 0; inicio < quadros; inicio += BLOCO) {
        if (cancelado()) throw new Error("Diagnóstico cancelado.");
        const n = Math.min(BLOCO, quadros - inicio);
        this.f32(buffer, n).set(canais[0].subarray(inicio, inicio + n));
        if (canais.length > 1) this.f32(buffer + BLOCO * 4, n).set(canais[1].subarray(inicio, inicio + n));
        conferir(this.x.slt_audio_analyzer_feed(analisador, buffer, canais.length > 1 ? buffer + BLOCO * 4 : 0, n), "slt_audio_analyzer_feed");
        progresso(Math.min(1, (inicio + n) / quadros));
      }
      const saida = buffer + BLOCO * 4 * 2;
      conferir(this.x.slt_audio_analyzer_result(analisador, saida, CAMPOS_DO_DIAGNOSTICO.length), "slt_audio_analyzer_result");
      const brutos = this.f32(saida, CAMPOS_DO_DIAGNOSTICO.length);
      const valores = Object.fromEntries(CAMPOS_DO_DIAGNOSTICO.map((nome, i) => [nome, brutos[i]]));
      conferir(this.x.slt_audio_analyzer_noise_profile(analisador, saida, BINS_DO_PERFIL), "slt_audio_analyzer_noise_profile");
      return { valores, perfil: this.f32(saida, BINS_DO_PERFIL).slice() };
    } finally {
      this.x.slt_audio_analyzer_destroy(analisador);
      this.liberar(buffer);
    }
  }

  /**
   * Reparo conservador (cópia; a entrada não muda). `parametros`: objeto com os nomes de
   * PARAMETROS_DO_REPARO (zero desliga). `perfil`: o do diagnóstico, opcional.
   * Devolve { canais, estatisticas: { estalos, recortes, reducaoDb, limitadorDb } }.
   * @param {{ canais: Float32Array[]; taxa: number; parametros: Record<string, number>; perfil?: Float32Array | null; progresso?: (f: number) => void; cancelado?: () => boolean }} pedido
   * @returns {{ canais: Float32Array[]; estatisticas: { estalos: number; recortes: number; reducaoDb: number; limitadorDb: number } }}
   */
  reparar({ canais, taxa, parametros, perfil = null, progresso = () => {}, cancelado = () => false }) {
    if (!this.tem("reparo")) throw new Error("Reparo de áudio indisponível neste núcleo.");
    const lista = PARAMETROS_DO_REPARO.map((nome) => Number(parametros?.[nome] ?? 0) || 0);
    const BLOCO = 1 << 15;
    const buffer = this.alocar(BLOCO * 4 * 2 + Math.max(BINS_DO_PERFIL, lista.length) * 4);
    const extra = buffer + BLOCO * 4 * 2;
    let reparo = 0;
    try {
      this.f32(extra, lista.length).set(lista);
      reparo = this.x.slt_audio_repair_create(taxa, extra, lista.length) >>> 0;
      if (!reparo) throw new Error("Reparo recusado (taxa ou parâmetros inválidos).");
      if (perfil && perfil.length === BINS_DO_PERFIL) {
        this.f32(extra, BINS_DO_PERFIL).set(perfil);
        conferir(this.x.slt_audio_repair_set_noise_profile(reparo, extra, BINS_DO_PERFIL), "slt_audio_repair_set_noise_profile");
      }
      const atraso = this.x.slt_audio_repair_latency(reparo) >>> 0;
      const quadros = canais[0].length;
      const estereo = canais.length > 1;
      const saida = canais.map(() => new Float32Array(quadros));
      const total = quadros + atraso;
      for (let inicio = 0; inicio < total; inicio += BLOCO) {
        if (cancelado()) throw new Error("Restauração cancelada.");
        const n = Math.min(BLOCO, total - inicio);
        for (let c = 0; c < (estereo ? 2 : 1); c += 1) {
          const destino = this.f32(buffer + c * BLOCO * 4, n);
          destino.fill(0);
          if (inicio < quadros) destino.set(canais[c].subarray(inicio, Math.min(quadros, inicio + n)));
        }
        conferir(this.x.slt_audio_repair_process(reparo, buffer, estereo ? buffer + BLOCO * 4 : 0, n), "slt_audio_repair_process");
        const de = inicio - atraso;
        const primeiro = Math.max(0, -de);
        const ultimo = Math.min(n, quadros - de);
        if (ultimo > primeiro) for (let c = 0; c < saida.length; c += 1) saida[c].set(this.f32(buffer + c * BLOCO * 4, n).subarray(primeiro, ultimo), de + primeiro);
        progresso(Math.min(1, (inicio + n) / total));
      }
      conferir(this.x.slt_audio_repair_stats(reparo, extra, 4), "slt_audio_repair_stats");
      const e = this.f32(extra, 4);
      return { canais: saida, estatisticas: { estalos: e[0], recortes: e[1], reducaoDb: e[2], limitadorDb: e[3] } };
    } finally {
      if (reparo) this.x.slt_audio_repair_destroy(reparo);
      this.liberar(buffer);
    }
  }

  /**
   * Resíduo e consistência com a mistura. `mistura` e cada item de `faixas`: [esq, dir].
   * Com `manterResiduo`, as faixas não mudam e o resíduo volta inteiro; sem ele, cada faixa
   * recebe a parte dela e a soma volta a ser a mistura. Devolve { faixas, residuo }.
   */
  consistencia({ mistura, faixas, manterResiduo = false, janela = 0, passo = 0 }) {
    const quadros = mistura[0].length;
    const bytes = quadros * 4;
    const ponteiros = [];
    const novo = (dados) => {
      const p = this.alocar(bytes);
      ponteiros.push(p);
      if (dados) this.f32(p, quadros).set(dados);
      return p;
    };
    try {
      const m = [novo(mistura[0]), novo(mistura[1] ?? mistura[0])];
      const f = faixas.map((faixa) => [novo(faixa[0]), novo(faixa[1] ?? faixa[0])]);
      const r = [novo(null), novo(null)];
      const tabela = this.alocar(faixas.length * 8 + 16);
      ponteiros.push(tabela);
      this.u32(tabela, faixas.length * 2).set(f.flat());
      const config = tabela + faixas.length * 8;
      this.u32(config, 4).set([16, janela, passo, manterResiduo ? 1 : 0]);
      conferir(this.x.slt_mixture_consistency(config, m[0], m[1], tabela, faixas.length, BigInt(quadros), r[0], r[1]), "slt_mixture_consistency");
      return { faixas: f.map((par) => par.map((p) => this.f32(p, quadros).slice())), residuo: r.map((p) => this.f32(p, quadros).slice()) };
    } finally {
      for (const p of ponteiros) this.liberar(p);
    }
  }

  /**
   * Separação com a STFT, as máscaras e a reconstrução no núcleo: a mesma assinatura de
   * `separar` em musica-separacao.mjs (o modelo continua injetado por `executar`).
   */
  /** @param {Parameters<typeof import("./musica-separacao.mjs").separar>[0]} opcoes */
  async separar({ comprimento, canais, ler, executar, emitir, modo = "4", mascara = "classica", progresso = () => {}, cancelado = () => false }) {
    const definicao = modoDeSeparacao(modo);
    if (!definicao) throw new Error(`Modo de separação desconhecido: ${modo}`);
    const m = MODELO_DE_SEPARACAO;
    const plano = 2 * m.quadrosPorTrecho * m.bandas;
    const capacidade = m.quadrosPorTrecho * m.passo + m.janela;
    const janelaMaxima = (m.quadrosPorTrecho - 1) * m.passo + m.janela;
    const indices = definicao.faixas.map((faixa) => faixa.de.map((nome) => m.instrumentos.indexOf(nome)));
    const config = this.alocar(48);
    const dv = () => new DataView(this.x.memory.buffer);
    let separador = 0;
    const blocos = [];
    const novo = (bytes) => {
      const p = this.alocar(bytes);
      blocos.push(p);
      return p;
    };
    try {
      this.u32(config, 12).set([48, 0, 0, 0, m.janela, m.passo, m.quadrosPorTrecho, m.bandas, m.instrumentos.length, definicao.faixas.length, mascara === "refinada" ? 1 : 0, 0]);
      dv().setBigUint64(config + 8, BigInt(comprimento), true);
      separador = this.x.slt_separator_create(config) >>> 0;
      if (!separador) throw new Error("Núcleo nativo: separador recusado.");
      const entrada = [novo(janelaMaxima * 4), novo(janelaMaxima * 4)];
      const magnitude = novo(plano * 4);
      const estimativas = novo(m.instrumentos.length * plano * 4);
      const saida = [novo(capacidade * 4), novo(capacidade * 4)];
      const tabela = novo(16 + 16);
      const trechos = this.x.slt_separator_chunk_count(separador) >>> 0;
      const temporario = new Float32Array(janelaMaxima);
      for (let s = 0; s < trechos; s += 1) {
        if (cancelado()) throw new Error("Separação cancelada.");
        conferir(this.x.slt_separator_chunk_window(separador, s, tabela, tabela + 8, tabela + 12), "slt_separator_chunk_window");
        const inicio = Number(dv().getBigInt64(tabela, true));
        const n = this.u32(tabela + 8, 1)[0];
        for (let c = 0; c < 2; c += 1) {
          temporario.fill(0, 0, n);
          const a = Math.max(0, inicio);
          const b = Math.min(comprimento, inicio + n);
          if (b > a) ler(Math.min(c, canais - 1), a, b - a, temporario.subarray(a - inicio, b - inicio));
          this.f32(entrada[c], n).set(temporario.subarray(0, n));
        }
        conferir(this.x.slt_separator_analyze(separador, s, entrada[0], entrada[1], n, magnitude, plano), "slt_separator_analyze");
        // O modelo pode ficar com o tensor (transferência entre threads): entrega uma cópia.
        const resultado = await executar(this.f32(magnitude, plano).slice());
        this.f32(estimativas, m.instrumentos.length * plano).set(resultado);
        for (let f = 0; f < definicao.faixas.length; f += 1) {
          this.u32(tabela + 16, indices[f].length).set(indices[f]);
          conferir(this.x.slt_separator_synthesize(separador, s, f, estimativas, m.instrumentos.length * plano, tabela + 16, indices[f].length, saida[0], saida[1], capacidade, tabela, tabela + 8), "slt_separator_synthesize");
          const de = Number(dv().getBigInt64(tabela, true));
          const prontas = this.u32(tabela + 8, 1)[0];
          if (prontas > 0) for (let c = 0; c < 2; c += 1) emitir(f, c, de, this.f32(saida[c], prontas), prontas);
        }
        progresso((s + 1) / trechos);
      }
      return definicao.faixas;
    } finally {
      if (separador) this.x.slt_separator_destroy(separador);
      for (const p of blocos) this.liberar(p);
      this.liberar(config);
    }
  }
}

/** Instancia o núcleo a partir dos bytes (ou de um WebAssembly.Module) do slt_native.wasm. */
export async function carregarNucleoNativo(origem) {
  const env = { sin: Math.sin, cos: Math.cos, exp: Math.exp, log10: Math.log10, pow: Math.pow, tanh: Math.tanh };
  const modulo = origem instanceof WebAssembly.Module ? origem : await WebAssembly.compile(origem);
  return new NucleoNativo(await WebAssembly.instantiate(modulo, { env }));
}
