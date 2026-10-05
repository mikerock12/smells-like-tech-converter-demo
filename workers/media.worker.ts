/// <reference lib="webworker" />
// SPDX-License-Identifier: GPL-3.0-or-later — worker do cliente navegador, não backend.

import type { CodificarAudioRequest, ContextoDoMotor, Motor, TarefaRequest, WorkerRequest, WorkerResponse } from "@/lib/converter/protocol";
import { ConversionError } from "@/lib/converter/protocol";

/**
 * O worker de mídia.
 *
 * Um só para tudo: ele recebe a tarefa e importa, sob demanda, o motor daquela família
 * — imagem, PDF, vídeo, legenda. Quem converte só imagem nunca baixa o código de vídeo,
 * e vice-versa. Nenhum byte sai do dispositivo em momento algum.
 */

const cancelados = new Set<string>();

function responder(mensagem: WorkerResponse, transfer?: Transferable[]) {
  (self as unknown as DedicatedWorkerGlobalScope).postMessage(mensagem, transfer ?? []);
}

self.addEventListener("message", (evento: MessageEvent<WorkerRequest>) => {
  const pedido = evento.data;

  if (pedido.type === "cancel") {
    cancelados.add(pedido.jobId);
    void import("@/lib/converter/motores/video").then((modulo) => modulo.cancelarVideo(pedido.jobId));
    return;
  }

  if (pedido.type === "tarefa") {
    void executar(pedido);
    return;
  }

  if (pedido.type === "codificarAudio") {
    void codificarAudio(pedido);
  }
});

async function motorPara(ferramenta: string): Promise<Motor> {
  const familia = ferramenta.split(".")[0];

  if (ferramenta === "imagem.para-pdf" || familia === "pdf") {
    return (await import("@/lib/converter/motores/pdf")).motorDePdf;
  }
  if (familia === "imagem") {
    return (await import("@/lib/converter/motores/imagem")).motorDeImagem;
  }
  if (familia === "video") {
    return (await import("@/lib/converter/motores/video")).motorDeVideo;
  }
  if (familia === "legenda") {
    return (await import("@/lib/converter/motores/legenda")).motorDeLegenda;
  }
  throw new ConversionError("ferramenta_desconhecida", `Não sei executar “${ferramenta}” no navegador.`);
}

async function executar(pedido: TarefaRequest) {
  const { jobId } = pedido;

  const contexto: ContextoDoMotor = {
    jobId,
    ferramenta: pedido.ferramenta,
    entradas: pedido.entradas,
    opcoes: pedido.opcoes,
    progresso: (etapa, fraction) => responder({ type: "progress", jobId, etapa, fraction: Math.max(0, Math.min(1, fraction)) }),
    cancelado: () => cancelados.has(jobId),
  };

  try {
    const motor = await motorPara(pedido.ferramenta);
    const { saidas, nota } = await motor(contexto);
    if (cancelados.has(jobId)) return;
    if (saidas.length === 0) {
      throw new ConversionError("sem_saida", "A conversão terminou sem produzir arquivo.");
    }
    responder({ type: "done", jobId, saidas, nota }, saidas.map((saida) => saida.bytes));
  } catch (erro) {
    if (cancelados.has(jobId)) return;
    const conhecido = erro instanceof ConversionError;
    responder({
      type: "failed",
      jobId,
      code: conhecido ? erro.code : "conversion_failed",
      message: conhecido
        ? erro.message
        : `Não foi possível converter. ${erro instanceof Error ? erro.message : "O arquivo pode estar corrompido ou usar um recurso não suportado."}`,
    });
  } finally {
    cancelados.delete(jobId);
  }
}

async function codificarAudio(pedido: CodificarAudioRequest) {
  const { jobId, canais, sampleRate, formato, bitrate } = pedido;
  try {
    const { codificarMp3, codificarWav } = await import("@/lib/converter/motores/audio-codificar");
    const bytes =
      formato === "wav"
        ? codificarWav(canais, sampleRate)
        : codificarMp3(canais, sampleRate, bitrate, (fracao) => responder({ type: "progress", jobId, etapa: "gravando", fraction: fracao }), () => cancelados.has(jobId));
    if (!bytes || cancelados.has(jobId)) return;

    responder(
      {
        type: "done",
        jobId,
        saidas: [
          {
            nome: pedido.nome,
            mime: formato === "wav" ? "audio/wav" : "audio/mpeg",
            bytes,
            duracao: canais[0].length / sampleRate,
          },
        ],
      },
      [bytes],
    );
  } catch (erro) {
    responder({
      type: "failed",
      jobId,
      code: "audio_encode_failed",
      message: erro instanceof Error ? erro.message : "Não foi possível converter este áudio.",
    });
  } finally {
    cancelados.delete(jobId);
  }
}
