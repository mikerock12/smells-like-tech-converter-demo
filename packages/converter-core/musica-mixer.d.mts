import type { Clique, ProjetoMusical } from "./musica.mjs";
export interface FonteDeAudio { canais: number; quadros: number; copiar(canal: number, inicio: number, n: number, destino: Float32Array): void }
export interface OpcoesDoMixer { entrada?: { esq: Float32Array; dir: Float32Array } | null; somente?: string | null; semMaster?: boolean; metronomo?: boolean }
export declare function fonteDaMemoria(canais: Float32Array[]): FonteDeAudio;
export declare function somDoClique(taxa: number, forte: boolean): Float32Array;
export declare class Misturador {
  constructor(taxa: number, bloco?: number);
  medidores: Map<string, Float32Array>;
  definirFonte(midia: string, fonte: FonteDeAudio | null): void;
  definirContagem(cliques: Clique[], fim: number): void;
  definirProjeto(projeto: ProjetoMusical): void;
  processar(inicio: number, n: number, saidaE: Float32Array, saidaD: Float32Array, opcoes?: OpcoesDoMixer): void;
}
export declare function caudaEmQuadros(projeto: ProjetoMusical, taxa?: number): number;
export declare function renderizar(
  projeto: ProjetoMusical,
  fontes: Map<string, FonteDeAudio>,
  opcoes: { inicio?: number; quadros: number; somente?: string | null; semMaster?: boolean; metronomo?: boolean },
): { esq: Float32Array; dir: Float32Array };
