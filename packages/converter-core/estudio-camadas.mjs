import { receitaFfmpeg, duracaoDoClipe, duracaoTotal, tamanhoDoGif, FPS_DO_GIF, numero } from "./estudio.mjs";
import { filtroDaTransformacao, filtroDoFundo, sobrepor } from "./camadas-ffmpeg.mjs";

/** Caminho RGBA da receita; a receita antiga continua atendendo projetos sem camadas. */
export function receitaComCamadas(p, entradas) {
  const audio = receitaFfmpeg({...p, saida:"audio", textos:[]}, entradas);
  const args = [...audio.args], filtros = [audio.filtro];
  const total = duracaoTotal(p), w = p.largura, h = p.altura, fps = p.fps;
  let indice = p.clipes.length + p.audios.length, video = "cfundo", etapa = 0;
  filtros.push(filtroDoFundo(p.fundo,w,h,fps,total));
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
    const n = indice++, rotulo = `ce${i}`;
    filtros.push(`[${n}:v]fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,${filtroDaTransformacao(c.transformacao,w,h)},tpad=stop_mode=clone:stop_duration=${numero(duracao)},trim=duration=${numero(duracao)},setpts=PTS-STARTPTS+${numero(c.inicio)}/TB[${rotulo}]`);
    compor(rotulo,c.transformacao,c.inicio,c.fim);
  };
  camadas.filter(({c}) => (c.ordem ?? 1) < 0).forEach(extra);
  let inicio = 0;
  p.clipes.forEach((c,i) => {
    const duracao = duracaoDoClipe(c), rotulo = `cp${i}`;
    const fim = `setsar=1,${filtroDaTransformacao(c.transformacao,w,h)},setpts=PTS-STARTPTS+${numero(inicio)}/TB[${rotulo}]`;
    if (c.ajuste === "preencher") filtros.push(`[${i}:v]fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=increase,crop=${w}:${h},${fim}`);
    else if (c.ajuste === "caber") filtros.push(`[${i}:v]fps=${fps},format=rgba,scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${w}:${h}:(ow-iw)/2:(oh-ih)/2:color=black@0,${fim}`);
    else filtros.push(`[${i}:v]fps=${fps},format=rgba,split[cb${i}][cf${i}];[cb${i}]scale=${w}:${h}:force_original_aspect_ratio=increase,crop=${w}:${h},scale=${Math.max(2,Math.round(w/16)*2)}:${Math.max(2,Math.round(h/16)*2)},gblur=sigma=3,scale=${w}:${h}[cblur${i}];[cf${i}]scale=${w}:${h}:force_original_aspect_ratio=decrease:force_divisible_by=2[cfit${i}];[cblur${i}][cfit${i}]overlay=(W-w)/2:(H-h)/2:format=auto,${fim}`);
    compor(rotulo,c.transformacao,inicio,inicio+duracao);
    inicio += duracao;
  });
  camadas.filter(({c}) => (c.ordem ?? 1) >= 0).forEach(extra);
  p.textos.forEach((t,i) => {
    args.push("-loop","1","-framerate",String(fps),"-t",numero(total),"-i",entradas.textos[i]);
    const n = indice++, rotulo = `ct${i}`;
    filtros.push(`[${n}:v]${filtroDaTransformacao(t.transformacao,w,h)}[${rotulo}]`);
    compor(rotulo,t.transformacao,t.inicio,t.fim+1/fps);
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
