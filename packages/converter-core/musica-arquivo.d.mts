import type { Midia, ProjetoMusical } from "./musica.mjs";
export declare const EXTENSAO_DA_MUSICA: string;
export declare const LIMITES_DO_ARQUIVO: { total: number; porMidia: number; projeto: number; entradas: number; razao: number };
export declare const EXTENSOES_DE_AUDIO: string[];
export declare function caminhoDaMidia(midia: Midia): string;
export declare function comprimirEntrada(caminho: string): boolean;
export declare function conferirEntradas(entradas: { nome: string; tamanho: number; comprimido: number }[]): string | null;
export declare function entradasDoProjeto(projeto: ProjetoMusical): { caminho: string; midia?: string; comprimir: boolean }[];
export declare function conferirProjetoDoArquivo(projeto: ProjetoMusical, nomes: string[]): string[];
