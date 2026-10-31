/**
 * Estúdio: o projeto que vira vídeo, GIF, imagem, áudio ou texto para as redes.
 *
 * Fonte única das regras para os quatro clientes (ver docs/estudio.md). O site usa este
 * módulo direto; o aplicativo Windows, o plugin e o Android reimplementam a mesma lógica em
 * C# e Kotlin, conferida pelos casos de `estudio-casos/` (a receita do FFmpeg tem de sair
 * idêntica, caractere por caractere). Os presets vêm de `estudio-presets.json`, copiado no
 * build dos outros apps. JS puro: nada de DOM, roda igual no navegador e no `node --test`.
 */
import dados from "./estudio-presets.json" with { type: "json" };
import { validarTransformacao } from "./camadas.mjs";
import { receitaComCamadas } from "./estudio-camadas.mjs";

export const VERSAO_DO_PROJETO = 4;
export const TRANSICOES = Object.freeze([
  {id:"corte",titulo:"Corte seco"}, {id:"fade",titulo:"Fade pelo preto"},
  {id:"dissolver",titulo:"Dissolver"}, {id:"deslizar",titulo:"Deslizar"}, {id:"zoom",titulo:"Zoom"},
]);
export function velocidadeDoClipe(c) { const v=c.velocidade??1; return c.tipo === "imagem" || !Number.isFinite(v) || v<.25 || v>4 ? 1 : v; }
export function duracaoDaOrigem(c) { return Math.max(DURACAO_MINIMA, (c.fim ?? 0) - (c.inicio ?? 0)); }
/** Saída do clipe; limita a metade dos vizinhos para não haver três imagens simultâneas. */
export function duracaoDaTransicao(p,i) {
  const c=p.clipes[i], seguinte=p.clipes[i+1];
  if (!c?.transicao || !seguinte || c.transicao.tipo === "corte") return 0;
  return arredondar(Math.max(0, Math.min(c.transicao.duracao ?? .5, duracaoDoClipe(c)/2, duracaoDoClipe(seguinte)/2)));
}
export function temTransicoes(p) { return p.clipes.some((_,i)=>duracaoDaTransicao(p,i)>0); }
export function filtroDeVelocidade(c) { const v=velocidadeDoClipe(c); return v===1?"":`setpts=(PTS-STARTPTS)/${numero(v)},`; }
export function filtroDeTempoAudio(c) {
  let v=velocidadeDoClipe(c); const lista=[];
  if (v===1) return "";
  while(v>2) {lista.push("atempo=2");v/=2;}
  while(v<.5) {lista.push("atempo=0.5");v*=2;}
  lista.push(`atempo=${numero(v)}`); return lista.join(",")+",";
}

/** Destinos prontos (rede · formato), na ordem em que aparecem para escolher. */
export const PRESETS = Object.freeze(dados.presets.map((preset) => Object.freeze({ ...preset })));

export const NOTA_DOS_PRESETS = dados.nota;

/** O que o estúdio entrega no fim. */
export const SAIDAS = Object.freeze([
  { id: "video", titulo: "Vídeo", descricao: "MP4 pronto para postar, com som.", extensao: "mp4" },
  { id: "gif", titulo: "GIF", descricao: "Animação leve, sem som, que toca sozinha.", extensao: "gif" },
  { id: "imagem", titulo: "Imagem", descricao: "Um quadro em PNG: capa, post ou miniatura.", extensao: "png" },
  { id: "audio", titulo: "Só o áudio", descricao: "MP3 com a mistura final do som.", extensao: "mp3" },
  { id: "texto", titulo: "Texto", descricao: "A fala transcrita, sem enviar nada para a nuvem.", extensao: "txt" },
]);

/** Como um vídeo ou imagem ocupa o quadro do destino. */
export const AJUSTES = Object.freeze([
  { id: "preencher", titulo: "Preencher", descricao: "Ocupa o quadro todo; corta as sobras." },
  { id: "caber", titulo: "Caber", descricao: "Mostra tudo, com faixas pretas." },
  { id: "caber-desfocado", titulo: "Caber com fundo desfocado", descricao: "Mostra tudo; as sobras viram a própria imagem desfocada." },
]);

