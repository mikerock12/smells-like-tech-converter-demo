/// <reference lib="webworker" />

import { PDFDocument, degrees } from "pdf-lib";

import { formatBytes, outputFileName } from "@/packages/converter-core/index.mjs";
import { PdfNaoCompactavel, compactarPdf, type EntradaDeImagem, type ImagemRecomprimida } from "@/packages/converter-core/pdf-compactar.mjs";

import { lerPaginas, type Opcoes } from "../ferramentas";
import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { SAIDAS_PARA_ZIP, empacotar } from "./zip";

/**
 * PDF sem servidor: juntar, dividir, girar e montar a partir de imagens.
 *
 * O pdf-lib manipula a estrutura do documento sem renderizar nada, então é leve e
 * rápido; ler o conteúdo (PDF → imagem, PDF → texto) fica com o pdf.js, na página.
 */

async function carregar(arquivo: Blob, nome: string): Promise<PDFDocument> {
  try {
    return await PDFDocument.load(await arquivo.arrayBuffer(), { ignoreEncryption: true, updateMetadata: false });
  } catch {
    throw new ConversionError("pdf_invalido", `“${nome}” não pôde ser lido como PDF. Se tiver senha, remova antes.`);
  }
}

async function salvar(documento: PDFDocument): Promise<ArrayBuffer> {
  const bytes = await documento.save({ useObjectStreams: true });
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

function base(nome: string): string {
  return outputFileName(nome, "pdf").replace(/\.pdf$/, "");
}

export async function motorDePdf(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota?: string }> {
  switch (contexto.ferramenta) {
    case "pdf.juntar":
      return juntar(contexto);
    case "pdf.dividir":
      return dividir(contexto);
    case "pdf.girar":
      return girar(contexto);
    case "pdf.comprimir":
      return comprimir(contexto);
    case "imagem.para-pdf":
      return deImagens(contexto);
    default:
      throw new ConversionError("ferramenta_desconhecida", `O motor de PDF não sabe ${contexto.ferramenta}.`);
  }
}

async function juntar(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, progresso } = contexto;
  if (entradas.length < 2) {
    throw new ConversionError("poucos_arquivos", "Para juntar, escolha pelo menos dois PDFs.");
  }

  const destino = await PDFDocument.create();
  let paginas = 0;

  for (const [indice, entrada] of entradas.entries()) {
    progresso("juntando", indice / entradas.length);
    const origem = await carregar(entrada.arquivo, entrada.nome);
    const copiadas = await destino.copyPages(origem, origem.getPageIndices());
    for (const pagina of copiadas) destino.addPage(pagina);
    paginas += copiadas.length;
    if (contexto.cancelado()) return { saidas: [], nota: "" };
  }

  progresso("gravando", 0.9);
  const nome = `${base(entradas[0].nome)}-junto.pdf`;
  return {
    saidas: [{ nome, mime: "application/pdf", bytes: await salvar(destino), paginas }],
    nota: `${entradas.length} arquivos · ${paginas} páginas`,
  };
}

async function dividir(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const entrada = entradas[0];
  const origem = await carregar(entrada.arquivo, entrada.nome);
  const total = origem.getPageCount();

  const grupos = gruposDePaginas(opcoes, total);
  if (grupos.length === 0) {
    throw new ConversionError("paginas_invalidas", `Nenhuma página válida. O PDF tem ${total} página(s).`);
  }

  const saidas: Saida[] = [];
  for (const [indice, grupo] of grupos.entries()) {
    progresso("dividindo", indice / grupos.length);
    const documento = await PDFDocument.create();
    const copiadas = await documento.copyPages(origem, grupo.map((pagina) => pagina - 1));
    for (const pagina of copiadas) documento.addPage(pagina);

    const rotulo = grupo.length === 1 ? `p${grupo[0]}` : `p${grupo[0]}-${grupo[grupo.length - 1]}`;
    saidas.push({
      nome: `${base(entrada.nome)}-${rotulo}.pdf`,
      mime: "application/pdf",
      bytes: await salvar(documento),
      paginas: grupo.length,
    });
    if (contexto.cancelado()) return { saidas: [], nota: "" };
  }

  const nota = `${saidas.length} arquivo(s) de ${total} páginas`;
  if (saidas.length >= SAIDAS_PARA_ZIP) {
    return { saidas: [empacotar(saidas, `${base(entrada.nome)}-dividido.zip`)], nota };
  }
  return { saidas, nota };
}

