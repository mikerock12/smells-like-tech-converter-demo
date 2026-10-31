import { receitaFfmpeg, duracaoDoClipe, duracaoTotal, tamanhoDoGif, FPS_DO_GIF, numero, temTransicoes, segmentos, duracaoDaTransicao, filtroDeVelocidade } from "./estudio.mjs";
import { filtroDaTransformacao, filtroDoFundo, sobrepor } from "./camadas-ffmpeg.mjs";

/** Caminho RGBA da receita; a receita antiga continua atendendo projetos sem camadas. */
export function receitaComCamadas(p, entradas, otimizar = false) {
  const audio = receitaFfmpeg({...p, saida:"audio", textos:[]}, entradas);
  const args = [...audio.args], filtros = [audio.filtro];
  const total = duracaoTotal(p), w = p.largura, h = p.altura, fps = p.fps;
  let indice = p.clipes.length + p.audios.length, video = "cfundo", etapa = 0;
  const transicoes=temTransicoes(p), lista=segmentos(p);
  if(!transicoes) filtros.push(filtroDoFundo(p.fundo,w,h,fps,total,otimizar));
  const compor = (rotulo, t, inicio, fim) => {
    const proximo = `cc${etapa++}`;
    filtros.push(sobrepor(video,rotulo,proximo,t?.mescla,inicio,fim));
    video = proximo;
  };
  const camadas = (p.camadas ?? []).map((c,i) => ({c,i})).filter(({c}) => c.visivel !== false)
    .sort((a,b) => (a.c.ordem ?? 1)-(b.c.ordem ?? 1));
  const extra = ({c,i}) => {
    const duracao = c.fim-c.inicio;
    if (c.tipo === "imagem") args.push("-loop","1","-framerate",String(fps));
    else {
      if (c.repetir !== false) args.push("-stream_loop","-1");
      if (c.tipo === "gif") args.push("-ignore_loop","1");
      args.push("-ss",numero(c.deslocamento ?? 0));
    }
    args.push("-t",numero(duracao),"-i",entradas.camadas[i]);
    const pad = `tpad=stop_mode=clone:stop_duration=${numero(duracao)},trim=duration=${numero(duracao)},`;
    const animada = Boolean(c.transformacao?.quadrosChave?.length);
    const n = indice++, rotulo = `ce${i}`;
    filtros.push(`[${n}:v]fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${animada?pad:""}${filtroDaTransformacao(c.transformacao,w,h,0,otimizar)},${animada?"":pad}setpts=PTS-STARTPTS+${numero(c.inicio)}/TB[${rotulo}]`);
    compor(rotulo,c.transformacao,c.inicio,c.fim);
  };
  if(!transicoes) camadas.filter(({c}) => (c.ordem ?? 1) < 0).forEach(extra);
  const fundoLocal=(i,inicio,duracao)=>{
    let base=`cbglocal${i}`, etapaLocal=0;
    filtros.push(filtroDoFundo(p.fundo,w,h,fps,duracao,otimizar).replace("[cfundo]",`[${base}]`));
    for(const {c, i:j} of camadas.filter(({c})=>(c.ordem??1)<0)) {
      const de=Math.max(inicio,c.inicio),ate=Math.min(inicio+duracao,c.fim);
      if(ate<=de) continue;
      const antes=de-c.inicio, tempo=ate-de, necessario=antes+tempo;
      if(c.tipo==="imagem") args.push("-loop","1","-framerate",String(fps));
      else {if(c.repetir!==false) args.push("-stream_loop","-1");if(c.tipo==="gif") args.push("-ignore_loop","1");args.push("-ss",numero(c.deslocamento??0));}
      args.push("-t",numero(necessario),"-i",entradas.camadas[j]);
      const n=indice++, rotulo=`cbl${i}_${j}`,proximo=`cblbase${i}_${etapaLocal++}`;
      filtros.push(`[${n}:v]fps=${fps},tpad=stop_mode=clone:stop_duration=${numero(necessario)},trim=start=${numero(antes)}:duration=${numero(tempo)},setpts=PTS-STARTPTS,format=rgba,scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${filtroDaTransformacao(c.transformacao,w,h,antes,otimizar)},setpts=PTS-STARTPTS+${numero(de-inicio)}/TB[${rotulo}]`);
      filtros.push(sobrepor(base,rotulo,proximo,c.transformacao?.mescla,de-inicio,ate-inicio));base=proximo;
    }
    return base;
  };
  let inicio = 0;

  p.clipes.forEach((c,i) => {
    inicio=lista[i].inicio;
    const original = duracaoDoClipe(c), duracao = i === p.clipes.length-1 ? Math.max(original,total-inicio) : original, rotulo = `cp${i}`;
    const estender = duracao > original ? `tpad=stop_mode=clone:stop_duration=${numero(duracao-original)},trim=duration=${numero(duracao)},` : "";
    const fim = `setsar=1,${estender}${filtroDaTransformacao(c.transformacao,w,h,0,otimizar)},setpts=PTS-STARTPTS${transicoes?",format=gbrap,settb=AVTB":"+"+numero(inicio)+"/TB"}[${rotulo}]`;
    if (c.ajuste === "preencher") filtros.push(`[${i}:v]${filtroDeVelocidade(c)}fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=increase,crop=${w}:${h},${fim}`);
    else if (c.ajuste === "caber") filtros.push(`[${i}:v]${filtroDeVelocidade(c)}fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0,${fim}`);
    else filtros.push(`[${i}:v]${filtroDeVelocidade(c)}fps=${fps},format=rgba,split[cb${i}][cf${i}];[cb${i}]scale=${w}:${h}:force_original_aspect_ratio=increase,crop=${w}:${h},scale=${Math.max(2,Math.round(w/16)*2)}:${Math.max(2,Math.round(h/16)*2)},gblur=sigma=3,scale=${w}:${h}[cblur${i}];[cf${i}]scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2[cfit${i}];[cblur${i}][cfit${i}]overlay=(W-w)/2:(H-h)/2:format=auto,${fim}`);
    if (!transicoes) compor(rotulo,c.transformacao,inicio,inicio+duracao);
    else {
      const base=fundoLocal(i,inicio,duracao);
      filtros.push(sobrepor(base,rotulo,`cq${i}`,c.transformacao?.mescla,0,duracao));
      filtros.push(`[cq${i}]format=gbrap,settb=AVTB[cqf${i}]`);
    }
    inicio += duracao;
  });
  if (transicoes) {
    let anterior="cqf0";
    for(let i=1;i<p.clipes.length;i++) {
      const d=duracaoDaTransicao(p,i-1), proximo=`cx${i}`;
      const tipo={fade:"custom:expr='if(eq(PLANE,3),255,if(gte(P,0.5),A*(2*P-1),B*(1-2*P)))'",dissolver:"fade",deslizar:"slideleft",zoom:"zoomin"}[p.clipes[i-1].transicao?.tipo];
      filtros.push(d>0?`[${anterior}][cqf${i}]xfade=transition=${tipo}:duration=${numero(d)}:offset=${numero(lista[i].inicio)}[${proximo}]`
        :`[${anterior}][cqf${i}]concat=n=2:v=1:a=0[${proximo}]`);
      anterior=proximo;
    }
    video=anterior;
  }
  camadas.filter(({c}) => (c.ordem ?? 1) >= 0).forEach(extra);
  p.textos.forEach((t,i) => {
    args.push("-loop","1","-framerate",String(fps),"-t",numero(total),"-i",entradas.textos[i]);
    const n = indice++, rotulo = `ct${i}`;
    filtros.push(`[${n}:v]${filtroDaTransformacao(t.transformacao,w,h,-t.inicio,otimizar)}[${rotulo}]`);
    compor(rotulo,t.transformacao,t.inicio,t.fim+((t.id.startsWith("legenda-") || t.id.startsWith("contagem-"))?0:1/fps));
  });
  if (p.saida === "gif") {
    const gif = tamanhoDoGif(w,h);
    filtros.push(`[${video}]fps=${FPS_DO_GIF},scale=${gif.largura}:${gif.altura}:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]`);
    video = "gif";
  }
  const mapas = ["-map",`[${video}]`];
  if (p.saida === "video") mapas.push(...audio.mapas);
  else filtros.push(`${audio.mapas[1]}anullsink`);
  return {args,filtro:filtros.join(";"),mapas,duracao:total};
}
