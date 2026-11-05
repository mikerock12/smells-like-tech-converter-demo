/**
 * Efeitos de entrada, saída e contínuos de clipes, camadas e textos. Ficam na transformação da
 * mídia (`efeitoEntrada`, `duracaoEntrada`, `efeitoSaida`, `duracaoSaida`, `efeitoContinuo`).
 * Espelho de StudioEffects.cs (Windows e plugin) e Efeitos.kt (Android): a receita do FFmpeg
 * sai idêntica nos três, conferida pelos casos de `estudio-casos/`.
 *
 * Entrada e saída rodam só na sua janela de tempo (split + trim + concat): um efeito de meio
 * segundo não custa nada no resto do vídeo. Tudo com filtros LGPL, que o FFmpeg do Android tem.
 * `estadoDoEfeito` dá o mesmo movimento em números, para o canvas do site e os quadros parados.
 */
export const EFEITOS_DE_ENTRADA = Object.freeze([
  { id: "nenhum", titulo: "Nenhum" }, { id: "fade", titulo: "Surgir suave (fade in)" },
  { id: "cima", titulo: "Surgir de cima" }, { id: "baixo", titulo: "Surgir de baixo" },
  { id: "esquerda", titulo: "Surgir da esquerda" }, { id: "direita", titulo: "Surgir da direita" },
  { id: "zoom", titulo: "Crescer do nada" }, { id: "impacto", titulo: "Impacto (vem de perto e recua)" },
  { id: "giro", titulo: "Entrar girando" }, { id: "quicar", titulo: "Cair quicando" },
  { id: "estilhacos", titulo: "Montar de estilhaços" }, { id: "desintegrar", titulo: "Formar de partículas" },
  { id: "maquina", titulo: "Máquina de escrever" },
]);
export const EFEITOS_DE_SAIDA = Object.freeze([
  { id: "nenhum", titulo: "Nenhum" }, { id: "fade", titulo: "Esmaecer (fade out)" },
  { id: "cima", titulo: "Sair por cima" }, { id: "baixo", titulo: "Sair por baixo" },
  { id: "esquerda", titulo: "Sair pela esquerda" }, { id: "direita", titulo: "Sair pela direita" },
  { id: "zoom", titulo: "Encolher até sumir" }, { id: "impacto", titulo: "Crescer e sumir" },
  { id: "giro", titulo: "Sair girando" }, { id: "quicar", titulo: "Pular para fora" },
  { id: "estilhacos", titulo: "Estilhaçar" }, { id: "desintegrar", titulo: "Desintegrar" },
]);
export const EFEITOS_CONTINUOS = Object.freeze([
  { id: "nenhum", titulo: "Nenhum" }, { id: "girar", titulo: "Girar sem parar" },
  { id: "balancar", titulo: "Balançar" }, { id: "pulsar", titulo: "Pulsar" },
  { id: "coracao", titulo: "Bater como coração" }, { id: "tremer", titulo: "Tremer" },
  { id: "flutuar", titulo: "Flutuar" }, { id: "gelatina", titulo: "Gelatina" },
  { id: "arcoiris", titulo: "Arco-íris" },
]);

export const DURACAO_PADRAO_DO_EFEITO = 0.6;
const COLUNAS = 6, LINHAS = 6;

const n = v => { const r = Math.round(v * 1e6) / 1e6; return String(r === 0 ? 0 : r); };
const par = v => Math.max(2, Math.round(v / 2) * 2);
const ativo = id => Boolean(id) && id !== "nenhum";

/** Há efeito nesta transformação? */
export function temEfeitos(t) {
  return Boolean(t) && (ativo(t.efeitoEntrada) || ativo(t.efeitoSaida) || ativo(t.efeitoContinuo));
}

