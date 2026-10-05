import * as pdfjs from "pdfjs-dist/legacy/build/pdf.mjs";
import workerUrl from "pdfjs-dist/legacy/build/pdf.worker.min.mjs?url";

import { getFormat, outputFileName } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";

import { lerPaginas } from "../ferramentas";
import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { SAIDAS_PARA_ZIP, empacotar } from "../motores/zip";

/**
 * Ler PDF com o pdf.js: página vira imagem, texto vira TXT ou Markdown.
 *
 * Roda na página porque o pdf.js já traz o próprio worker para o trabalho pesado; o
 * script dele é servido da nossa origem, como exige o isolamento de origem cruzada.
 */

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

const recursosDoPdf = {
  cMapUrl: "/motores/pdfjs/cmaps/",
  cMapPacked: true,
  standardFontDataUrl: "/motores/pdfjs/standard_fonts/",
  wasmUrl: "/motores/pdfjs/wasm/",
};

async function abrir(arquivo: Blob, nome: string) {
  try {
    return await pdfjs.getDocument({ ...recursosDoPdf, data: new Uint8Array(await arquivo.arrayBuffer()) }).promise;
  } catch (erro) {
    const mensagem = erro instanceof Error ? erro.message : "";
    if (/password/i.test(mensagem)) {
      throw new ConversionError("pdf_com_senha", `“${nome}” tem senha. Remova a senha antes de converter.`);
    }
    throw new ConversionError("pdf_invalido", `“${nome}” não pôde ser lido como PDF.`);
  }
}

export async function pdfParaImagens(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const formato = (["png", "jpg", "webp"].includes(String(opcoes.formato)) ? String(opcoes.formato) : "png") as FormatId;
  const mime = getFormat(formato)?.mime ?? "image/png";
  const dpi = Number(opcoes.dpi ?? 150) || 150;
  const qualidade = Math.min(100, Math.max(1, Number(opcoes.qualidade ?? 82))) / 100;
  const saidas: Saida[] = [];

  for (const entrada of entradas) {
    const documento = await abrir(entrada.arquivo, entrada.nome);
    try {
      const paginas = lerPaginas(opcoes.paginas, documento.numPages);
      if (paginas.length === 0) throw new ConversionError("paginas_invalidas", `O PDF tem ${documento.numPages} página(s).`);
      const base = outputFileName(entrada.nome, formato).replace(/\.[^.]+$/, "");

      for (const [indice, numero] of paginas.entries()) {
        if (contexto.cancelado()) return { saidas: [], nota: "" };
        const pagina = await documento.getPage(numero);
        const viewport = pagina.getViewport({ scale: dpi / 72 });
        const canvas = new OffscreenCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height));
        const ctx = canvas.getContext("2d") as unknown as CanvasRenderingContext2D;
        await pagina.render({ canvasContext: ctx, canvas: canvas as unknown as HTMLCanvasElement, viewport }).promise;
        const blob = await canvas.convertToBlob({ type: mime, quality: qualidade });
        saidas.push({
          nome: `${base}-p${String(numero).padStart(3, "0")}.${formato}`,
          mime,
          bytes: await blob.arrayBuffer(),
          largura: canvas.width,
          altura: canvas.height,
        });
        pagina.cleanup();
        progresso("desenhando", (indice + 1) / paginas.length);
      }
    } finally {
      await documento.loadingTask.destroy();
    }
  }

  const nota = `${saidas.length} página(s) a ${dpi} DPI`;
  if (saidas.length >= SAIDAS_PARA_ZIP) {
    return { saidas: [empacotar(saidas, `${outputFileName(entradas[0].nome, formato).replace(/\.[^.]+$/, "")}-paginas.zip`)], nota };
  }
  return { saidas, nota };
}

