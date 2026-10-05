export declare class GifInvalido extends Error {}

export interface QuadroDoGif {
  readonly esquerda: number;
  readonly topo: number;
  readonly largura: number;
  readonly altura: number;
  readonly entrelacado: boolean;
  readonly descarte: number;
  readonly transparente: number;
  /** Milissegundos, já com o mínimo que os navegadores aplicam. */
  readonly atraso: number;
}

export interface Gif {
  readonly bytes: Uint8Array;
  readonly largura: number;
  readonly altura: number;
  readonly quadros: readonly QuadroDoGif[];
  /** Voltas pedidas pelo GIF (0 = para sempre), ou nulo quando não diz. */
  readonly repeticoes: number | null;
}

export declare function lerGif(bytes: Uint8Array | ArrayBuffer): Gif;
export declare function descomprimirLzw(tamanhoMinimo: number, dados: Uint8Array, pixels: number): Uint8Array;
export declare function quadrosDoGif(gif: Gif): Generator<{ rgba: Uint8ClampedArray; atraso: number; indice: number }>;
export declare function duracaoDoGif(gif: Gif): number;
