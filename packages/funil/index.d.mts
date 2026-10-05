export interface Visita {
  readonly dia: string;
  readonly caminho: string;
  readonly origem: string;
}

export declare function caminhoContavel(url: URL, artigos?: readonly string[]): string | null;
export declare function origemDaVisita(url: URL, referer: string | null): string;
export declare function ehVisitaDePessoa(request: Request): boolean;
export declare function diaEmBrasilia(agora?: Date): string;
export declare const SQL_DA_VISITA: string;
export declare function visitaDaRequisicao(request: Request, status: number, artigos?: readonly string[], agora?: Date): Visita | null;
