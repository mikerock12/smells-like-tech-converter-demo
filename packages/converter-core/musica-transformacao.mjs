import { mudarTempo } from "./audio-tempo.mjs";

export const VELOCIDADE_MINIMA = 0.25;
export const VELOCIDADE_MAXIMA = 4;
export const SEMITONS_MINIMO = -12;
export const SEMITONS_MAXIMO = 12;

export const velocidadeDoClipe = (clipe) => clipe.velocidade ?? 1;
export const semitonsDoClipe = (clipe) => clipe.semitons ?? 0;
export const preservarTomDoClipe = (clipe) => clipe.preservarTom ?? true;
export const fatorDoTom = (semitons) => Math.pow(2, semitons / 12);

export function temTransformacao(clipe) {
  return velocidadeDoClipe(clipe) !== 1 || semitonsDoClipe(clipe) !== 0;
}

/** Fonte registrada no motor para este clipe; a original continua disponível como recurso. */
export function chaveDaFonteDoClipe(clipe) {
  if (!temTransformacao(clipe)) return clipe.midia;
  return `${clipe.midia}~${velocidadeDoClipe(clipe).toFixed(6)}~${semitonsDoClipe(clipe).toFixed(3)}~${preservarTomDoClipe(clipe) ? 1 : 0}`;
}

/** Mantém o trecho escolhido e muda sua duração na linha do tempo com a velocidade. */
export function clipeComVelocidade(clipe, midia, velocidade) {
  if (!Number.isFinite(velocidade) || velocidade < VELOCIDADE_MINIMA || velocidade > VELOCIDADE_MAXIMA) throw new Error("A velocidade vai de 25% a 400%.");
  const anterior = velocidadeDoClipe(clipe);
  const disponivel = Math.max(1, midia.quadros - clipe.inicioNaMidia);
  const quadrosDaOrigem = Math.min(disponivel, clipe.duracao * anterior);
  const duracao = Math.max(1, Math.floor(quadrosDaOrigem / velocidade));
  return { ...clipe, velocidade, duracao, entradaSuave: Math.min(clipe.entradaSuave, duracao), saidaSuave: Math.min(clipe.saidaSuave, duracao) };
}

/** velocidade para o destino tocar no BPM da origem. */
export function velocidadeParaIgualar(bpmDaOrigem, bpmDoDestino) {
  if (!(bpmDaOrigem > 0) || !(bpmDoDestino > 0)) throw new Error("Identifique o BPM das duas faixas primeiro.");
  return Math.min(VELOCIDADE_MAXIMA, Math.max(VELOCIDADE_MINIMA, bpmDaOrigem / bpmDoDestino));
}

export function reamostrar(canais, fator) {
  if (fator === 1) return canais.map((canal) => canal.slice());
  return canais.map((canal) => {
    const tamanho = Math.max(1, Math.round(canal.length / fator));
    const saida = new Float32Array(tamanho);
    for (let i = 0; i < tamanho; i += 1) {
      const posicao = Math.min(canal.length - 1, i * fator);
      const a = Math.max(0, Math.floor(posicao));
      const b = Math.min(canal.length - 1, a + 1);
      const peso = Math.max(0, posicao - a);
      saida[i] = canal[a] + (canal[b] - canal[a]) * peso;
    }
    return saida;
  });
}

function esticar(canais, taxa, velocidade) {
  if (velocidade === 1) return canais.map((canal) => canal.slice());
  // O WSOLA existente aceita 0,25–4; a combinação com transposição pode sair dessa faixa.
  // Uma passada com o algoritmo sem o limite mantém a duração e o mesmo casamento de janelas.
  if (velocidade >= 0.25 && velocidade <= 4) return mudarTempo(canais, taxa, velocidade);
  const origem = canais[0]?.length ?? 0;
  const tamanho = Math.max(1, Math.round(origem / velocidade));
  const janela = Math.max(64, Math.round((taxa * 0.04) / 2) * 2);
  const passo = janela / 2;
  const busca = Math.round(taxa * 0.008);
  const saida = canais.map(() => new Float32Array(tamanho));
  const pesos = new Float32Array(tamanho);
  const envelope = Float32Array.from({ length: janela }, (_, i) => 0.5 - 0.5 * Math.cos((2 * Math.PI * (i + 0.5)) / janela));
  let anterior = 0;
  for (let pos = 0; pos < tamanho; pos += passo) {
    const esperado = Math.round(pos * velocidade);
    const maximo = Math.max(0, origem - janela);
    let escolhido = Math.min(esperado, maximo);
    let melhor = -Infinity;
    if (pos > 0 && esperado < maximo) {
      const de = Math.max(0, esperado - busca);
      const ate = Math.min(maximo, esperado + busca);
      for (let candidato = de; candidato <= ate; candidato += 4) {
        let produto = 0;
        let a = 1e-9;
        let b = 1e-9;
        for (let i = 0; i < passo; i += 8) {
          const x = canais[0][anterior + passo + i] ?? 0;
          const y = canais[0][candidato + i] ?? 0;
          produto += x * y;
          a += x * x;
          b += y * y;
        }
        const nota = produto / Math.sqrt(a * b) - Math.abs(candidato - esperado) * 1e-8;
        if (nota > melhor) { melhor = nota; escolhido = candidato; }
      }
    }
    for (let i = 0; i < janela && pos + i < tamanho; i += 1) {
      const peso = envelope[i];
      pesos[pos + i] += peso;
      for (let c = 0; c < canais.length; c += 1) saida[c][pos + i] += (canais[c][escolhido + i] ?? 0) * peso;
    }
    anterior = escolhido;
  }
  for (let i = 0; i < tamanho; i += 1) if (pesos[i] > 0) for (const canal of saida) canal[i] /= pesos[i];
  return saida;
}

/**
 * Tempo e tom sem alterar a mídia original. Com preservação: reamostra pelo tom e aplica
 * WSOLA em velocidade/tom. No modo fita: a velocidade também muda o tom; a transposição
 * adicional é compensada para não mudar a duração outra vez.
 */
export function transformarCanais(canais, taxa, { velocidade = 1, semitons = 0, preservarTom = true } = {}) {
  if (!(velocidade >= VELOCIDADE_MINIMA && velocidade <= VELOCIDADE_MAXIMA)) throw new Error("A velocidade vai de 25% a 400%.");
  if (!(semitons >= SEMITONS_MINIMO && semitons <= SEMITONS_MAXIMO)) throw new Error("A tonalidade vai de −12 a +12 semitons.");
  const tom = fatorDoTom(semitons);
  if (preservarTom) {
    const afinado = reamostrar(canais, tom);
    return esticar(afinado, taxa, velocidade / tom);
  }
  const naVelocidade = reamostrar(canais, velocidade * tom);
  return esticar(naVelocidade, taxa, 1 / tom);
}
