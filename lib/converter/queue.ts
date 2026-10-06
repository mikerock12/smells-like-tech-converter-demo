import { detectFormat, formatFromFileName, kindOf } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";
import { ferramenta as buscarFerramenta } from "@/packages/converter-core/catalogo.mjs";

import { type Opcoes } from "./ferramentas";
import { ConversionError, type Entrada, type Saida, type WorkerRequest, type WorkerResponse } from "./protocol";

export type JobStatus = "pending" | "running" | "done" | "failed" | "cancelled";

export interface Resultado {
  readonly blob: Blob;
  readonly url: string;
  readonly nome: string;
  readonly tamanho: number;
  readonly largura?: number;
  readonly altura?: number;
  readonly duracao?: number;
  readonly paginas?: number;
}

export interface Job {
  readonly id: string;
  readonly ferramenta: string;
  readonly titulo: string;
  readonly nomes: readonly string[];
  readonly inputSize: number;
  readonly formato: FormatId | null;
  readonly status: JobStatus;
  readonly progress: number;
  readonly segundosRestantes: number | null;
  readonly etapa: string;
  readonly error: string | null;
  readonly resultados: readonly Resultado[] | null;
  readonly nota: string | null;
  readonly startedAt: number | null;
  readonly finishedAt: number | null;
}

interface InternalJob extends Job {
  entradas: Entrada[];
  opcoes: Opcoes;
}

/** Ferramentas que juntam vários arquivos numa saída só. */
export const FERRAMENTAS_DE_LOTE = new Set(["pdf.juntar", "imagem.para-pdf"]);

/** Ferramentas que rodam na página, e não no worker. */
const NA_PAGINA = new Set(["audio.converter", "audio.editar", "pdf.para-imagens", "pdf.para-texto", "imagem.ocr", "voz.narrar"]);

const NO_JOBS: Job[] = [];

/** Lê a assinatura do arquivo; a extensão só desempata. */
export async function identificar(arquivo: File): Promise<FormatId | null> {
  const cabecalho = new Uint8Array(await arquivo.slice(0, 64).arrayBuffer());
  return detectFormat(cabecalho) ?? formatFromFileName(arquivo.name);
}

/**
 * A oficina: fila de tarefas locais.
 *
 * Distribui o trabalho entre Web Workers (imagem, PDF, vídeo, legenda) e a própria
 * página (áudio, leitura de PDF, OCR), informa progresso de verdade e permite cancelar.
 * Nada aqui fala com a rede.
 */
export class Oficina {
  private jobs: InternalJob[] = [];
  private readonly listeners = new Set<() => void>();
  private readonly workers: Worker[] = [];
  private readonly ocupados = new Map<Worker, string>();
  private readonly naPagina = new Set<string>();
  private readonly canceladosNaPagina = new Set<string>();
  private readonly maxParallel: number;
  private snapshot: Job[] = NO_JOBS;
  private disposed = false;
  private pumping = false;

  constructor(maxParallel?: number) {
    const cores = typeof navigator !== "undefined" ? navigator.hardwareConcurrency || 2 : 2;
    this.maxParallel = Math.max(1, Math.min(maxParallel ?? cores - 1, 3));
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): Job[] => this.snapshot;

  /**
   * Coloca arquivos na fila para uma ferramenta. Ferramentas de lote viram um job só;
   * as outras, um job por arquivo. Devolve os ids criados.
   */
  async add(ferramentaId: string, arquivos: readonly File[], opcoes: Opcoes): Promise<string[]> {
    const ferramenta = buscarFerramenta(ferramentaId);
    if (!ferramenta) throw new Error(`Ferramenta desconhecida: ${ferramentaId}`);

    const entradas: Entrada[] = [];
    for (const arquivo of arquivos) {
      const formato = await identificar(arquivo);
      entradas.push({ nome: arquivo.name, arquivo, formato });
    }

    const grupos = FERRAMENTAS_DE_LOTE.has(ferramentaId) ? [entradas] : entradas.map((entrada) => [entrada]);
    const ids: string[] = [];

    for (const grupo of grupos) {
      const aceito = grupo.every((entrada) => entrada.formato !== null && ferramenta.entradas.includes(kindOf(entrada.formato) as never));
      const id = crypto.randomUUID();
      ids.push(id);
      this.jobs = [
        ...this.jobs,
        {
          id,
          ferramenta: ferramentaId,
          titulo: ferramenta.titulo,
          nomes: grupo.map((entrada) => entrada.nome),
          inputSize: grupo.reduce((soma, entrada) => soma + entrada.arquivo.size, 0),
          formato: grupo[0]?.formato ?? null,
          status: aceito ? "pending" : "failed",
          progress: 0,
          segundosRestantes: null,
          etapa: "",
          error: aceito ? null : `“${ferramenta.titulo}” não aceita ${grupo.map((entrada) => entrada.nome).join(", ")}.`,
          resultados: null,
          nota: null,
          startedAt: null,
          finishedAt: null,
          entradas: grupo,
          opcoes: { ...opcoes },
        },
      ];
    }

    this.publish();
    this.pump();
    return ids;
  }

