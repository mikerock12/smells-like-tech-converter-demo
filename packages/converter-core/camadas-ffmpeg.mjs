import { expressaoDoMovimento } from "./estudio-animacao.mjs";
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
export function filtroDaTransformacao(transformacao, w, h, deslocamento = 0) {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  const cantos = cantosDaCamada(w,h,w,h,t).map(p => ({x:p.x/w,y:p.y/h}));
  const m = inversa(homografia(cantos));
  if (!m) return "format=rgba,colorchannelmixer=aa=0";
  let u = "(X+0.5)/W", v = "(Y+0.5)/H", prefixo = "";
  if (t.quadrosChave?.length) {
    const tempo = deslocamento ? `(T+${decimal(deslocamento)})` : "T";
    const x = expressaoDoMovimento(t,"x",tempo), y = expressaoDoMovimento(t,"y",tempo), z = expressaoDoMovimento(t,"escala",tempo);
    prefixo = `st(2,${x});st(3,${y});st(4,${decimal(t.escala)}/(${z}));st(5,(${u}-ld(2))*ld(4)+${decimal(t.x)});st(6,(${v}-ld(3))*ld(4)+${decimal(t.y)});`;
    u = "ld(5)"; v = "ld(6)";
  }
  const termo = j => `(${decimal(m[j])}*${u}+${decimal(m[j+1])}*${v}+${decimal(m[j+2])})`;
  const animada = Boolean(t.quadrosChave?.length);
  const divisor = animada ? "ld(7)" : termo(6);
  if(animada) prefixo += `st(7,${termo(6)});`;
  const x = `(W*${termo(0)}/${divisor}-0.5)`, y = `(H*${termo(3)}/${divisor}-0.5)`;
  const cor = matrizDeCor(t);
  const canais = ["r","g","b"];
  // st/ld evita recalcular a homografia para cada componente da matriz de cor.
  const posicao = `${prefixo}st(0,${x});st(1,${y});`;
  const rgb = canais.map((c,i) => `${c}='${posicao}${canais.map((fonte,j) => !animada || cor[i*4+j] !== 0 ? `${decimal(cor[i*4+j])}*${fonte}(ld(0),ld(1))` : "").filter(Boolean).join("+") || "0"}+${decimal(cor[i*4+3])}'`);
  const mascara = t.mascara === "circulo" ? `lte(pow((ld(0)+0.5-W/2)/(min(W,H)/2),2)+pow((ld(1)+0.5-H/2)/(min(W,H)/2),2),1)`
    : t.mascara === "oval" ? `lte(pow((ld(0)+0.5-W/2)/(W/2),2)+pow((ld(1)+0.5-H/2)/(H/2),2),1)`
    : t.mascara === "arredondada" ? `lte(pow(max(abs(ld(0)+0.5-W/2)-(W/2-min(W,H)*0.15),0),2)+pow(max(abs(ld(1)+0.5-H/2)-(H/2-min(W,H)*0.15),0),2),pow(min(W,H)*0.15,2))` : "1";
  const alfa = t.mascara && t.mascara !== "nenhuma" ? `*${mascara}` : "";
  return `format=gbrap,geq=${rgb.join(":")}:a='${posicao}if(between(ld(0),-0.5,W-0.5)*between(ld(1),-0.5,H-0.5),alpha(ld(0),ld(1))*${decimal(t.opacidade)}${alfa},0)':interpolation=bilinear,format=rgba`;
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
