"use client";
import { useState } from 'react';
import { MODELOS_DO_ESTUDIO, aplicarModelo, adicionarContagem } from '@/packages/converter-core/estudio-criacao.mjs';
import type { Projeto } from '@/packages/converter-core/estudio.mjs';
import { abrirProjeto, salvarProjeto } from '@/lib/estudio/projetos';

export default function ProjetosDoEstudio({projeto,arquivos,instante,mudar,abrir,ocupado=false}: {
  projeto:Projeto|null; arquivos:ReadonlyMap<string,File>; instante:number;
  mudar:(mudanca:(p:Projeto)=>Projeto)=>void; abrir:(p:Projeto,arquivos:ReadonlyMap<string,File>)=>Promise<void>; ocupado?:boolean;
}) {
 const [erro,setErro]=useState(''),[salvando,setSalvando]=useState(false),[contagem,setContagem]=useState(3);
 const falha=(e:unknown)=>setErro(e instanceof Error?e.message:'Não foi possível abrir ou salvar o projeto.');
 return <details className="estudio__detalhe"><summary>Projetos e modelos</summary>
  <label className="button">Abrir projeto salvo<input hidden disabled={ocupado||salvando} type="file" accept=".sltstudio,.zip" onChange={async e=>{
   const f=e.target.files?.[0];e.target.value='';if(!f)return;setSalvando(true);setErro('');
   try {const novo=await abrirProjeto(f);await abrir(novo.projeto,novo.arquivos);}catch(e){falha(e);}finally{setSalvando(false);}
  }}/></label>
  {projeto&&<>
   <button type="button" className="button" disabled={ocupado||salvando||!projeto.clipes.length} onClick={async()=>{
    setSalvando(true);setErro('');try {const blob=await salvarProjeto(projeto,arquivos),url=URL.createObjectURL(blob),a=document.createElement('a');
     a.href=url;a.download=`projeto-${new Date().toISOString().replace(/[:.]/g,'-')}.sltstudio`;a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);
    }catch(e){falha(e);}finally{setSalvando(false);}
   }}>{salvando?'Preparando projeto…':'Salvar projeto com mídias'}</button>
   <p>O arquivo inclui as mídias e pode ser reaberto no site, Windows e Android. Salve antes de iniciar outro projeto.</p>
   <label className="estudio__campo">Aplicar modelo editável<select disabled={ocupado||!projeto.clipes.length} value="" onChange={e=>{const id=e.target.value;try{mudar(p=>aplicarModelo(p,id));}catch(e){falha(e);}}}>
    <option value="" disabled>Escolher modelo</option>{MODELOS_DO_ESTUDIO.map(m=><option key={m.id} value={m.id}>{m.titulo}</option>)}
   </select></label>
   <label className="estudio__campo">Contagem regressiva (1–10 s)<input type="number" min={1} max={10} value={contagem} onChange={e=>setContagem(Number(e.target.value))}/></label>
   <button type="button" className="button" disabled={ocupado||!projeto.clipes.length} onClick={()=>{try{const novo=adicionarContagem(projeto,instante,contagem);mudar(()=>novo);setErro('');}catch(e){falha(e);}}}>Adicionar contagem no cursor</button>
  </>}
  {salvando&&<p role="status">Preparando projeto com mídias…</p>}
  {erro&&<p role="alert">{erro}</p>}
 </details>;
}
