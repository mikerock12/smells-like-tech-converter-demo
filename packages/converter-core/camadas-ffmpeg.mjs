import { cantosDaCamada, homografia, matrizDeCor, TRANSFORMACAO_PADRAO } from "./camadas.mjs";

// Nove casas bastam para coordenadas normalizadas em quadros de até 4096 px.
export const decimal = v => String(Math.round(v * 1e9) / 1e9);

export function inversa(m) {
  if (!m) return null;
  const [a,b,c,d,e,f,g,h,i] = m;
  const cof = [e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d];
  const det = a*cof[0]+b*cof[3]+c*cof[6];
  return Math.abs(det) < 1e-8 ? null : cof.map(v => v/det);
}

/** GEQ é LGPL, ao contrário de perspective. O mapeamento inverso preserva transparência. */
export function filtroDaTransformacao(transformacao, w, h) {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  const cantos = cantosDaCamada(w,h,w,h,t).map(p => ({x:p.x/w,y:p.y/h}));
  const m = inversa(homografia(cantos));
  if (!m) return "format=rgba,colorchannelmixer=aa=0";
  const termo = j => `(${decimal(m[j])}*(X+0.5)/W+${decimal(m[j+1])}*(Y+0.5)/H+${decimal(m[j+2])})`;
  const x = `(W*${termo(0)}/${termo(6)}-0.5)`, y = `(H*${termo(3)}/${termo(6)}-0.5)`;
  const cor = matrizDeCor(t);
  const canais = ["r","g","b"];
  // st/ld evita recalcular a homografia para cada componente da matriz de cor.
  const posicao = `st(0,${x});st(1,${y});`;
  const rgb = canais.map((c,i) => `${c}='${posicao}${canais.map((fonte,j) => `${decimal(cor[i*4+j])}*${fonte}(ld(0),ld(1))`).join("+")}+${decimal(cor[i*4+3])}'`);
  return `format=gbrap,geq=${rgb.join(":")}:a='${posicao}if(between(ld(0),-0.5,W-0.5)*between(ld(1),-0.5,H-0.5),alpha(ld(0),ld(1))*${decimal(t.opacidade)},0)':interpolation=bilinear,format=rgba`;
}

/** Composição com modos de mesclagem e alfa de ambas as superfícies. */
export function sobrepor(fundo, frente, saida, mescla, inicio, fim) {
  const ativo = `gte(t,${decimal(inicio)})*lt(t,${decimal(fim)})`;
  const final = (f) => `[${fundo}][${f}]overlay=0:0:format=auto:enable='${ativo}'[${saida}]`;
  if (!mescla || mescla === "normal") return final(frente);
  const modo = {multiplicar:"multiply",clarear:"screen",sobrepor:"overlay"}[mescla];
  const p = saida;
  return `[${fundo}]split=3[${p}b0][${p}b1][${p}b2];[${frente}]split=3[${p}f0][${p}f1][${p}f2];`
    + `[${p}b0][${p}f0]blend=all_mode=${modo}:shortest=0[${p}rgb];`
    + `[${p}b1]alphaextract,format=gbrp[${p}ba];[${p}f1]alphaextract[${p}fa];`
    + `[${p}f2][${p}rgb][${p}ba]maskedmerge[${p}mix];[${p}mix][${p}fa]alphamerge[${p}fg];`
    + `[${p}b2][${p}fg]overlay=0:0:format=auto:enable='${ativo}'[${saida}]`;
}

export function filtroDoFundo(fundo, w, h, fps, duracao) {
  const a = fundo?.cor ?? "#000000", b = fundo?.corFinal ?? a;
  const rgba = cor => [0,2,4,6].map((i) => i === 6 && cor.length === 7 ? 255 : parseInt(cor.slice(i+1,i+3),16));
  const inicio = rgba(a), fim = rgba(b);
  const canais = ["r","g","b","a"].map((c,i) => `${c}='${inicio[i]}+(${fim[i]-inicio[i]})*clip(((X+0.5)*W+(Y+0.5)*H)/(W*W+H*H),0,1)'`);
  return `color=c=black:s=${w}x${h}:r=${fps}:d=${decimal(duracao)},format=rgba,geq=${canais.join(":")},format=rgba[cfundo]`;
}
