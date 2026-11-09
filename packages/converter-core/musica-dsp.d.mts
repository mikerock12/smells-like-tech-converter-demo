import type { EfeitoDaMusica } from "./musica.mjs";
export interface ParametroDoEfeito { id: string; rotulo: string; min: number; max: number; padrao: number; unidade: string }
export interface DefinicaoDoEfeito { tipo: string; rotulo: string; grupo: string; parametros: ParametroDoEfeito[] }
export interface Processador { processar(esq: Float32Array, dir: Float32Array, n: number): void; atualizar(efeito: EfeitoDaMusica): void }
export declare const EFEITOS_DA_MUSICA: DefinicaoDoEfeito[];
export declare function definicaoDoEfeito(tipo: string): DefinicaoDoEfeito | null;
export declare function novoEfeito(tipo: string, id: string): EfeitoDaMusica;
export declare function parametro(efeito: EfeitoDaMusica, id: string): number;
export declare function validarEfeito(efeito: EfeitoDaMusica): string | null;
export declare function caudaDoEfeito(efeito: EfeitoDaMusica): number;
export declare function coeficientesBiquad(tipo: string, frequencia: number, taxa: number, q?: number, ganhoDb?: number): number[];
export declare class Biquad {
  constructor(coeficientes: number[]);
  definir(c: number[]): void;
  amostra(x: number, canal: number): number;
  processar(esq: Float32Array, dir: Float32Array, n: number): void;
}
export declare function criarProcessador(efeito: EfeitoDaMusica, taxa: number): Processador;
export declare class Cadeia {
  constructor(taxa: number);
  sincronizar(efeitos: EfeitoDaMusica[]): void;
  processar(esq: Float32Array, dir: Float32Array, n: number): void;
}
