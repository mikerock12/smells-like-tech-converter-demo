/// <reference lib="webworker" />

import type { LibHeif } from "libheif-js/libheif-wasm/libheif-bundle.mjs";

import { ConversionError } from "../protocol";

/**
 * HEIC (as fotos do iPhone) sem servidor e sem app.
 *
 * Só o Safari abre HEIC sozinho. Nos outros navegadores, o libheif compilado para
 * WebAssembly decodifica aqui mesmo, no worker. São cerca de 2 MB, baixados só na
 * primeira vez que aparece um HEIC — quem converte PNG nunca paga por isso.
 *
 * O libheif é LGPL-3.0 e entra como módulo separado, carregado à parte do resto do
 * site (ver /termos, "Software de terceiros").
 */

let biblioteca: Promise<LibHeif> | null = null;

function carregar(): Promise<LibHeif> {
  biblioteca ??= import("libheif-js/libheif-wasm/libheif-bundle.mjs").then(async (modulo) => await modulo.default());
  return biblioteca;
}

export async function decodificarHeic(bytes: ArrayBuffer): Promise<ImageData> {
  // O decodificador do próprio navegador, quando existe (Safari), é mais rápido.
  try {
    const bitmap = await createImageBitmap(new Blob([bytes], { type: "image/heic" }));
    try {
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const contexto = canvas.getContext("2d", { willReadFrequently: true });
      if (contexto) {
        contexto.drawImage(bitmap, 0, 0);
        return contexto.getImageData(0, 0, bitmap.width, bitmap.height);
      }
    } finally {
      bitmap.close();
    }
  } catch {
    // Sem decodificador nativo: segue para o libheif.
  }

  let libheif: LibHeif;
  try {
    libheif = await carregar();
  } catch {
    biblioteca = null;
    throw new ConversionError("heic_indisponivel", "Não consegui carregar o leitor de HEIC neste navegador. O plugin converte HEIC também.");
  }

  const imagens = new libheif.HeifDecoder().decode(new Uint8Array(bytes));
  if (imagens.length === 0) {
    throw new ConversionError("heic_invalido", "Este HEIC não tem nenhuma imagem que eu consiga ler. O arquivo pode estar incompleto.");
  }

  try {
    const principal = imagens.find((imagem) => imagem.is_primary()) ?? imagens[0];
    const largura = principal.get_width();
    const altura = principal.get_height();
    const destino = new ImageData(largura, altura);
    await new Promise<void>((resolva, rejeite) => {
      principal.display(destino, (resultado) => (resultado ? resolva() : rejeite(new Error("heic"))));
    });
    return destino;
  } catch (erro) {
    if (erro instanceof ConversionError) throw erro;
    throw new ConversionError("heic_invalido", "Não consegui decodificar este HEIC. O arquivo pode estar corrompido.");
  } finally {
    for (const imagem of imagens) imagem.free();
  }
}