/** Fontes do texto: as mesmas das figurinhas, com as licenças junto dos arquivos. */
export const FONTES = Object.freeze([
  { id: "padrao", nome: "Padrão", arquivo: null },
  { id: "anton", nome: "Impacto", arquivo: "Anton-Regular.ttf" },
  { id: "bangers", nome: "Quadrinho", arquivo: "Bangers-Regular.ttf" },
  { id: "pacifico", nome: "Cursiva", arquivo: "Pacifico-Regular.ttf" },
  { id: "marcador", nome: "Marcador", arquivo: "PermanentMarker-Regular.ttf" },
]);

/**
 * Como o texto é desenhado, igual em todas as plataformas (frações do tamanho da letra,
 * que é `tamanho` × altura do quadro): quebra de linha em 90% da largura, linhas
 * centralizadas, contorno atrás do preenchimento e caixa de fundo com cantos arredondados.
 */
export const LAYOUT_DO_TEXTO = Object.freeze({
  larguraMaxima: 0.9,
  entrelinha: 1.15,
  contorno: 1 / 8,
  margemDaCaixa: 0.3,
  raioDaCaixa: 0.25,
});

export const DURACAO_PADRAO_DA_IMAGEM = 3;
export const DURACAO_MINIMA = 0.1;
/** Lado menor do GIF e a taxa de quadros: GIF maior que isso fica pesado demais. */
export const LADO_DO_GIF = 480;
export const FPS_DO_GIF = 15;
/** Acima disso o site manda o projeto ao plugin (ou avisa que pode demorar). */
export const LIMITE_DO_NAVEGADOR = Object.freeze({ segundos: 180, pixels: 1920 * 1080 });

export function presetPorId(id) {
  return PRESETS.find((preset) => preset.id === id) ?? null;
}

export function saidaPorId(id) {
  return SAIDAS.find((saida) => saida.id === id) ?? null;
}

/** Um projeto vazio para o destino e a saída escolhidos no assistente. */
export function novoProjeto(destino, saida = "video", tamanho = null) {
  const preset = presetPorId(destino) ?? presetPorId("personalizado");
  const largura = par(tamanho?.largura ?? preset.largura);
  const altura = par(tamanho?.altura ?? preset.altura);
  return {
    versao: VERSAO_DO_PROJETO,
    destino: preset.id,
    largura,
    altura,
    fps: preset.fps,
    saida: saidaPorId(saida) ? saida : "video",
    clipes: [],
    textos: [],
    audios: [],
    quadro: 0,
  };
}

/** Quanto um clipe ocupa na linha do tempo. */
export function duracaoDoClipe(clipe) {
  if (clipe.tipo === "imagem") return Math.max(DURACAO_MINIMA, clipe.duracao ?? DURACAO_PADRAO_DA_IMAGEM);
  return duracaoDaOrigem(clipe) / velocidadeDoClipe(clipe);
}

/** A faixa principal em ordem, com o começo e o fim de cada clipe na linha do tempo. */
export function segmentos(projeto) {
  let agora = 0;
  return projeto.clipes.map((clipe, indice) => {
    if (indice > 0) agora -= duracaoDaTransicao(projeto, indice - 1);
    const duracao = duracaoDoClipe(clipe);
    const segmento = { indice, id: clipe.id, inicio: arredondar(agora), fim: arredondar(agora + duracao), duracao: arredondar(duracao) };
    agora += duracao;
    return segmento;
  });
}

export function duracaoTotal(projeto) {
  if (projeto.versao < 4) return segmentos(projeto).at(-1)?.fim ?? 0;
  return arredondar(Math.max(segmentos(projeto).at(-1)?.fim ?? 0, ...(projeto.camadas ?? []).filter(c=>c.visivel!==false).map(c=>c.fim), ...projeto.audios.map(a=>(a.entrada??0)+a.fim-a.inicio)));
}

/** Qual clipe aparece no instante `t` da linha do tempo e em que ponto do arquivo dele. */
export function clipeNoTempo(projeto, t) {
  const lista = segmentos(projeto);
  if (lista.length === 0) return null;
  const alvo = Math.min(Math.max(0, t), duracaoTotal(projeto));
  const segmento = lista.find((s) => alvo < s.fim) ?? lista[lista.length - 1];
  const clipe = projeto.clipes[segmento.indice];
  const dentro = Math.min(alvo - segmento.inicio, projeto.versao>=4 && alvo>=segmento.fim ? Math.max(0,(Math.ceil(segmento.duracao*projeto.fps)-1)/projeto.fps) : segmento.duracao);
  return {
    indice: segmento.indice,
    clipe,
    tempoNoArquivo: arredondar(clipe.tipo === "imagem" ? 0 : (clipe.inicio ?? 0) + dentro * velocidadeDoClipe(clipe)),
  };
}

