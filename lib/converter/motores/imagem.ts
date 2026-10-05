/// <reference lib="webworker" />

import decodeJpeg from "@jsquash/jpeg/decode";
import encodeJpeg from "@jsquash/jpeg/encode";
import decodePng from "@jsquash/png/decode";
import encodePng from "@jsquash/png/encode";
import resizeImage from "@jsquash/resize";
import decodeWebp from "@jsquash/webp/decode";
import encodeWebp from "@jsquash/webp/encode";

import { getFormat, outputFileName, resolveTargetSize } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";

import type { Opcoes } from "../ferramentas";
import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { codificarBmp, codificarIco } from "./bmp-ico";

/**
 * Tudo de imagem: converter, comprimir, redimensionar, recortar, girar e gerar ícone.
 *
 * Decodificar → geometria → codificar. Roda inteiro no worker; a página só recebe
 * progresso e o resultado.
 */

export async function decodificar(arquivo: Blob, formato: FormatId | null): Promise<ImageData> {
  const bytes = await arquivo.arrayBuffer();
  try {
    switch (formato) {
      case "jpg":
        return await decodeJpeg(bytes);
      case "png":
        return await decodePng(bytes);
      case "webp":
        return await decodeWebp(bytes);
      case "heic":
        return await (await import("./heic")).decodificarHeic(bytes);
      case "tiff":
        return (await import("./tiff")).decodificarTiff(bytes);
      default:
        return await decodificarComONavegador(bytes, formato);
    }
  } catch (erro) {
    if (erro instanceof ConversionError) throw erro;
    throw new ConversionError(
      "decode_failed",
      `Não foi possível ler esta imagem ${formato ? getFormat(formato)?.label ?? "" : ""}. O arquivo pode estar corrompido.`.replace(/\s+\./, "."),
    );
  }
}

async function decodificarComONavegador(bytes: ArrayBuffer, formato: FormatId | null): Promise<ImageData> {
  const descritor = formato ? getFormat(formato) : null;
  const blob = new Blob([bytes], { type: descritor?.mime ?? "application/octet-stream" });

  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(blob);
  } catch {
    throw new ConversionError(
      "decode_unavailable",
      `Este navegador não abre ${descritor?.label ?? "este formato"}. O plugin converte praticamente qualquer imagem.`,
    );
  }

  try {
    return bitmapParaImageData(bitmap);
  } finally {
    bitmap.close();
  }
}

export function bitmapParaImageData(bitmap: ImageBitmap): ImageData {
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const contexto = canvas.getContext("2d", { willReadFrequently: true });
  if (!contexto) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
  contexto.drawImage(bitmap, 0, 0);
  return contexto.getImageData(0, 0, bitmap.width, bitmap.height);
}

// ==================== geometria ====================

export async function redimensionar(imagem: ImageData, largura: number, altura: number): Promise<ImageData> {
  if (largura === imagem.width && altura === imagem.height) return imagem;
  return resizeImage(imagem, { width: Math.max(1, Math.round(largura)), height: Math.max(1, Math.round(altura)) });
}

export function recortar(imagem: ImageData, x: number, y: number, largura: number, altura: number): ImageData {
  const saida = new ImageData(largura, altura);
  for (let linha = 0; linha < altura; linha += 1) {
    const origem = ((y + linha) * imagem.width + x) * 4;
    saida.data.set(imagem.data.subarray(origem, origem + largura * 4), linha * largura * 4);
  }
  return saida;
}

/** Recorte centrado numa proporção "l:a". */
export function recortarNaProporcao(imagem: ImageData, proporcao: string): ImageData {
  const [l, a] = proporcao.split(":").map(Number);
  if (!l || !a) return imagem;
  const alvo = l / a;
  const atual = imagem.width / imagem.height;

  let largura = imagem.width;
  let altura = imagem.height;
  if (atual > alvo) largura = Math.round(imagem.height * alvo);
  else altura = Math.round(imagem.width / alvo);

  const x = Math.floor((imagem.width - largura) / 2);
  const y = Math.floor((imagem.height - altura) / 2);
  return recortar(imagem, x, y, Math.max(1, largura), Math.max(1, altura));
}

