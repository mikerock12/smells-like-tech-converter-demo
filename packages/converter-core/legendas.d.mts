export interface BlocoDeLegenda {
  readonly inicio: number;
  readonly fim: number;
  readonly texto: string;
}
export declare function lerTempo(texto: string): number | null;
export declare function escreverTempo(ms: number, separador?: "," | "."): string;
export declare function lerLegenda(conteudo: string): BlocoDeLegenda[];
export declare function escreverSrt(blocos: readonly BlocoDeLegenda[]): string;
export declare function escreverVtt(blocos: readonly BlocoDeLegenda[]): string;
export declare function escreverTexto(blocos: readonly BlocoDeLegenda[]): string;
export declare function deslocar(blocos: readonly BlocoDeLegenda[], milissegundos: number): BlocoDeLegenda[];
export declare function converterLegenda(conteudo: string, formatoDeSaida: "srt" | "vtt" | "txt", atrasoSegundos?: number): string;
