/**
 * O mixer da criação musical: lê os clipes das faixas audíveis, passa pelos efeitos de cada
 * faixa, aplica fader/pan/ajuste L/R (com automação), manda aos barramentos pelos envios
 * pré ou pós-fader, soma no master, aplica os efeitos do master e o metrônomo.
 *
 * O mesmo objeto toca em tempo real (AudioWorklet, WASAPI, AudioTrack) e exporta. Depois de
 * `definirProjeto`, `processar` não aloca memória: os buffers são criados uma vez por bloco.
 * Espelhos: MusicMixer.cs e MisturadorDaMusica.kt, conferidos por musica-casos/.
 */

import { BLOCO, dbParaLinear, envelopeDoClipe, faixasAudiveis, ganhosEstereo, quadrosPorBatida, valorDaAutomacao } from "./musica.mjs";
import { Cadeia, caudaDoEfeito } from "./musica-dsp.mjs";
import { chaveDaFonteDoClipe } from "./musica-transformacao.mjs";

/** Fonte de áudio em memória: um Float32Array por canal. */
export function fonteDaMemoria(canais) {
  return {
    canais: canais.length,
    quadros: canais[0]?.length ?? 0,
    copiar(canal, inicio, n, destino) {
      const origem = canais[Math.min(canal, canais.length - 1)];
      const fim = Math.min(origem.length, inicio + n);
      let i = 0;
      for (let j = inicio; j < fim; j += 1) destino[i++] = origem[j];
      for (; i < n; i += 1) destino[i] = 0;
    },
  };
}

const DURACAO_DO_CLIQUE = 0.03;

/** Clique do metrônomo: seno curto com queda exponencial (2 kHz no tempo forte, 1,5 kHz nos outros). */
export function somDoClique(taxa, forte) {
  const n = Math.round(taxa * DURACAO_DO_CLIQUE);
  const som = new Float32Array(n);
  const f = forte ? 2000 : 1500;
  for (let i = 0; i < n; i += 1) som[i] = Math.sin((2 * Math.PI * f * i) / taxa) * Math.exp(-i / (taxa * 0.006)) * (forte ? 1 : 0.7);
  return som;
}

export class Misturador {
  constructor(taxa, bloco = BLOCO) {
    this.taxa = taxa;
    this.bloco = bloco;
    this.fontes = new Map();
    this.cadeias = new Map();
    this.medidores = new Map();
    const novo = () => new Float32Array(bloco);
    this.te = novo();
    this.td = novo();
    this.le = novo();
    this.ld = novo();
    this.me = novo();
    this.md = novo();
    this.barramentos = new Map();
    this.cliqueForte = somDoClique(taxa, true);
    this.cliqueFraco = somDoClique(taxa, false);
    this.cliquesDaContagem = [];
    this.fimDaContagem = -1;
    this.projeto = null;
  }

  definirFonte(midia, fonte) {
    if (fonte) this.fontes.set(midia, fonte);
    else this.fontes.delete(midia);
  }

  /** A contagem antes da gravação (planoDaGravacao): toca mesmo com o metrônomo desligado. */
  definirContagem(cliques, fim) {
    this.cliquesDaContagem = cliques;
    this.fimDaContagem = fim;
  }

  definirProjeto(projeto) {
    this.projeto = projeto;
    this.audiveis = new Set(faixasAudiveis(projeto));
    const vivas = new Set(["master"]);
    const cadeia = (id, efeitos) => {
      vivas.add(id);
      let atual = this.cadeias.get(id);
      if (!atual) {
        atual = new Cadeia(this.taxa);
        this.cadeias.set(id, atual);
      }
      atual.sincronizar(efeitos);
      if (!this.medidores.has(id)) this.medidores.set(id, new Float32Array(4));
    };
    for (const faixa of projeto.faixas) cadeia(faixa.id, faixa.efeitos);
    for (const bus of projeto.barramentos) {
      cadeia(bus.id, bus.efeitos);
      if (!this.barramentos.has(bus.id)) this.barramentos.set(bus.id, [new Float32Array(this.bloco), new Float32Array(this.bloco)]);
    }
    cadeia("master", projeto.master.efeitos);
    for (const id of [...this.cadeias.keys()]) if (!vivas.has(id)) this.cadeias.delete(id);
    for (const id of [...this.medidores.keys()]) if (!vivas.has(id)) this.medidores.delete(id);
    for (const id of [...this.barramentos.keys()]) if (!projeto.barramentos.some((b) => b.id === id)) this.barramentos.delete(id);
  }

