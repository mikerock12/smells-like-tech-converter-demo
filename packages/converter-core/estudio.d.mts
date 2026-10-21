import type { Transformacao } from "./camadas.mjs";
export type IdDaSaida = "video" | "gif" | "imagem" | "audio" | "texto";
export type IdDoAjuste = "preencher" | "caber" | "caber-desfocado";

export type Preset = {
  readonly id: string;
  readonly rede: string;
  readonly nome: string;
  readonly largura: number;
  readonly altura: number;
  readonly fps: number;
  /** Duração de referência da rede, em segundos; null quando não há limite prático. */
  readonly duracaoMaxima: number | null;
};

export type Saida = { id: IdDaSaida; titulo: string; descricao: string; extensao: string };
export type Ajuste = { id: IdDoAjuste; titulo: string; descricao: string };
export type Fonte = { id: string; nome: string; arquivo: string | null };

export type Clipe = {
  transformacao?: Partial<Transformacao>;
  id: string;
  /** Referência do arquivo no app (nome, caminho ou chave da sessão). */
  arquivo: string;
  tipo: "video" | "imagem";
  /** Trecho do arquivo, em segundos (só vídeo). */
  inicio?: number;
  fim?: number;
  /** Quanto a imagem fica na tela, em segundos. */
  duracao?: number;
  ajuste: IdDoAjuste;
  volume?: number;
  mudo?: boolean;
};

export type Texto = {
  transformacao?: Partial<Transformacao>;
  id: string;
  texto: string;
  /** Na linha do tempo final, em segundos. */
  inicio: number;
  fim: number;
  /** Centro do texto, em fração do quadro. */
  x: number;
  y: number;
  /** Altura da letra, em fração da altura do quadro. */
  tamanho: number;
  cor: string;
  contorno: string | null;
  caixa: string | null;
  fonte: string;
};

export type AudioExtra = {
  id: string;
  arquivo: string;
  /** Onde começa na linha do tempo. */
  entrada: number;
  /** Trecho do arquivo. */
  inicio: number;
  fim: number;
  volume?: number;
  fadeEntrada?: number;
  fadeSaida?: number;
};

export type Projeto = {
  camadas?: Camada[];
  fundo?: { cor: string; corFinal?: string };
  versao: number;
  destino: string;
  largura: number;
  altura: number;
  fps: number;
  saida: IdDaSaida;
  clipes: Clipe[];
  textos: Texto[];
  audios: AudioExtra[];
  /** Instante exportado quando a saída é imagem. */
  quadro: number;
};

export type Camada = {
  id: string;
  arquivo: string;
  tipo: "imagem" | "video" | "gif";
  inicio: number;
  fim: number;
  deslocamento?: number;
  repetir?: boolean;
  visivel?: boolean;
  ordem?: number;
  transformacao?: Partial<Transformacao>;
};
export declare function camadasNoTempo(projeto: Projeto, t: number): Camada[];
export declare function usaCamadas(projeto: Projeto): boolean;

export type Segmento = { indice: number; id: string; inicio: number; fim: number; duracao: number };

export type EntradasDaReceita = {
  clipes: { caminho: string; temAudio: boolean }[];
  textos: string[];
  audios: string[];
  camadas?: string[];
};

export type ReceitaFfmpeg = { args: string[]; filtro: string; mapas: string[]; duracao: number };

export declare const VERSAO_DO_PROJETO: number;
export declare const PRESETS: readonly Preset[];
export declare const NOTA_DOS_PRESETS: string;
export declare const SAIDAS: readonly Saida[];
export declare const AJUSTES: readonly Ajuste[];
export declare const FONTES: readonly Fonte[];
export declare const LAYOUT_DO_TEXTO: {
  readonly larguraMaxima: number;
  readonly entrelinha: number;
  readonly contorno: number;
  readonly margemDaCaixa: number;
  readonly raioDaCaixa: number;
};
export declare const DURACAO_PADRAO_DA_IMAGEM: number;
export declare const DURACAO_MINIMA: number;
export declare const LADO_DO_GIF: number;
export declare const FPS_DO_GIF: number;
export declare const LIMITE_DO_NAVEGADOR: { readonly segundos: number; readonly pixels: number };

export declare function presetPorId(id: string): Preset | null;
export declare function saidaPorId(id: string): Saida | null;
export declare function novoProjeto(destino: string, saida?: IdDaSaida, tamanho?: { largura: number; altura: number } | null): Projeto;
export declare function duracaoDoClipe(clipe: Clipe): number;
export declare function segmentos(projeto: Projeto): Segmento[];
export declare function duracaoTotal(projeto: Projeto): number;
export declare function clipeNoTempo(projeto: Projeto, t: number): { indice: number; clipe: Clipe; tempoNoArquivo: number } | null;
export declare function textosNoTempo(projeto: Projeto, t: number): Texto[];
export declare function retanguloDoAjuste(ajuste: IdDoAjuste, larguraDaOrigem: number, alturaDaOrigem: number, largura: number, altura: number): { x: number; y: number; w: number; h: number };
export declare function tamanhoDoGif(largura: number, altura: number): { largura: number; altura: number };
export declare function problemas(projeto: Projeto): string[];
export declare function avisos(projeto: Projeto): string[];
export declare function cabeNoNavegador(projeto: Projeto): boolean;
export declare function receitaFfmpeg(projeto: Projeto, entradas: EntradasDaReceita): ReceitaFfmpeg;
export declare function numero(valor: number): string;
export declare function duracaoLegivel(segundos: number): string;