  cancel(id: string): void {
    const job = this.jobs.find((item) => item.id === id);
    if (!job || job.status === "done" || job.status === "failed" || job.status === "cancelled") return;

    for (const [worker, jobId] of this.ocupados) {
      if (jobId === id) {
        worker.postMessage({ type: "cancel", jobId: id } satisfies WorkerRequest);
        this.ocupados.delete(worker);
      }
    }
    if (this.naPagina.has(id)) this.canceladosNaPagina.add(id);

    this.update(id, { status: "cancelled", etapa: "", progress: 0, finishedAt: Date.now() });
    this.pump();
  }

  remove(id: string): void {
    const job = this.jobs.find((item) => item.id === id);
    for (const resultado of job?.resultados ?? []) URL.revokeObjectURL(resultado.url);
    this.jobs = this.jobs.filter((item) => item.id !== id);
    this.publish();
  }

  clearFinished(): void {
    for (const job of this.jobs) {
      if (job.status !== "pending" && job.status !== "running") {
        for (const resultado of job.resultados ?? []) URL.revokeObjectURL(resultado.url);
      }
    }
    this.jobs = this.jobs.filter((job) => job.status === "pending" || job.status === "running");
    this.publish();
  }

  /** Reprocessa um job que terminou (ou falhou) com opções novas. */
  retry(id: string, opcoes?: Opcoes): void {
    this.jobs = this.jobs.map((job) => {
      if (job.id !== id || job.status === "running") return job;
      for (const resultado of job.resultados ?? []) URL.revokeObjectURL(resultado.url);
      return {
        ...job,
        status: "pending",
        progress: 0,
        segundosRestantes: null,
        etapa: "",
        resultados: null,
        nota: null,
        error: null,
        startedAt: null,
        finishedAt: null,
        opcoes: opcoes ? { ...opcoes } : job.opcoes,
      };
    });
    this.publish();
    this.pump();
  }

  dispose(): void {
    this.disposed = true;
    for (const worker of this.workers) worker.terminate();
    this.workers.length = 0;
    this.ocupados.clear();
    for (const job of this.jobs) for (const resultado of job.resultados ?? []) URL.revokeObjectURL(resultado.url);
    this.jobs = [];
    this.publish();
  }

  // ==================== execução ====================

  private pump(): void {
    if (this.disposed || this.pumping) return;
    this.pumping = true;
    void this.drain().finally(() => {
      this.pumping = false;
    });
  }

  private async drain(): Promise<void> {
    while (!this.disposed) {
      const emAndamento = this.ocupados.size + this.naPagina.size;
      if (emAndamento >= this.maxParallel) return;

      const next = this.jobs.find((job) => job.status === "pending");
      if (!next) return;

      this.update(next.id, { status: "running", etapa: "preparando", startedAt: Date.now() });

      if (NA_PAGINA.has(next.ferramenta)) {
        this.naPagina.add(next.id);
        void this.executarNaPagina(next.id);
        continue;
      }

      let worker: Worker | null;
      try {
        worker = await this.acquireWorker();
      } catch {
        this.update(next.id, { status: "failed", error: "O motor não carregou. Aguarde a preparação do modo offline ou reconecte para baixar os recursos; depois tente novamente.", etapa: "", finishedAt: Date.now() });
        continue;
      }
      if (!worker) {
        this.update(next.id, { status: "pending", etapa: "", startedAt: null });
        return;
      }
      this.ocupados.set(worker, next.id);
      worker.postMessage({
        type: "tarefa",
        jobId: next.id,
        ferramenta: next.ferramenta,
        entradas: next.entradas,
        opcoes: next.opcoes,
      } satisfies WorkerRequest);
    }
  }

