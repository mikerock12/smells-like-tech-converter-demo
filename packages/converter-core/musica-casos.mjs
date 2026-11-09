/**
 * O que cada caso compartilhado da criação musical confere. C# (MusicCasesTests) e Kotlin
 * (MusicaCasosTest) recalculam os mesmos campos a partir do caso e comparam com o
 * `esperado` gravado por `node scripts/musica-casos.mjs`.
 *
 * As mídias são geradas pelo caso (seno, ruído, impulsos), para não versionar áudio: o
 * gerador é parte da regra e é espelhado nos três núcleos.
 */

import { cliquesDoMetronomo, clipeDaTomada, duracaoEmQuadros, faixasAudiveis, ganhosEstereo, medirLatencia, planoDaGravacao, posicaoMusical, problemasDaMusica } from "./musica.mjs";
import { validarEfeito } from "./musica-dsp.mjs";
import { caudaEmQuadros, fonteDaMemoria, renderizar } from "./musica-mixer.mjs";
import { codificarWav, criarRuido, masterizar, medirMaster } from "./musica-master.mjs";
import { MODELO_DE_SEPARACAO, ProcessadorEspectral, mascaraDaFaixa, modoDeSeparacao, reduzirRuido } from "./musica-separacao.mjs";
import { conferirEntradas } from "./musica-arquivo.mjs";

/** Gera os canais de uma mídia sintética. */
export function gerarMidia(gerador, quadros, taxa) {
  const canais = [];
  for (let c = 0; c < (gerador.canais ?? 1); c += 1) {
    const x = new Float32Array(quadros);
    const amplitude = gerador.amplitude ?? 0.5;
    if (gerador.tipo === "seno") {
      const f = c === 1 && gerador.frequencia2 ? gerador.frequencia2 : gerador.frequencia;
      for (let i = 0; i < quadros; i += 1) x[i] = amplitude * Math.sin((2 * Math.PI * f * i) / taxa);
    } else if (gerador.tipo === "ruido") {
      const ruido = criarRuido((gerador.semente ?? 1) + c);
      for (let i = 0; i < quadros; i += 1) x[i] = amplitude * (ruido() * 2 - 1);
    } else if (gerador.tipo === "impulsos") {
      for (let i = gerador.inicio ?? 0; i < quadros; i += gerador.periodo) x[i] = amplitude;
    }
    canais.push(x);
  }
  return canais;
}

