import type { Transformacao } from "./camadas.mjs";

export type Medidas = { largura: number; altura: number; quadroW: number; quadroH: number };
export type Caixa = { esquerda: number; topo: number; direita: number; base: number };
export type Guia = { vertical: boolean; posicao: number; tipo: "centro" | "borda" | "camada" };
export declare const TOLERANCIA: number;
export declare const TOLERANCIA_DO_GIRO: number;
export declare function caixaDaCamada(medidas: Medidas | null | undefined, transformacao?: Partial<Transformacao>): Caixa | null;
export declare function alinharCamada(
  bruta: Partial<Transformacao>, medidas: Medidas | null | undefined, referencias?: Caixa[],
  opcoes?: { tolerancia?: number; toleranciaDoGiro?: number },
): { transformacao: Transformacao; guias: Guia[]; reta: boolean };
export declare function guiasDaCamada(medidas: Medidas | null | undefined, transformacao: Partial<Transformacao>, referencias?: Caixa[]): Guia[];
export declare function preencher(transformacao: Partial<Transformacao> | undefined, medidas: Medidas | null | undefined): Transformacao;
export declare function caberInteira(transformacao: Partial<Transformacao> | undefined): Transformacao;
export declare function centralizar(transformacao: Partial<Transformacao> | undefined): Transformacao;