  private async acquireWorker(): Promise<Worker | null> {
    const livre = this.workers.find((worker) => !this.ocupados.has(worker));
    if (livre) return livre;
    if (this.workers.length >= this.maxParallel) return null;

    // Importado sob demanda e só no navegador: durante a renderização no servidor não
    // existe Worker, e o empacotador precisa resolver a URL do lado do cliente.
    const { default: Fabrica } = await import("@/workers/media.worker?worker");
    const worker = new Fabrica({ name: `oficina-${this.workers.length + 1}` });

    worker.addEventListener("message", (event: MessageEvent<WorkerResponse>) => this.handleMessage(worker, event.data));
    worker.addEventListener("error", () => {
      const jobId = this.ocupados.get(worker);
      if (jobId) {
        this.update(jobId, { status: "failed", error: "O conversor parou de responder.", etapa: "", finishedAt: Date.now() });
        this.ocupados.delete(worker);
        this.pump();
      }
    });

    this.workers.push(worker);
    return worker;
  }

  private handleMessage(worker: Worker, message: WorkerResponse): void {
    const job = this.jobs.find((item) => item.id === message.jobId);
    if (!job || job.status === "cancelled") return;

    if (message.type === "progress") {
      this.update(message.jobId, { etapa: message.etapa, progress: message.fraction });
      return;
    }

    this.ocupados.delete(worker);
    if (message.type === "failed") this.falhar(message.jobId, message.message);
    else this.concluir(message.jobId, message.saidas, message.nota);
    this.pump();
  }

  /** Áudio, leitura de PDF e OCR rodam aqui, com a mesma cara para a interface. */
  private async executarNaPagina(jobId: string): Promise<void> {
    const job = this.jobs.find((item) => item.id === jobId);
    if (!job) return;

    const contexto = {
      jobId,
      ferramenta: job.ferramenta,
      entradas: job.entradas,
      opcoes: job.opcoes,
      progresso: (etapa: string, fraction: number, segundosRestantes: number | null = null) =>
        this.update(jobId, { etapa, progress: Math.max(0, Math.min(1, fraction)), segundosRestantes }),
      cancelado: () => this.disposed || this.canceladosNaPagina.has(jobId),
    };

    try {
      let resultado: { saidas: Saida[]; nota?: string };
      if (job.ferramenta === "audio.converter" || job.ferramenta === "audio.editar") {
        resultado = await this.converterAudio(jobId, job);
      } else if (job.ferramenta === "pdf.para-imagens") {
        resultado = await (await import("./pagina/pdfjs")).pdfParaImagens(contexto);
      } else if (job.ferramenta === "pdf.para-texto") {
        resultado = await (await import("./pagina/pdfjs")).pdfParaTexto(contexto);
      } else if (job.ferramenta === "imagem.ocr") {
        resultado = await (await import("./pagina/ocr")).reconhecerTexto(contexto);
      } else if (job.ferramenta === "voz.narrar") {
        resultado = await (await import("./pagina/narracao")).narrar(contexto);
      } else {
        throw new ConversionError("ferramenta_desconhecida", "Esta ferramenta não roda na página.");
      }

      if (this.canceladosNaPagina.has(jobId)) return;
      if (resultado.saidas.length === 0) throw new ConversionError("sem_saida", "A conversão terminou sem produzir arquivo.");
      this.concluir(jobId, resultado.saidas, resultado.nota);
    } catch (erro) {
      if (!this.canceladosNaPagina.has(jobId)) {
        this.falhar(jobId, erro instanceof Error ? erro.message : "Não foi possível converter.");
      }
    } finally {
      this.naPagina.delete(jobId);
      this.canceladosNaPagina.delete(jobId);
      this.pump();
    }
  }

