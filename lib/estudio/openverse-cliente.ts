import { MAX_BYTES_OPENVERSE, MAX_MS_OPENVERSE, type CategoriaOpenverse, type ResultadoOpenverse, type ProvenienciaOpenverse } from "@/packages/converter-core/openverse.mjs";

export class ErroOpenverse extends Error {
  constructor(mensagem: string, readonly aguardar: number) { super(mensagem); }
}

export async function consultarBiblioteca<T>(parametros: { category: CategoriaOpenverse; q?: string; page?: string; id?: string }, signal: AbortSignal): Promise<T> {
  const resposta = await fetch(`/api/estudio/openverse?${new URLSearchParams(parametros)}`, { signal, cache: "no-store" });
  const dados = await resposta.json();
  if (!resposta.ok) throw new ErroOpenverse(dados.erro ?? "A biblioteca musical está indisponível.", Number(resposta.headers.get("Retry-After")) || 0);
  return dados as T;
}

const formatos: Record<string, string> = { mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg", oga: "audio/ogg" };

export async function baixarAudioOpenverse(item: ResultadoOpenverse, cancelar: AbortSignal, progresso: (bytes: number, total: number) => void) {
  const sinal = AbortSignal.any([cancelar, AbortSignal.timeout(60000)]);
  const fonte = await consultarBiblioteca<ResultadoOpenverse>({ category: item.categoria, id: item.id }, sinal);
  if (fonte.estado !== "APPROVED" || fonte.id !== item.id) throw new Error("Esta faixa precisa de nova revisão.");
  const url = new URL(fonte.midia);
  if (url.protocol !== "https:" || url.username || url.password || url.port || url.hostname !== "upload.wikimedia.org" || !url.pathname.startsWith("/wikipedia/commons/")) {
    throw new Error("A origem da faixa não está disponível.");
  }
  const extensao = url.pathname.split(".").pop()?.toLowerCase() ?? "";
  const mime = formatos[extensao];
  if (!mime) throw new Error("Formato de áudio incompatível.");
  const resposta = await fetch(url, { signal: sinal, credentials: "omit", redirect: "error", referrerPolicy: "no-referrer" }).catch((erro: unknown) => {
    if (sinal.aborted) throw erro;
    throw new Error("A origem não permite baixar esta faixa pelo navegador.");
  });
  if (!resposta.ok || !resposta.body) throw new Error("A origem da faixa não está disponível.");
  const tipo = resposta.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() ?? "";
  const tamanho = Number(resposta.headers.get("Content-Length")) || fonte.tamanho || 0;
  const leitor = resposta.body.getReader();
  const partes: BlobPart[] = [];
  let bytes = 0;
  try {
    if (!tipo.startsWith("audio/") && tipo !== "application/ogg" && tipo !== "application/octet-stream") {
      throw new Error("A origem devolveu um arquivo que não é áudio.");
    }
    if (tamanho > MAX_BYTES_OPENVERSE) throw new Error("O arquivo excede o limite de 80 MiB.");
    for (;;) {
      const { done, value } = await leitor.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > MAX_BYTES_OPENVERSE) throw new Error("O arquivo excede o limite de 80 MiB.");
      partes.push(value.slice());
      progresso(bytes, tamanho);
    }
  } finally {
    await leitor.cancel().catch(() => {});
    leitor.releaseLock();
  }
  const nome = fonte.titulo.replace(/[\\/:*?"<>|\p{Cc}]/gu, "_").slice(0, 100);
  const arquivo = new File(partes, `${nome}.${extensao}`, { type: mime });
  const dados = await arquivo.arrayBuffer();
  sinal.throwIfAborted();
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", dados)), (v) => v.toString(16).padStart(2, "0")).join("");
  if (hash !== fonte.sha256) throw new Error("O arquivo da fonte mudou e precisa de nova revisão.");
  const mesa = new OfflineAudioContext(2, 1, 48000);
  const audio = await mesa.decodeAudioData(dados).catch(() => { throw new Error("O navegador não consegue decodificar esta faixa."); });
  sinal.throwIfAborted();
  if (audio.duration <= 0 || audio.duration * 1000 > MAX_MS_OPENVERSE) throw new Error("A duração da faixa está fora do limite de 15 minutos.");
  const proveniencia: ProvenienciaOpenverse = { ...fonte, importadoEm: new Date().toISOString() };
  return { arquivo, proveniencia, duracao: audio.duration };
}