export function transicaoNoTempo(p,t) {
  const lista=segmentos(p);
  for(let i=1;i<lista.length;i++) {
    const d=duracaoDaTransicao(p,i-1), s=lista[i], c=p.clipes[i];
    if(d>0 && t>=s.inicio && t<lista[i-1].fim) return {indice:i,clipe:c,tipo:p.clipes[i-1].transicao.tipo,
      progresso:Math.min(1,(t-s.inicio)/d),tempoNoArquivo:c.tipo==="imagem"?0:(c.inicio??0)+(t-s.inicio)*velocidadeDoClipe(c)};
  }
  return null;
}

/** Os textos visíveis no instante `t`. */
export function textosNoTempo(projeto, t) {
  return projeto.textos.filter((texto) => t >= texto.inicio && ((texto.id.startsWith("legenda-") || texto.id.startsWith("contagem-")) ? t < texto.fim : t <= texto.fim));
}

/** Camadas extras: início/fim na composição, deslocamento no arquivo de origem. */
export function camadasNoTempo(projeto, t) {
  return (projeto.camadas ?? []).filter((c) => c.visivel !== false && t >= c.inicio && t < c.fim)
    .slice().sort((a, b) => (a.ordem ?? 1) - (b.ordem ?? 1));
}

export function usaCamadas(projeto) {
  return Boolean(projeto.fundo || projeto.camadas?.length || projeto.clipes.some((c) => c.transformacao)
    || projeto.textos.some((t) => t.transformacao));
}

/**
 * Onde a imagem de origem é desenhada no quadro, para preencher ou caber (o desfocado usa
 * o de caber por cima do de preencher). Mesma conta que o `scale` do FFmpeg com
 * `force_original_aspect_ratio`, arredondada para pares.
 */
export function retanguloDoAjuste(ajuste, larguraDaOrigem, alturaDaOrigem, largura, altura) {
  const cobrir = ajuste === "preencher";
  const escala = cobrir
    ? Math.max(largura / larguraDaOrigem, altura / alturaDaOrigem)
    : Math.min(largura / larguraDaOrigem, altura / alturaDaOrigem);
  const w = par(larguraDaOrigem * escala);
  const h = par(alturaDaOrigem * escala);
  return { x: (largura - w) / 2, y: (altura - h) / 2, w, h };
}

/** Tamanho do GIF: lado menor até 480 px, sempre par. */
export function tamanhoDoGif(largura, altura) {
  const menor = Math.min(largura, altura);
  if (menor <= LADO_DO_GIF) return { largura: par(largura), altura: par(altura) };
  const escala = LADO_DO_GIF / menor;
  return { largura: par(largura * escala), altura: par(altura * escala) };
}

