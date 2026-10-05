import type { CargaDaLicenca } from "../licenca/index.mjs";

export declare function novaReferencia(aleatorio?: Uint8Array): string;
export declare function referenciaValida(texto: string): boolean;
export declare function emailValido(texto: string): boolean;
export declare function origemValida(texto: unknown): string | null;
export declare function corpoDaPreferencia(entrada: {
  ref: string;
  produtoId: string;
  email: string;
  site: string;
}): Record<string, unknown>;
export declare function lerAssinatura(cabecalho: string | null | undefined): { ts: string; v1: string } | null;
export declare function manifestoDoWebhook(entrada: { dataId: string; requestId: string; ts: string }): string;
export declare function webhookAutentico(entrada: {
  segredo: string | undefined;
  assinatura: string | null | undefined;
  requestId: string | null | undefined;
  dataId: string | null | undefined;
  agora?: number;
  toleranciaMs?: number;
}): Promise<boolean>;
export declare function cargaDaLicenca(entrada: {
  ref: string;
  produtoId: string;
  email: string;
  pagoEm?: Date | string | number;
}): CargaDaLicenca;
export declare function estadoDoPagamento(status: string | undefined): "pago" | "recusado" | "estornado" | "criado";
export declare const DIAS_DE_GARANTIA: number;
export declare function centavosDoValor(texto: string | null | undefined): number | null;
export declare function pedidoDeEstorno(entrada: {
  pagoCentavos: number;
  jaEstornadoCentavos?: number;
  pedidoCentavos?: number | null;
}): { centavos: number; zera: boolean; corpo: { amount?: number } };
export declare function chaveDoEstorno(ref: string, centavos: number, jaEstornadoCentavos?: number): string;
export declare function dentroDaGarantia(pagoEm: Date | string | number, agora?: number, dias?: number): boolean;
export declare function formatarCentavos(centavos: number): string;