export async function pdfParaTexto(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const formato = (String(opcoes.formato) === "md" ? "md" : "txt") as FormatId;
  const marcar = opcoes.marcarPaginas !== false;
  const saidas: Saida[] = [];
  let vazias = 0;
  let reconhecidas = 0;

  for (const entrada of entradas) {
    const documento = await abrir(entrada.arquivo, entrada.nome);
    try {
      const partes: string[] = [];
      let temTextoLegivel = false;
      for (let numero = 1; numero <= documento.numPages; numero += 1) {
        if (contexto.cancelado()) return { saidas: [], nota: "" };
        const pagina = await documento.getPage(numero);
        const conteudo = await pagina.getTextContent();
        let texto = textoDaPagina(conteudo.items as { str: string; hasEOL?: boolean }[]);
        if (!texto.trim()) {
          const viewport = pagina.getViewport({ scale: 150 / 72 });
          const canvas = new OffscreenCanvas(Math.ceil(viewport.width), Math.ceil(viewport.height));
          const ctx = canvas.getContext("2d") as unknown as CanvasRenderingContext2D;
          await pagina.render({ canvasContext: ctx, canvas: canvas as unknown as HTMLCanvasElement, viewport }).promise;
          const imagem = await canvas.convertToBlob({ type: "image/png" });
          const { reconhecerTexto } = await import("./ocr");
          try {
            const resultado = await reconhecerTexto({
              ...contexto,
              entradas: [{ nome: `${entrada.nome}-p${numero}.png`, arquivo: imagem, formato: "png" }],
              progresso: (etapa, fraction) => progresso(etapa, (numero - 1 + fraction) / documento.numPages),
            });
            texto = new TextDecoder().decode(resultado.saidas[0]?.bytes);
            if (texto.trim()) reconhecidas++;
          } catch (erro) {
            if (!(erro instanceof ConversionError && erro.code === "sem_texto")) throw erro;
          }
          canvas.width = 0;
          canvas.height = 0;
          if (contexto.cancelado()) return { saidas: [], nota: "" };
        }
        if (!texto.trim()) vazias += 1;
        else temTextoLegivel = true;
        if (marcar) partes.push(formato === "md" ? `## Página ${numero}\n\n${texto}` : `===== Página ${numero} =====\n\n${texto}`);
        else partes.push(texto);
        pagina.cleanup();
        progresso("lendo", numero / documento.numPages);
      }

      const corpo = partes.join("\n\n").trim();
      if (!temTextoLegivel) {
        throw new ConversionError(
          "pdf_sem_texto",
          "Não encontrei texto legível neste PDF, mesmo com o reconhecimento das páginas digitalizadas. Tente uma digitalização mais nítida ou com mais contraste.",
        );
      }

      const bytes = new TextEncoder().encode(`${corpo}\n`);
      saidas.push({
        nome: outputFileName(entrada.nome, formato),
        mime: getFormat(formato)?.mime ?? "text/plain",
        bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
        paginas: documento.numPages,
      });
    } finally {
      await documento.loadingTask.destroy();
    }
  }

  return { saidas, nota: [reconhecidas ? `${reconhecidas} página(s) reconhecida(s) por OCR` : "", vazias ? `${vazias} página(s) sem texto legível` : ""].filter(Boolean).join(" · ") };
}

function textoDaPagina(itens: { str: string; hasEOL?: boolean }[]): string {
  let texto = "";
  for (const item of itens) {
    texto += item.str;
    if (item.hasEOL) texto += "\n";
    else if (item.str && !item.str.endsWith(" ")) texto += " ";
  }
  return texto
    .split("\n")
    .map((linha) => linha.replace(/\s+/g, " ").trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n");
}

/** Quantas páginas um PDF tem — para os limites do plano grátis. */
export async function contarPaginas(arquivo: Blob): Promise<number> {
  const documento = await pdfjs.getDocument({ ...recursosDoPdf, data: new Uint8Array(await arquivo.arrayBuffer()) }).promise;
  try {
    return documento.numPages;
  } finally {
    await documento.loadingTask.destroy();
  }
}
