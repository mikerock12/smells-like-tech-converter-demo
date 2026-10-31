import { duracaoTotal, segmentos, duracaoDoClipe, temTransicoes, velocidadeDoClipe } from './estudio.mjs';
import { kenBurns } from './estudio-animacao.mjs';

export const MODELOS_DO_ESTUDIO = Object.freeze([
  {id:'abertura',titulo:'Título de abertura'},
  {id:'fotos',titulo:'Fotos em movimento'},
  {id:'cartao',titulo:'Cartão social'},
]);

export function aplicarModelo(p,id) {
  p = atualizarProjeto(p);
  if(!MODELOS_DO_ESTUDIO.some(m=>m.id===id)||!p.clipes.length) throw new Error('Adicione uma mídia e escolha um modelo.');
  if(id==='fotos') return {...p,versao:4,clipes:p.clipes.map(c=>c.tipo==='imagem'?{...c,transformacao:kenBurns(c.transformacao,duracaoDoClipe(c))}:c)};
  const total=duracaoTotal(p);
  const texto={id:'modelo-titulo',texto:id==='cartao'?'Sua mensagem aqui':'Seu título aqui',inicio:0,fim:Math.min(total,3),x:.5,y:id==='cartao'?.85:.2,
    tamanho:.075,cor:'#FFFFFF',contorno:'#000000',caixa:'#000000B3',fonte:'anton'};
  return {...p,versao:4,...(id==='cartao'?{fundo:{cor:'#7536E8',corFinal:'#FF7A18'},clipes:p.clipes.map(c=>({...c,transformacao:{...c.transformacao,escala:.78,y:.42}}))}:{}),
    textos:[...p.textos.filter(t=>t.id!=='modelo-titulo'),texto]};
}

export function adicionarContagem(p,inicio=0,quantidade=3) {
  p = atualizarProjeto(p);
  if(!Number.isFinite(inicio)||inicio<0||!Number.isInteger(quantidade)||quantidade<1||quantidade>10||inicio+quantidade>duracaoTotal(p)+.001)
    throw new Error('A contagem deve ter de 1 a 10 segundos e caber na linha do tempo.');
  const textos=Array.from({length:quantidade},(_,i)=>({id:`contagem-${i}`,texto:String(quantidade-i),inicio:inicio+i,fim:inicio+i+1,
    x:.5,y:.5,tamanho:.25,cor:'#FFFFFF',contorno:'#000000',caixa:null,fonte:'anton'}));
  return {...p,versao:4,textos:[...p.textos.filter(t=>!t.id.startsWith('contagem-')),...textos]};
}

export function dividirClipe(p,id,tempo,novoId) {
  const c=p.clipes.find(c=>c.id===id), segmento=segmentos(p).find(s=>s.id===id);
  if(!c||!segmento||p.clipes.length>=100) throw new Error('Selecione um clipe e use até 100 clipes.');
  const local=tempo-segmento.inicio, duracao=duracaoDoClipe(c);
  if(local<=.05||local>=duracao-.05) throw new Error('Posicione o cursor dentro do clipe para dividir.');
  if(temTransicoes(p)) throw new Error('Troque as transições por corte seco antes de dividir o clipe.');
  const t=c.transformacao, depois=t?.quadrosChave?.length?{...t,tempoDaAnimacao:(t.tempoDaAnimacao??0)+local}:t;
  const corte=(c.inicio??0)+local*velocidadeDoClipe(c);
  const primeiro=c.tipo==='imagem'?{...c,duracao:local,transicao:null}:{...c,fim:corte,transicao:null};
  const segundo=c.tipo==='imagem'?{...c,id:novoId,duracao:duracao-local,transformacao:depois}:{...c,id:novoId,inicio:corte,transformacao:depois};
  return {...p,clipes:p.clipes.flatMap(a=>a.id===id?[primeiro,segundo]:[a])};
}

/** Cortar uma faixa mantém o relógio da animação e a posição na mídia original. */
export function dividirCamada(p,id,tempo,novoId) {
  p = atualizarProjeto(p);
  const c=p.camadas?.find(c=>c.id===id);
  if(!c||tempo<=c.inicio+.05||tempo>=c.fim-.05||p.camadas.length>=12) throw new Error('Posicione o cursor dentro da faixa e use até 12 camadas.');
  const local=tempo-c.inicio, antes=c.transformacao;
  const depois=c.transformacao?.quadrosChave?.length ? {...c.transformacao,tempoDaAnimacao:(c.transformacao.tempoDaAnimacao??0)+local} : c.transformacao;
  return {...p,versao:4,camadas:p.camadas.flatMap(a=>a.id===id?[{...c,fim:tempo,transformacao:antes},
    {...c,id:novoId,inicio:tempo,deslocamento:(c.deslocamento??0)+(c.tipo==='imagem'?0:local),transformacao:depois}]:[a])};
}

export function arquivosDoProjeto(p) {return [...new Set([...p.clipes,...p.audios,...(p.camadas??[])].map(c=>c.arquivo))];}
export function trocarArquivos(p,trocar) {return {...p,clipes:p.clipes.map(c=>({...c,arquivo:trocar(c.arquivo)})),audios:p.audios.map(c=>({...c,arquivo:trocar(c.arquivo)})),camadas:p.camadas?.map(c=>({...c,arquivo:trocar(c.arquivo)}))};}
export function inicioDoClipe(p,id) { return segmentos(p).find(s=>s.id===id)?.inicio??0; }

/** Migrar não pode revelar músicas/camadas que os projetos antigos cortavam no fim. */
export function atualizarProjeto(p) {
  if(p.versao>=4) return p;
  const total=duracaoTotal(p);
  return {...p,versao:4,camadas:p.camadas?.filter(c=>c.inicio<total).map(c=>({...c,fim:Math.min(total,c.fim)})),
    audios:p.audios.filter(a=>(a.entrada??0)<total).map(a=>({...a,fim:Math.min(a.fim,a.inicio+total-(a.entrada??0))}))};
}
