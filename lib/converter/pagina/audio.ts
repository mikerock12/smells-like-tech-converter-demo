import { sampleRateDoArquivo, sampleRateParaMp3, outputFileName } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";

import { lerTempoEmSegundos, type Opcoes } from "../ferramentas";
import { ConversionError } from "../protocol";

/**
 * A parte do áudio que precisa da página: ler com os codecs do navegador e editar.
 *
 * `decodeAudioData` depende do AudioContext, que não existe num Worker. Em compensação
 * ele lê MP3, M4A, OGG, FLAC e a trilha de vídeo MP4/WEBM de graça — são os codecs que
 * o navegador já carrega para tocar som. A edição (corte, volume, fade, velocidade,
 * mono) é aritmética sobre o PCM e acontece aqui mesmo; a gravação vai para o worker.
 */

export interface Pcm {
  readonly canais: Float32Array[];
  readonly sampleRate: number;
}

export async function decodificarAudio(arquivo: Blob, formato: FormatId | null): Promise<Pcm> {
  const Contexto =
    window.OfflineAudioContext ??
    (window as unknown as { webkitOfflineAudioContext?: typeof OfflineAudioContext }).webkitOfflineAudioContext;

  if (!Contexto) {
    throw new ConversionError("sem_audio_api", "Este navegador não sabe ler áudio. Tente pelo plugin ou pelo aplicativo.");
  }

  const bytes = await arquivo.arrayBuffer();

  // A taxa sai do cabeçalho antes de decodificar: `decodeAudioData` reamostra para a
  // taxa do contexto, então perguntar depois devolveria a taxa do contexto, nunca a do
  // arquivo. Um MP3 de 44,1 kHz continua 44,1 kHz.
  const desejada = formato ? sampleRateParaMp3(sampleRateDoArquivo(bytes, formato)) : null;
  const contexto = criarContexto(Contexto, desejada);

  try {
    const buffer = await contexto.decodeAudioData(bytes);
    const canais: Float32Array[] = [];
    for (let indice = 0; indice < buffer.numberOfChannels; indice += 1) {
      canais.push(new Float32Array(buffer.getChannelData(indice)));
    }
    return { canais, sampleRate: buffer.sampleRate };
  } catch {
    throw new ConversionError("audio_ilegivel", "Não foi possível ler este áudio. O arquivo pode estar incompleto, protegido, ou usar um codec que este navegador não tem.");
  }
}

function criarContexto(Contexto: typeof OfflineAudioContext, taxa: number | null): OfflineAudioContext {
  if (taxa !== null) {
    try {
      return new Contexto(1, 1, taxa);
    } catch {
      // Taxa fora do que este navegador aceita; segue para o padrão.
    }
  }
  return new Contexto(1, 1, 48000);
}

// ==================== edição ====================