/** Problemas que impedem exportar, em português, prontos para mostrar. */
export function problemas(projeto) {
  const lista = [];
  if (![1, 2, 3, VERSAO_DO_PROJETO].includes(projeto.versao)) lista.push("Projeto de uma versão que este app não conhece.");
  if (!(projeto.largura >= 16 && projeto.altura >= 16 && projeto.largura <= 4096 && projeto.altura <= 4096)) {
    lista.push("O tamanho do quadro precisa ficar entre 16 e 4096 pixels.");
  }
  if (!saidaPorId(projeto.saida)) lista.push("Escolha o que exportar.");
  if (projeto.clipes.length === 0) lista.push("Adicione pelo menos um vídeo ou imagem.");
  projeto.clipes.forEach((clipe, i) => {
    if (clipe.tipo === "video" && !((clipe.fim ?? 0) - (clipe.inicio ?? 0) >= DURACAO_MINIMA)) {
      lista.push(`O clipe ${i + 1} termina antes de começar.`);
    }
    if (!Number.isFinite(clipe.velocidade ?? 1) || (clipe.velocidade ?? 1)<.25 || (clipe.velocidade ?? 1)>4) lista.push(`A velocidade do clipe ${i+1} precisa ficar entre 0,25× e 4×.`);
    if (clipe.transicao && (!TRANSICOES.some(t=>t.id===clipe.transicao.tipo) || !Number.isFinite(clipe.transicao.duracao) || clipe.transicao.duracao<.1 || clipe.transicao.duracao>5)) lista.push(`A transição do clipe ${i+1} é inválida (use de 0,1 a 5 segundos).`);
    if (!AJUSTES.some((a) => a.id === clipe.ajuste)) lista.push(`O clipe ${i + 1} está sem enquadramento.`);
  });
  projeto.textos.forEach((texto, i) => {
    if (!texto.texto?.trim()) lista.push(`O texto ${i + 1} está vazio.`);
    if (!(texto.fim > texto.inicio)) lista.push(`O texto ${i + 1} termina antes de começar.`);
  });
  projeto.audios.forEach((audio, i) => {
    if (!(audio.fim - audio.inicio >= DURACAO_MINIMA)) lista.push(`O áudio ${i + 1} termina antes de começar.`);
  });
  const camadas = projeto.camadas ?? [];
  if (camadas.length > 12) lista.push("Use até 12 camadas adicionais.");
  camadas.forEach((camada, i) => {
    if (!["imagem", "video", "gif"].includes(camada.tipo) || !camada.arquivo) lista.push(`A camada ${i + 1} está sem mídia válida.`);
    if (!Number.isFinite(camada.inicio) || !Number.isFinite(camada.fim) || camada.inicio < 0 || camada.fim <= camada.inicio) lista.push(`A camada ${i + 1} está com o intervalo inválido.`);
    if (!Number.isFinite(camada.deslocamento ?? 0) || (camada.deslocamento ?? 0) < 0) lista.push(`A camada ${i + 1} está com o início da mídia inválido.`);
  });
  for (const item of [...projeto.clipes, ...projeto.textos, ...camadas]) {
    const erro = validarTransformacao(item.transformacao);
    if (erro) lista.push(erro);
  }
  const corValida = (cor) => /^#[0-9a-f]{6}([0-9a-f]{2})?$/i.test(cor);
  if (projeto.fundo && (!corValida(projeto.fundo.cor) || (projeto.fundo.corFinal && !corValida(projeto.fundo.corFinal)))) lista.push("Cor de fundo inválida.");
  const total = duracaoTotal(projeto);
  if(projeto.versao>=4) {
    if(!Number.isInteger(projeto.fps)||projeto.fps<1||projeto.fps>60) lista.push("Use uma taxa de 1 a 60 quadros por segundo.");
    if(!Number.isFinite(total)||total<0||total>86400) lista.push("A composição deve ter duração finita de até 24 horas.");
    if(projeto.clipes.length>100||projeto.textos.length>1100||projeto.audios.length>20) lista.push("Use até 100 clipes, 1100 textos e 20 áudios.");
    if(projeto.audios.some(a=>!Number.isFinite(a.entrada??0)||(a.entrada??0)<0||!Number.isFinite(a.inicio)||a.inicio<0||!Number.isFinite(a.fim))) lista.push("O intervalo de um áudio é inválido.");
  }
  if (projeto.saida === "imagem" && projeto.clipes.length > 0 && !(projeto.quadro >= 0 && projeto.quadro <= total)) {
    lista.push("Escolha um instante dentro do vídeo para a imagem.");
  }
  return lista;
}

/** Avisos que não impedem exportar (a rede pode recusar ou cortar). */
export function avisos(projeto) {
  const lista = [];
  const preset = presetPorId(projeto.destino);
  const total = duracaoTotal(projeto);
  if (preset?.duracaoMaxima && total > preset.duracaoMaxima && ["video", "gif"].includes(projeto.saida)) {
    lista.push(`${preset.rede} · ${preset.nome} costuma aceitar até ${duracaoLegivel(preset.duracaoMaxima)}; este projeto tem ${duracaoLegivel(total)}.`);
  }
  if (projeto.saida === "gif" && total > 15) lista.push("GIF com mais de 15 segundos fica pesado; considere exportar em vídeo.");
  if (total > 0 && projeto.textos.some((texto) => texto.inicio >= total)) lista.push("Algum texto começa depois do fim do vídeo e não vai aparecer.");
  return lista;
}

/** Se o navegador dá conta sozinho; senão o site prefere o plugin. */
export function cabeNoNavegador(projeto) {
  return projeto.saida !== "texto"
    && duracaoTotal(projeto) <= LIMITE_DO_NAVEGADOR.segundos
    && projeto.largura * projeto.altura <= LIMITE_DO_NAVEGADOR.pixels;
}

