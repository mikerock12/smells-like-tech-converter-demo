/**
 * libheif compilado para WebAssembly, com o .wasm embutido no próprio módulo. Só o
 * pedaço que o motor de imagem usa.
 */
declare module "libheif-js/libheif-wasm/libheif-bundle.mjs" {
  export interface HeifImage {
    get_width(): number;
    get_height(): number;
    is_primary(): boolean;
    display(
      destino: { data: Uint8ClampedArray; width: number; height: number },
      pronto: (resultado: { data: Uint8ClampedArray; width: number; height: number } | null) => void,
    ): void;
    free(): void;
  }
  export interface LibHeif {
    HeifDecoder: new () => { decode(bytes: Uint8Array | ArrayBuffer): HeifImage[] };
  }
  const fabrica: () => LibHeif;
  export default fabrica;
}
// SPDX-License-Identifier: GPL-3.0-or-later — declarações do cliente navegador.
