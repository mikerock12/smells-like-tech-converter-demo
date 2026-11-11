export type Compasso = [number, number];
export interface PontoDeAutomacao { quadro: number; valor: number }
export interface Clipe {
  id: string; midia: string; inicio: number; inicioNaMidia: number; duracao: number;
  ganhoDb: number; entradaSuave: number; saidaSuave: number; mudo: boolean; nome?: string;
  /** Velocidade na linha do tempo (0,25–4); o padrão de projetos antigos é 1. */
  velocidade?: number;
  /** Transposição independente da duração, em semitons (−12 a +12). */
  semitons?: number;
  /** true = time-stretch; false = velocidade também muda o tom, como uma fita. */
  preservarTom?: boolean;
}
export interface Envio { barramento: string; nivelDb: number; preFader: boolean }
export interface EfeitoDaMusica { id: string; tipo: string; ligado: boolean; [parametro: string]: number | string | boolean }
export interface Faixa {
  id: string; nome: string; cor: string; ganhoDb: number; pan: number;
  ajusteEsquerdoDb: number; ajusteDireitoDb: number; gravar: boolean; monitorar: boolean;
  mudo: boolean; solo: boolean; entrada: string | null; canaisDeEntrada: number; saida: string;
  efeitos: EfeitoDaMusica[]; envios: Envio[];
  automacao: { volume: PontoDeAutomacao[]; pan: PontoDeAutomacao[] }; clipes: Clipe[];
  oculta?: boolean; congelada?: boolean;
}
export interface Barramento { id: string; nome: string; ganhoDb: number; pan: number; mudo: boolean; efeitos: EfeitoDaMusica[] }
export interface Midia {
  id: string; nome: string; formato: string; taxa: number; canais: number; quadros: number; bits: number;
  sha256?: string; origem?: string | null; instrumento?: string | null; modelo?: string | null; versaoDoModelo?: string | null;
}
export interface Marcador { id: string; quadro: number; nome: string }
export interface ProjetoMusical {
  versao: number; id: string; titulo: string; taxa: number; profundidade: number; bpm: number; compasso: Compasso;
  faixas: Faixa[]; barramentos: Barramento[]; master: { ganhoDb: number; efeitos: EfeitoDaMusica[]; velocidade?: number; semitons?: number; preservarTom?: boolean };
  marcadores: Marcador[]; midias: Midia[];
  metronomo: { ligado: boolean; volumeDb: number; naExportacao: boolean };
  contagem: number; latencia: { entrada: number; saida: number; medida: boolean; ajuste?: number };
  loop: { ligado: boolean; inicio: number; fim: number };
  punch: { ligado: boolean; entrada: number; saida: number };
  separacoes: { id: string; midia: string; modo: string; modelo: string; faixas: string[] }[];
}
export interface Clique { quadro: number; forte: boolean }
export interface PlanoDaGravacao { inicioDoTransporte: number; inicioDaTomada: number; fimDaTomada: number | null; cliquesDaContagem: Clique[]; preRolagem: number }

export declare const VERSAO_DA_MUSICA: number;
export declare const TAXA_PADRAO: number;
export declare const TAXAS: number[];
export declare const BLOCO: number;
export declare const GANHO_MINIMO_DB: number;
export declare const GANHO_MAXIMO_DB: number;
export declare const AJUSTE_MINIMO_DB: number;
export declare const AJUSTE_MAXIMO_DB: number;
export declare const BPM_MINIMO: number;
export declare const BPM_MAXIMO: number;
export declare const CORES_DAS_FAIXAS: string[];
export declare const PROFUNDIDADES: number[];
export declare const MODELOS_DA_MUSICA: { id: string; rotulo: string; faixas: string[]; barramentos: string[] }[];
export declare function dbParaLinear(db: number): number;
export declare function linearParaDb(ganho: number): number;
export declare function volumeParaPercentual(db: number): number;
export declare function percentualParaVolume(percentual: number): number;
export declare function textoDoPan(pan: number): string;
export declare function quadrosPorBatida(projeto: ProjetoMusical): number;
export declare function quadroDaBatida(projeto: ProjetoMusical, k: number): number;
export declare function quadrosPorCompasso(projeto: ProjetoMusical): number;
export declare function posicaoMusical(projeto: ProjetoMusical, quadro: number): { compasso: number; batida: number; centesimos: number; texto: string };
export declare function tempoDoQuadro(projeto: ProjetoMusical, quadro: number): string;
export declare function cliquesDoMetronomo(projeto: ProjetoMusical, inicio: number, fim: number): Clique[];
export declare function novaFaixa(id: string, nome: string, indice?: number): Faixa;
export declare function novoBarramento(id: string, nome: string): Barramento;
export declare function novoProjetoMusical(id: string, titulo?: string, taxa?: number): ProjetoMusical;
export declare function projetoDoModelo(modeloId: string, id: string, titulo: string, taxa?: number): ProjetoMusical;
export declare function faixasAudiveis(projeto: ProjetoMusical): string[];
export declare function ganhosEstereo(ganhoDb: number, pan: number, ajusteEsquerdoDb?: number, ajusteDireitoDb?: number): [number, number];
export declare function valorDaAutomacao(pontos: PontoDeAutomacao[] | undefined, quadro: number, padrao: number): number;
export declare function duracaoEmQuadros(projeto: ProjetoMusical): number;
export declare function envelopeDoClipe(clipe: Clipe, deslocamento: number): number;
export declare function planoDaGravacao(projeto: ProjetoMusical, cursor: number): PlanoDaGravacao;
export declare function inicioCompensado(quadroAlinhado: number, latencia: { entrada?: number; saida?: number; ajuste?: number }): number;
export declare function clipeDaTomada(projeto: ProjetoMusical, tomada: { id: string; midia: string; quadroAlinhado: number; quadrosCapturados: number }, plano: PlanoDaGravacao): Clipe | null;
export declare function adicionarTomada(faixa: Faixa, clipe: Clipe): Faixa;
export declare function medirLatencia(gravado: Float32Array, pulsos: number[], taxa: number): number | null;
export declare function problemasDaMusica(projeto: unknown, validarEfeito?: (efeito: EfeitoDaMusica) => string | null): string[];

export declare const PASSOS_DA_MUSICA: { ganhoDb: number; pan: number; ajusteDb: number; envioDb: number; metronomoDb: number; suaveSegundos: number };
export declare function passoDoParametro(parametro: { min: number; max: number; unidade: string }): { passo: number; relativo: boolean };
export declare function ajusteFino(valor: number, direcao: number, passo: number, de: number, ate: number, relativo?: boolean): number;
export declare function nomeDaFaixaSeparada(rotulo: string, nomeDaFaixa: string): string;
