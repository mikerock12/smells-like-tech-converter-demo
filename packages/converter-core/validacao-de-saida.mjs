/**
 * Conferência do arquivo final antes de chamar uma conversão de "pronta". Nasceu de um caso
 * real: no Chrome do Linux (sem codificador AAC no WebCodecs), "Tirar ou isolar a voz do
 * vídeo" devolvia um MP4 só com a imagem, e a tela dizia "1 pronto". Agora a saída precisa
 * ter as trilhas esperadas, áudio com a duração do vídeo, som de verdade (não silêncio) e,
 * quando a ferramenta muda o áudio, algo diferente do original.
 *
 * Funções puras sobre números já medidos (trilhas, durações e PCM), testadas no Node.
 */

/** Abaixo disto o áudio inteiro conta como silêncio. */
export const LIMITE_DE_SILENCIO_DB = -60;
/** Diferença aceita entre a duração do áudio e a do vídeo, em segundos. */
export const TOLERANCIA_DE_DURACAO = 0.5;

function db(valor) {
  return valor > 0 ? 20 * Math.log10(valor) : -Infinity;
}

/** Pico (dBFS) de todos os canais; −Infinity no silêncio digital. */
export function picoDb(canais) {
  let pico = 0;
  for (const canal of canais) for (let i = 0; i < canal.length; i++) {
    const v = Math.abs(canal[i]);
    if (v > pico) pico = v;
  }
  return db(pico);
}

/** RMS (dBFS) de todos os canais juntos. */
export function rmsDb(canais) {
  let soma = 0;
  let n = 0;
  for (const canal of canais) {
    for (let i = 0; i < canal.length; i++) soma += canal[i] * canal[i];
    n += canal.length;
  }
  return n ? db(Math.sqrt(soma / n)) : -Infinity;
}

/**
 * Correlação (Pearson) entre a soma mono de `a` e de `b`, no comprimento comum, olhando uma
 * amostra a cada `passo` (barato em áudios longos). 1 = o mesmo sinal.
 */
export function correlacao(a, b, passo = 1) {
  const n = Math.min(a[0]?.length ?? 0, b[0]?.length ?? 0);
  let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, k = 0;
  for (let i = 0; i < n; i += passo) {
    let x = 0, y = 0;
    for (const c of a) x += c[i];
    for (const c of b) y += c[i];
    sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y; k++;
  }
  if (!k) return 0;
  const cov = sab / k - (sa / k) * (sb / k);
  const va = saa / k - (sa / k) ** 2;
  const vb = sbb / k - (sb / k) ** 2;
  return va > 0 && vb > 0 ? cov / Math.sqrt(va * vb) : 0;
}

/**
 * Os problemas do arquivo final (lista vazia = pode ir para o download). Cada problema traz a
 * etapa e uma mensagem técnica, para o "Detalhes" da tela e o console.
 *
 * @param {object} d
 * @param {boolean} [d.esperaVideo]           a saída precisa ter imagem
 * @param {number}  [d.faixasDeVideo]         trilhas de vídeo encontradas na saída
 * @param {boolean} [d.esperaAudio]           a saída precisa ter som (padrão: sim)
 * @param {number}  [d.faixasDeAudio]         trilhas de áudio encontradas na saída
 * @param {number|null} [d.duracaoDoVideo]    segundos
 * @param {number|null} [d.duracaoDoAudio]    segundos
 * @param {number|null} [d.picoDaSaidaDb]     pico do áudio que foi gravado
 * @param {number|null} [d.picoDaEntradaDb]   pico do áudio de entrada
 * @param {number|null} [d.correlacaoComEntrada]
 * @param {number|null} [d.diferencaDeNivelDb] RMS da saída − RMS da entrada
 * @param {boolean} [d.deveMudar]             a ferramenta altera o áudio (tirar/isolar a voz)
 */
export function conferirSaida(d) {
  const problemas = [];
  const esperaAudio = d.esperaAudio !== false;
  if (d.esperaVideo && d.faixasDeVideo !== 1) {
    problemas.push({ etapa: "conferir", mensagem: `a saída tem ${d.faixasDeVideo ?? 0} trilha(s) de vídeo; esperava 1` });
  }
  if (esperaAudio && d.faixasDeAudio !== 1) {
    problemas.push({ etapa: "conferir", mensagem: `a saída tem ${d.faixasDeAudio ?? 0} trilha(s) de áudio; esperava 1` });
  }
  if (esperaAudio && d.esperaVideo && d.faixasDeAudio === 1 && d.duracaoDoVideo != null && d.duracaoDoAudio != null &&
      Math.abs(d.duracaoDoAudio - d.duracaoDoVideo) > TOLERANCIA_DE_DURACAO) {
    problemas.push({ etapa: "conferir", mensagem: `o áudio tem ${d.duracaoDoAudio.toFixed(2)} s e o vídeo ${d.duracaoDoVideo.toFixed(2)} s` });
  }
  if (esperaAudio && d.picoDaSaidaDb != null && d.picoDaSaidaDb < LIMITE_DE_SILENCIO_DB &&
      !(d.picoDaEntradaDb != null && d.picoDaEntradaDb < LIMITE_DE_SILENCIO_DB)) {
    const pico = Number.isFinite(d.picoDaSaidaDb) ? `${d.picoDaSaidaDb.toFixed(1)} dBFS` : "silêncio digital";
    problemas.push({ etapa: "conferir", mensagem: `o áudio gravado ficou em silêncio (pico ${pico}) e a entrada não era silenciosa` });
  }
  if (d.deveMudar && d.correlacaoComEntrada != null && d.correlacaoComEntrada > 0.9995 &&
      (d.diferencaDeNivelDb == null || Math.abs(d.diferencaDeNivelDb) < 0.5)) {
    problemas.push({ etapa: "separar", mensagem: `o áudio saiu igual ao original (correlação ${d.correlacaoComEntrada.toFixed(4)}): a separação não agiu` });
  }
  return problemas;
}

/** A frase amigável da tela quando a conferência ou uma etapa falha. */
export const MENSAGEM_DE_FALHA_DO_AUDIO =
  "Não conseguimos processar o áudio deste vídeo neste navegador. Tente pelo plugin, pelo app para Windows/Android ou por outro navegador.";

/** "Etapa: mensagem" de cada problema, uma por linha, para o "Detalhes". */
export function detalhesDosProblemas(problemas) {
  return problemas.map((p) => `${p.etapa}: ${p.mensagem}`).join("\n");
}
