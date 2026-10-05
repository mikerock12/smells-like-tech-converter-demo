import type { FormatId, FormatKind } from "./index.mjs";

export type TipoDeMidia = FormatKind;
export type OndeRoda = "navegador" | "plugin";
export type PlanoDaFerramenta = "gratis" | "pro";
export type Contador = "lote" | "video" | "pdf" | "pesado" | "transcricao" | "narracao" | "ocr";

export interface Categoria {
  readonly id: "imagem" | "audio" | "video" | "pdf" | "voz";
  readonly titulo: string;
  readonly chamada: string;
  readonly resumo: string;
}

export interface Ferramenta {
  readonly id: string;
  readonly slug: string;
  readonly categoria: Categoria["id"];
  readonly titulo: string;
  readonly acao: string;
  readonly resumo: string;
  readonly entradas: readonly TipoDeMidia[];
  readonly saidas: readonly FormatId[];
  readonly navegador: boolean;
  readonly plugin: string | null;
  readonly plano: PlanoDaFerramenta;
  readonly limite: Contador;
  readonly buscas: readonly string[];
  readonly pares: readonly (readonly [FormatId, FormatId])[];
  /** Só alguns formatos do tipo: "GIF → vídeo" aceita imagem, mas só GIF. */
  readonly formatosDeEntrada?: readonly FormatId[];
}

export interface Variante {
  readonly slug: string;
  readonly ferramenta: string;
  readonly formato: FormatId | null;
  readonly opcoes: Readonly<Record<string, string | number | boolean>>;
  readonly entrada: TipoDeMidia;
}

export interface SlugResolvido {
  readonly ferramenta: Ferramenta;
  readonly de: FormatId | null;
  readonly para: FormatId | null;
  /** Formato de saída que a página já deixa escolhido. */
  readonly formato: FormatId | null;
  /** Ajustes que a página já deixa escolhidos. */
  readonly opcoes: Readonly<Record<string, string | number | boolean>>;
}

export interface Par {
  readonly slug: string;
  readonly de: FormatId;
  readonly para: FormatId;
  readonly ferramenta: string;
  readonly titulo: string;
}

export declare const TIPOS: Readonly<Record<TipoDeMidia, { id: TipoDeMidia; titulo: string; plural: string }>>;
export declare const CATEGORIAS: readonly Categoria[];
export declare const FERRAMENTAS: readonly Ferramenta[];
export declare const FERRAMENTAS_POR_ID: Readonly<Record<string, Ferramenta>>;
export declare const PARES: readonly Par[];
export declare function ferramenta(id: string): Ferramenta | null;
export declare function ferramentasDaCategoria(categoria: Categoria["id"]): Ferramenta[];
export declare function ferramentasPara(tipo: TipoDeMidia, formatos?: readonly (FormatId | null)[] | null): Ferramenta[];
export declare function aceitaOsFormatos(ferramenta: Ferramenta, formatos: readonly (FormatId | null)[] | null): boolean;
export declare const VARIANTES: readonly Variante[];
export declare const SLUGS_ANTIGOS: Readonly<Record<string, string>>;
export declare function slugDoPar(de: FormatId, para: FormatId): string;
export declare function rotulo(formato: string): string;
export declare function resolverSlug(slug: string): SlugResolvido | null;
export declare function todosOsSlugs(): string[];
export declare function extensoesAceitas(): string[];
export declare function buscarFerramentas(consulta: string): Ferramenta[];
export declare function contagem(): { total: number; noNavegador: number; soPlugin: number; pares: number };
