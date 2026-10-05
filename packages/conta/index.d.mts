export declare const MINUTOS_DO_CODIGO: number;
export declare const TENTATIVAS_DO_CODIGO: number;
export declare const SEGUNDOS_ENTRE_CODIGOS: number;
export declare const CODIGOS_POR_HORA: number;
export declare const DIAS_DA_SESSAO: number;
export declare const COOKIE_DA_SESSAO: string;
export declare const EMAIL_LEMBRADO: string;
export declare const USO_DESLIGADO: string;
export declare const USO_PENDENTE: string;
export declare const DIAS_DE_USO_ACEITOS: number;
export declare const LINHAS_DE_USO_POR_ENVIO: number;

export type UsoPendente = Record<string, Record<string, { arquivos: number; bytes: number }>>;
export declare function somarUso(
  pendente: UsoPendente | null | undefined,
  conversao: { dia: string; ferramenta: string; arquivos: number; bytes: number },
): UsoPendente;
export declare function linhasDoUso(
  corpo: unknown,
  ferramentaExiste: (id: string) => boolean,
  agora?: Date,
): { dia: string; ferramenta: string; arquivos: number; bytes: number }[] | null;

export declare function normalizarEmail(texto: unknown): string;
export declare function novoCodigo(sortear?: () => number): string;
export declare function codigoDigitado(texto: unknown): string | null;
export declare function resumoDoCodigo(email: string, codigo: string): Promise<string>;
export declare function novoToken(bytes?: Uint8Array): string;
export declare function resumoDoToken(token: string): Promise<string>;

export interface EnvioAnterior {
  readonly enviadoEm: string;
  readonly janelaInicio: string;
  readonly enviados: number;
}
export declare function podeEnviarCodigo(
  anterior: EnvioAnterior | null,
  agora?: number,
): { ok: true; janelaInicio: string; enviados: number } | { ok: false; esperarSegundos: number };

export interface CodigoGuardado {
  readonly resumo: string;
  readonly expira: string;
  readonly tentativas: number;
}
export declare function conferirCodigo(
  registro: CodigoGuardado | null,
  resumoDigitado: string,
  agora?: number,
): "ok" | "errado" | "expirado" | "esgotado" | "sem-codigo";

export declare function cookieDaSessao(token: string, dias?: number): string;
export declare function cookieQueApaga(): string;
export declare function lerCookie(cabecalho: string | null | undefined, nome?: string): string | null;
export declare function origemDoProprioSite(origem: string | null | undefined, urlDaRequisicao: string): boolean;

export interface LicencaDaConta {
  readonly ref: string;
  readonly plano: string;
  readonly estado: string;
  readonly chave: string | null;
  readonly expira: string | null;
}
export declare function chaveParaONavegador<T extends LicencaDaConta>(licencas: readonly T[], agora?: number): T | null;

export declare function emailDoCodigo(entrada: { codigo: string; site: string }): { assunto: string; texto: string };
export declare function arquivoDaChave(entrada: {
  chave: string;
  ref: string;
  nomeDoPlano: string;
  expira: string | null;
  site: string;
}): string;