export function validarEfeitos(t) {
  const conhecido = (lista, id) => id === undefined || lista.some(e => e.id === id);
  if (!conhecido(EFEITOS_DE_ENTRADA, t.efeitoEntrada)) return "Efeito de entrada desconhecido.";
  if (!conhecido(EFEITOS_DE_SAIDA, t.efeitoSaida)) return "Efeito de saída desconhecido.";
  if (!conhecido(EFEITOS_CONTINUOS, t.efeitoContinuo)) return "Efeito contínuo desconhecido.";
  for (const d of [t.duracaoEntrada, t.duracaoSaida]) if (d !== undefined && (!Number.isFinite(d) || d < .1 || d > 10)) return "A duração do efeito vai de 0,1 a 10 segundos.";
  return null;
}

/** Quanto do tempo da mídia cada janela ocupa; a saída nunca invade a entrada. */
export function janelasDoEfeito(t, duracao) {
  const entrada = ativo(t?.efeitoEntrada) && t.efeitoEntrada !== "maquina" ? Math.min(t.duracaoEntrada ?? DURACAO_PADRAO_DO_EFEITO, duracao) : 0;
  const saida = ativo(t?.efeitoSaida) ? Math.min(t.duracaoSaida ?? DURACAO_PADRAO_DO_EFEITO, Math.max(0, duracao - entrada)) : 0;
  return { entrada, saida };
}

const suavizar = p => 1 - Math.pow(1 - p, 3);
const quique = r => Math.abs(Math.cos(7.854 * r)) * Math.pow(1 - r, 2);

/** Sorteio fixo de cada estilhaço (0 a 1): o mesmo resultado em todos os clientes. */
export const sorteio = (k, m) => ((k * 73 + m * 37 + 11) % 101) / 100;

/** Ruído por bloco das partículas, o mesmo do geq. */
export function ruidoDoBloco(x, y) {
  const v = Math.sin(x * 12.9898 + y * 78.233) * 43758.5453;
  return v - Math.floor(v);
}

function janelaNoEstado(e, tipo, p, entrada) {
  const q = entrada ? suavizar(p) : 1 - Math.pow(p, 3), r = entrada ? p : 1 - p;
  if (tipo === "fade") e.alfa *= entrada ? p : 1 - p;
  else if (tipo === "cima") e.dy -= 1 - q;
  else if (tipo === "baixo") e.dy += 1 - q;
  else if (tipo === "esquerda") e.dx -= 1 - q;
  else if (tipo === "direita") e.dx += 1 - q;
  else if (tipo === "zoom") { e.sx *= Math.max(.02, q); e.sy *= Math.max(.02, q); }
  else if (tipo === "impacto") { const s = 1 + 2 * (1 - q); e.sx *= s; e.sy *= s; e.alfa *= entrada ? p : 1 - p; }
  else if (tipo === "giro") { e.giro += 6.2832 * (1 - q); e.sx *= Math.max(.02, q); e.sy *= Math.max(.02, q); }
  else if (tipo === "quicar") e.dy -= quique(r);
  else if (tipo === "estilhacos") { e.pedacos = 1 - q; e.alfa *= entrada ? p : 1 - p; }
  else if (tipo === "desintegrar") e.particulas = q;
}

/**
 * O efeito num instante local da mídia: deslocamento em frações do quadro, escala, giro em
 * radianos, alfa, `pedacos` (0 = inteiro, 1 = estilhaços no ponto mais longe), `particulas`
 * (1 = inteiro, 0 = nada) e `matiz` em graus.
 */
export function estadoDoEfeito(t, tempo, duracao) {
  const e = { dx: 0, dy: 0, sx: 1, sy: 1, giro: 0, alfa: 1, pedacos: 0, particulas: 1, matiz: 0 };
  if (!temEfeitos(t)) return e;
  const c = t.efeitoContinuo, T = tempo;
  if (c === "girar") e.giro += Math.PI * T;
  else if (c === "balancar") e.giro += .26 * Math.sin(5.0265 * T);
  else if (c === "pulsar") { const s = 1 + .1 * Math.sin(7.5398 * T); e.sx *= s; e.sy *= s; }
  else if (c === "coracao") { const s = 1 + .18 * Math.pow(Math.max(0, Math.sin(6.2832 * T)), 4); e.sx *= s; e.sy *= s; }
  else if (c === "tremer") { e.dx += .012 * Math.sin(56.5487 * T); e.dy += .012 * Math.sin(69.115 * T + 1); }
  else if (c === "flutuar") e.dy += .025 * Math.sin(3.7699 * T);
  else if (c === "gelatina") { const v = .14 * Math.sin(9.4248 * T); e.sx *= 1 + v; e.sy *= 1 - v; }
  else if (c === "arcoiris") e.matiz = 120 * T;
  const { entrada, saida } = janelasDoEfeito(t, duracao);
  if (entrada > 0 && tempo < entrada) janelaNoEstado(e, t.efeitoEntrada, Math.max(0, tempo / entrada), true);
  else if (saida > 0 && tempo >= duracao - saida) janelaNoEstado(e, t.efeitoSaida, Math.min(1, (tempo - duracao + saida) / saida), false);
  return e;
}