/**
 * A receita do FFmpeg: argumentos de entrada, o `filter_complex` e os rótulos de saída.
 * Os codificadores ficam por conta de cada plataforma (libx264 no Windows, MediaCodec no
 * Android). `entradas` diz o que só o app sabe:
 * `{ clipes: [{ caminho, temAudio }], textos: [caminhoDoPng], audios: [caminho] }`. Cada
 * texto já vem desenhado num PNG transparente do tamanho do quadro.
 *
 * Saídas: `video` mapeia vídeo e áudio; `gif` e `imagem` só vídeo; `audio` e `texto` só
 * áudio. O GIF já sai com a paleta, no tamanho de `tamanhoDoGif`.
 */
export function receitaFfmpeg(projeto, entradas, otimizar = false) {
  if (!["audio", "texto"].includes(projeto.saida) && (usaCamadas(projeto) || temTransicoes(projeto))) return receitaComCamadas(projeto, entradas, otimizar);
  const { largura: W, altura: H, fps } = projeto;
  const total = duracaoTotal(projeto);
  const comVideo = projeto.saida !== "audio" && projeto.saida !== "texto";
  const comAudio = projeto.saida === "video" || projeto.saida === "audio" || projeto.saida === "texto";
  const args = [];
  const filtros = [];
  let indiceDaEntrada = 0;

  projeto.clipes.forEach((clipe, i) => {
    const caminho = entradas.clipes[i].caminho;
    const duracao = duracaoDoClipe(clipe);
    if (clipe.tipo === "imagem") args.push("-loop", "1", "-framerate", String(fps), "-t", numero(duracao), "-i", caminho);
    else args.push("-ss", numero(clipe.inicio ?? 0), "-t", numero(duracaoDaOrigem(clipe)), "-i", caminho);
    const n = indiceDaEntrada++;
    if (comVideo) filtros.push(filtroDoClipe(clipe.ajuste, n, i, W, H, fps).replace(`[${n}:v]`, `[${n}:v]${filtroDeVelocidade(clipe)}`));
    if (comAudio) {
      const temAudio = clipe.tipo === "video" && entradas.clipes[i].temAudio && !clipe.mudo && (clipe.volume ?? 1) > 0;
      filtros.push(temAudio
        ? `[${n}:a]${filtroDeTempoAudio(clipe)}aresample=48000,aformat=channel_layouts=stereo,volume=${numero(clipe.volume ?? 1)},apad=whole_dur=${numero(duracao)},atrim=duration=${numero(duracao)},asetpts=PTS-STARTPTS[a${i}]`
        : `anullsrc=r=48000:cl=stereo,atrim=duration=${numero(duracao)},asetpts=PTS-STARTPTS[a${i}]`);
    }
  });

  const pares = projeto.clipes.map((_, i) => `${comVideo ? `[v${i}]` : ""}${comAudio ? `[a${i}]` : ""}`).join("");
  if (temTransicoes(projeto) && !comVideo) {
    let anterior="a0";
    for(let i=1;i<projeto.clipes.length;i++) {
      const d=duracaoDaTransicao(projeto,i-1), proximo=`ax${i}`;
      filtros.push(d>0?`[${anterior}][a${i}]acrossfade=d=${numero(d)}:c1=tri:c2=tri[${proximo}]`:`[${anterior}][a${i}]concat=n=2:v=0:a=1[${proximo}]`);
      anterior=proximo;
    }
    filtros.push(`[${anterior}]anull[ab]`);
  } else filtros.push(`${pares}concat=n=${projeto.clipes.length}:v=${comVideo ? 1 : 0}:a=${comAudio ? 1 : 0}${comVideo ? "[vb]" : ""}${comAudio ? "[ab]" : ""}`);

  if (comAudio && total > (segmentos(projeto).at(-1)?.fim ?? 0)) {
    const antigo = filtros.pop(); filtros.push(antigo.replaceAll("[ab]","[abase]"));
    filtros.push(`[abase]apad=whole_dur=${numero(total)},atrim=duration=${numero(total)}[ab]`);
  }

  if (comVideo && total > (segmentos(projeto).at(-1)?.fim ?? 0)) {
    for(let i=0;i<filtros.length;i++) filtros[i]=filtros[i].replaceAll("[vb]","[vbase]");
    filtros.push(`[vbase]tpad=stop_mode=clone:stop_duration=${numero(total-(segmentos(projeto).at(-1)?.fim??0))},trim=duration=${numero(total)}[vb]`);
  }
  let video = "vb";
  if (comVideo) {
    projeto.textos.forEach((texto, j) => {
      args.push("-loop", "1", "-framerate", String(fps), "-t", numero(total), "-i", entradas.textos[j]);
      const n = indiceDaEntrada++;
      const proximo = `vt${j}`;
      filtros.push(`[${video}][${n}:v]overlay=0:0:enable='${(texto.id.startsWith("legenda-") || texto.id.startsWith("contagem-"))?`gte(t,${numero(texto.inicio)})*lt(t,${numero(texto.fim)})`:`between(t,${numero(texto.inicio)},${numero(texto.fim)})`}'[${proximo}]`);
      video = proximo;
    });
    if (projeto.saida === "gif") {
      const gif = tamanhoDoGif(W, H);
      filtros.push(`[${video}]fps=${FPS_DO_GIF},scale=${gif.largura}:${gif.altura}:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]`);
      video = "gif";
    }
  }

  let audio = "ab";
  if (comAudio && projeto.audios.length > 0) {
    const rotulos = projeto.audios.map((musica, k) => {
      args.push("-ss", numero(musica.inicio), "-t", numero(musica.fim - musica.inicio), "-i", entradas.audios[k]);
      const n = indiceDaEntrada++;
      const comprimento = musica.fim - musica.inicio;
      const cadeia = [`aresample=48000`, `aformat=channel_layouts=stereo`, `volume=${numero(musica.volume ?? 1)}`];
      if ((musica.fadeEntrada ?? 0) > 0) cadeia.push(`afade=t=in:st=0:d=${numero(musica.fadeEntrada)}`);
      if ((musica.fadeSaida ?? 0) > 0) cadeia.push(`afade=t=out:st=${numero(Math.max(0, comprimento - musica.fadeSaida))}:d=${numero(musica.fadeSaida)}`);
      const atraso = Math.round((musica.entrada ?? 0) * 1000);
      if (atraso > 0) cadeia.push(`adelay=${atraso}:all=1`);
      filtros.push(`[${n}:a]${cadeia.join(",")}[m${k}]`);
      return `[m${k}]`;
    });
    filtros.push(`[ab]${rotulos.join("")}amix=inputs=${rotulos.length + 1}:duration=first:normalize=0[am]`);
    audio = "am";
  }

  const mapas = [];
  if (comVideo) mapas.push("-map", `[${video}]`);
  if (comAudio) mapas.push("-map", `[${audio}]`);
  return { args, filtro: filtros.join(";"), mapas, duracao: total };
}

