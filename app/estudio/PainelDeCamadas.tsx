"use client";

import { dividirCamada } from "@/packages/converter-core/estudio-criacao.mjs";
import { useState } from "react";
import { camadasNoTempo, duracaoTotal, type Projeto, type Camada } from "@/packages/converter-core/estudio.mjs";
import { caixaDaCamada, type Caixa, type Medidas } from "@/packages/converter-core/guias.mjs";
import type { Tamanho } from "@/lib/estudio/medidas";
import ControlesDeCamada from "./ControlesDeCamada";
import { CampoDeTempo } from "./Deslizante";
import SeletorDeStickers from "./SeletorDeStickers";

export default function PainelDeCamadas({ projeto, nomes, mudar, importar, instante = 0, medidas = {}, aberta = null, abrir }: {
  instante?: number; projeto: Projeto; nomes: ReadonlyMap<string, File>;
  mudar: (mudanca: (p: Projeto) => Projeto) => void; importar: (arquivo: File) => void;
  medidas?: Readonly<Record<string, Tamanho>>; aberta?: string | null; abrir?: (id: string) => void;
}) {
  const medidasDe = (c: Camada): Medidas | null => medidas[c.arquivo] ? { ...medidas[c.arquivo], quadroW: projeto.largura, quadroH: projeto.altura } : null;
  const referencias = (id: string): Caixa[] => camadasNoTempo(projeto, instante).filter(c => c.id !== id && c.visivel !== false)
    .flatMap(c => { const m = medidasDe(c); const caixa = m && caixaDaCamada(m, c.transformacao); return caixa ? [caixa] : []; });
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
      <p><small>A cor aparece nas áreas livres e substitui o preenchimento desfocado dos clipes.</small></p>
      <label className="estudio__campo">Cor<input type="color" value={(projeto.fundo?.cor ?? "#000000").slice(0,7)} onChange={e => mudar(p => ({ ...p, fundo: { ...p.fundo, cor: e.target.value }, clipes: p.clipes.map(c => c.ajuste === "caber-desfocado" ? { ...c, ajuste: "caber" as const } : c) }))} /></label>
      <button type="button" className="button" onClick={() => mudar(p => ({ ...p, fundo: { cor: "#00000000" }, clipes: p.clipes.map(c => c.ajuste === "caber-desfocado" ? { ...c, ajuste: "caber" as const } : c) }))}>Transparente</button>
      <label><input type="checkbox" checked={Boolean(projeto.fundo?.corFinal)} onChange={e => mudar(p => ({ ...p, fundo: { cor: p.fundo?.cor ?? "#000000", corFinal: e.target.checked ? "#FF7A18" : undefined }, clipes: p.clipes.map(c => c.ajuste === "caber-desfocado" ? { ...c, ajuste: "caber" as const } : c) }))} /> Gradiente</label>
      {projeto.fundo?.corFinal && <label className="estudio__campo">Segunda cor<input type="color" value={projeto.fundo.corFinal.slice(0,7)} onChange={e => mudar(p => ({ ...p, fundo: { cor: p.fundo?.cor ?? "#000000", corFinal: e.target.value } }))} /></label>}
    </details>
    {projeto.camadas?.map(c => <details key={c.id} className="estudio__item" open={aberta === c.id} onToggle={e => { if (e.currentTarget.open) abrir?.(c.id); }}>
      <summary>{nomes.get(c.arquivo)?.name ?? "Camada"}{aberta === c.id ? " · arraste na prévia" : ""}</summary>
      <div className="estudio__detalhe">
        <div className="estudio__linha">
          <button type="button" className="button" onClick={() => atualizar(c.id, { visivel: c.visivel === false })}>{c.visivel === false ? "Mostrar" : "Ocultar"}</button>
          <button type="button" className="button" onClick={() => atualizar(c.id, { ordem: -1, transformacao: { ...c.transformacao, x: .5, y: .5, escala: 1 } })}>Usar como fundo</button>
          <button type="button" className="button" onClick={() => mudar(p => ({ ...p, camadas: p.camadas?.filter(item => item.id !== c.id) }))}>Remover</button>
        </div>
        <button type="button" className="button" onClick={()=>{try{const novo=dividirCamada(projeto,c.id,instante,crypto.randomUUID());mudar(()=>novo);setErro("");}catch(e){setErro(e instanceof Error?e.message:"Não foi possível dividir.");}}}>Dividir faixa no cursor</button>
        <div className="estudio__linha">
          <CampoDeTempo rotulo="Entrada livre (s)" fps={projeto.fps} min={0} max={86400} valor={c.inicio} mudar={v=>atualizar(c.id,{inicio:v,fim:Math.max(c.fim,v+.1)})} />
          <CampoDeTempo rotulo="Saída livre (s)" fps={projeto.fps} min={c.inicio+.1} max={86400} valor={c.fim} mudar={v=>atualizar(c.id,{fim:v})} />
        </div>
        <label className="estudio__campo">Ordem (menor fica atrás)<input type="number" min={-12} max={12} value={c.ordem ?? 1} onChange={e => atualizar(c.id, { ordem: Number(e.target.value) })} /></label>
        <label className="estudio__campo">Aparece em {c.inicio.toFixed(1)} s<input type="range" min={0} max={Math.max(0, c.fim - .1)} step={.1} value={c.inicio} onChange={e => atualizar(c.id, { inicio: Number(e.target.value) })} /></label>
        <label className="estudio__campo">Some em {c.fim.toFixed(1)} s<input type="range" min={c.inicio + .1} max={Math.max(total, c.inicio + .1)} step={.1} value={c.fim} onChange={e => atualizar(c.id, { fim: Number(e.target.value) })} /></label>
        {c.tipo !== "imagem" && <label><input type="checkbox" checked={c.repetir !== false} onChange={e => atualizar(c.id, { repetir: e.target.checked })} /> Repetir animação</label>}
        <ControlesDeCamada duracao={c.fim-c.inicio} valor={c.transformacao} medidas={medidasDe(c)} referencias={referencias(c.id)} mudar={transformacao => atualizar(c.id, { transformacao })} />
      </div>
    </details>)}
  </div>;
}
