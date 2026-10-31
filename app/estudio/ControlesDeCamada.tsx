"use client";

import { kenBurns, mudarBaseDaAnimacao } from "@/packages/converter-core/estudio-animacao.mjs";
import { useState } from "react";
import { TRANSFORMACAO_PADRAO, type Transformacao } from "@/packages/converter-core/camadas.mjs";
import { caberInteira, centralizar, guiasDaCamada, preencher, type Caixa, type Medidas } from "@/packages/converter-core/guias.mjs";
import { mostrarGuias } from "@/lib/estudio/guias-na-tela";
import { Deslizante } from "./Deslizante";

const filtros = { original: "Original", vivido: "Vívido", pb: "P&B", sepia: "Sépia", vintage: "Vintage", frio: "Frio", quente: "Quente", negativo: "Negativo" };
const mesclas = { normal: "Normal", multiplicar: "Multiplicar", clarear: "Clarear", sobrepor: "Sobrepor" };
const faixas: [keyof Transformacao, string, number, number, number][] = [
  ["x", "Posição horizontal", -.5, 1.5, .01], ["y", "Posição vertical", -.5, 1.5, .01],
  ["escala", "Tamanho", .05, 3, .01], ["giro", "Girar (Z)", -180, 180, .1],
  ["giroX", "Inclinar para frente / trás (X)", -180, 180, .1],
  ["giroY", "Inclinar para esquerda / direita (Y)", -180, 180, .1],
  ["opacidade", "Opacidade", 0, 1, .01], ["brilho", "Brilho", -1, 1, .01],
  ["contraste", "Contraste", -.8, 1, .01], ["saturacao", "Saturação", -1, 1, .01],
];

/**
 * `medidas`: a mídia e o quadro, para as guias e o Preencher. `referencias`: as caixas das outras
 * camadas visíveis. `enquadrar`: nos clipes, Preencher e Caber trocam o enquadramento do clipe.
 */
