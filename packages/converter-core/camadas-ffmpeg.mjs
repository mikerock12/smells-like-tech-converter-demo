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

/**
 * Cor, opacidade e máscara no espaço da própria mídia (antes de girar), sem geq por pixel:
 * mixer/LUT para a cor e um quadro de máscara + alphamerge. Devolve o trecho que continua a
 * entrada; os segmentos auxiliares vão para `partes`. Espelho de StudioLayerFilters.cs.
 */
function corEMascaraNaOrigem(t, w, h, fps, rotulo, partes) {
  const cor = matrizDeCor(t);
  const cruzado = [1,2,4,6,8,9].some(i => cor[i] !== 0);
  let atual = "format=rgba";
  if (cruzado) {
    atual += `,colorchannelmixer=rr=${decimal(cor[0])}:rg=${decimal(cor[1])}:rb=${decimal(cor[2])}:gr=${decimal(cor[4])}:gg=${decimal(cor[5])}:gb=${decimal(cor[6])}:br=${decimal(cor[8])}:bg=${decimal(cor[9])}:bb=${decimal(cor[10])}:aa=${decimal(t.opacidade)}`;
    if (cor[3] !== 0 || cor[7] !== 0 || cor[11] !== 0) atual += `,lutrgb=r='clip(val+${decimal(cor[3])},0,255)':g='clip(val+${decimal(cor[7])},0,255)':b='clip(val+${decimal(cor[11])},0,255)'`;
  } else if (cor[0] !== 1 || cor[5] !== 1 || cor[10] !== 1 || cor[3] !== 0 || cor[7] !== 0 || cor[11] !== 0 || t.opacidade !== 1) {
    const alfa = t.opacidade !== 1 ? `:a='clip(val*${decimal(t.opacidade)},0,255)'` : "";
    atual += `,lutrgb=r='clip(${decimal(cor[0])}*val+${decimal(cor[3])},0,255)':g='clip(${decimal(cor[5])}*val+${decimal(cor[7])},0,255)':b='clip(${decimal(cor[10])}*val+${decimal(cor[11])},0,255)'${alfa}`;
  }
  if ((t.mascara ?? "nenhuma") !== "nenhuma") {
    const expr = t.mascara === "circulo" ? "lte(pow((X+0.5-W/2)/(min(W,H)/2),2)+pow((Y+0.5-H/2)/(min(W,H)/2),2),1)"
      : t.mascara === "oval" ? "lte(pow((X+0.5-W/2)/(W/2),2)+pow((Y+0.5-H/2)/(H/2),2),1)"
      : "lte(pow(max(abs(X+0.5-W/2)-(W/2-min(W,H)*0.15),0),2)+pow(max(abs(Y+0.5-H/2)-(H/2-min(W,H)*0.15),0),2),pow(min(W,H)*0.15,2))";
    partes.push(`${atual}[${rotulo}pre]`);
    // Um quadro de máscara basta: ao fim dele, o alphamerge repete o último.
    partes.push(`color=c=black:s=${w}x${h}:r=${fps}:d=${decimal(1/fps)},format=gray,geq=lum='255*(${expr})',format=gray[${rotulo}m]`);
    atual = `[${rotulo}pre][${rotulo}m]alphamerge`;
  }
  return atual;
}