function gruposDePaginas(opcoes: Opcoes, total: number): number[][] {
  const modo = String(opcoes.modo ?? "paginas");
  if (modo === "cada") {
    return Array.from({ length: total }, (_, indice) => [indice + 1]);
  }
  if (modo === "blocos") {
    const tamanho = Math.max(1, Math.floor(Number(opcoes.porBloco ?? 10)));
    const grupos: number[][] = [];
    for (let inicio = 1; inicio <= total; inicio += tamanho) {
      grupos.push(Array.from({ length: Math.min(tamanho, total - inicio + 1) }, (_, indice) => inicio + indice));
    }
    return grupos;
  }
  const paginas = lerPaginas(opcoes.paginas, total);
  return paginas.length ? [paginas] : [];
}

async function girar(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const saidas: Saida[] = [];
  const angulo = Number(opcoes.angulo ?? 90);

  for (const [indice, entrada] of entradas.entries()) {
    progresso("girando", indice / entradas.length);
    const documento = await carregar(entrada.arquivo, entrada.nome);
    const escolhidas = new Set(lerPaginas(opcoes.paginas, documento.getPageCount()));

    documento.getPages().forEach((pagina, posicao) => {
      if (!escolhidas.has(posicao + 1)) return;
      pagina.setRotation(degrees((pagina.getRotation().angle + angulo) % 360));
    });

    saidas.push({
      nome: `${base(entrada.nome)}-girado.pdf`,
      mime: "application/pdf",
      bytes: await salvar(documento),
      paginas: documento.getPageCount(),
    });
  }

  return { saidas, nota: `${angulo}°` };
}

// ==================== comprimir ====================

async function comprimir(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const saidas: Saida[] = [];
  const notas: string[] = [];

  for (const [indice, entrada] of entradas.entries()) {
    let resultado: Awaited<ReturnType<typeof compactarPdf>>;
    try {
      resultado = await compactarPdf(new Uint8Array(await entrada.arquivo.arrayBuffer()), {
        nivel: String(opcoes.nivel ?? "equilibrado"),
        recomprimir: recomprimirNoCanvas,
        progresso: (fracao) => progresso("comprimindo", (indice + fracao * 0.95) / entradas.length),
        cancelado: contexto.cancelado,
      });
    } catch (erro) {
      if (erro instanceof PdfNaoCompactavel) throw new ConversionError("pdf_nao_compactavel", `“${entrada.nome}”: ${erro.message}`);
      throw erro;
    }
    if (!resultado) return { saidas: [], nota: "" };

    const { bytes, antes, depois, mudou } = resultado;
    saidas.push({
      nome: `${base(entrada.nome)}${mudou ? "-comprimido" : ""}.pdf`,
      mime: "application/pdf",
      bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
    });
    notas.push(
      mudou
        ? `${formatBytes(antes)} → ${formatBytes(depois)} (−${Math.round((1 - depois / antes) * 100)}%)`
        : "já estava enxuto: devolvido como veio",
    );
  }

  return { saidas, nota: notas.join(" · ") };
}

/**
 * Recomprime uma imagem do PDF com o codificador JPEG do navegador. A orientação EXIF é
 * ignorada de propósito: dentro do PDF vale o desenho cru, não o que a câmera anotou.
 */
