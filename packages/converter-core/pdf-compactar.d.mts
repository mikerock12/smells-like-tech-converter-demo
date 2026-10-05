export declare class PdfNaoCompactavel extends Error {}

export type NivelDeCompressao = "leve" | "equilibrado" | "forte";

export declare const NIVEIS: Readonly<Record<NivelDeCompressao, { ladoMaximo: number; qualidade: number; fotosEmFlate: boolean }>>;

export type EntradaDeImagem =
  | { tipo: "jpeg"; bytes: Uint8Array; largura: number; altura: number; alvoLargura: number; alvoAltura: number; qualidade: number }
  | { tipo: "pixels"; dados: Uint8Array; componentes: 1 | 3; largura: number; altura: number; alvoLargura: number; alvoAltura: number; qualidade: number };

export interface ImagemRecomprimida {
  bytes: Uint8Array;
  largura: number;
  altura: number;
}

export interface ResultadoDaCompressao {
  bytes: Uint8Array;
  antes: number;
  depois: number;
  imagens: number;
  streams: number;
  orfaos: number;
  mudou: boolean;
}

export declare function compactarPdf(
  bytes: Uint8Array | ArrayBuffer,
  opcoes: {
    nivel?: NivelDeCompressao | string;
    recomprimir: (entrada: EntradaDeImagem) => Promise<ImagemRecomprimida | null>;
    progresso?: (fracao: number) => void;
    cancelado?: () => boolean;
  },
): Promise<ResultadoDaCompressao | null>;

export declare function desfazerPreditorPng(dados: Uint8Array, porLinha: number, bytesPorPixel: number, linhas: number): Uint8Array | null;
export declare function pareceFoto(dados: Uint8Array, componentes: number): boolean;
