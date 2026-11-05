import type { Texto } from "./estudio.mjs";
import type { Transformacao } from "./camadas.mjs";

export type OpcaoDeEfeito = { readonly id: string; readonly titulo: string };
export type EstadoDoEfeito = { dx: number; dy: number; sx: number; sy: number; giro: number; alfa: number; pedacos: number; particulas: number; matiz: number };
export type Pedaco = { x0: number; y0: number; cw: number; ch: number; cx: number; cy: number; vx: number; vy: number; volta: number };

export declare const EFEITOS_DE_ENTRADA: readonly OpcaoDeEfeito[];
export declare const EFEITOS_DE_SAIDA: readonly OpcaoDeEfeito[];
export declare const EFEITOS_CONTINUOS: readonly OpcaoDeEfeito[];
export declare const DURACAO_PADRAO_DO_EFEITO: number;
export declare function temEfeitos(t?: Partial<Transformacao> | null): boolean;
export declare function validarEfeitos(t: Partial<Transformacao>): string | null;
export declare function janelasDoEfeito(t: Partial<Transformacao>, duracao: number): { entrada: number; saida: number };
export declare function sorteio(k: number, m: number): number;
export declare function ruidoDoBloco(x: number, y: number): number;
export declare function estadoDoEfeito(t: Partial<Transformacao> | undefined, tempo: number, duracao: number): EstadoDoEfeito;
export declare function passosDaMaquina(texto: Texto, fps: number): { letras: number; passos: number; duracao: number; taxa: number };
export declare function letrasNoPasso(letras: number, passos: number, k: number): number;
export declare function usaMaquina(texto: Texto): boolean;
export declare function letrasVisiveis(texto: Texto, tempo: number, fps: number): number;
export declare function pedacosDoQuadro(w: number, h: number, px: number, py: number): { tw: number; th: number; lista: (Pedaco | null)[] };
export declare function blocosDoQuadro(w: number, h: number): { mw: number; mh: number };
export declare function filtroDosEfeitos(t: Partial<Transformacao> | undefined, w: number, h: number, fps: number, duracao: number, rotulo: string, pivo?: { x: number; y: number } | null): string;