function filtroDoClipe(ajuste, n, i, W, H, fps) {
  const fim = `setsar=1,format=yuv420p,setpts=PTS-STARTPTS[v${i}]`;
  if (ajuste === "preencher") {
    return `[${n}:v]fps=${fps},scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},${fim}`;
  }
  if (ajuste === "caber") {
    return `[${n}:v]fps=${fps},scale=${W}:${H}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=${W}:${H}:(ow-iw)/2:(oh-ih)/2:color=black,${fim}`;
  }
  // Desfoque barato e disponível em qualquer FFmpeg (o boxblur é GPL e o do Android é LGPL):
  // reduz a 1/8, aplica o gblur e amplia de volta.
  return `[${n}:v]fps=${fps},split[f${i}][p${i}];`
    + `[f${i}]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},scale=${par(W / 8)}:${par(H / 8)},gblur=sigma=3,scale=${W}:${H}[fd${i}];`
    + `[p${i}]scale=${W}:${H}:force_original_aspect_ratio=decrease:force_divisible_by=2[pc${i}];`
    + `[fd${i}][pc${i}]overlay=(W-w)/2:(H-h)/2,${fim}`;
}

/**
 * Número para o FFmpeg: até 3 casas, sem zeros sobrando ("2.5", "10", "0.333"). O C# e o
 * Kotlin formatam do mesmo jeito (arredondamento com o meio para cima).
 */
export function numero(valor) {
  return String(Math.round(valor * 1000) / 1000);
}

/** "1 min 30 s", "45 s". */
export function duracaoLegivel(segundos) {
  const inteiro = Math.round(segundos);
  const minutos = Math.floor(inteiro / 60);
  const resto = inteiro % 60;
  if (minutos === 0) return `${resto} s`;
  return resto === 0 ? `${minutos} min` : `${minutos} min ${resto} s`;
}

function arredondar(valor) {
  return Math.round(valor * 1000) / 1000;
}

function par(valor) {
  return Math.max(2, Math.round(valor / 2) * 2);
}
