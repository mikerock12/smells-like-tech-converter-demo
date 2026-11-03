/**
 * As três qualidades da exportação e a prévia leve. Espelho de StudioQuality.cs (Windows e
 * plugin) e de Qualidade.kt (Android): a baixa reduz o quadro para até 720p e codifica depressa;
 * a média mantém o tamanho do projeto com codificação rápida; a alta mantém o tamanho com o
 * melhor acabamento. A prévia é só para ter uma ideia: lado maior de 480 px, até 12 quadros/s.
 * `mascarasPorSegundo` diz quantas máscaras de fundo o modelo calcula por segundo de vídeo no
 * FFmpeg (Windows/plugin); os quadros do meio misturam as vizinhas. 0 é uma por quadro.
 */
import { duracaoDaOrigem, duracaoTotal, temTransicoes } from "./estudio.mjs";

/** Lado par (como StudioRules.Even), exigido pelo H.264. */
const par = v => Math.max(2, Math.round(v / 2) * 2);

export const PREVIA = Object.freeze({ id: "previa", titulo: "Prévia", descricao: "Só para ter uma ideia dos efeitos.", ladoMaximo: 480, fpsMaximo: 12, qualidadeDoVideo: 40, preset: "ultrafast", custo: 0.35, mascarasPorSegundo: 2 });

export const QUALIDADES = Object.freeze([
  Object.freeze({ id: "baixa", titulo: "Baixa", descricao: "Até 720p, a mais rápida e leve.", ladoMaximo: 1280, fpsMaximo: 60, qualidadeDoVideo: 55, preset: "veryfast", custo: 0.55, mascarasPorSegundo: 10 }),
  Object.freeze({ id: "media", titulo: "Média", descricao: "Tamanho do projeto, codificação rápida.", ladoMaximo: 4096, fpsMaximo: 60, qualidadeDoVideo: 70, preset: "faster", custo: 0.75, mascarasPorSegundo: 15 }),
  Object.freeze({ id: "alta", titulo: "Alta", descricao: "Tamanho do projeto, melhor acabamento.", ladoMaximo: 4096, fpsMaximo: 60, qualidadeDoVideo: 82, preset: "medium", custo: 1, mascarasPorSegundo: 0 }),
]);

export function qualidadePorId(id) {
  return QUALIDADES.find(q => q.id === id) ?? QUALIDADES[QUALIDADES.length - 1];
}

/** O projeto no tamanho e fps da qualidade (o lado maior nunca passa do limite dela). */
export function aplicarQualidade(projeto, qualidade) {
  const escala = Math.min(1, qualidade.ladoMaximo / Math.max(projeto.largura, projeto.altura));
  return { ...projeto, largura: par(projeto.largura * escala), altura: par(projeto.altura * escala), fps: Math.min(projeto.fps, qualidade.fpsMaximo) };
}

/** Velocidade inicial (PC de 4 núcleos, 03/11/2026); cada cliente ajusta com o que mede. */
export const VELOCIDADE_PADRAO = Object.freeze({ segundosPorMegapixelQuadro: 0.03, segundosPorMascara: 0.37 });

/** Ler e decodificar o vídeo de origem custa o mesmo para qualquer tamanho de saída. */
export const SEGUNDOS_POR_QUADRO = 0.015;

/** De quantos em quantos quadros o modelo calcula uma máscara nova. */
export function mascaraACada(qualidade, fps) {
  return qualidade.mascarasPorSegundo > 0 ? Math.max(1, Math.round(fps / qualidade.mascarasPorSegundo)) : 1;
}

/** Mistura a medida nova com a anterior, sem deixar uma medição estranha dominar. */
export function aprenderVelocidade(anterior, megapixelQuadro, mascara) {
  return {
    segundosPorMegapixelQuadro: megapixelQuadro > 0 && megapixelQuadro < 10 ? anterior.segundosPorMegapixelQuadro * 0.4 + megapixelQuadro * 0.6 : anterior.segundosPorMegapixelQuadro,
    segundosPorMascara: mascara > 0 && mascara < 30 ? anterior.segundosPorMascara * 0.4 + mascara * 0.6 : anterior.segundosPorMascara,
  };
}

/** Quadros de vídeo que passam pelo recorte do fundo. */
export function quadrosRecortados(projeto) {
  if (projeto.saida === "audio" || projeto.saida === "texto") return 0;
  const clipes = projeto.clipes.filter(c => c.tipo === "video" && c.transformacao?.removerFundo)
    .reduce((s, c) => s + Math.ceil(duracaoDaOrigem(c) * projeto.fps), 0);
  const camadas = (projeto.camadas ?? []).filter(c => c.visivel !== false && c.tipo !== "imagem" && c.transformacao?.removerFundo)
    .reduce((s, c) => s + Math.ceil(((c.deslocamento ?? 0) + c.fim - c.inicio) * projeto.fps), 0);
  return clipes + camadas;
}

/** Peso relativo da composição: camadas, fundo desfocado, textos e transições pesam mais. */
export function pesoDaComposicao(projeto) {
  const visiveis = (projeto.camadas ?? []).filter(c => c.visivel !== false).length;
  const desfocados = projeto.clipes.filter(c => c.ajuste === "caber-desfocado").length / Math.max(1, projeto.clipes.length);
  return 1 + visiveis * 0.35 + desfocados * 0.25 + (projeto.textos.length ? 0.1 : 0) + (temTransicoes(projeto) ? 0.2 : 0);
}

/**
 * Segundos estimados para montar o projeto na qualidade dada (a prévia inclusive). O navegador
 * recorta todos os quadros (`todasAsMascaras`); o FFmpeg do Windows/plugin espaça as máscaras.
 */
export function tempoEstimado(projeto, qualidade, velocidade = VELOCIDADE_PADRAO, todasAsMascaras = false) {
  const p = aplicarQualidade(projeto, qualidade);
  if (p.saida === "audio" || p.saida === "texto") return Math.max(2, duracaoTotal(p) * 0.05);
  const quadros = p.saida === "imagem" ? 1 : duracaoTotal(p) * p.fps;
  const megapixels = p.largura * p.altura / 1e6;
  const composicao = quadros * (SEGUNDOS_POR_QUADRO + megapixels * velocidade.segundosPorMegapixelQuadro * pesoDaComposicao(p) * qualidade.custo);
  // Ler, aplicar a máscara e gravar cada quadro custa pouco perto de calcular a máscara.
  const recortados = quadrosRecortados(p);
  const recorte = Math.ceil(recortados / (todasAsMascaras ? 1 : mascaraACada(qualidade, p.fps))) * velocidade.segundosPorMascara
    + recortados * 0.01 * Math.min(1.5, Math.max(0.35, megapixels / 2.07));
  return Math.max(1, composicao + recorte + 1.5);
}

/** "cerca de 40 s", "cerca de 3 min", "cerca de 1 h 10 min". */
export function tempoLegivel(segundos) {
  const s = Math.max(1, Math.round(segundos));
  if (s < 60) return `cerca de ${Math.max(5, Math.ceil(s / 5) * 5)} s`;
  const minutos = Math.ceil(s / 60);
  return minutos < 60 ? `cerca de ${minutos} min` : `cerca de ${Math.floor(minutos / 60)} h ${String(minutos % 60).padStart(2, "0")} min`;
}
