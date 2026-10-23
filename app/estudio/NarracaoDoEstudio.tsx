"use client";
import {useEffect,useRef,useState} from 'react';
import {usePlano} from '@/lib/plano/usePlano';
import {registrarUso,restanteHoje} from '@/lib/plano/contadores';
import type {Projeto} from '@/packages/converter-core/estudio.mjs';
import {VOZES} from '@/packages/kokoro/config.mjs';
import {prepararKokoro} from '@/lib/converter/pagina/kokoro';
import {narrar} from '@/lib/converter/pagina/narracao';

export default function NarracaoDoEstudio({adicionar,instante,projeto,desabilitado=false}:{projeto:Projeto;adicionar:(arquivo:File,entrada:number)=>Promise<void>;instante:number;desabilitado?:boolean}) {
 const [texto,setTexto]=useState(''),[voz,setVoz]=useState('pf_dora'),[rate,setRate]=useState(0),[status,setStatus]=useState(''),[ocupado,setOcupado]=useState(false),[erro,setErro]=useState('');
 const cancelar=useRef(false);
 const {plano}=usePlano();
 useEffect(()=>()=>{cancelar.current=true;},[projeto]);
 return <details className="estudio__detalhe"><summary>Narrar com Kokoro na linha do tempo</summary>
  <label className="estudio__campo">Texto da narração<textarea maxLength={50000} value={texto} disabled={ocupado} onChange={e=>setTexto(e.target.value)} /></label>
  <label className="estudio__campo">Voz<select value={voz} onChange={e=>setVoz(e.target.value)} disabled={ocupado}>{VOZES.map(v=><option key={v.id} value={v.id}>{v.nome}</option>)}</select></label>
  <label className="estudio__campo">Velocidade da fala<input type="range" min={-10} max={10} value={rate} onChange={e=>setRate(Number(e.target.value))} disabled={ocupado}/></label>
  <p>A narração entra no cursor ({instante.toFixed(1)} s) e pode ser cortada, movida e mixada na aba Áudio.</p>
  <button type="button" className="button" disabled={ocupado||desabilitado||!texto.trim()} onClick={async()=>{
   const restante=restanteHoje(plano,"narracao");
   if(restante!==null&&texto.length>restante){setErro(`Seu plano tem ${restante} caracteres de narração disponíveis hoje. Diminua o texto ou use o Pro.`);return;}
   const entrada=instante;setOcupado(true);cancelar.current=false;setErro('');
   try {
    await prepararKokoro(p=>setStatus(`Preparando voz para uso offline: ${Math.round(p)}%`));
    if(cancelar.current) return;
    const resultado=await narrar({jobId:crypto.randomUUID(),ferramenta:'voz.narrar',entradas:[{nome:'narracao.txt',formato:'txt',arquivo:new Blob([texto],{type:'text/plain'})}],
     opcoes:{voz,velocidade:rate,volume:100,formato:'wav'},cancelado:()=>cancelar.current,progresso:(etapa,p)=>setStatus(`${etapa}: ${Math.round(p*100)}%`)});
    if(cancelar.current)return;
    const a=resultado.saidas[0];await adicionar(new File([a.bytes],a.nome,{type:a.mime}),entrada);registrarUso("narracao",texto.length);setStatus('Narração adicionada à linha do tempo.');
   }catch(e){setErro(e instanceof Error?e.message:'Não foi possível gerar a narração.');}finally{setOcupado(false);}
  }}>Gerar e adicionar narração</button>
  {ocupado&&<button type="button" className="button" onClick={()=>{cancelar.current=true;setStatus('Cancelando…');}}>Cancelar</button>}
  {status&&<p role="status">{status}</p>}{erro&&<p role="alert">{erro}</p>}
 </details>;
}
