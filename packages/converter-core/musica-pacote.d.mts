import type { ProjetoMusical } from "./musica.mjs";

export declare const VERSAO_DO_PACOTE: number;
export declare const MAXIMO_DE_ARQUIVOS: number;
export type IdDoModeloDoPacote = "spleeter" | "roformer";
export interface ModeloDoPacote { id: IdDoModeloDoPacote; rotulo: string; resumo: string; modo: string; faixas: string[] }
export interface GrupoDoPacote { nome: string; faixas: string[] }
export interface CombinacaoPronta { id: string; rotulo: string; grupos: GrupoDoPacote[] }
export interface PedidoDoPacote {
  versao: number;
  modelo: IdDoModeloDoPacote;
  grupos: GrupoDoPacote[];
  configuracao: ProjetoMusical;
  formato: string;
  master: string;
  manter?: "voz" | "resto";
}
export declare const MODELOS_DO_PACOTE: readonly ModeloDoPacote[];
export declare function modeloDoPacote(id: string): ModeloDoPacote | null;
export declare function rotuloDaFaixaDoPacote(id: string): string;
export declare function combinacoesProntas(modeloId: string): CombinacaoPronta[];
export declare function nomeDoGrupo(faixas: readonly string[], modelo: ModeloDoPacote | null): string;
export declare function configuracaoDoPacote(modeloId: string, taxa?: number, titulo?: string): ProjetoMusical;
export declare function problemasDoPacote(modeloId: string, grupos: readonly GrupoDoPacote[]): string[];
export declare function projetoDoArquivo(configuracao: ProjetoMusical, grupo: GrupoDoPacote, quadros: number): ProjetoMusical;
export declare function nomesDosArquivos(titulo: string, grupos: readonly GrupoDoPacote[], extensao: string): string[];
export declare const MODOS_DA_VOZ_NO_VIDEO: readonly { id: "voz" | "resto"; rotulo: string }[];
export declare function grupoDoVideo(modeloId: string, manter: "voz" | "resto"): GrupoDoPacote;
export declare function lerPedidoDoPacote(texto: string | object, padrao?: Partial<PedidoDoPacote>): PedidoDoPacote;
export declare const ETAPAS_DO_PACOTE: Readonly<Record<"lendo" | "modelo" | "separando" | "mixando" | "juntando", [number, number]>>;
export declare function fracaoDoPacote(etapa: "lendo" | "modelo" | "separando" | "mixando" | "juntando", fracao: number): number;
export declare function resumoDoArquivo(projeto: ProjetoMusical): unknown;
