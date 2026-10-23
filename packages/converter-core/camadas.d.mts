export type QuadroChave = { tempo: number; x: number; y: number; escala: number; curva?: "linear" | "suave" };
export type Transformacao = {
  mascara?: "nenhuma" | "circulo" | "oval" | "arredondada";
  quadrosChave?: QuadroChave[];
  tempoDaAnimacao?: number;
  x: number; y: number; escala: number; giro: number; giroX: number; giroY: number;
  espelharX: boolean; espelharY: boolean; opacidade: number; mescla: string;
  filtro: string; brilho: number; contraste: number; saturacao: number; removerFundo: boolean;
};
export declare const TRANSFORMACAO_PADRAO: Readonly<Transformacao>;
export declare const FILTROS_DE_CAMADA: string[];
export declare const MESCLAS_DE_CAMADA: string[];
export declare function matrizDeCor(transformacao?: Partial<Transformacao>): number[];
export declare function cantosDaCamada(largura: number, altura: number, quadroW: number, quadroH: number, transformacao?: Partial<Transformacao>): { x: number; y: number }[];
export declare function homografia(cantos: { x: number; y: number }[]): number[] | null;
export declare function projetarPonto(m: number[], x: number, y: number): { x: number; y: number };
export declare function validarTransformacao(t?: Partial<Transformacao>): string | null;