async function recomprimirNoCanvas(entrada: EntradaDeImagem): Promise<ImagemRecomprimida | null> {
  let fonte: ImageBitmap | OffscreenCanvas;
  if (entrada.tipo === "jpeg") {
    try {
      fonte = await createImageBitmap(new Blob([entrada.bytes as BlobPart], { type: "image/jpeg" }), {
        imageOrientation: "none",
        colorSpaceConversion: "none",
      });
    } catch {
      return null;
    }
    // Um JPEG cujo tamanho não bate com o que o PDF declara é esquisito demais para mexer.
    if (fonte.width !== entrada.largura || fonte.height !== entrada.altura) {
      fonte.close();
      return null;
    }
  } else {
    const { dados, componentes, largura, altura } = entrada;
    const pixels = new ImageData(largura, altura);
    for (let pixel = 0, i = 0; pixel < largura * altura; pixel += 1, i += componentes) {
      const destino = pixel * 4;
      pixels.data[destino] = dados[i];
      pixels.data[destino + 1] = componentes === 3 ? dados[i + 1] : dados[i];
      pixels.data[destino + 2] = componentes === 3 ? dados[i + 2] : dados[i];
      pixels.data[destino + 3] = 255;
    }
    fonte = new OffscreenCanvas(largura, altura);
    fonte.getContext("2d")?.putImageData(pixels, 0, 0);
  }

  try {
    const canvas = new OffscreenCanvas(entrada.alvoLargura, entrada.alvoAltura);
    const pincel = canvas.getContext("2d");
    if (!pincel) return null;
    pincel.imageSmoothingEnabled = true;
    pincel.imageSmoothingQuality = "high";
    pincel.drawImage(fonte, 0, 0, entrada.alvoLargura, entrada.alvoAltura);
    const jpeg = await canvas.convertToBlob({ type: "image/jpeg", quality: entrada.qualidade });
    return { bytes: new Uint8Array(await jpeg.arrayBuffer()), largura: canvas.width, altura: canvas.height };
  } finally {
    if ("close" in fonte) fonte.close();
  }
}

const A4 = { largura: 595.28, altura: 841.89 };
const PONTOS_POR_MM = 2.8346;

async function deImagens(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const documento = await PDFDocument.create();
  const qualidade = Math.min(100, Math.max(30, Number(opcoes.qualidade ?? 85))) / 100;
  const modoDaPagina = String(opcoes.pagina ?? "a4-retrato");
  const margem = Number(opcoes.margem ?? 10) * PONTOS_POR_MM;

  for (const [indice, entrada] of entradas.entries()) {
    progresso("montando", indice / entradas.length);
    if (contexto.cancelado()) return { saidas: [], nota: "" };

    const embutida = await embutir(documento, entrada.arquivo, entrada.formato, entrada.nome, qualidade);
    const { width: lImagem, height: aImagem } = embutida;

    let largura: number;
    let altura: number;
    if (modoDaPagina === "imagem") {
      largura = lImagem;
      altura = aImagem;
    } else if (modoDaPagina === "a4-paisagem") {
      largura = A4.altura;
      altura = A4.largura;
    } else {
      largura = A4.largura;
      altura = A4.altura;
    }

    const pagina = documento.addPage([largura, altura]);
    const caixaL = modoDaPagina === "imagem" ? largura : largura - margem * 2;
    const caixaA = modoDaPagina === "imagem" ? altura : altura - margem * 2;
    const escala = Math.min(caixaL / lImagem, caixaA / aImagem);
    const l = lImagem * escala;
    const a = aImagem * escala;

    pagina.drawImage(embutida, { x: (largura - l) / 2, y: (altura - a) / 2, width: l, height: a });
  }

  progresso("gravando", 0.9);
  const nome = entradas.length === 1 ? outputFileName(entradas[0].nome, "pdf") : `${base(entradas[0].nome)}-e-mais-${entradas.length - 1}.pdf`;
  return {
    saidas: [{ nome, mime: "application/pdf", bytes: await salvar(documento), paginas: entradas.length }],
    nota: `${entradas.length} página(s)`,
  };
}

/** JPG e PNG entram direto; o resto vira JPEG pelo canvas. */
async function embutir(documento: PDFDocument, arquivo: Blob, formato: string | null, nome: string, qualidade: number) {
  const bytes = await arquivo.arrayBuffer();
  try {
    if (formato === "jpg") return await documento.embedJpg(bytes);
    if (formato === "png") return await documento.embedPng(bytes);
  } catch {
    // Cai para o caminho do canvas: alguns JPG (CMYK, progressivo raro) o pdf-lib recusa.
  }

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(new Blob([bytes]));
  } catch {
    throw new ConversionError("imagem_ilegivel", `Este navegador não abre “${nome}”. Converta para JPG ou PNG antes.`);
  }

  try {
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const contexto = canvas.getContext("2d");
    if (!contexto) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
    contexto.fillStyle = "#ffffff";
    contexto.fillRect(0, 0, bitmap.width, bitmap.height);
    contexto.drawImage(bitmap, 0, 0);
    const jpeg = await canvas.convertToBlob({ type: "image/jpeg", quality: qualidade });
    return await documento.embedJpg(await jpeg.arrayBuffer());
  } finally {
    bitmap.close();
  }
}