export function girar(imagem: ImageData, graus: number): ImageData {
  const passos = ((Math.round(graus / 90) % 4) + 4) % 4;
  if (passos === 0) return imagem;

  const { width: w, height: h, data } = imagem;
  const trocaLados = passos % 2 === 1;
  const saida = new ImageData(trocaLados ? h : w, trocaLados ? w : h);
  const destino = saida.data;
  const W = saida.width;

  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      let nx: number;
      let ny: number;
      if (passos === 1) {
        nx = h - 1 - y;
        ny = x;
      } else if (passos === 2) {
        nx = w - 1 - x;
        ny = h - 1 - y;
      } else {
        nx = y;
        ny = w - 1 - x;
      }
      const de = (y * w + x) * 4;
      const para = (ny * W + nx) * 4;
      destino[para] = data[de];
      destino[para + 1] = data[de + 1];
      destino[para + 2] = data[de + 2];
      destino[para + 3] = data[de + 3];
    }
  }
  return saida;
}

export function espelhar(imagem: ImageData, horizontal: boolean, vertical: boolean): ImageData {
  if (!horizontal && !vertical) return imagem;
  const { width: w, height: h, data } = imagem;
  const saida = new ImageData(w, h);
  for (let y = 0; y < h; y += 1) {
    const ny = vertical ? h - 1 - y : y;
    for (let x = 0; x < w; x += 1) {
      const nx = horizontal ? w - 1 - x : x;
      const de = (y * w + x) * 4;
      const para = (ny * w + nx) * 4;
      saida.data[para] = data[de];
      saida.data[para + 1] = data[de + 1];
      saida.data[para + 2] = data[de + 2];
      saida.data[para + 3] = data[de + 3];
    }
  }
  return saida;
}

/** Remove a transparência sobre a cor escolhida, para formatos sem canal alfa. */
export function achatar(imagem: ImageData, fundo: string): ImageData {
  const cor = lerCor(fundo);
  const saida = new ImageData(imagem.width, imagem.height);
  const d = imagem.data;
  const s = saida.data;
  for (let i = 0; i < d.length; i += 4) {
    const alfa = d[i + 3] / 255;
    s[i] = Math.round(d[i] * alfa + cor.r * (1 - alfa));
    s[i + 1] = Math.round(d[i + 1] * alfa + cor.g * (1 - alfa));
    s[i + 2] = Math.round(d[i + 2] * alfa + cor.b * (1 - alfa));
    s[i + 3] = 255;
  }
  return saida;
}

export function lerCor(valor: string): { r: number; g: number; b: number } {
  const hex = /^#[0-9a-f]{6}$/i.test(valor) ? valor : "#ffffff";
  return {
    r: Number.parseInt(hex.slice(1, 3), 16),
    g: Number.parseInt(hex.slice(3, 5), 16),
    b: Number.parseInt(hex.slice(5, 7), 16),
  };
}

async function encaixarExato(imagem: ImageData, largura: number, altura: number, ajuste: string, fundo: string): Promise<ImageData> {
  if (ajuste === "stretch") return redimensionar(imagem, largura, altura);

  if (ajuste === "cover") {
    const escala = Math.max(largura / imagem.width, altura / imagem.height);
    const ampliada = await redimensionar(imagem, imagem.width * escala, imagem.height * escala);
    const x = Math.max(0, Math.round((ampliada.width - largura) / 2));
    const y = Math.max(0, Math.round((ampliada.height - altura) / 2));
    return recortar(ampliada, x, y, Math.min(largura, ampliada.width), Math.min(altura, ampliada.height));
  }

  // contain: a imagem inteira, centrada, com a cor de fundo em volta.
  const escala = Math.min(largura / imagem.width, altura / imagem.height);
  const reduzida = await redimensionar(imagem, imagem.width * escala, imagem.height * escala);
  const canvas = new OffscreenCanvas(largura, altura);
  const contexto = canvas.getContext("2d");
  if (!contexto) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
  contexto.fillStyle = fundo;
  contexto.fillRect(0, 0, largura, altura);
  const bitmap = await createImageBitmap(reduzida);
  try {
    contexto.drawImage(bitmap, Math.round((largura - reduzida.width) / 2), Math.round((altura - reduzida.height) / 2));
  } finally {
    bitmap.close();
  }
  return contexto.getImageData(0, 0, largura, altura);
}

// ==================== codificação ====================

export async function codificar(imagem: ImageData, formato: FormatId, qualidade: number): Promise<ArrayBuffer> {
  switch (formato) {
    case "jpg":
      return encodeJpeg(imagem, { quality: qualidade });
    case "webp":
      return encodeWebp(imagem, { quality: qualidade });
    case "png":
      return encodePng(imagem);
    case "bmp":
      return codificarBmp(imagem);
    case "ico":
      return codificarIco([{ imagem, png: await encodePng(imagem) }]);
    default:
      throw new ConversionError("unsupported_output", `Não sei gravar ${formato}.`);
  }
}