/** FNV-1a de 32 bits: a impressão digital dos bytes do WAV. */
export function fnv1a(bytes) {
  let h = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i += 1) {
    h ^= bytes[i];
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

const arredondar = (v) => (Number.isFinite(v) ? Math.round(v * 1e6) / 1e6 + 0 : v === -Infinity ? "-inf" : String(v));

/** Resumo de um par de canais: somas, pico e algumas amostras. */
export function resumo(esq, dir, indices) {
  let soma = 0;
  let energia = 0;
  let pico = 0;
  for (let i = 0; i < esq.length; i += 1) {
    soma += esq[i] + dir[i];
    energia += esq[i] * esq[i] + dir[i] * dir[i];
    pico = Math.max(pico, Math.abs(esq[i]), Math.abs(dir[i]));
  }
  return { soma: arredondar(soma), energia: arredondar(energia), pico: arredondar(pico), amostras: indices.filter((i) => i < esq.length).map((i) => [i, arredondar(esq[i]), arredondar(dir[i])]) };
}

export function indicesDeAmostra(quadros) {
  const indices = [];
  for (let k = 0; k < 16; k += 1) indices.push(Math.floor((k * (quadros - 1)) / 15));
  return indices;
}

function fontesDoCaso(caso) {
  const fontes = new Map();
  for (const midia of caso.projeto.midias ?? []) {
    const gerador = caso.midias?.[midia.id];
    if (gerador) fontes.set(midia.id, fonteDaMemoria(gerarMidia({ canais: midia.canais, ...gerador }, midia.quadros, caso.projeto.taxa)));
  }
  return fontes;
}

/** Modelo falso e determinístico para conferir a matemática da separação sem a rede neural. */
export function estimativasFalsas(entrada, T, bandas, instrumentos) {
  const saida = new Float32Array(instrumentos * entrada.length);
  for (let i = 0; i < instrumentos; i += 1) {
    for (let c = 0; c < 2; c += 1) {
      for (let f = 0; f < T; f += 1) {
        for (let k = 0; k < bandas; k += 1) {
          const j = (c * T + f) * bandas + k;
          const peso = 1 + ((i + 1) * ((k * 7 + f * 3 + c) % 11)) / 10;
          saida[i * entrada.length + j] = entrada[j] * peso;
        }
      }
    }
  }
  return saida;
}

function separacaoFalsa(caso) {
  const { comprimento, modo } = caso.separacao;
  const original = gerarMidia(caso.separacao.gerador, comprimento, MODELO_DE_SEPARACAO.taxa);
  const m = MODELO_DE_SEPARACAO;
  const definicao = modoDeSeparacao(modo);
  const p = new ProcessadorEspectral({ comprimento, janela: m.janela, passo: m.passo, quadrosPorTrecho: m.quadrosPorTrecho, saidas: definicao.faixas.length });
  const saidas = definicao.faixas.map(() => [new Float32Array(comprimento), new Float32Array(comprimento)]);
  const entrada = new Float32Array(2 * m.quadrosPorTrecho * m.bandas);
  const mascara = [new Float32Array(m.quadrosPorTrecho * p.B), new Float32Array(m.quadrosPorTrecho * p.B)];
  const ler = (c, i, n, d) => d.set(original[Math.min(c, original.length - 1)].subarray(i, i + n));
  for (let s = 0; s < p.trechos; s += 1) {
    const q = p.analisar(s, ler, original.length);
    p.magnitude(q, m.bandas, entrada);
    const estimativas = estimativasFalsas(entrada, m.quadrosPorTrecho, m.bandas, m.instrumentos.length);
    definicao.faixas.forEach((faixa, f) => {
      mascaraDaFaixa(estimativas, m.instrumentos.length, faixa.de.map((nome) => m.instrumentos.indexOf(nome)), q, m.quadrosPorTrecho, m.bandas, p.B, mascara);
      p.sintetizar(s, f, mascara, (c, inicio, amostras, n) => saidas[f][c].set(amostras.subarray(0, n), inicio));
    });
  }
  const indices = indicesDeAmostra(comprimento);
  return { trechos: p.trechos, faixas: definicao.faixas.map((faixa, f) => ({ id: faixa.id, ...resumo(saidas[f][0], saidas[f][1], indices) })) };
}

function ruidoReduzido(caso) {
  const { comprimento, gerador } = caso.reducaoDeRuido;
  const original = gerarMidia(gerador, comprimento, 48000);
  const saida = [new Float32Array(comprimento), new Float32Array(comprimento)];
  reduzirRuido({ comprimento, canais: original.length, ler: (c, i, n, d) => d.set(original[Math.min(c, original.length - 1)].subarray(i, i + n)), emitir: (c, inicio, amostras, n) => saida[c].set(amostras.subarray(0, n), inicio) });
  return resumo(saida[0], saida[1], indicesDeAmostra(comprimento));
}

export function esperadoDoCasoMusical(caso) {
  const esperado = {};
  const projeto = caso.projeto;
  if (projeto) {
    const erros = problemasDaMusica(projeto, validarEfeito);
    esperado.problemas = erros;
    if (erros.length === 0) {
      esperado.duracao = duracaoEmQuadros(projeto);
      esperado.cauda = caudaEmQuadros(projeto);
      esperado.audiveis = faixasAudiveis(projeto);
      esperado.ganhos = projeto.faixas.map((f) => ganhosEstereo(f.ganhoDb, f.pan, f.ajusteEsquerdoDb, f.ajusteDireitoDb).map(arredondar));
      if (caso.posicoes) esperado.posicoes = caso.posicoes.map((q) => posicaoMusical(projeto, q).texto);
      if (caso.metronomo) esperado.metronomo = cliquesDoMetronomo(projeto, caso.metronomo.inicio, caso.metronomo.fim);
      if (caso.gravacao) {
        const plano = planoDaGravacao(projeto, caso.gravacao.cursor);
        esperado.gravacao = { plano, clipe: clipeDaTomada(projeto, { id: "tomada-1", midia: "gravacao-1", quadroAlinhado: caso.gravacao.quadroAlinhado, quadrosCapturados: caso.gravacao.quadrosCapturados }, plano) };
      }
      if (caso.renders) {
        const fontes = fontesDoCaso(caso);
        esperado.renders = caso.renders.map((pedido) => {
          const { esq, dir } = renderizar(projeto, fontes, pedido);
          const r = resumo(esq, dir, indicesDeAmostra(pedido.quadros));
          if (pedido.medir) {
            const medidas = medirMaster(esq, dir, projeto.taxa);
            r.medidas = { lufs: arredondar(medidas.lufs), amostraDb: arredondar(medidas.amostraDb), realDb: arredondar(medidas.realDb) };
          }
          if (pedido.master) {
            const m = masterizar(esq, dir, projeto.taxa, pedido.master);
            r.master = { ganhoDb: arredondar(m.ganhoDb), lufs: arredondar(m.depois.lufs), realDb: arredondar(m.depois.realDb), ...resumo(m.esq, m.dir, indicesDeAmostra(pedido.quadros)) };
          }
          if (pedido.wav) r.wav = pedido.wav.map((bits) => fnv1a(codificarWav([esq, dir], projeto.taxa, bits)));
          return r;
        });
      }
    }
  }
  if (caso.latencia) {
    const { quadros, atraso, pulsos, taxa } = caso.latencia;
    const gravado = new Float32Array(quadros);
    for (const p of pulsos) for (let i = 0; i < 48 && p + atraso + i < quadros; i += 1) gravado[p + atraso + i] = 0.6 * Math.exp(-i / 8) * (i % 2 ? -1 : 1);
    esperado.latencia = medirLatencia(gravado, pulsos, taxa);
  }
  if (caso.arquivo) esperado.arquivo = caso.arquivo.map((entradas) => conferirEntradas(entradas));
  if (caso.separacao) esperado.separacao = separacaoFalsa(caso);
  if (caso.reducaoDeRuido) esperado.reducaoDeRuido = ruidoReduzido(caso);
  return esperado;
}