export default function ControlesDeCamada({ valor, mudar, permitirRecorte = true, duracao = 3, medidas, referencias = [], enquadrar }: {
  valor?: Partial<Transformacao>; mudar: (valor: Partial<Transformacao>) => void; permitirRecorte?: boolean; duracao?: number;
  medidas?: Medidas | null; referencias?: Caixa[]; enquadrar?: (ajuste: "preencher" | "caber") => void;
}) {
  const [tempo, setTempo] = useState(0);
  const [secao, setSecao] = useState<"Cor" | "Posição" | "Recorte" | "Animação" | "Mescla">("Cor");
  const t = { ...TRANSFORMACAO_PADRAO, ...valor };
  // Os controles mostram as guias em que a camada ficou, sem encaixar: encaixar prenderia o − e o +.
  const aplicar = (novo: Transformacao) => {
    mudar(mudarBaseDaAnimacao(t, novo));
    const mexeuNoLugar = (["x", "y", "escala", "giro", "giroX", "giroY"] as const).some((campo) => novo[campo] !== t[campo]);
    if (medidas && mexeuNoLugar) mostrarGuias(guiasDaCamada(medidas, novo, referencias));
  };
  const controles = (nomes: string[]) => faixas.filter(([campo]) => nomes.includes(campo)).map(([campo, nome, min, max, step]) => <div className="estudio__campo" key={campo}>
    <span>{nome}: {Number(t[campo]).toLocaleString("pt-BR", { maximumFractionDigits: 2 })}{campo.startsWith("giro") ? "°" : ""}</span>
    <Deslizante rotulo={nome} min={min} max={max} passo={step} valor={Number(t[campo])} mudar={v => aplicar({ ...t, [campo]: v })} />
  </div>);
  return <section className="estudio__detalhe" aria-label="Editar esta mídia">
    <strong>Editar esta mídia</strong>
    <div className="estudio__linha" role="tablist" aria-label="Grupo de ajustes">{(["Cor", "Posição", "Recorte", "Animação", "Mescla"] as const).map(nome =>
      <button key={nome} type="button" className={`estudio__chip${secao === nome ? " estudio__chip--ativo" : ""}`} aria-pressed={secao === nome} onClick={() => setSecao(nome)}>{nome}</button>)}
    </div>
    {secao === "Cor" && <>
      <label className="estudio__campo">Filtro<select value={t.filtro} onChange={e => mudar({ ...t, filtro: e.target.value })}>{Object.entries(filtros).map(([id, nome]) => <option key={id} value={id}>{nome}</option>)}</select></label>
      {controles(["brilho", "contraste", "saturacao"])}
    </>}
    {secao === "Posição" && <>
      <div className="estudio__linha">
        <button type="button" className="button" title="Cobre o quadro inteiro, centralizada e reta" onClick={() => { if (enquadrar) { enquadrar("preencher"); aplicar(caberInteira(t)); } else aplicar(preencher(t, medidas)); }}>Preencher</button>
        <button type="button" className="button" title="Inteira dentro do quadro, centralizada e reta" onClick={() => { enquadrar?.("caber"); aplicar(caberInteira(t)); }}>Caber inteira</button>
        <button type="button" className="button" onClick={() => aplicar(centralizar(t))}>Centralizar</button>
      </div>
      <p><small>Arraste para mover, use a roda para zoom. As guias ajudam a alinhar.</small></p>
      {controles(["x", "y", "escala", "giro", "giroX", "giroY", "opacidade"])}
      <div className="estudio__linha">
        <button type="button" className="button" onClick={() => mudar({ ...t, espelharX: !t.espelharX })}>Espelhar na horizontal</button>
        <button type="button" className="button" onClick={() => mudar({ ...t, espelharY: !t.espelharY })}>Espelhar na vertical</button>
      </div>
    </>}
    {secao === "Recorte" && <>
      <p><small>Use Preencher em Posição e mova a imagem para escolher o que fica no quadro. Máscaras recortam o formato sem apagar o original.</small></p>
      <label className="estudio__campo">Máscara<select value={t.mascara ?? "nenhuma"} onChange={e => mudar({...t, mascara:e.target.value as Transformacao["mascara"]})}>
        <option value="nenhuma">Sem máscara</option><option value="circulo">Círculo</option><option value="oval">Oval</option><option value="arredondada">Cantos arredondados</option>
      </select></label>
      {permitirRecorte && <label className="estudio__campo">
        <span><input type="checkbox" checked={t.removerFundo} onChange={e => mudar({ ...t, removerFundo: e.target.checked })} /> Remover o fundo desta mídia</span>
        <small>Processado no dispositivo. Em vídeos, a remoção é feita quadro a quadro e pode demorar.</small>
      </label>}
    </>}
    {secao === "Animação" && <>
      <div className="estudio__linha"><button type="button" className="button" onClick={()=>mudar(kenBurns(t,duracao))}>Ken Burns: aproximar</button>
      <button type="button" className="button" onClick={()=>mudar(kenBurns(t,duracao,true))}>Ken Burns: afastar</button></div>
      <label className="estudio__campo">Tempo local do novo quadro (s)<input type="number" min={0} max={duracao} step={.1} value={tempo} onChange={e=>setTempo(Math.max(0,Math.min(duracao,Number(e.target.value))))} /></label>
      <button type="button" className="button" disabled={(t.quadrosChave?.length??0)>=100} onClick={()=>mudar({...t,quadrosChave:[...(t.quadrosChave??[]).filter(q=>q.tempo!==tempo),{tempo,x:t.x,y:t.y,escala:t.escala,curva:"linear" as const}].sort((a,b)=>a.tempo-b.tempo)})}>Gravar posição e tamanho neste tempo</button>
      <p>Tempos contados desde o início desta mídia na composição. Cada quadro tem posição e zoom próprios.</p>
      {t.quadrosChave?.map((q,i)=><fieldset key={i}><legend>{q.tempo.toLocaleString("pt-BR")} s</legend>
        {(["x","y","escala"] as const).map(c=><label className="estudio__campo" key={c}>{c==="x"?"Horizontal":c==="y"?"Vertical":"Zoom"}<input type="number" min={c==="escala"?.05:-2} max={c==="escala"?6:3} step={.01} value={q[c]} onChange={e=>mudar({...t,quadrosChave:t.quadrosChave?.map((a,j)=>i===j?{...a,[c]:Number(e.target.value)}:a)})}/></label>)}
        <label className="estudio__campo">Interpolação<select value={q.curva??"linear"} onChange={e=>mudar({...t,quadrosChave:t.quadrosChave?.map((a,j)=>i===j?{...a,curva:e.target.value as "linear"|"suave"}:a)})}><option value="linear">Linear</option><option value="suave">Suave</option></select></label>
        <button type="button" className="button" onClick={()=>mudar({...t,quadrosChave:t.quadrosChave?.filter((_,j)=>i!==j)})}>Remover quadro</button>
      </fieldset>)}
      <button type="button" className="button" onClick={()=>mudar({...t,quadrosChave:[]})}>Remover animação</button>
    </>}
    {secao === "Mescla" && <label className="estudio__campo">Mesclagem
      <select value={t.mescla} onChange={e => mudar({ ...t, mescla: e.target.value })}>{Object.entries(mesclas).map(([id, nome]) => <option key={id} value={id}>{nome}</option>)}</select>
    </label>}
    <button type="button" className="button" onClick={() => mudar({ ...TRANSFORMACAO_PADRAO })}>Redefinir esta camada</button>
  </section>;
}
