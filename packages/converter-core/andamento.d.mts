export interface EstadoDoAndamento { base?: { momento: number; fracao: number }; ultimo?: number; momento?: number; fracao?: number }
export declare function estimarRestante(estado: EstadoDoAndamento, fracao: number, agoraMs?: number): number | null;
export declare function descreverRestante(segundos: number | null | undefined): string | null;
export declare function textoDoAndamento(fracao: number, segundos: number | null | undefined): string;
