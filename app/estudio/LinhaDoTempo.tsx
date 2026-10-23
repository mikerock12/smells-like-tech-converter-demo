"use client";
import {duracaoTotal,segmentos,type Projeto} from '@/packages/converter-core/estudio.mjs';

export default function LinhaDoTempo({projeto,instante,irPara,selecionar}:{projeto:Projeto;instante:number;irPara:(t:number)=>void;selecionar:(id:string,tipo:'camadas'|'clipes'|'audio')=>void}) {
 const total=duracaoTotal(projeto);if(!total)return null;
 const linhas=[{nome:'Principal',tipo:'clipes' as const,itens:segmentos(projeto)},
  ...(projeto.camadas??[]).slice().sort((a,b)=>(a.ordem??1)-(b.ordem??1)).map(c=>({nome:`Vídeo/mídia · ${c.ordem??1}`,tipo:'camadas' as const,itens:[c]})),
  ...projeto.audios.map(a=>({nome:'Áudio',tipo:'audio' as const,itens:[{id:a.id,inicio:a.entrada??0,fim:(a.entrada??0)+a.fim-a.inicio}]}))];
 return <details className="estudio__detalhe" open><summary>Faixas da composição · {instante.toFixed(1)} / {total.toFixed(1)} s</summary>
  {linhas.map((l,i)=><div key={i} style={{display:'grid',gridTemplateColumns:'110px 1fr',gap:8,marginBlock:6}}>
   <span>{l.nome}</span><div style={{position:'relative',height:32,background:'#262626',borderRadius:4}}>
    {l.itens.map(c=><button key={c.id} type="button" title={`${c.inicio.toFixed(1)}–${c.fim.toFixed(1)} s`} aria-label={`${l.nome}: ${c.inicio.toFixed(1)} a ${c.fim.toFixed(1)} segundos`}
     style={{position:'absolute',left:`${c.inicio/total*100}%`,width:`${Math.max(.5,(c.fim-c.inicio)/total*100)}%`,height:'100%',overflow:'hidden',background:l.tipo==='audio'?'#27644b':'#95400d',border:'1px solid #FF7A18',borderRadius:4,color:'#fff'}}
     onClick={()=>{irPara(c.inicio);selecionar(c.id,l.tipo);}}>{c.inicio.toFixed(1)}s</button>)}
    <span aria-hidden="true" style={{position:'absolute',left:`${instante/total*100}%`,height:'100%',borderLeft:'2px solid white',pointerEvents:'none'}}/>
   </div>
  </div>)}
 </details>;
}
