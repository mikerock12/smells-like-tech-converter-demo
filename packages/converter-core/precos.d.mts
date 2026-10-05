import type { Contador, Ferramenta } from "./catalogo.mjs";

export type PlanoId = "gratis" | "pro" | "app" | "android" | "completo";
export type ProdutoId = "pro-mensal" | "pro-anual" | "app-vitalicio" | "android-vitalicio" | "completo-vitalicio";

export interface Plano {
  readonly id: PlanoId;
  readonly nome: string;
  readonly chamada: string;
  readonly descricao: string;
  readonly centavos: number;
  readonly site: boolean;
  readonly app: boolean;
  readonly android: boolean;
  readonly cor: "neutra" | "laranja" | "ambar" | "amarela";
}

export interface Produto {
  readonly id: ProdutoId;
  readonly plano: PlanoId;
  readonly titulo: string;
  readonly centavos: number;
  readonly dias: number | null;
  readonly periodo: string;
  readonly nota: string;
}

export interface LicencaResumo {
  readonly plano: PlanoId | string;
  readonly expira?: string | null;
}

export declare const MOEDA: "BRL";
export declare const PLANOS: Readonly<Record<PlanoId, Plano>>;
export declare const PRODUTOS: readonly Produto[];
export declare const PRODUTOS_POR_ID: Readonly<Record<ProdutoId, Produto>>;
export declare const MAQUINAS_POR_LICENCA: number;
export declare const DIAS_DE_TESTE_DO_APP: number;
export declare const LIMITES: Readonly<{
  gratis: Readonly<Record<Contador, number>>;
  pro: Readonly<Record<Contador, null>>;
}>;
export declare const ROTULOS_DOS_LIMITES: Readonly<
  Record<Contador, { titulo: string; unidade: string; periodo: "dia" | null }>
>;
export declare const COMPARATIVO: readonly { item: string; gratis: string; pro: string }[];
export declare function produto(id: string): Produto | null;
export declare function formatarReais(centavos: number): string;
export declare function emReais(centavos: number): number;
export declare function economiaDoAnual(): number;
export declare function alcanceDoPlano(plano: string): { site: boolean; app: boolean; android: boolean };
export declare function planoDoSite(licenca: LicencaResumo | null | undefined): "gratis" | "pro";
export declare function limite(plano: "gratis" | "pro", contador: Contador): number | null;
export declare function ferramentaLiberada(ferramenta: Ferramenta, plano: "gratis" | "pro"): boolean;
