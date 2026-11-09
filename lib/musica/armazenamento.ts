"use client";

import { strFromU8, strToU8, unzipSync, zipSync, type Zippable } from "fflate";

import { lerWav } from "@/packages/converter-core/musica-master.mjs";
import type { ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import { caminhoDaMidia, comprimirEntrada, conferirEntradas, conferirProjetoDoArquivo } from "@/packages/converter-core/musica-arquivo.mjs";

/**
 * Onde a criação musical guarda tudo no navegador: o armazenamento privado da origem (OPFS),
 * em `musica/<projeto>/projeto.json` e `musica/<projeto>/midias/<id>.wav`. Nada sai daqui
 * para servidor nenhum. O projeto é gravado num arquivo temporário e só então trocado, e as
 * tomadas vão direto para o disco enquanto são gravadas (gravador.worker.ts).
 */

export type ResumoDoProjeto = { id: string; titulo: string; faixas: number; atualizado: number; semProjeto?: boolean };

const RAIZ = "musica";

export function temArmazenamento(): boolean {
  return typeof navigator !== "undefined" && !!navigator.storage?.getDirectory;
}

async function pasta(caminho: string[], criar = true): Promise<FileSystemDirectoryHandle> {
  let atual = await navigator.storage.getDirectory();
  for (const parte of caminho) atual = await atual.getDirectoryHandle(parte, { create: criar });
  return atual;
}

async function escrever(dir: FileSystemDirectoryHandle, nome: string, dados: BufferSource | Blob | string) {
  const arquivo = await dir.getFileHandle(nome, { create: true });
  const fluxo = await arquivo.createWritable();
  await fluxo.write(dados);
  await fluxo.close();
}

export async function pedirArmazenamentoPersistente(): Promise<boolean> {
  try {
    return (await navigator.storage.persisted()) || (await navigator.storage.persist());
  } catch {
    return false;
  }
}

export async function espacoLivre(): Promise<{ usado: number; total: number } | null> {
  try {
    const e = await navigator.storage.estimate();
    return { usado: e.usage ?? 0, total: e.quota ?? 0 };
  } catch {
    return null;
  }
}

export async function listarProjetos(): Promise<ResumoDoProjeto[]> {
  if (!temArmazenamento()) return [];
  const raiz = await pasta([RAIZ]);
  const lista: ResumoDoProjeto[] = [];
  for await (const [nome, item] of raiz as unknown as AsyncIterable<[string, FileSystemHandle]>) {
    if (item.kind !== "directory") continue;
    try {
      const projeto = await lerProjeto(nome);
      lista.push({ id: projeto.id, titulo: projeto.titulo, faixas: projeto.faixas.length, atualizado: await quando(item as FileSystemDirectoryHandle) });
    } catch {
      // Sem projeto legível, mas com gravações: aparece para recuperar as tomadas.
      if (await temGravacoes(item as FileSystemDirectoryHandle)) lista.push({ id: nome, titulo: "Gravações sem projeto", faixas: 0, atualizado: 0, semProjeto: true });
    }
  }
  return lista.sort((a, b) => b.atualizado - a.atualizado);
}

async function quando(dir: FileSystemDirectoryHandle): Promise<number> {
  for (const nome of ["projeto.json", "projeto.json.tmp"]) {
    try {
      return (await (await dir.getFileHandle(nome)).getFile()).lastModified;
    } catch {
      // tenta o próximo
    }
  }
  return 0;
}

async function temGravacoes(dir: FileSystemDirectoryHandle): Promise<boolean> {
  try {
    const midias = await dir.getDirectoryHandle("midias");
    for await (const [nome] of midias as unknown as AsyncIterable<[string, FileSystemHandle]>) if (nome.endsWith(".wav")) return true;
  } catch {
    // sem mídias
  }
  return false;
}

/** Lê o projeto; se o principal faltar ou estiver corrompido, usa a cópia de segurança. */
export async function lerProjeto(id: string): Promise<ProjetoMusical> {
  const dir = await pasta([RAIZ, id], false);
  for (const nome of ["projeto.json", "projeto.json.tmp"]) {
    try {
      const projeto = JSON.parse(await (await (await dir.getFileHandle(nome)).getFile()).text()) as ProjetoMusical;
      if (projeto?.id) return projeto;
    } catch {
      // tenta a cópia de segurança
    }
  }
  throw new Error("O arquivo do projeto não pôde ser lido.");
}

/**
 * Salva o projeto: primeiro a cópia de segurança completa, depois o principal. A cópia fica:
 * se o principal se perder no meio de uma gravação, a leitura usa ela.
 */
export async function salvarProjeto(projeto: ProjetoMusical): Promise<void> {
  const dir = await pasta([RAIZ, projeto.id]);
  const texto = JSON.stringify(projeto);
  await escrever(dir, "projeto.json.tmp", texto);
  await escrever(dir, "projeto.json", texto);
}

/** Uma pasta só com gravações vira um projeto novo, uma faixa por arquivo. */
export async function recuperarGravacoes(id: string, novo: ProjetoMusical): Promise<ProjetoMusical> {
  const soltas = await midiasSoltas({ ...novo, id, midias: [] });
  const projeto: ProjetoMusical = {
    ...novo,
    id,
    titulo: "Gravações recuperadas",
    midias: soltas.map((s) => ({ id: s.id, nome: `${s.id}.wav`, formato: "wav", taxa: novo.taxa, canais: s.canais, quadros: Math.max(1, s.quadros), bits: 32 })),
    faixas: soltas.map((s, i) => ({
      ...novo.faixas[0],
      id: `faixa-recuperada-${i + 1}`,
      nome: `Gravação ${i + 1}`,
      clipes: [{ id: `clipe-recuperado-${i + 1}`, midia: s.id, inicio: 0, inicioNaMidia: 0, duracao: Math.max(1, s.quadros), ganhoDb: 0, entradaSuave: 0, saidaSuave: 0, mudo: false }],
    })),
  };
  await salvarProjeto(projeto);
  return projeto;
}

export async function apagarProjeto(id: string): Promise<void> {
  const raiz = await pasta([RAIZ]);
  await raiz.removeEntry(id, { recursive: true });
}

export async function lerMidia(projetoId: string, midiaId: string): Promise<Float32Array[]> {
  const dir = await pasta([RAIZ, projetoId, "midias"], false);
  const arquivo = await (await dir.getFileHandle(`${midiaId}.wav`)).getFile();
  return lerWav(new Uint8Array(await arquivo.arrayBuffer())).canais;
}

export async function bytesDaMidia(projetoId: string, midiaId: string): Promise<File> {
  const dir = await pasta([RAIZ, projetoId, "midias"], false);
  return (await dir.getFileHandle(`${midiaId}.wav`)).getFile();
}

/** Mídias gravadas no disco que o projeto não conhece: tomadas de uma gravação interrompida. */
export async function midiasSoltas(projeto: ProjetoMusical): Promise<{ id: string; quadros: number; canais: number }[]> {
  const conhecidas = new Set(projeto.midias.map((m) => m.id));
  const soltas: { id: string; quadros: number; canais: number }[] = [];
  try {
    const dir = await pasta([RAIZ, projeto.id, "midias"], false);
    for await (const [nome, item] of dir as unknown as AsyncIterable<[string, FileSystemHandle]>) {
      const id = nome.replace(/\.wav$/, "");
      if (item.kind !== "file" || !nome.endsWith(".wav") || conhecidas.has(id)) continue;
      const arquivo = await (item as FileSystemFileHandle).getFile();
      if (arquivo.size <= 44) continue;
      const canais = new DataView(await arquivo.slice(0, 44).arrayBuffer()).getUint16(22, true) || 1;
      soltas.push({ id, quadros: Math.floor((arquivo.size - 44) / (4 * canais)), canais });
    }
  } catch {
    // Projeto ainda sem pasta de mídias.
  }
  return soltas;
}

export async function apagarMidia(projetoId: string, midiaId: string): Promise<void> {
  const dir = await pasta([RAIZ, projetoId, "midias"], false);
  await dir.removeEntry(`${midiaId}.wav`).catch(() => undefined);
}

/* ------------------------------------------------------------------ gravação em disco */

let worker: Worker | null = null;
let proximo = 1;
const esperas = new Map<number, { resolver: (v: number) => void; rejeitar: (e: Error) => void }>();

async function gravador(): Promise<Worker> {
  if (worker) return worker;
  const { default: Fabrica } = await import("./gravador.worker?worker");
  worker = new Fabrica({ name: "gravador-de-musica" });
  worker.onmessage = (evento: MessageEvent<{ tipo: string; id: number; quadros?: number; mensagem?: string }>) => {
    const m = evento.data;
    const espera = esperas.get(m.id);
    if (!espera) return;
    if (m.tipo === "erro") {
      esperas.delete(m.id);
      espera.rejeitar(new Error(m.mensagem ?? "Erro ao gravar no armazenamento."));
    } else if (m.tipo === "aberto" || m.tipo === "fechado") {
      esperas.delete(m.id);
      espera.resolver(m.quadros ?? 0);
    }
  };
  return worker;
}

/** Uma mídia sendo escrita no disco aos pedaços (gravação ou importação). */
export class EscritorDeMidia {
  private constructor(private readonly w: Worker, private readonly id: number) {}

  static async abrir(projetoId: string, midiaId: string, canais: number, taxa: number): Promise<EscritorDeMidia> {
    const w = await gravador();
    const id = proximo++;
    await new Promise<number>((resolver, rejeitar) => {
      esperas.set(id, { resolver, rejeitar });
      w.postMessage({ tipo: "abrir", id, caminho: [RAIZ, projetoId, "midias", `${midiaId}.wav`], canais, taxa });
    });
    return new EscritorDeMidia(w, id);
  }

  escrever(canais: Float32Array[]) {
    this.w.postMessage({ tipo: "escrever", id: this.id, canais });
  }

  /** Fecha e devolve quantos quadros foram escritos. */
  fechar(): Promise<number> {
    return new Promise((resolver, rejeitar) => {
      esperas.set(this.id, { resolver, rejeitar });
      this.w.postMessage({ tipo: "fechar", id: this.id });
    });
  }
}

export async function salvarMidia(projetoId: string, midiaId: string, canais: Float32Array[], taxa: number): Promise<void> {
  const escritor = await EscritorDeMidia.abrir(projetoId, midiaId, canais.length, taxa);
  const passo = 48000 * 4;
  for (let i = 0; i < canais[0].length; i += passo) escritor.escrever(canais.map((c) => c.slice(i, i + passo)));
  await escritor.fechar();
}

/* ------------------------------------------------------------------ .sltmusic */

/** Junta o projeto e as mídias num .sltmusic (WAV comprimido, o resto guardado). */
export async function exportarArquivo(projeto: ProjetoMusical): Promise<Blob> {
  const zip: Zippable = { "projeto.json": [strToU8(JSON.stringify(projeto)), { level: 6 }] };
  for (const midia of projeto.midias) {
    const caminho = caminhoDaMidia(midia);
    const bytes = new Uint8Array(await (await bytesDaMidia(projeto.id, midia.id)).arrayBuffer());
    zip[caminho] = [bytes, { level: comprimirEntrada(caminho) ? 1 : 0 }];
  }
  const dados = zipSync(zip);
  return new Blob([new Uint8Array(dados).buffer], { type: "application/zip" });
}

/** Abre um .sltmusic, confere tudo antes de extrair e guarda no navegador. */
export async function importarArquivo(arquivo: File): Promise<ProjetoMusical> {
  const entradas: { nome: string; tamanho: number; comprimido: number }[] = [];
  const bytes = new Uint8Array(await arquivo.arrayBuffer());
  const zip = unzipSync(bytes, {
    filter: (entrada) => {
      entradas.push({ nome: entrada.name, tamanho: entrada.originalSize, comprimido: entrada.size });
      const problema = conferirEntradas(entradas.concat(entradas.some((e) => e.nome === "projeto.json") ? [] : [{ nome: "projeto.json", tamanho: 0, comprimido: 0 }]));
      if (problema) throw new Error(problema);
      return true;
    },
  });
  const problema = conferirEntradas(entradas);
  if (problema) throw new Error(problema);
  const projeto = JSON.parse(strFromU8(zip["projeto.json"])) as ProjetoMusical;
  const erros = conferirProjetoDoArquivo(projeto, Object.keys(zip));
  if (erros.length) throw new Error(erros.join(" "));
  const dir = await pasta([RAIZ, projeto.id, "midias"]);
  for (const midia of projeto.midias) await escrever(dir, `${midia.id}.wav`, zip[caminhoDaMidia(midia)] as Uint8Array<ArrayBuffer>);
  await salvarProjeto(projeto);
  return projeto;
}
