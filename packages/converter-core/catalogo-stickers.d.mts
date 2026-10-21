export type ItemDoCatalogo = { id: string; nome: string; previa: string; url: string; pagina: string; autor: string; fonte: "noto" | "giphy" };
export type EstadoDoCatalogo = { tentativas: number[]; tentarEm: number; motivo: string | null; falhas: number };
export type ResultadoDoCatalogo = { fonte: "noto" | "giphy"; itens: ItemDoCatalogo[]; aviso: string | null; tentarEm: number | null; proximaPagina: number | null };
export declare const HORA_DO_CATALOGO: number;
export declare const LIMITE_LOCAL_DO_GIPHY: number;
export declare function estadoDoCatalogo(): EstadoDoCatalogo;
export declare function avisoDoCatalogo(motivo: string | null): string | null;
export declare function esperaDoGiphy(valor: string | null, agora: number, esperaPadrao?: number): number;
export declare function proximoCatalogo(estado: EstadoDoCatalogo, agora: number, configurado?: boolean): { estado: EstadoDoCatalogo; fonte: "noto" | "giphy"; motivo: string | null; tentarEm: number | null };
export declare function registrarResposta(estado: EstadoDoCatalogo, status: number, agora: number, retryAfter?: string | null): EstadoDoCatalogo;
export declare class CatalogoDeStickers {
  constructor(opcoes: { chave?: string; buscarNoto: (consulta: string, signal?: AbortSignal) => Promise<ItemDoCatalogo[]>; fetch?: typeof globalThis.fetch; agora?: () => number; estado?: EstadoDoCatalogo; salvarEstado?: (estado: EstadoDoCatalogo) => void });
  estado: EstadoDoCatalogo;
  buscar(consulta?: string, opcoes?: { signal?: AbortSignal; pagina?: number }): Promise<ResultadoDoCatalogo>;
}
