"use client";
import {useRef} from "react";
import {duracaoTotal,segmentos,type Projeto} from '@/packages/converter-core/estudio.mjs';

export default function LinhaDoTempo({projeto,instante,irPara,selecionar,moverAudio}:{projeto:Projeto;instante:number;irPara:(t:number)=>void;selecionar:(id:string,tipo:'camadas'|'clipes'|'audio')=>void;moverAudio:(id:string,t:number)=>void}) {
 const arraste=useRef<{id:string;x:number;largura:number;destino:number;moveu:boolean}|null>(null);
 const cliqueDoArraste=useRef(false);
 const total=duracaoTotal(projeto);if(!total)return null;
 const nome=(caminho:string)=>caminho.split(/[\\/]/).pop()??caminho;
 type Item={id:string;inicio:number;fim:number};
 type Linha={nome:string;tipo:'camadas'|'clipes'|'audio';itens:Item[]};
 const linhas:Linha[]=[...segmentos(projeto).map(s=>({nome:`${s.indice+1}. ${nome(projeto.clipes[s.indice].arquivo)}`,tipo:'clipes' as const,itens:[s]})),
  ...(projeto.camadas??[]).slice().sort((a,b)=>(a.ordem??1)-(b.ordem??1)).map(c=>({nome:`Camada · ${nome(c.arquivo)}`,tipo:'camadas' as const,itens:[c]})),
  ...projeto.audios.map(a=>({nome:`Áudio · ${a.openverse?.titulo ?? nome(a.arquivo)}`,tipo:'audio' as const,itens:[{id:a.id,inicio:a.entrada??0,fim:(a.entrada??0)+a.fim-a.inicio}]}))];
 return <details className="estudio__detalhe" open><summary>Faixas da composição · {instante.toFixed(1)} / {total.toFixed(1)} s</summary>
  <p><small>Toque para selecionar a mídia e posicionar o cursor. Arraste uma faixa de áudio para movê-la.</small></p>
  {linhas.map((l,i)=><div key={i} style={{display:'grid',gridTemplateColumns:'minmax(90px,180px) 1fr',gap:8,marginBlock:6}}>
   <span title={l.nome}>{l.nome}</span><div style={{position:'relative',height:32,background:'#262626',borderRadius:4}}>
    {l.itens.map(c=><button key={c.id} type="button" title={`${c.inicio.toFixed(1)}–${c.fim.toFixed(1)} s`} aria-label={`${l.nome}: ${c.inicio.toFixed(1)} a ${c.fim.toFixed(1)} segundos`}
     style={{position:'absolute',left:`${c.inicio/total*100}%`,width:`${Math.max(.5,(c.fim-c.inicio)/total*100)}%`,height:'100%',overflow:'hidden',background:l.tipo==='audio'?'#27644b':'#95400d',border:'1px solid #FF7A18',borderRadius:4,color:'#fff',touchAction:l.tipo==='audio'?'pan-y':undefined}}
     onPointerDown={e=>{
       cliqueDoArraste.current=false;
       if(l.tipo!=='audio'||e.button!==0)return;
       const largura=e.currentTarget.parentElement!.getBoundingClientRect().width;
       if(!largura)return;
       arraste.current={id:c.id,x:e.clientX,largura,destino:c.inicio,moveu:false};
       e.currentTarget.setPointerCapture(e.pointerId);
     }}
     onPointerMove={e=>{
       const a=arraste.current;
       if(a?.id!==c.id)return;
       const dx=e.clientX-a.x;
       if(!a.moveu&&Math.abs(dx)<4)return;
       a.moveu=true;
       a.destino=Math.max(0,Math.min(total-1/projeto.fps,c.inicio+dx/a.largura*total));
       e.currentTarget.style.transform=`translateX(${(a.destino-c.inicio)/total*a.largura}px)`;
     }}
     onPointerCancel={e=>{arraste.current=null;e.currentTarget.style.transform="";}}
     onPointerUp={e=>{
       const a=arraste.current;
       if(a?.id!==c.id)return;
       arraste.current=null;
       e.currentTarget.style.transform="";
       if(a.moveu){cliqueDoArraste.current=true;moverAudio(c.id,a.destino);selecionar(c.id,l.tipo);}
     }}
     onClick={e=>{if(cliqueDoArraste.current){cliqueDoArraste.current=false;return;}const r=e.currentTarget.parentElement!.getBoundingClientRect();irPara(e.detail===0?c.inicio:Math.max(0,Math.min(total,(e.clientX-r.left)/r.width*total)));selecionar(c.id,l.tipo);}}>{c.inicio.toFixed(1)}s</button>)}
    <span aria-hidden="true" style={{position:'absolute',left:`${instante/total*100}%`,height:'100%',borderLeft:'2px solid white',pointerEvents:'none'}}/>
   </div>
  </div>)}
 </details>;
}
