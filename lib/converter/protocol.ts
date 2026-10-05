import type { FormatId } from "@/packages/converter-core/index.mjs";

import type { Opcoes } from "./ferramentas";

/**
 * Contrato entre a página e o Web Worker que converte.
 *
 * O worker recebe uma tarefa (ferramenta + arquivos + opções) e devolve uma ou mais
 * saídas. Os arquivos viajam como `File`, por referência: o navegador não copia os
 * bytes, então um vídeo de gigabytes entra sem dobrar a memória.
 */

export interface Entrada {
  readonly nome: string;
  readonly arquivo: Blob;
  readonly formato: FormatId | null;
}

export interface TarefaRequest {
  readonly type: "tarefa";
  readonly jobId: string;
  readonly ferramenta: string;
  readonly entradas: readonly Entrada[];
  readonly opcoes: Opcoes;
}

/**
 * Codificação de áudio a partir de PCM já decodificado na página.
 *
 * A decodificação fica na página porque `decodeAudioData` depende do AudioContext,
 * que não existe num Worker. A parte cara — codificar — sai da thread da interface.
 */
export interface CodificarAudioRequest {
  readonly type: "codificarAudio";
  readonly jobId: string;
  readonly nome: string;
  /** Um Float32Array por canal, entre -1 e 1. Transferidos, não copiados. */
  readonly canais: readonly Float32Array[];
  readonly sampleRate: number;
  readonly formato: "mp3" | "wav";
  readonly bitrate: number;
}

export interface CancelRequest {
  readonly type: "cancel";
  readonly jobId: string;
}

export type WorkerRequest = TarefaRequest | CodificarAudioRequest | CancelRequest;

export interface Saida {
  readonly nome: string;
  readonly mime: string;
  readonly bytes: ArrayBuffer;
  readonly largura?: number;
  readonly altura?: number;
  readonly duracao?: number;
  readonly paginas?: number;
}

export interface ProgressMessage {
  readonly type: "progress";
  readonly jobId: string;
  readonly etapa: string;
  readonly fraction: number;
}

export interface DoneMessage {
  readonly type: "done";
  readonly jobId: string;
  readonly saidas: readonly Saida[];
  readonly nota?: string;
}

export interface FailedMessage {
  readonly type: "failed";
  readonly jobId: string;
  readonly code: string;
  readonly message: string;
}

export type WorkerResponse = ProgressMessage | DoneMessage | FailedMessage;

export class ConversionError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** Assinatura comum dos motores que rodam no worker. */
export interface ContextoDoMotor {
  readonly jobId: string;
  readonly ferramenta: string;
  readonly entradas: readonly Entrada[];
  readonly opcoes: Opcoes;
  readonly progresso: (etapa: string, fraction: number) => void;
  readonly cancelado: () => boolean;
}

export type Motor = (contexto: ContextoDoMotor) => Promise<{ saidas: Saida[]; nota?: string }>;
