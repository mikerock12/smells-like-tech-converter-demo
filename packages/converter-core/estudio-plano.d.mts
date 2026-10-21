import type { Projeto } from "./estudio.mjs";

export interface BloqueioDoEstudio {
  readonly contador: "transcricao" | "estudio" | "estudio-duracao" | "estudio-recorte";
  readonly mensagem: string;
  /** Quando o recurso inteiro é do Pro (não é questão de volume). */
  readonly recurso?: string;
}

export declare function removeFundoDeVideo(projeto: Projeto): boolean;
export declare function conferirEstudio(
  projeto: Projeto,
  plano: "gratis" | "pro",
  uso?: { exportacoesHoje?: number; transcricaoRestante?: number | null },
): BloqueioDoEstudio | null;
