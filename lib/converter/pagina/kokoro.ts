import { unzipSync } from "fflate";
import { CACHE, MODELO_URL } from "@/packages/kokoro/config.mjs";
import { ConversionError } from "../protocol";

interface Manifesto {
  bundle: { bytes: number; sha256: string };
  files: { name: string; bytes: number; sha256: string }[];
}
async function sha(bytes: ArrayBuffer): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function manifesto(): Promise<Manifesto> {
  const resposta = await fetch("/motores/kokoro/manifest.json");
  if (!resposta.ok) throw new Error("O manifesto Kokoro não carregou.");
  return resposta.json();
}
export async function tamanhoKokoro(): Promise<number> { return (await manifesto()).bundle.bytes; }
export async function kokoroPronto(): Promise<boolean> {
  if (!globalThis.caches) return false;
  const resposta = await (await caches.open(CACHE)).match(MODELO_URL);
  return Boolean(resposta && resposta.headers.get("X-Kokoro-SHA256") === (await manifesto()).bundle.sha256);
}
let preparando: Promise<void> | null = null;
/** Cache próprio: o pacote não ocupa o cache versionado das páginas do site. */
export async function prepararKokoro(progresso: (percentual: number) => void = () => {}): Promise<void> {
  if (preparando) return preparando;
  preparando = (async () => {
    if (!globalThis.caches) throw new Error("Este navegador não permite guardar o modelo offline. Use HTTPS, Windows ou Android.");
    const cache = await caches.open(CACHE);
    if (await kokoroPronto()) { progresso(100); return; }
    const meta = await manifesto();
    const resposta = await fetch(MODELO_URL);
    if (!resposta.ok || !resposta.body) throw new Error("Não foi possível baixar o Kokoro. Conecte a internet para o preparo inicial.");
    const leitor = resposta.body.getReader();
    const bytes = new Uint8Array(meta.bundle.bytes);
    let total = 0;
    while (true) {
      const { value, done } = await leitor.read();
      if (done) break;
      if (total + value.length > meta.bundle.bytes) { await leitor.cancel(); throw new Error("Pacote Kokoro maior que o esperado."); }
      bytes.set(value, total); total += value.length;
      progresso(Math.min(95, total / meta.bundle.bytes * 95));
    }
    if (total !== meta.bundle.bytes || await sha(bytes.buffer) !== meta.bundle.sha256) throw new Error("O download Kokoro está incompleto ou danificado. Tente novamente.");
    try {
      await cache.put(MODELO_URL, new Response(bytes, { headers: { "Content-Type": "application/zip", "X-Kokoro-SHA256": meta.bundle.sha256 } }));
      // Pedido de persistência não é garantia: a interface informa que limpar os dados remove o modelo.
      await navigator.storage?.persist?.().catch(() => false);
    } catch (erro) {
      if (erro instanceof DOMException && erro.name === 'QuotaExceededError')
        throw new Error(`Não há espaço para guardar o Kokoro offline. Libere pelo menos ${Math.ceil(meta.bundle.bytes / 1024 ** 2)} MB e tente novamente.`);
      throw new Error(`O navegador não conseguiu guardar o Kokoro: ${erro instanceof Error ? erro.message : 'armazenamento indisponível'}. Tente fora do modo anônimo ou use o aplicativo.`);
    }
    progresso(100);
  })();
  try { await preparando; } finally { preparando = null; }
}
export async function arquivosKokoro(): Promise<[string, ArrayBuffer][]> {
  const cache = await caches.open(CACHE);
  const resposta = await cache.match(MODELO_URL);
  if (!resposta) throw new ConversionError("kokoro_ausente", "Prepare a narração Kokoro com internet uma vez. Depois ela funciona offline.");
  const meta = await manifesto();
  const bytes = await resposta.arrayBuffer();
  if (bytes.byteLength !== meta.bundle.bytes || await sha(bytes) !== meta.bundle.sha256) {
    await cache.delete(MODELO_URL);
    throw new Error("Pacote Kokoro danificado. Prepare a narração novamente com internet.");
  }
  const nomes = new Map(meta.files.map((arquivo) => [arquivo.name, arquivo]));
  const extraidos = unzipSync(new Uint8Array(bytes), { filter: (arquivo) => nomes.get(arquivo.name)?.bytes === arquivo.originalSize });
  const arquivos: [string, ArrayBuffer][] = [];
  for (const item of meta.files) {
    const dados = extraidos[item.name];
    if (!dados || dados.byteLength !== item.bytes) throw new Error("Pacote Kokoro incompleto.");
    // O SHA do ZIP já autentica todos os arquivos, sem duplicar o modelo para cada hash.
    arquivos.push([item.name, (dados.byteOffset === 0 && dados.byteLength === dados.buffer.byteLength
      ? dados.buffer : dados.buffer.slice(dados.byteOffset, dados.byteOffset + dados.byteLength)) as ArrayBuffer]);
  }
  return arquivos;
}
