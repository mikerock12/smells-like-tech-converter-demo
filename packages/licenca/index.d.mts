export interface CargaDaLicenca {
  readonly v: 1;
  readonly id: string;
  readonly plano: string;
  readonly email: string;
  readonly emitida: string;
  readonly expira: string | null;
  readonly maquinas: number;
}

export interface LicencaLida {
  readonly carga: CargaDaLicenca;
  readonly assinatura: Uint8Array;
  readonly assinado: Uint8Array;
}

export type Verificacao =
  | { ok: true; carga: CargaDaLicenca }
  | { ok: false; motivo: "formato" | "assinatura" };

export declare const PREFIXO: "SLT1";
export declare function paraBase64Url(bytes: Uint8Array): string;
export declare function deBase64Url(texto: string): Uint8Array;
export declare function serializarCarga(carga: CargaDaLicenca): Uint8Array;
export declare function montarChave(carga: CargaDaLicenca, assinatura: Uint8Array): string;
export declare function lerChave(texto: string): LicencaLida | null;
export declare function chaveLegivel(chave: string): string;
export declare function gerarParDeChaves(): Promise<{ publica: string; privada: string }>;
export declare function importarPublica(spkiBase64Url: string): Promise<CryptoKey>;
export declare function importarPrivada(pkcs8Base64Url: string): Promise<CryptoKey>;
export declare function assinarLicenca(carga: CargaDaLicenca, privada: CryptoKey): Promise<string>;
export declare function verificarLicenca(chave: string, publica: CryptoKey): Promise<Verificacao>;
export declare function vencida(carga: CargaDaLicenca, agora?: number): boolean;
