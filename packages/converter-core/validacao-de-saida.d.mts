export declare const LIMITE_DE_SILENCIO_DB: number;
export declare const TOLERANCIA_DE_DURACAO: number;
export declare const MENSAGEM_DE_FALHA_DO_AUDIO: string;
export declare function picoDb(canais: ArrayLike<number>[]): number;
export declare function rmsDb(canais: ArrayLike<number>[]): number;
export declare function correlacao(a: ArrayLike<number>[], b: ArrayLike<number>[], passo?: number): number;
export interface ProblemaDaSaida { etapa: string; mensagem: string }
export interface DadosDaSaida {
  esperaVideo?: boolean; faixasDeVideo?: number; esperaAudio?: boolean; faixasDeAudio?: number;
  duracaoDoVideo?: number | null; duracaoDoAudio?: number | null;
  picoDaSaidaDb?: number | null; picoDaEntradaDb?: number | null;
  correlacaoComEntrada?: number | null; diferencaDeNivelDb?: number | null; deveMudar?: boolean;
}
export declare function conferirSaida(d: DadosDaSaida): ProblemaDaSaida[];
export declare function detalhesDosProblemas(problemas: ProblemaDaSaida[]): string;
