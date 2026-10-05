declare module "gifenc" {
  export interface GifEncoderInstance {
    writeFrame(
      index: Uint8Array,
      width: number,
      height: number,
      options?: { palette?: number[][]; delay?: number; repeat?: number; transparent?: boolean; transparentIndex?: number; dispose?: number },
    ): void;
    finish(): void;
    bytes(): Uint8Array;
    reset(): void;
  }
  export function GIFEncoder(options?: { initialCapacity?: number; auto?: boolean }): GifEncoderInstance;
  export function quantize(rgba: Uint8Array | Uint8ClampedArray, maxColors: number, options?: { format?: "rgb565" | "rgb444" | "rgba4444"; oneBitAlpha?: boolean | number; clearAlpha?: boolean }): number[][];
  export function applyPalette(rgba: Uint8Array | Uint8ClampedArray, palette: number[][], format?: "rgb565" | "rgb444" | "rgba4444"): Uint8Array;
}
// SPDX-License-Identifier: GPL-3.0-or-later — declarações do cliente navegador.
