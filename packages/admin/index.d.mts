export declare const PERIODOS: readonly number[];
export declare function periodoDoPainel(texto: unknown): number;
export declare function ehAdmin(email: string | null | undefined, lista: string | null | undefined): boolean;
export declare function inicioEmUtc(dia: string): string;

export interface Balde {
  readonly de: string;
  readonly ate: string;
  readonly rotulo: string;
  readonly rotuloLongo: string;
}
export interface Janela {
  readonly dias: number;
  readonly tipo: "dia" | "semana" | "mes";
  readonly hoje: string;
  readonly desde: string;
  readonly baldes: readonly Balde[];
}
export declare function janelaDoPainel(dias: number, agora?: Date): Janela;
export declare function baldeDoDia(janela: Janela, dia: string): number;
export declare function receitaPorBalde(
  janela: Janela,
  pagamentos: readonly { pagoEm: string | null; centavos: number }[],
): (Balde & { centavos: number; pedidos: number })[];

export interface LicencaDoPainel {
  readonly email: string;
  readonly plano: string;
  readonly produto: string;
  readonly estado: string;
  readonly expira: string | null;
  readonly centavos: number;
}
export declare function planosAtivosPorEmail(
  licencas: readonly LicencaDoPainel[],
  agora?: number,
): Record<string, { plano: string; expira: string | null }[]>;
export declare function retratoDoPro(
  licencas: readonly LicencaDoPainel[],
  agora?: number,
): { pessoasComPro: number; porMesCentavos: number; vitalicias: number };

export declare const ORIGEM_DA_CORTESIA: "cortesia";
export declare function validarCortesia(entrada: unknown): { ok: true; email: string; produtoId: string; ref: string | null } | { ok: false; erro: string };