// ==================== o motor ====================

export async function motorDeImagem(contexto: ContextoDoMotor): Promise<{ saidas: Saida[] }> {
  const { ferramenta, opcoes, progresso } = contexto;
  const saidas: Saida[] = [];

  for (const [indice, entrada] of contexto.entradas.entries()) {
    const base = indice / contexto.entradas.length;
    const fatia = 1 / contexto.entradas.length;
    const passo = (etapa: string, fracao: number) => progresso(etapa, base + fracao * fatia);

    passo("lendo", 0.05);
    let imagem = await decodificar(entrada.arquivo, entrada.formato);
    if (contexto.cancelado()) return { saidas };

    passo("ajustando", 0.4);
    if (ferramenta === "imagem.icone") {
      saidas.push(await gerarIcone(entrada.nome, imagem, opcoes));
      continue;
    }

    imagem = await aplicarGeometria(ferramenta, imagem, opcoes);
    if (contexto.cancelado()) return { saidas };

    const formato = (String(opcoes.formato) || "webp") as FormatId;
    const descritor = getFormat(formato);
    const precisaAchatar = !descritor?.supportsAlpha || opcoes.manterTransparencia === false;
    if (precisaAchatar) imagem = achatar(imagem, String(opcoes.fundo ?? "#ffffff"));

    passo("gravando", 0.65);
    const bytes = await codificar(imagem, formato, Number(opcoes.qualidade ?? 82));
    if (contexto.cancelado()) return { saidas };

    saidas.push({
      nome: outputFileName(entrada.nome, formato),
      mime: descritor?.mime ?? "application/octet-stream",
      bytes,
      largura: imagem.width,
      altura: imagem.height,
    });
    passo("gravando", 1);
  }

  return { saidas };
}

async function aplicarGeometria(ferramenta: string, imagem: ImageData, opcoes: Opcoes): Promise<ImageData> {
  switch (ferramenta) {
    case "imagem.recortar":
      return recortarNaProporcao(imagem, String(opcoes.proporcao ?? "1:1"));
    case "imagem.girar":
      return espelhar(girar(imagem, Number(opcoes.rotacao ?? 0)), opcoes.espelharH === true, opcoes.espelharV === true);
    case "imagem.comprimir":
      return imagem;
    default: {
      const alvo = resolveTargetSize(imagem.width, imagem.height, {
        resizeMode: String(opcoes.redimensionar ?? "none") as "none",
        width: Number(opcoes.largura ?? 0) || null,
        height: Number(opcoes.altura ?? 0) || null,
        percent: Number(opcoes.porcentagem ?? 0) || null,
      } as never);
      if (!alvo) return imagem;
      if (opcoes.redimensionar === "exact") {
        return encaixarExato(imagem, alvo.width, alvo.height, String(opcoes.ajuste ?? "cover"), String(opcoes.fundo ?? "#ffffff"));
      }
      return redimensionar(imagem, alvo.width, alvo.height);
    }
  }
}

const TAMANHOS_DE_ICONE: Record<string, number[]> = {
  completo: [16, 32, 48, 64, 128, 256],
  favicon: [16, 32, 48],
  "256": [256],
};

async function gerarIcone(nome: string, imagem: ImageData, opcoes: Opcoes): Promise<Saida> {
  const tamanhos = TAMANHOS_DE_ICONE[String(opcoes.tamanhos ?? "completo")] ?? TAMANHOS_DE_ICONE.completo;

  // Ícone é quadrado. Imagem retangular ganha a cor de fundo em volta (contain).
  const lado = Math.max(imagem.width, imagem.height);
  const quadrada =
    imagem.width === imagem.height
      ? imagem
      : await encaixarExato(imagem, lado, lado, "contain", String(opcoes.fundo ?? "#ffffff"));

  const camadas = [];
  for (const tamanho of tamanhos) {
    const reduzida = await redimensionar(quadrada, tamanho, tamanho);
    camadas.push({ imagem: reduzida, png: await encodePng(reduzida) });
  }

  return {
    nome: outputFileName(nome, "ico"),
    mime: "image/x-icon",
    bytes: codificarIco(camadas),
    largura: tamanhos[tamanhos.length - 1],
    altura: tamanhos[tamanhos.length - 1],
  };
}
