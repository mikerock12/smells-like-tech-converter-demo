import { validarAnimacao } from "./estudio-animacao.mjs";
/** Geometria das camadas. X/Y inclinam a superfície; Z gira no plano. Medidas normalizadas. */
export const TRANSFORMACAO_PADRAO = Object.freeze({
  x: 0.5, y: 0.5, escala: 1, giro: 0, giroX: 0, giroY: 0,
  espelharX: false, espelharY: false, opacidade: 1, mescla: "normal",
  filtro: "original", brilho: 0, contraste: 0, saturacao: 0, removerFundo: false,
});
export const FILTROS_DE_CAMADA = ["original", "vivido", "pb", "sepia", "vintage", "frio", "quente", "negativo"];
export const MESCLAS_DE_CAMADA = ["normal", "multiplicar", "clarear", "sobrepor"];

/** Matriz RGB 3×4, incluindo deslocamentos em 0..255, igual ao ColorMatrix do Android. */
export function matrizDeCor(transformacao = {}) {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  const identidade = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0];
  const saturar = (s) => {
    const r = .213 * (1 - s), g = .715 * (1 - s), b = .072 * (1 - s);
    return [r + s, g, b, 0, r, g + s, b, 0, r, g, b + s, 0];
  };
  const compor = (a, b) => Array.from({ length: 12 }, (_, i) => {
    const linha = Math.floor(i / 4), coluna = i % 4;
    return a[linha * 4] * b[coluna] + a[linha * 4 + 1] * b[4 + coluna] + a[linha * 4 + 2] * b[8 + coluna] + (coluna === 3 ? a[i] : 0);
  });
  let m = identidade();
  if (t.filtro === "vivido") m = saturar(1.6);
  if (t.filtro === "pb") m = saturar(0);
  if (t.filtro === "sepia") m = compor([1.07,0,0,0, 0,.74,0,0, 0,0,.43,0], saturar(0));
  if (t.filtro === "vintage") m = compor([1,0,0,30, 0,.95,0,20, 0,0,.8,0], saturar(.6));
  if (t.filtro === "frio") m = [.9,0,0,0, 0,1,0,0, 0,0,1.15,15];
  if (t.filtro === "quente") m = [1.15,0,0,15, 0,1.02,0,5, 0,0,.85,0];
  if (t.filtro === "negativo") m = [-1,0,0,255, 0,-1,0,255, 0,0,-1,255];
  m = compor(saturar(1 + t.saturacao), m);
  const c = 1 + t.contraste, d = 128 * (1 - c) + t.brilho * 100;
  return compor([c,0,0,d, 0,c,0,d, 0,0,c,d], m);
}

/** Retângulo projetado, sentido horário. Distância da câmera proporcional ao quadro. */
export function cantosDaCamada(largura, altura, quadroW, quadroH, transformacao = {}) {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  const a = t.giro * Math.PI / 180, ax = t.giroX * Math.PI / 180, ay = t.giroY * Math.PI / 180;
  const escala = Math.min(quadroW / largura, quadroH / altura) * t.escala;
  const camera = Math.max(quadroW, quadroH) * 3;
  return [[0, 0], [largura, 0], [largura, altura], [0, altura]].map(([u, v]) => {
    const x = (u - largura / 2) * escala;
    const y = (v - altura / 2) * escala;
    const y1 = y * Math.cos(ax), z1 = y * Math.sin(ax);
    const x2 = x * Math.cos(ay) + z1 * Math.sin(ay);
    const z2 = -x * Math.sin(ay) + z1 * Math.cos(ay);
    const fator = camera / Math.max(camera * 0.1, camera - z2);
    return {
      x: quadroW * t.x + (x2 * Math.cos(a) - y1 * Math.sin(a)) * fator * (t.espelharX ? -1 : 1),
      y: quadroH * t.y + (x2 * Math.sin(a) + y1 * Math.cos(a)) * fator * (t.espelharY ? -1 : 1),
    };
  });
}

/** Homografia que leva o quadrado UV ao quadrilátero; null se a superfície está de perfil. */
export function homografia(cantos) {
  const [p0, p1, p2, p3] = cantos;
  const dx1 = p1.x - p2.x, dx2 = p3.x - p2.x, dx3 = p0.x - p1.x + p2.x - p3.x;
  const dy1 = p1.y - p2.y, dy2 = p3.y - p2.y, dy3 = p0.y - p1.y + p2.y - p3.y;
  const determinante = dx1 * dy2 - dx2 * dy1;
  if (Math.abs(determinante) < 1e-8) return null;
  const g = (dx3 * dy2 - dx2 * dy3) / determinante;
  const h = (dx1 * dy3 - dx3 * dy1) / determinante;
  return [p1.x - p0.x + g * p1.x, p3.x - p0.x + h * p3.x, p0.x,
    p1.y - p0.y + g * p1.y, p3.y - p0.y + h * p3.y, p0.y, g, h, 1];
}

export function projetarPonto(m, x, y) {
  const w = m[6] * x + m[7] * y + m[8];
  return { x: (m[0] * x + m[1] * y + m[2]) / w, y: (m[3] * x + m[4] * y + m[5]) / w };
}

export function validarTransformacao(t = {}) {
  if (t === null) return null;
  const animacao = validarAnimacao(t); if (animacao) return animacao;
  const limites = { x: [-2, 3], y: [-2, 3], escala: [0.05, 6], giro: [-360, 360], giroX: [-180, 180], giroY: [-180, 180], opacidade: [0, 1], brilho: [-1, 1], contraste: [-0.8, 1], saturacao: [-1, 1] };
  for (const [campo, [min, max]] of Object.entries(limites)) {
    if (t[campo] !== undefined && (!Number.isFinite(t[campo]) || t[campo] < min || t[campo] > max)) return `Ajuste inválido da camada: ${campo}.`;
  }
  if (t.mescla !== undefined && !MESCLAS_DE_CAMADA.includes(t.mescla)) return "Modo de mesclagem desconhecido.";
  if (t.filtro !== undefined && !FILTROS_DE_CAMADA.includes(t.filtro)) return "Filtro desconhecido.";
  return null;
}
