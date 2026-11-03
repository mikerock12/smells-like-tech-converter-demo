import type { Projeto } from "./estudio.mjs";

export type Qualidade = {
  readonly id: "previa" | "baixa" | "media" | "alta";
  readonly titulo: string;
  readonly descricao: string;
  readonly ladoMaximo: number;
  readonly fpsMaximo: number;
  /** Qualidade do vídeo de 0 a 100 (vira o CRF/CQ do codificador). */
  readonly qualidadeDoVideo: number;
  readonly preset: string;
  readonly custo: number;
  /** Máscaras de fundo por segundo de vídeo no FFmpeg; 0 é uma por quadro. */
  readonly mascarasPorSegundo: number;
};
export type Velocidade = { segundosPorMegapixelQuadro: number; segundosPorMascara: number };

export declare const PREVIA: Qualidade;
export declare const QUALIDADES: readonly Qualidade[];
export declare function qualidadePorId(id: string | null | undefined): Qualidade;
export declare function aplicarQualidade(projeto: Projeto, qualidade: Qualidade): Projeto;
export declare const VELOCIDADE_PADRAO: Velocidade;
export declare const SEGUNDOS_POR_QUADRO: number;
export declare function aprenderVelocidade(anterior: Velocidade, megapixelQuadro: number | null, mascara: number | null): Velocidade;
export declare function mascaraACada(qualidade: Qualidade, fps: number): number;
export declare function quadrosRecortados(projeto: Projeto): number;
export declare function pesoDaComposicao(projeto: Projeto): number;
export declare function tempoEstimado(projeto: Projeto, qualidade: Qualidade, velocidade?: Velocidade, todasAsMascaras?: boolean): number;
export declare function tempoLegivel(segundos: number): string;