/** GEQ é LGPL, ao contrário de perspective. O mapeamento inverso preserva transparência. */
export function filtroDaTransformacao(transformacao, w, h, deslocamento = 0, otimizar = false, rotulo = "transformacao", fps = 30) {
  const t = { ...TRANSFORMACAO_PADRAO, ...transformacao };
  if (otimizar) {
    const candidato = { ...t, mescla: "normal", removerFundo: false, mascara: t.mascara ?? "nenhuma", quadrosChave: t.quadrosChave ?? [], tempoDaAnimacao: 0 };
    const padrao = { ...TRANSFORMACAO_PADRAO, mascara: "nenhuma", quadrosChave: [], tempoDaAnimacao: 0 };
    const identidade = Object.keys(padrao).every(k => JSON.stringify(candidato[k]) === JSON.stringify(padrao[k]));
    if (identidade) return "format=rgba";
    if (t.giro === 0 && t.giroX === 0 && t.giroY === 0 && !t.espelharX && !t.espelharY &&
      (t.mascara ?? "nenhuma") === "nenhuma" && t.opacidade === 1 && t.filtro === "original" &&
      t.brilho === 0 && t.contraste === 0 && t.saturacao === 0) {
      const tempo = deslocamento ? `(n/${fps}+${decimal(deslocamento)})` : `(n/${fps})`;
      const ax = expressaoDoMovimento(t,"x",tempo), ay = expressaoDoMovimento(t,"y",tempo), az = expressaoDoMovimento(t,"escala",tempo);
      return `format=rgba,scale=w='max(2,trunc(iw*(${az})/2)*2)':h='max(2,trunc(ih*(${az})/2)*2)':eval=frame[${rotulo}zoom];`
        + `color=c=black@0:s=${w}x${h}:r=${fps},format=rgba[${rotulo}base];`
        + `[${rotulo}base][${rotulo}zoom]overlay=x='(W-w)/2+(${ax}-0.5)*W':y='(H-h)/2+(${ay}-0.5)*H':eval=frame:shortest=1,format=rgba`;
    }
  }
  if (otimizar && !(t.quadrosChave?.length) && (t.giroX !== 0 || t.giroY !== 0)) {
    // Giro 3D estático: cor e máscara no espaço da mídia, como no geq, e a perspectiva por um
    // mapa de coordenadas calculado num quadro só. Cada quadro vira uma consulta por pixel
    // (remap, LGPL), e não mais uma expressão por pixel e canal.
    const cantos = cantosDaCamada(w,h,w,h,t).map(p => ({x:p.x/w,y:p.y/h}));
    const m = inversa(homografia(cantos));
    if (!m) return "format=rgba,colorchannelmixer=aa=0";
    const partes = [];
    partes.push(`${corEMascaraNaOrigem(t, w, h, fps, rotulo, partes)}[${rotulo}src]`);
    const termo = j => `(${decimal(m[j])}*(X+0.5)/${w}+${decimal(m[j+1])}*(Y+0.5)/${h}+${decimal(m[j+2])})`;
    const posicao = `st(0,${w}*${termo(0)}/${termo(6)}-0.5);st(1,${h}*${termo(3)}/${termo(6)}-0.5);`;
    const dentro = `between(ld(0),-0.5,${w}-0.5)*between(ld(1),-0.5,${h}-0.5)`;
    const mapa = (eixo, ultimo, nome) =>
      `color=c=black:s=${w}x${h}:r=${fps}:d=${decimal(1/fps)},format=gray16le,geq=lum='${posicao}if(${dentro},clip(round(ld(${eixo})),0,${ultimo}),65535)',loop=loop=-1:size=1[${rotulo}${nome}]`;
    partes.push(mapa(0, w - 1, "mx"));
    partes.push(mapa(1, h - 1, "my"));
    partes.push(`[${rotulo}src][${rotulo}mx][${rotulo}my]remap=fill=black@0,format=rgba`);
    return partes.join(";");
  }
  if (otimizar && !(t.quadrosChave?.length) && t.giroX === 0 && t.giroY === 0) {
    // Transformação estática sem 3D: cor por mixer/LUT, máscara por um quadro + alphamerge,
    // giro planar pelo rotate e posição por overlay — tudo LGPL, sem geq por pixel.
    const partes = [];
    const atual = corEMascaraNaOrigem(t, w, h, fps, rotulo, partes);
    // O rotate já escreve no tamanho da tela (ow/oh); sem giro, o pad recoloca o conteúdo
    // redimensionado no centro da tela. O overlay só entra fora do centro.
    let cauda = atual;
    if (t.escala !== 1) cauda += `,scale=w='max(2,trunc(iw*(${decimal(t.escala)})/2)*2)':h='max(2,trunc(ih*(${decimal(t.escala)})/2)*2)'`;
    if (t.espelharX) cauda += ",hflip";
    if (t.espelharY) cauda += ",vflip";
    if (t.giro !== 0) cauda += `,rotate=a=${decimal(t.giro*Math.PI/180)}:ow=${w}:oh=${h}:fillcolor=black@0.0`;
    else if (t.escala !== 1) cauda += `,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0`;
    if (t.x === 0.5 && t.y === 0.5) {
      partes.push(`${cauda},format=rgba`);
      return partes.join(";");
    }
    partes.push(`${cauda}[${rotulo}z]`);
    partes.push(`color=c=black@0:s=${w}x${h}:r=${fps},format=rgba[${rotulo}b]`);
    partes.push(`[${rotulo}b][${rotulo}z]overlay=x=${decimal((t.x-0.5)*w)}:y=${decimal((t.y-0.5)*h)}:shortest=1,format=rgba`);
    return partes.join(";");
  }
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

export function filtroDoFundo(fundo, w, h, fps, duracao, otimizar = false) {
  const a = fundo?.cor ?? "#000000", b = fundo?.corFinal ?? a;
  const rgba = cor => [0,2,4,6].map((i) => i === 6 && cor.length === 7 ? 255 : parseInt(cor.slice(i+1,i+3),16));
  const inicio = rgba(a), fim = rgba(b);
  if (otimizar && inicio.every((v,i) => v === fim[i])) return `color=c=black:s=${w}x${h}:r=${fps}:d=${decimal(duracao)},format=rgba,lutrgb=r=${inicio[0]}:g=${inicio[1]}:b=${inicio[2]}:a=${inicio[3]}[cfundo]`;
  const canais = ["r","g","b","a"].map((c,i) => `${c}='${inicio[i]}+(${fim[i]-inicio[i]})*clip(((X+0.5)*W+(Y+0.5)*H)/(W*W+H*H),0,1)'`);
  return `color=c=black:s=${w}x${h}:r=${fps}:d=${decimal(duracao)},format=rgba,geq=${canais.join(":")},format=rgba[cfundo]`;
}