  /**
   * Preenche `saidaE`/`saidaD` com os quadros [inicio, inicio + n) da linha do tempo.
   * `opcoes.entrada` ({esq, dir}) é o que entra ao vivo, ouvido nas faixas armadas com MON;
   * `opcoes.somente` toca uma faixa só (exportação por faixa); `opcoes.metronomo` liga o clique.
   */
  processar(inicio, n, saidaE, saidaD, opcoes = {}) {
    for (const m of this.medidores.values()) m.fill(0);
    let feito = 0;
    while (feito < n) {
      const k = Math.min(this.bloco, n - feito);
      this.bloco_(inicio + feito, k, opcoes, feito);
      for (let i = 0; i < k; i += 1) {
        saidaE[feito + i] = this.me[i];
        saidaD[feito + i] = this.md[i];
      }
      feito += k;
    }
    for (const m of this.medidores.values()) {
      m[2] = Math.sqrt(m[2] / Math.max(1, n));
      m[3] = Math.sqrt(m[3] / Math.max(1, n));
    }
  }

  medir(id, esq, dir, n) {
    const m = this.medidores.get(id);
    if (!m) return;
    for (let i = 0; i < n; i += 1) {
      const a = Math.abs(esq[i]);
      const b = Math.abs(dir[i]);
      if (a > m[0]) m[0] = a;
      if (b > m[1]) m[1] = b;
      m[2] += esq[i] * esq[i];
      m[3] += dir[i] * dir[i];
    }
  }

