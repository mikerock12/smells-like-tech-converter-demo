import { strFromU8, strToU8, unzipSync, zipSync, type Zippable } from "fflate";

export interface Camada {
  id: string;
  nome: string;
  tipo: "foto" | "texto";
  asset?: string;
  texto?: string;
  cor: string;
  tamanho: number;
  x: number;
  y: number;
  escala: number;
  angulo: number;
  opacidade: number;
  visivel: boolean;
  exposicao: number;
  contraste: number;
  saturacao: number;
}

export interface Documento {
  schemaVersion: 1;
  documentId: string;
  title: string;
  canvas: { width: number; height: number; colorSpace: "sRGB"; pixelFormat: "RGBA8" };
  layers: Camada[];
}

const MIME = "application/vnd.smellsliketech.image-project+zip";
const LIMITE = 128 * 1024 * 1024;
const ID = /^[a-zA-Z0-9-]{1,64}$/;

export function camada(tipo: Camada["tipo"], nome: string): Camada {
  return { id: crypto.randomUUID(), nome, tipo, cor: "#ffffff", tamanho: 48, x: 0, y: 0,
    escala: 1, angulo: 0, opacidade: 1, visivel: true, exposicao: 0, contraste: 1, saturacao: 1 };
}

function entre(n: number, min: number, max: number) {
  return Number.isFinite(n) && n >= min && n <= max;
}

export function conferirDocumento(doc: Documento) {
  if (!doc || doc.schemaVersion !== 1 || typeof doc.documentId !== "string" || !ID.test(doc.documentId) ||
      typeof doc.title !== "string" || doc.title.length > 512 ||
      !doc.canvas || doc.canvas.colorSpace !== "sRGB" || doc.canvas.pixelFormat !== "RGBA8" ||
      !Number.isInteger(doc.canvas.width) || !Number.isInteger(doc.canvas.height) ||
      !entre(doc.canvas.width, 1, 16384) || !entre(doc.canvas.height, 1, 16384) ||
      doc.canvas.width * doc.canvas.height > 16 * 1024 ** 2 ||
      !Array.isArray(doc.layers) || doc.layers.length > 64) throw new Error("Documento de imagem incompatível.");
  const ids = new Set<string>();
  for (const c of doc.layers) {
    if (!c || typeof c.id !== "string" || !ID.test(c.id) || ids.has(c.id) || typeof c.nome !== "string" ||
        c.nome.length > 512 || typeof c.visivel !== "boolean" || !/^#[\da-f]{6}$/i.test(c.cor) ||
        !entre(c.x, -32768, 32768) || !entre(c.y, -32768, 32768) || !entre(c.tamanho, 1, 4096) ||
        !entre(c.escala, 0.01, 16) || !entre(c.angulo, -360, 360) || !entre(c.opacidade, 0, 1) ||
        !entre(c.exposicao, -4, 4) || !entre(c.contraste, 0, 4) || !entre(c.saturacao, 0, 4) ||
        !["texto", "foto"].includes(c.tipo) || (c.tipo === "foto" && !/^[a-f0-9]{64}$/.test(c.asset ?? "")) ||
        (c.tipo === "texto" && (typeof c.texto !== "string" || c.texto.length > 4096))) {
      throw new Error("Camada incompatível no projeto.");
    }
    ids.add(c.id);
  }
}

export async function hash(bytes: Uint8Array<ArrayBuffer>) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}

export async function salvar(doc: Documento, fontes: Map<string, Blob>) {
  conferirDocumento(doc);
  const zip: Zippable = { mimetype: strToU8(MIME), "manifest.json": strToU8(JSON.stringify(doc)) };
  let total = 0;
  for (const id of new Set(doc.layers.map(c => c.asset).filter((a): a is string => !!a))) {
    const fonte = fontes.get(id);
    if (!fonte || (total += fonte.size) > LIMITE - 1024 ** 2) throw new Error("Os arquivos do projeto excedem o limite ou estão incompletos.");
    zip["assets/" + id] = new Uint8Array(await fonte.arrayBuffer());
  }
  const pacote = zipSync(zip, { level: 0 });
  return new Blob([pacote as Uint8Array<ArrayBuffer>], { type: MIME });
}

export async function abrir(arquivo: Blob) {
  if (arquivo.size > LIMITE) throw new Error("O limite por projeto é de 128 MiB.");
  const nomes = new Set<string>();
  let total = 0;
  const zip = unzipSync(new Uint8Array(await arquivo.arrayBuffer()), { filter: f => {
    total += f.originalSize;
    if (nomes.has(f.name) || nomes.size >= 66 || total > LIMITE || f.compression !== 0 ||
        f.size !== f.originalSize || !/^(mimetype|manifest\.json|assets\/[a-f0-9]{64})$/.test(f.name) ||
        (f.name.startsWith("assets/") && f.size > 64 * 1024 ** 2) ||
        (f.name === "manifest.json" && f.size > 1024 ** 2) || (f.name === "mimetype" && f.size !== MIME.length)) {
      throw new Error("Estrutura inválida no projeto de imagem.");
    }
    nomes.add(f.name);
    return true;
  } });
  if (!zip.mimetype || strFromU8(zip.mimetype) !== MIME || !zip["manifest.json"]) throw new Error("Arquivo .sltimage inválido.");
  const doc: Documento = JSON.parse(strFromU8(zip["manifest.json"]));
  conferirDocumento(doc);
  const fontes = new Map<string, Blob>();
  for (const c of doc.layers) {
    if (!c.asset || fontes.has(c.asset)) continue;
    const bytes = zip["assets/" + c.asset] as Uint8Array<ArrayBuffer> | undefined;
    if (!bytes || await hash(bytes) !== c.asset) throw new Error("Uma imagem do projeto está ausente ou corrompida.");
    fontes.set(c.asset, new Blob([bytes]));
  }
  return { doc, fontes };
}
