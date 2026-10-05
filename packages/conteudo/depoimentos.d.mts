export interface Depoimento {
  readonly texto: string;
  readonly autor: string;
  readonly local?: string;
  readonly tema: "pdf" | "transcricao" | "imagem" | "video" | "geral";
}

export declare const DEPOIMENTOS: readonly Depoimento[];
export declare function depoimentosDoTema(tema: Depoimento["tema"]): Depoimento[];