  bloco_(t0, n, opcoes, deslocamentoDaEntrada) {
    const projeto = this.projeto;
    const { te, td, le, ld, me, md } = this;
    me.fill(0, 0, n);
    md.fill(0, 0, n);
    for (const [e, d] of this.barramentos.values()) {
      e.fill(0, 0, n);
      d.fill(0, 0, n);
    }
    const entrada = opcoes.entrada ?? null;
    for (const faixa of projeto.faixas) {
      const audivel = opcoes.somente ? faixa.id === opcoes.somente : this.audiveis.has(faixa.id);
      if (!audivel) continue;
      te.fill(0, 0, n);
      td.fill(0, 0, n);
      for (const clipe of faixa.clipes) {
        if (clipe.mudo) continue;
        const a = Math.max(t0, clipe.inicio);
        const b = Math.min(t0 + n, clipe.inicio + clipe.duracao);
        if (a >= b) continue;
        const transformada = this.fontes.get(chaveDaFonteDoClipe(clipe));
        const fonte = transformada ?? this.fontes.get(clipe.midia);
        if (!fonte) continue;
        const deslocamento = a - clipe.inicio;
        const k = b - a;
        // A fonte transformada já está na duração da linha do tempo; a original é o recurso
        // até o preparo terminar e para projetos sem transformação.
        const origem = transformada ? Math.floor(clipe.inicioNaMidia / (clipe.velocidade ?? 1)) + deslocamento : clipe.inicioNaMidia + deslocamento;
        fonte.copiar(0, origem, k, le);
        if (fonte.canais > 1) fonte.copiar(1, origem, k, ld);
        else for (let i = 0; i < k; i += 1) ld[i] = le[i];
        const destino = a - t0;
        for (let i = 0; i < k; i += 1) {
          const f = envelopeDoClipe(clipe, deslocamento + i);
          te[destino + i] += le[i] * f;
          td[destino + i] += ld[i] * f;
        }
      }
      if (entrada && faixa.gravar && faixa.monitorar) {
        for (let i = 0; i < n; i += 1) {
          te[i] += entrada.esq[deslocamentoDaEntrada + i];
          td[i] += entrada.dir[deslocamentoDaEntrada + i];
        }
      }
      this.cadeias.get(faixa.id)?.processar(te, td, n);
      for (const envio of faixa.envios) {
        if (!envio.preFader) continue;
        const bus = this.barramentos.get(envio.barramento);
        if (!bus) continue;
        const g = dbParaLinear(envio.nivelDb);
        for (let i = 0; i < n; i += 1) {
          bus[0][i] += te[i] * g;
          bus[1][i] += td[i] * g;
        }
      }
      const volume = faixa.automacao?.volume;
      const pan = faixa.automacao?.pan;
      const [e0, d0] = ganhosEstereo(valorDaAutomacao(volume, t0, faixa.ganhoDb), valorDaAutomacao(pan, t0, faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb);
      const [e1, d1] = ganhosEstereo(valorDaAutomacao(volume, t0 + n, faixa.ganhoDb), valorDaAutomacao(pan, t0 + n, faixa.pan), faixa.ajusteEsquerdoDb, faixa.ajusteDireitoDb);
      for (let i = 0; i < n; i += 1) {
        const w = i / n;
        te[i] *= e0 + (e1 - e0) * w;
        td[i] *= d0 + (d1 - d0) * w;
      }
      for (const envio of faixa.envios) {
        if (envio.preFader) continue;
        const bus = this.barramentos.get(envio.barramento);
        if (!bus) continue;
        const g = dbParaLinear(envio.nivelDb);
        for (let i = 0; i < n; i += 1) {
          bus[0][i] += te[i] * g;
          bus[1][i] += td[i] * g;
        }
      }
      const saida = faixa.saida === "master" ? null : this.barramentos.get(faixa.saida);
      const de = saida ? saida[0] : me;
      const dd = saida ? saida[1] : md;
      for (let i = 0; i < n; i += 1) {
        de[i] += te[i];
        dd[i] += td[i];
      }
      this.medir(faixa.id, te, td, n);
    }
    for (const bus of projeto.barramentos) {
      const buffers = this.barramentos.get(bus.id);
      if (!buffers || bus.mudo) continue;
      const [e, d] = buffers;
      this.cadeias.get(bus.id)?.processar(e, d, n);
      const [ge, gd] = ganhosEstereo(bus.ganhoDb, bus.pan);
      for (let i = 0; i < n; i += 1) {
        e[i] *= ge;
        d[i] *= gd;
        me[i] += e[i];
        md[i] += d[i];
      }
      this.medir(bus.id, e, d, n);
    }
    if (!opcoes.semMaster) this.cadeias.get("master")?.processar(me, md, n);
    const g = dbParaLinear(projeto.master.ganhoDb);
    for (let i = 0; i < n; i += 1) {
      me[i] *= g;
      md[i] *= g;
    }
    this.medir("master", me, md, n);
    if (opcoes.metronomo || this.fimDaContagem > t0) this.metronomo(t0, n, opcoes.metronomo);
  }

  somar(som, quadroDoClique, t0, n, volume) {
    const a = Math.max(t0, quadroDoClique);
    const b = Math.min(t0 + n, quadroDoClique + som.length);
    for (let t = a; t < b; t += 1) {
      const v = som[t - quadroDoClique] * volume;
      this.me[t - t0] += v;
      this.md[t - t0] += v;
    }
  }

  metronomo(t0, n, grade) {
    const projeto = this.projeto;
    const volume = dbParaLinear(projeto.metronomo?.volumeDb ?? -6);
    const comprimento = this.cliqueForte.length;
    for (const clique of this.cliquesDaContagem) {
      if (clique.quadro + comprimento > t0 && clique.quadro < t0 + n) this.somar(clique.forte ? this.cliqueForte : this.cliqueFraco, clique.quadro, t0, n, volume);
    }
    if (!grade) return;
    const porBatida = quadrosPorBatida(projeto);
    const batidas = projeto.compasso[0];
    let k = Math.max(0, Math.floor((t0 - comprimento) / porBatida));
    for (;;) {
      const quadro = Math.round(k * porBatida);
      if (quadro >= t0 + n) break;
      if (quadro >= this.fimDaContagem && quadro + comprimento > t0) this.somar(k % batidas === 0 ? this.cliqueForte : this.cliqueFraco, quadro, t0, n, volume);
      k += 1;
    }
  }
}

/** Quanto a mistura continua soando depois do último clipe (reverb, delay), em quadros. */
export function caudaEmQuadros(projeto, taxa = projeto.taxa) {
  let segundos = 0;
  const olhar = (efeitos) => {
    for (const efeito of efeitos ?? []) segundos = Math.max(segundos, caudaDoEfeito(efeito));
  };
  for (const faixa of projeto.faixas) olhar(faixa.efeitos);
  for (const bus of projeto.barramentos) olhar(bus.efeitos);
  olhar(projeto.master.efeitos);
  return Math.round(Math.min(10, segundos) * taxa);
}

/** Exporta um trecho inteiro em memória: {esq, dir}. */
export function renderizar(projeto, fontes, { inicio = 0, quadros, somente = null, semMaster = false, metronomo = false } = {}) {
  const misturador = new Misturador(projeto.taxa);
  for (const [id, fonte] of fontes) misturador.definirFonte(id, fonte);
  misturador.definirProjeto(projeto);
  const esq = new Float32Array(quadros);
  const dir = new Float32Array(quadros);
  const passo = 4096;
  const be = new Float32Array(passo);
  const bd = new Float32Array(passo);
  for (let feito = 0; feito < quadros; feito += passo) {
    const k = Math.min(passo, quadros - feito);
    misturador.processar(inicio + feito, k, be, bd, { somente, semMaster, metronomo });
    esq.set(be.subarray(0, k), feito);
    dir.set(bd.subarray(0, k), feito);
  }
  return { esq, dir };
}
