export interface Artigo {
  readonly slug: string;
  readonly prioridade: "A" | "B" | "C";
  readonly titulo: string;
  readonly resumo: string;
  /** Vale para qualquer conversão, não só para as listadas. */
  readonly geral: boolean;
  /** Páginas de conversão com que o artigo conversa. */
  readonly conversoes: readonly string[];
}

export declare const ARTIGOS: readonly Artigo[];
export declare function artigo(slug: string): Artigo | null;
export declare function artigosDaConversao(slug: string, quantos?: number): Artigo[];
