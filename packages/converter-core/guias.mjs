import { cantosDaCamada, TRANSFORMACAO_PADRAO } from "./camadas.mjs";

/**
 * As guias do enquadramento no estúdio, a mesma regra do criador de figurinhas do Android
 * (Guias.kt): onde a camada está no quadro e o que ela encosta, em frações do lado (0..1).
 *
 * Ao mexer na posição, o que fica a menos de TOLERANCIA de uma linha (centro, borda do quadro,
 * centro ou borda de outra camada) encaixa nela, e o giro encaixa no reto (0°, ±90°, 180°) a
 * menos de TOLERANCIA_DO_GIRO. A tela mostra as linhas encaixadas piscando.
 */
export const TOLERANCIA = 0.015;
export const TOLERANCIA_DO_GIRO = 3;
const IGUAL = 0.002;
const ORDEM = { centro: 0, borda: 1, camada: 2 };

/** A caixa que a camada ocupa no quadro (0..1), já com escala, giro e perspectiva. */
export function caixaDaCamada(medidas, transformacao = {}) {
  const { largura, altura, quadroW, quadroH } = medidas ?? {};
  if (!(largura > 0 && altura > 0 && quadroW > 0 && quadroH > 0)) return null;
  const cantos = cantosDaCamada(largura, altura, quadroW, quadroH, transformacao);
  const xs = cantos.map((p) => p.x / quadroW), ys = cantos.map((p) => p.y / quadroH);
  return { esquerda: Math.min(...xs), topo: Math.min(...ys), direita: Math.max(...xs), base: Math.max(...ys) };
}

const verticais = (c) => [c.esquerda, (c.esquerda + c.direita) / 2, c.direita];
const horizontais = (c) => [c.topo, (c.topo + c.base) / 2, c.base];

function linhas(referencias, vertical) {
  return [[0, "borda"], [0.5, "centro"], [1, "borda"],
    ...referencias.flatMap((r) => (vertical ? verticais(r) : horizontais(r)).map((v) => [v, "camada"]))];
}

/** O menor deslocamento que leva uma das marcas da caixa a uma das linhas, ou 0. */
function encaixe(marcas, linhasDoEixo, tolerancia) {
  let melhor = 0, distancia = Infinity;
  for (const marca of marcas) for (const [linha] of linhasDoEixo) {
    const d = linha - marca;
    if (Math.abs(d) <= tolerancia && Math.abs(d) < distancia) { melhor = d; distancia = Math.abs(d); }
  }
  return melhor;
}

function guiasDe(caixa, linhasX, linhasY) {
  const achadas = [
    ...linhasX.filter(([x]) => verticais(caixa).some((v) => Math.abs(v - x) <= IGUAL)).map(([posicao, tipo]) => ({ vertical: true, posicao, tipo })),
    ...linhasY.filter(([y]) => horizontais(caixa).some((v) => Math.abs(v - y) <= IGUAL)).map(([posicao, tipo]) => ({ vertical: false, posicao, tipo })),
  ];
  // A mesma posição só uma vez: o centro do quadro vale mais que o de outra camada.
  const unicas = new Map();
  for (const guia of achadas) {
    const chave = `${guia.vertical}|${Math.round(guia.posicao * 1000)}`;
    const atual = unicas.get(chave);
    if (!atual || ORDEM[guia.tipo] < ORDEM[atual.tipo]) unicas.set(chave, guia);
  }
  return [...unicas.values()];
}

const normalizarGiro = (graus) => ((((graus + 540) % 360) + 360) % 360) - 180;

/**
 * Encaixa a transformação que o controle propôs (`bruta`) nas linhas mais próximas, se estiverem
 * a menos da tolerância, e diz quais linhas ficaram encaixadas. `referencias` são as caixas das
 * outras camadas visíveis. Sem as medidas da mídia, só o giro encaixa.
 */
export function alinharCamada(bruta, medidas, referencias = [], { tolerancia = TOLERANCIA, toleranciaDoGiro = TOLERANCIA_DO_GIRO } = {}) {
  let t = { ...TRANSFORMACAO_PADRAO, ...bruta };
  const reto = Math.round(t.giro / 90) * 90;
  if (Math.abs(t.giro - reto) <= toleranciaDoGiro) t = { ...t, giro: normalizarGiro(reto) };
  const reta = t.giro % 90 === 0;
  const caixa = caixaDaCamada(medidas, t);
  if (!caixa) return { transformacao: t, guias: [], reta };
  const linhasX = linhas(referencias, true), linhasY = linhas(referencias, false);
  const dx = encaixe(verticais(caixa), linhasX, tolerancia), dy = encaixe(horizontais(caixa), linhasY, tolerancia);
  if (dx !== 0 || dy !== 0) t = { ...t, x: t.x + dx, y: t.y + dy };
  const final = caixaDaCamada(medidas, t);
  return { transformacao: t, guias: final ? guiasDe(final, linhasX, linhasY) : [], reta };
}

/** As linhas em que a camada já está, sem mexer em nada. */
export function guiasDaCamada(medidas, transformacao, referencias = []) {
  const caixa = caixaDaCamada(medidas, { ...TRANSFORMACAO_PADRAO, ...transformacao });
  return caixa ? guiasDe(caixa, linhas(referencias, true), linhas(referencias, false)) : [];
}

const reta = (t) => ({ ...TRANSFORMACAO_PADRAO, ...t, x: 0.5, y: 0.5, giro: 0, giroX: 0, giroY: 0 });

/** Cobre o quadro inteiro, centralizada e reta (como "preencher" no Instagram). */
export function preencher(transformacao, medidas) {
  const { largura, altura, quadroW, quadroH } = medidas ?? {};
  if (!(largura > 0 && altura > 0 && quadroW > 0 && quadroH > 0)) return reta(transformacao);
  const cobrir = Math.max(quadroW / largura, quadroH / altura) / Math.min(quadroW / largura, quadroH / altura);
  return { ...reta(transformacao), escala: Math.min(6, cobrir) };
}

/** Inteira dentro do quadro, centralizada e reta. */
export function caberInteira(transformacao) {
  return { ...reta(transformacao), escala: 1 };
}

/** Só leva ao centro, sem mudar tamanho nem giro. */
export function centralizar(transformacao) {
  return { ...TRANSFORMACAO_PADRAO, ...transformacao, x: 0.5, y: 0.5 };
}
