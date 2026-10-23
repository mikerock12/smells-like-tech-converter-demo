"use client";

import { dividirCamada } from "@/packages/converter-core/estudio-criacao.mjs";
import { useState } from "react";
import { duracaoTotal, type Projeto, type Camada } from "@/packages/converter-core/estudio.mjs";
import ControlesDeCamada from "./ControlesDeCamada";
import SeletorDeStickers from "./SeletorDeStickers";

export default function PainelDeCamadas({ projeto, nomes, mudar, importar, instante = 0 }: {
  instante?: number; projeto: Projeto; nomes: ReadonlyMap<string, File>;
  mudar: (mudanca: (p: Projeto) => Projeto) => void; importar: (arquivo: File) => void;
}) {
  const [erro, setErro] = useState("");
  const [catalogo, setCatalogo] = useState(false);
  const total = duracaoTotal(projeto);
  const atualizar = (id: string, campos: Partial<Camada>) => mudar(p => ({ ...p, camadas: p.camadas?.map(c => c.id === id ? { ...c, ...campos } : c) }));
  return <div className="estudio__lista">
    {erro&&<p role="alert">{erro}</p>}
    <p>Faixas livres: escolha entrada, saída e ordem de cada mídia. As faixas podem prolongar a composição; o último quadro principal permanece.</p>
    <label className="button">Adicionar imagem, vídeo ou GIF
      <input type="file" accept="image/*,video/*" hidden disabled={!total} onChange={e => { if (e.target.files?.[0]) importar(e.target.files[0]); e.target.value = ""; }} />
    </label>
    <button type="button" className="button" disabled={!total} onClick={() => setCatalogo(!catalogo)}>Stickers e GIF stickers</button>
    {!total && <p>Adicione uma imagem ou vídeo na aba Clipes para começar.</p>}
    {catalogo && <SeletorDeStickers adicionar={importar} fechar={() => setCatalogo(false)} />}
    <details className="estudio__detalhe"><summary>Fundo da composição</summary>
      <label className="estudio__campo">Cor<input type="color" value={(projeto.fundo?.cor ?? "#000000").slice(0,7)} onChange={e => mudar(p => ({ ...p, fundo: { ...p.fundo, cor: e.target.value } }))} /></label>
      <button type="button" className="button" onClick={() => mudar(p => ({ ...p, fundo: { cor: "#00000000" } }))}>Transparente</button>
      <label><input type="checkbox" checked={Boolean(projeto.fundo?.corFinal)} onChange={e => mudar(p => ({ ...p, fundo: { cor: p.fundo?.cor ?? "#000000", corFinal: e.target.checked ? "#FF7A18" : undefined } }))} /> Gradiente</label>
      {projeto.fundo?.corFinal && <label className="estudio__campo">Segunda cor<input type="color" value={projeto.fundo.corFinal.slice(0,7)} onChange={e => mudar(p => ({ ...p, fundo: { cor: p.fundo?.cor ?? "#000000", corFinal: e.target.value } }))} /></label>}
    </details>
    {projeto.camadas?.map(c => <details key={c.id} className="estudio__item" open>
      <summary>{nomes.get(c.arquivo)?.name ?? "Camada"}</summary>
      <div className="estudio__detalhe">
        <div className="estudio__linha">
          <button type="button" className="button" onClick={() => atualizar(c.id, { visivel: c.visivel === false })}>{c.visivel === false ? "Mostrar" : "Ocultar"}</button>
          <button type="button" className="button" onClick={() => atualizar(c.id, { ordem: -1, transformacao: { ...c.transformacao, x: .5, y: .5, escala: 1 } })}>Usar como fundo</button>
          <button type="button" className="button" onClick={() => mudar(p => ({ ...p, camadas: p.camadas?.filter(item => item.id !== c.id) }))}>Remover</button>
        </div>
        <button type="button" className="button" onClick={()=>{try{const novo=dividirCamada(projeto,c.id,instante,crypto.randomUUID());mudar(()=>novo);setErro("");}catch(e){setErro(e instanceof Error?e.message:"Não foi possível dividir.");}}}>Dividir faixa no cursor</button>
        <label className="estudio__campo">Entrada livre (s)<input type="number" min={0} max={86400} step={.1} value={c.inicio} onChange={e=>atualizar(c.id,{inicio:Number(e.target.value),fim:Math.max(c.fim,Number(e.target.value)+.1)})}/></label>
        <label className="estudio__campo">Saída livre (s)<input type="number" min={c.inicio+.1} max={86400} step={.1} value={c.fim} onChange={e=>atualizar(c.id,{fim:Number(e.target.value)})}/></label>
        <label className="estudio__campo">Ordem (menor fica atrás)<input type="number" min={-12} max={12} value={c.ordem ?? 1} onChange={e => atualizar(c.id, { ordem: Number(e.target.value) })} /></label>
        <label className="estudio__campo">Aparece em {c.inicio.toFixed(1)} s<input type="range" min={0} max={Math.max(0, c.fim - .1)} step={.1} value={c.inicio} onChange={e => atualizar(c.id, { inicio: Number(e.target.value) })} /></label>
        <label className="estudio__campo">Some em {c.fim.toFixed(1)} s<input type="range" min={c.inicio + .1} max={Math.max(total, c.inicio + .1)} step={.1} value={c.fim} onChange={e => atualizar(c.id, { fim: Number(e.target.value) })} /></label>
        {c.tipo !== "imagem" && <label><input type="checkbox" checked={c.repetir !== false} onChange={e => atualizar(c.id, { repetir: e.target.checked })} /> Repetir animação</label>}
        <ControlesDeCamada duracao={c.fim-c.inicio} valor={c.transformacao} mudar={transformacao => atualizar(c.id, { transformacao })} />
      </div>
    </details>)}
  </div>;
}
