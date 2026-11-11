export type AnaliseMusical = { bpm: number | null; tonalidade: string | null; confiancaDaTonalidade: number };
export declare function analisarMusica(canais: Float32Array[], taxa: number): AnaliseMusical;