/** Máquina de escrever: quantas imagens o texto tem e a taxa delas (uma letra ou mais por imagem). */
export function passosDaMaquina(texto, fps) {
  const letras = Array.from(texto.texto.trim()).length;
  const d = Math.max(.1, Math.min(texto.transformacao?.duracaoEntrada ?? DURACAO_PADRAO_DO_EFEITO, texto.fim - texto.inicio));
  const passos = Math.max(1, Math.min(letras, Math.round(d * fps), 120));
  return { letras, passos, duracao: d, taxa: passos / d };
}

/** Letras visíveis no passo `k` (de 1 a passos). */
export const letrasNoPasso = (letras, passos, k) => Math.ceil(k * letras / passos);

export const usaMaquina = texto => texto.transformacao?.efeitoEntrada === "maquina";

/** Letras visíveis de um texto com máquina de escrever num instante local. */
export function letrasVisiveis(texto, tempo, fps) {
  const { letras, passos, taxa } = passosDaMaquina(texto, fps);
  return letrasNoPasso(letras, passos, Math.max(1, Math.min(passos, Math.floor(tempo * taxa) + 1)));
}

/**
 * Move, gira e redimensiona o quadro inteiro em volta do ponto (px, py). Gira primeiro (tamanho
 * fixo, numa tela que comporta qualquer ângulo) e redimensiona depois: o escalonamento por
 * quadro muda o tamanho, e o overlay é quem aceita isso. O overlay devolve ao lugar. As
 * expressões usam o tempo `t`: o `n` do overlay começa em 1 e o dos outros filtros, em 0.
 */
function movimento({ dx = "0", dy = "0", sx = null, sy = null, giro = null }, w, h, fps, px, py, r) {
  const cadeia = [];
  if (giro) { const d = par(Math.ceil(Math.hypot(w, h))); cadeia.push(`rotate=a='${giro}':ow=${d}:oh=${d}:fillcolor=black@0`); }
  if (sx) cadeia.push(`scale=w='max(2,trunc(iw*(${sx})/2)*2)':h='max(2,trunc(ih*(${sy})/2)*2)':eval=frame`);
  if (!cadeia.length) cadeia.push("setsar=1");
  let x = dx, y = dy;
  if (giro || sx) {
    const cx = n(w / 2 - px), cy = n(h / 2 - py);
    const vx = giro ? `(${cx}*cos(${giro})-(${cy})*sin(${giro}))` : cx, vy = giro ? `(${cx}*sin(${giro})+(${cy})*cos(${giro}))` : cy;
    x = `${n(px)}+(${dx})+(${sx ?? 1})*(${vx})-w/2`;
    y = `${n(py)}+(${dy})+(${sy ?? 1})*(${vy})-h/2`;
  }
  return `${cadeia.join(",")}[${r}z];color=c=black@0:s=${w}x${h}:r=${fps},format=rgba[${r}b];`
    + `[${r}b][${r}z]overlay=x='${x}':y='${y}':eval=frame:format=auto:shortest=1,format=rgba`;
}

