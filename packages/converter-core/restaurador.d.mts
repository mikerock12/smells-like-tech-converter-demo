export declare const VERSAO_DO_RESTAURADOR: number;
export type IdDoPerfilDoRestaurador = "auto" | "lossy" | "live" | "archive" | "manual";
export declare const PERFIS_DO_RESTAURADOR: { id: IdDoPerfilDoRestaurador; rotulo: string; resumo: string }[];
export declare const ETAPAS_DO_RESTAURADOR: { id: string; rotulo: string }[];
export declare const FORMATOS_DO_RESTAURADOR: string[];
export interface OpcoesDoRestaurador {
  versao: number; perfil: IdDoPerfilDoRestaurador; intensidade: number; preservarAmbiencia: number;
  formato: string; etapas: Record<string, number>; relatorio: boolean; ia: boolean;
}
export type DiagnosticoDoAudio = Record<"duracao" | "picoDb" | "rmsDb" | "cristaDb" | "dc" | "recorte" | "pisoDeRuidoDb" | "zumbidoHz" | "zumbidoDb" | "bandaHz" | "estalosPorMinuto" | "correlacao" | "silencio" | "agudosDb" | "dinamicaDb" | "taxa" | "planuraDoRuido", number>;
export interface Problema { gravidade: number; confianca: number; hz?: number }
export interface Avaliacao { problemas: Record<"recorte" | "zumbido" | "ruido" | "estalos" | "dc" | "banda", Problema>; recomendacoes: string[]; avisos: string[]; semDefeitos: boolean }
export interface PlanoDaRestauracao {
  versao: number; perfil: IdDoPerfilDoRestaurador; etapas: { id: string; quanto: number; hz?: number }[];
  parametros: Record<string, number>; ia: { modelo: "apollo"; mistura: number; corteHz: number } | null; avisos: string[]; semMudanca: boolean; avaliacao: Avaliacao;
}
export declare function lerOpcoesDoRestaurador(entrada: unknown): OpcoesDoRestaurador;
export declare function avaliarDiagnostico(d: DiagnosticoDoAudio): Avaliacao;
export declare function planejarRestauracao(d: DiagnosticoDoAudio, opcoes: unknown): PlanoDaRestauracao;
export declare function textoDoAviso(id: string): string;
export declare function resumoDoDiagnostico(d: DiagnosticoDoAudio): string;
export declare function nomeDoRestaurado(nome: string, extensao: string): string;