export function editarPcm(pcm: Pcm, opcoes: Opcoes): Pcm {
  let canais = pcm.canais.map((canal) => canal);
  const { sampleRate } = pcm;

  const inicio = lerTempoEmSegundos(opcoes.inicio);
  const fim = lerTempoEmSegundos(opcoes.fim);
  if (inicio !== null || fim !== null) {
    const de = Math.max(0, Math.floor((inicio ?? 0) * sampleRate));
    const ate = Math.min(canais[0].length, Math.floor((fim ?? Number.POSITIVE_INFINITY) * sampleRate));
    if (ate <= de) throw new ConversionError("corte_invalido", "O fim do corte precisa vir depois do início.");
    canais = canais.map((canal) => canal.slice(de, ate));
  }

  if (opcoes.mono === true && canais.length > 1) {
    const mono = new Float32Array(canais[0].length);
    for (let i = 0; i < mono.length; i += 1) {
      let soma = 0;
      for (const canal of canais) soma += canal[i];
      mono[i] = soma / canais.length;
    }
    canais = [mono];
  }

  const velocidade = Number(opcoes.velocidade ?? 1) || 1;
  if (Math.abs(velocidade - 1) > 0.001) {
    canais = canais.map((canal) => mudarVelocidade(canal, velocidade, sampleRate));
  }

  const volume = (Number(opcoes.volume ?? 100) || 100) / 100;
  if (Math.abs(volume - 1) > 0.001) {
    for (const canal of canais) for (let i = 0; i < canal.length; i += 1) canal[i] *= volume;
  }

  if (opcoes.normalizar === true) {
    let pico = 0;
    for (const canal of canais) for (let i = 0; i < canal.length; i += 1) pico = Math.max(pico, Math.abs(canal[i]));
    if (pico > 0) {
      const alvo = 0.891; // -1 dBFS
      const ganho = alvo / pico;
      for (const canal of canais) for (let i = 0; i < canal.length; i += 1) canal[i] *= ganho;
    }
  }

  const fadeIn = Math.max(0, Number(opcoes.fadeIn ?? 0) || 0);
  const fadeOut = Math.max(0, Number(opcoes.fadeOut ?? 0) || 0);
  const total = canais[0].length;
  if (fadeIn > 0) {
    const n = Math.min(total, Math.floor(fadeIn * sampleRate));
    for (const canal of canais) for (let i = 0; i < n; i += 1) canal[i] *= i / n;
  }
  if (fadeOut > 0) {
    const n = Math.min(total, Math.floor(fadeOut * sampleRate));
    for (const canal of canais) for (let i = 0; i < n; i += 1) canal[total - 1 - i] *= i / n;
  }

  return { canais, sampleRate };
}

/**
 * Muda a velocidade sem mudar o tom (WSOLA simplificado).
 *
 * Corta o áudio em janelas de 50 ms, sobrepõe pela metade e, a cada janela nova, procura
 * numa vizinhança de 10 ms o ponto que melhor casa com o que já foi gravado. É o
 * suficiente para fala e música em 0,5× a 2× sem virar "voz de esquilo".
 */
export function mudarVelocidade(amostras: Float32Array, fator: number, sampleRate: number): Float32Array {
  const janela = Math.floor(sampleRate * 0.05);
  const salto = Math.floor(janela / 2);
  const busca = Math.floor(sampleRate * 0.01);
  const saltoDeLeitura = salto * fator;
  const tamanhoDaSaida = Math.floor(amostras.length / fator);
  const saida = new Float32Array(tamanhoDaSaida + janela);

  const hann = new Float32Array(janela);
  for (let i = 0; i < janela; i += 1) hann[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (janela - 1)));

  let posicaoDeEscrita = 0;
  let posicaoDeLeitura = 0;
  let ultimoInicio = 0;

  while (posicaoDeEscrita + janela < saida.length && posicaoDeLeitura + janela + busca < amostras.length) {
    // Onde a continuação natural do trecho anterior estaria.
    const esperado = ultimoInicio + salto;
    let melhor = Math.floor(posicaoDeLeitura);
    let melhorPontuacao = -Infinity;
    const de = Math.max(0, Math.floor(posicaoDeLeitura) - busca);
    const ate = Math.min(amostras.length - janela, Math.floor(posicaoDeLeitura) + busca);

    for (let candidato = de; candidato <= ate; candidato += 4) {
      let pontuacao = 0;
      for (let i = 0; i < salto; i += 8) {
        pontuacao += amostras[candidato + i] * amostras[Math.min(amostras.length - 1, esperado + i)];
      }
      if (pontuacao > melhorPontuacao) {
        melhorPontuacao = pontuacao;
        melhor = candidato;
      }
    }

    for (let i = 0; i < janela; i += 1) {
      saida[posicaoDeEscrita + i] += amostras[melhor + i] * hann[i];
    }

    ultimoInicio = melhor;
    posicaoDeEscrita += salto;
    posicaoDeLeitura += saltoDeLeitura;
  }

  return saida.slice(0, tamanhoDaSaida);
}

/** Nome do arquivo de saída de um job de áudio. */
export function nomeDeSaidaDeAudio(nome: string, opcoes: Opcoes, sufixo = ""): string {
  const formato = (String(opcoes.formato ?? "mp3") === "wav" ? "wav" : "mp3") as FormatId;
  return outputFileName(nome, formato).replace(/(\.[^.]+)$/, `${sufixo}$1`);
}
