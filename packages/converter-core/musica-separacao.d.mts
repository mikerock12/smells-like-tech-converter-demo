export interface FaixaDoModo { id: string; rotulo: string; de: string[] }
export interface ModoDeSeparacao { id: string; rotulo: string; faixas: FaixaDoModo[] }
export declare const MODELO_DE_SEPARACAO: {
  id: string; rotulo: string; arquivo: string; url: string; bytes: number; sha256: string; licenca: string;
  liberadoParaUsoComercial: boolean; taxa: number; janela: number; passo: number; quadrosPorTrecho: number;
  bandas: number; instrumentos: string[]; entrada: string; saida: string; memoriaMb: number;
};
export declare const MODOS_DE_SEPARACAO: ModoDeSeparacao[];
export declare const AVISO_DA_SEPARACAO: string;
export declare function modoDeSeparacao(id: string): ModoDeSeparacao | null;
export declare class Fft {
  constructor(n: number);
  transformar(re: Float64Array, im: Float64Array, sinal: number): void;
}
type Ler = (canal: number, inicio: number, n: number, destino: Float32Array) => void;
type Emitir = (canal: number, inicio: number, amostras: Float32Array, n: number) => void;
export declare class ProcessadorEspectral {
  constructor(opcoes: { comprimento: number; janela?: number; passo?: number; quadrosPorTrecho?: number; saidas?: number });
  trechos: number;
  B: number;
  re: Float32Array[];
  im: Float32Array[];
  analisar(s: number, ler: Ler, canais: number): number;
  magnitude(q: number, bandas: number, destino: Float32Array): Float32Array;
  sintetizar(s: number, saida: number, mascara: Float32Array[], emitir: Emitir): void;
}
export declare function mascaraDaFaixa(estimativas: Float32Array, instrumentos: number, indices: number[], q: number, T: number, bandas: number, B: number, destino: Float32Array[]): void;
export declare function separar(opcoes: {
  mascara?: "classica" | "refinada";
  comprimento: number;
  canais: number;
  ler: Ler;
  executar: (entrada: Float32Array) => Promise<Float32Array>;
  emitir: (faixa: number, canal: number, inicio: number, amostras: Float32Array, n: number) => void;
  modo?: string;
  progresso?: (fracao: number) => void;
  cancelado?: () => boolean;
}): Promise<FaixaDoModo[]>;
export declare function reduzirRuido(opcoes: { comprimento: number; canais: number; ler: Ler; emitir: Emitir; intensidade?: number; piso?: number }): void;