function filtroContinuo(tipo, w, h, fps, px, py, r) {
  const T = "t";
  const m = (opcoes) => movimento(opcoes, w, h, fps, px, py, r);
  switch (tipo) {
    case "girar": return m({ giro: `PI*${T}` });
    case "balancar": return m({ giro: `0.26*sin(5.0265*${T})` });
    case "pulsar": { const s = `(1+0.1*sin(7.5398*${T}))`; return m({ sx: s, sy: s }); }
    case "coracao": { const s = `(1+0.18*pow(max(0,sin(6.2832*${T})),4))`; return m({ sx: s, sy: s }); }
    case "tremer": return m({ dx: `${n(.012 * w)}*sin(56.5487*${T})`, dy: `${n(.012 * h)}*sin(69.115*${T}+1)` });
    case "flutuar": return m({ dy: `${n(.025 * h)}*sin(3.7699*${T})` });
    case "gelatina": return m({ sx: `(1+0.14*sin(9.4248*${T}))`, sy: `(1-0.14*sin(9.4248*${T}))` });
    default: return `format=gbrap,hue=h='120*t',format=rgba`;
  }
}

/** A grade de estilhaços do quadro: recorte, centro, destino em pixels e giro de cada pedaço. */
export function pedacosDoQuadro(w, h, px, py) {
  const tw = par(Math.ceil(w / COLUNAS)), th = par(Math.ceil(h / LINHAS)), alcance = 1.2 * Math.max(w, h), lista = [];
  for (let k = 0; k < COLUNAS * LINHAS; k++) {
    const x0 = (k % COLUNAS) * tw, y0 = Math.floor(k / COLUNAS) * th;
    const cw = Math.min(tw, w - x0), ch = Math.min(th, h - y0);
    if (cw <= 0 || ch <= 0) { lista.push(null); continue; }
    const cx = x0 + cw / 2, cy = y0 + ch / 2, ax = cx - px, ay = cy - py, d = Math.max(1, Math.hypot(ax, ay));
    const dist = alcance * (.5 + sorteio(k, 1));
    lista.push({ x0, y0, cw, ch, cx, cy, vx: ax / d * dist, vy: ay / d * dist, volta: (sorteio(k, 2) - .5) * 12.5664 });
  }
  return { tw, th, lista };
}

function estilhacos(q, w, h, fps, px, py, r) {
  const { tw, th, lista } = pedacosDoQuadro(w, h, px, py), total = COLUNAS * LINHAS;
  const lado = par(Math.ceil(Math.hypot(tw, th)));
  const partes = [`split=${total}${Array.from({ length: total }, (_, k) => `[${r}p${k}]`).join("")}`];
  partes.push(`color=c=black@0:s=${w}x${h}:r=${fps},format=rgba[${r}b0]`);
  lista.forEach((p, k) => {
    if (!p) { partes.push(`[${r}p${k}]nullsink`); return; }
    const ultimo = k === total - 1;
    partes.push(`[${r}p${k}]crop=${p.cw}:${p.ch}:${p.x0}:${p.y0},rotate=a='${n(p.volta)}*(1-${q})':ow=${lado}:oh=${lado}:fillcolor=black@0[${r}t${k}]`);
    partes.push(`[${r}b${k}][${r}t${k}]overlay=x='${n(p.cx)}+(${n(p.vx)})*(1-${q})-w/2':y='${n(p.cy)}+(${n(p.vy)})*(1-${q})-h/2':eval=frame:format=auto:shortest=1${ultimo ? "" : `[${r}b${k + 1}]`}`);
  });
  return partes.join(";");
}

/** A grade de blocos das partículas. */
export function blocosDoQuadro(w, h) {
  const b = Math.max(2, Math.round(Math.min(w, h) / 90));
  return { mw: Math.ceil(w / b), mh: Math.ceil(h / b) };
}

function particulas(q, w, h, fps, d, r) {
  const { mw, mh } = blocosDoQuadro(w, h);
  return `split[${r}a][${r}c];[${r}a]alphaextract[${r}l];`
    + `color=c=black:s=${mw}x${mh}:r=${fps}:d=${n(d)},format=gray,geq=lum='255*lt(mod(sin(X*12.9898+Y*78.233)*43758.5453,1)*0.7+X/W*0.3,${q})',scale=${w}:${h}:flags=neighbor,format=gray[${r}m];`
    + `[${r}l][${r}m]blend=all_mode=multiply:shortest=1[${r}n];[${r}c][${r}n]alphamerge`;
}

