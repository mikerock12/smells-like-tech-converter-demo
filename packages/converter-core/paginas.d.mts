import type { Ferramenta, SlugResolvido } from "./catalogo.mjs";

export type Prioridade = "A" | "B" | "C";

export interface PaginaPriorizada {
  readonly slug: string;
  readonly prioridade: Prioridade;
  readonly h1: string;
  readonly frase?: string;
}

export interface PaginaDeBusca extends SlugResolvido {
  readonly slug: string;
  readonly h1: string;
  readonly titulo: string;
  readonly descricao: string;
  readonly prioridade: Prioridade | null;
  readonly comFaq: boolean;
}

export interface Pergunta {
  readonly pergunta: string;
  readonly resposta: string;
}

export declare const PRIVACIDADE_NO_TOPO: string;
export declare const PRIVACIDADE_NO_TOPO_DO_PLUGIN: string;
export declare function privacidadeNoTopo(ferramenta: Ferramenta): string;
export declare const PAGINAS_PRIORIZADAS: readonly PaginaPriorizada[];
export declare const ATALHOS_DA_HOME: readonly { readonly slug: string; readonly rotulo: string }[];
export declare function paginasDaPrioridade(prioridade: Prioridade): PaginaPriorizada[];
export declare function paginaDeBusca(slug: string): PaginaDeBusca | null;
export declare function slugsEmOrdemDePrioridade(): string[];
export declare function perguntasFrequentes(ferramenta: Ferramenta): Pergunta[];
export declare function contagemDePaginas(): { priorizadas: number; total: number; pares: number; ferramentas: number };
