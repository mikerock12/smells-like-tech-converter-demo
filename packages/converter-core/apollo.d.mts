export declare const APOLLO: Readonly<{
  id: string; arquivo: string; url: string; bytes: number; sha256: string; licenca: string; fonte: string; pesos: string;
  atribuicao: string; taxa: number; bloco: number;
}>;
export declare function inicioDosBlocos(comprimento: number): number[];
export declare function restaurarComApollo(
  canais: Float32Array[], executar: (bloco: Float32Array) => Promise<Float32Array> | Float32Array,
  progresso?: (fracao: number) => void, cancelado?: () => boolean,
): Promise<Float32Array[]>;
