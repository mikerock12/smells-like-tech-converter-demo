export interface PresetDeMaster { id: string; rotulo: string; alvoLufs: number | null; tetoDbtp: number }
export interface FormatoDeExportacao { id: string; rotulo: string; extensao: string; bits: number }
export interface Medidas { lufs: number; amostraDb: number; realDb: number }
export declare const PRESETS_DE_MASTER: PresetDeMaster[];
export declare const FORMATOS_DE_EXPORTACAO: FormatoDeExportacao[];
export declare function coeficientesK(taxa: number): number[][];
export declare function loudnessIntegrado(esq: Float32Array, dir: Float32Array, taxa: number): number;
export declare function picos(esq: Float32Array, dir: Float32Array): { amostraDb: number; realDb: number };
export declare function medirMaster(esq: Float32Array, dir: Float32Array, taxa: number): Medidas;
export declare function masterizar(esq: Float32Array, dir: Float32Array, taxa: number, presetId: string): { esq: Float32Array; dir: Float32Array; ganhoDb: number; antes: Medidas; depois: Medidas };
export declare function criarRuido(semente?: number): () => number;
export declare function quantizar(valor: number, bits: number, ruido: (() => number) | null): number;
export declare function codificarWav(canais: Float32Array[], taxa: number, bits?: number, opcoes?: { dither?: boolean; semente?: number }): Uint8Array;
export declare function lerWav(bytes: Uint8Array): { taxa: number; canais: Float32Array[]; bits: number };