function filtroDaJanela(tipo, entrada, d, w, h, fps, px, py, r) {
  const P = `clip(t/${n(d)},0,1)`;
  const Q = entrada ? `(1-pow(1-${P},3))` : `(1-pow(${P},3))`, R = entrada ? P : `(1-${P})`;
  const fade = `fade=t=${entrada ? "in" : "out"}:st=0:d=${n(d)}:alpha=1`;
  const m = (opcoes) => movimento(opcoes, w, h, fps, px, py, r);
  switch (tipo) {
    case "fade": return fade;
    case "cima": return m({ dy: `-${h}*(1-${Q})` });
    case "baixo": return m({ dy: `${h}*(1-${Q})` });
    case "esquerda": return m({ dx: `-${w}*(1-${Q})` });
    case "direita": return m({ dx: `${w}*(1-${Q})` });
    case "zoom": { const s = `max(0.02,${Q})`; return m({ sx: s, sy: s }); }
    case "impacto": { const s = `(1+2*(1-${Q}))`; return `${m({ sx: s, sy: s })},${fade}`; }
    case "giro": { const s = `max(0.02,${Q})`; return m({ giro: `6.2832*(1-${Q})`, sx: s, sy: s }); }
    case "quicar": return m({ dy: `-${h}*abs(cos(7.854*${R}))*pow(1-${R},2)` });
    case "estilhacos": return `${estilhacos(Q, w, h, fps, px, py, r)},format=rgba,${fade}`;
    default: { const G = `clip(T/${n(d)},0,1)`; return `${particulas(entrada ? `(1-pow(1-${G},3))` : `(1-pow(${G},3))`, w, h, fps, d, r)},format=rgba`; }
  }
}

/**
 * A cadeia dos efeitos, para vir logo depois da transformação; começa com vírgula, ou é vazia.
 * `pivo` é o centro do efeito em frações do quadro. O fluxo tem de durar exatamente `duracao`.
 */
export function filtroDosEfeitos(t, w, h, fps, duracao, rotulo, pivo = null) {
  if (!temEfeitos(t)) return "";
  const px = (pivo?.x ?? t.x ?? .5) * w, py = (pivo?.y ?? t.y ?? .5) * h;
  let s = "";
  if (ativo(t.efeitoContinuo)) s += `,setpts=PTS-STARTPTS,${filtroContinuo(t.efeitoContinuo, w, h, fps, px, py, `${rotulo}c`)}`;
  const { entrada, saida } = janelasDoEfeito(t, duracao);
  if (entrada <= 0 && saida <= 0) return s;
  const meio = duracao - entrada - saida > 1e-6, partes = [];
  const fim = duracao - saida;
  if (entrada > 0) partes.push({ r: "e", trim: `end=${n(entrada)}`, cadeia: `,${filtroDaJanela(t.efeitoEntrada, true, entrada, w, h, fps, px, py, `${rotulo}e`)}` });
  if (meio) partes.push({ r: "m", trim: `start=${n(entrada)}:end=${n(fim)}`, cadeia: "" });
  if (saida > 0) partes.push({ r: "s", trim: `start=${n(fim)}`, cadeia: `,${filtroDaJanela(t.efeitoSaida, false, saida, w, h, fps, px, py, `${rotulo}s`)}` });
  s += ",setpts=PTS-STARTPTS";
  if (partes.length === 1) return `${s}${partes[0].cadeia}`;
  s += `,split=${partes.length}${partes.map(p => `[${rotulo}w${p.r}]`).join("")}`;
  for (const p of partes) s += `;[${rotulo}w${p.r}]trim=${p.trim},setpts=PTS-STARTPTS${p.cadeia},setsar=1[${rotulo}x${p.r}]`;
  return `${s};${partes.map(p => `[${rotulo}x${p.r}]`).join("")}concat=n=${partes.length}:v=1:a=0`;
}
