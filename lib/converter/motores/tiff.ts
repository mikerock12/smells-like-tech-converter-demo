/// <reference lib="webworker" />

import * as UTIF from "utif2";

import { ConversionError } from "../protocol";

/**
 * TIFF — o formato de scanner e de gráfica — sem servidor.
 *
 * Nenhum navegador comum abre TIFF sozinho. O UTIF.js lê as compressões usuais
 * (nenhuma, LZW, Deflate, PackBits, JPEG) em JavaScript puro. Um TIFF de várias
 * páginas vira a primeira página; é o que o visualizador do Windows mostra primeiro.
 */

type Utif = typeof UTIF;

// O UTIF é CommonJS: dependendo do empacotador, as funções chegam no próprio módulo ou
// em `default`.
const utif: Utif = ((UTIF as unknown as { default?: Utif }).default ?? UTIF) as Utif;

export function decodificarTiff(bytes: ArrayBuffer): ImageData {
  let paginas: ReturnType<Utif["decode"]>;
  try {
    paginas = utif.decode(bytes);
  } catch {
    throw new ConversionError("tiff_invalido", "Este arquivo não pôde ser lido como TIFF.");
  }

  // t256 e t257 são largura e altura; miniaturas e páginas vazias não têm.
  const medida = (pagina: (typeof paginas)[number], tag: string) => Number((pagina[tag] as ArrayLike<number> | undefined)?.[0] ?? 0);
  const primeira = paginas.find((pagina) => medida(pagina, "t256") > 0 && medida(pagina, "t257") > 0);
  if (!primeira) throw new ConversionError("tiff_invalido", "Este TIFF não tem imagem que eu consiga ler.");

  try {
    utif.decodeImage(bytes, primeira);
    const rgba = utif.toRGBA8(primeira);
    const largura = Number(primeira.width);
    const altura = Number(primeira.height);
    if (!largura || !altura || rgba.length < largura * altura * 4) throw new Error("tamanho");
    return new ImageData(new Uint8ClampedArray(rgba.buffer as ArrayBuffer, rgba.byteOffset, largura * altura * 4), largura, altura);
  } catch {
    throw new ConversionError(
      "tiff_nao_suportado",
      "Este TIFF usa uma compressão que o navegador não lê. O plugin converte com o FFmpeg, que abre praticamente todos.",
    );
  }
}
