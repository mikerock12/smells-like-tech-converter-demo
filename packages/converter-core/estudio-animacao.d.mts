import type { Transformacao } from "./camadas.mjs";
export declare function validarAnimacao(t: Partial<Transformacao>): string | null;
export declare function movimentoNoTempo(t?: Partial<Transformacao>, tempo?: number): { x: number; y: number; escala: number };
export declare function expressaoDoMovimento(t: Partial<Transformacao>, campo: "x" | "y" | "escala", tempo?: string): string;
export declare function kenBurns(t?: Partial<Transformacao>, duracao?: number, afastar?: boolean): Partial<Transformacao>;

export declare function mudarBaseDaAnimacao(antes:Partial<Transformacao>,depois:Partial<Transformacao>):Partial<Transformacao>;