  /** Decodifica e edita na página, codifica no worker. */
  private async converterAudio(jobId: string, job: InternalJob): Promise<{ saidas: Saida[] }> {
    const { decodificarAudio, editarPcm, nomeDeSaidaDeAudio } = await import("./pagina/audio");
    const entrada = job.entradas[0];

    this.update(jobId, { etapa: "lendo", progress: 0.05 });
    let pcm = await decodificarAudio(entrada.arquivo, entrada.formato);
    if (this.canceladosNaPagina.has(jobId)) return { saidas: [] };

    if (job.ferramenta === "audio.editar") {
      this.update(jobId, { etapa: "editando", progress: 0.3 });
      pcm = editarPcm(pcm, job.opcoes);
    }

    const formato = String(job.opcoes.formato ?? "mp3") === "wav" ? "wav" : "mp3";
    const nome = nomeDeSaidaDeAudio(entrada.nome, job.opcoes, job.ferramenta === "audio.editar" ? "-editado" : "");

    const worker = await this.acquireWorker();
    if (!worker) {
      // Todos ocupados: espera um vagar, sem perder a vez.
      await new Promise<void>((resolva) => {
        const tentar = () => {
          if (this.workers.some((item) => !this.ocupados.has(item))) resolva();
          else setTimeout(tentar, 200);
        };
        tentar();
      });
      return this.converterAudio(jobId, job);
    }

    return new Promise<{ saidas: Saida[] }>((resolva, rejeita) => {
      const ouvinte = (event: MessageEvent<WorkerResponse>) => {
        const mensagem = event.data;
        if (mensagem.jobId !== jobId) return;
        if (mensagem.type === "progress") {
          this.update(jobId, { etapa: mensagem.etapa, progress: 0.35 + mensagem.fraction * 0.65 });
          return;
        }
        worker.removeEventListener("message", ouvinte);
        this.ocupados.delete(worker);
        if (mensagem.type === "failed") rejeita(new ConversionError(mensagem.code, mensagem.message));
        else resolva({ saidas: [...mensagem.saidas] });
      };
      worker.addEventListener("message", ouvinte);
      this.ocupados.set(worker, jobId);
      worker.postMessage(
        {
          type: "codificarAudio",
          jobId,
          nome,
          canais: pcm.canais,
          sampleRate: pcm.sampleRate,
          formato,
          bitrate: Number(job.opcoes.bitrate ?? 192) || 192,
        } satisfies WorkerRequest,
        pcm.canais.map((canal) => canal.buffer),
      );
    });
  }

  private concluir(jobId: string, saidas: readonly Saida[], nota?: string): void {
    const resultados: Resultado[] = saidas.map((saida) => {
      const blob = new Blob([saida.bytes], { type: saida.mime });
      return {
        blob,
        url: URL.createObjectURL(blob),
        nome: saida.nome,
        tamanho: blob.size,
        largura: saida.largura,
        altura: saida.altura,
        duracao: saida.duracao,
        paginas: saida.paginas,
      };
    });
    this.update(jobId, { status: "done", etapa: "", progress: 1, finishedAt: Date.now(), resultados, nota: nota || null });
  }

  private falhar(jobId: string, mensagem: string): void {
    this.update(jobId, { status: "failed", etapa: "", error: mensagem, finishedAt: Date.now() });
  }

  private update(id: string, patch: Partial<InternalJob>): void {
    if (patch.status && patch.status !== "running") patch = { ...patch, segundosRestantes: null };
    this.jobs = this.jobs.map((job) => {
      if (job.id !== id) return job;
      const updated = { ...job, ...patch };
      return updated.status === "running" ? updated : { ...updated, segundosRestantes: null };
    });
    this.publish();
  }

  private publish(): void {
    // O retrato para a interface não carrega os File nem as opções.
    this.snapshot = this.jobs.map((job) => ({
      id: job.id,
      ferramenta: job.ferramenta,
      titulo: job.titulo,
      nomes: job.nomes,
      inputSize: job.inputSize,
      formato: job.formato,
      status: job.status,
      progress: job.progress,
      segundosRestantes: job.segundosRestantes,
      etapa: job.etapa,
      error: job.error,
      resultados: job.resultados,
      nota: job.nota,
      startedAt: job.startedAt,
      finishedAt: job.finishedAt,
    }));
    for (const listener of this.listeners) listener();
  }
}
