"use client";

import { useEffect, useRef, useState, type PointerEvent as EventoDoPonteiro } from "react";

import { AJUSTE_MAXIMO_DB, AJUSTE_MINIMO_DB, GANHO_MAXIMO_DB, GANHO_MINIMO_DB, duracaoEmQuadros, novaFaixa, quadroDaBatida, quadrosPorBatida, quadrosPorCompasso, type Clipe, type Faixa, type ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import type { EstadoDoMotor } from "@/lib/musica/motor";
import { QUADROS_POR_PICO } from "@/lib/musica/picos";
import { novoId, type Mudar, type Selecao } from "./CriacaoMusical";

type Props = {
  projeto: ProjetoMusical;
  picos: Map<string, Float32Array>;
  estado: EstadoDoMotor;
  zoom: number;
  mudar: Mudar;
  irPara: (quadro: number) => void;
  selecao: Selecao;
  selecionar: (s: Selecao | ((s: Selecao) => Selecao)) => void;
  gravando: string | null;
  exportarFaixa: (id: string) => void;
  abrirEfeitos: (id: string) => void;
};

const LARGURA_DA_CABECA = 250;

function mudarFaixa(mudar: Mudar, id: string, f: (faixa: Faixa) => Faixa, juntar?: string) {
  mudar((p) => ({ ...p, faixas: p.faixas.map((faixa) => (faixa.id === id ? f(faixa) : faixa)) }), juntar);
}

function Onda({ picos, clipe, largura, cor }: { picos: Float32Array | undefined; clipe: Clipe; largura: number; cor: string }) {
  const tela = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const c = tela.current;
    if (!c) return;
    const w = Math.max(1, Math.min(8192, Math.round(largura)));
    const h = 56;
    c.width = w;
    c.height = h;
    const ctx = c.getContext("2d")!;
    ctx.clearRect(0, 0, w, h);
    if (!picos) return;
    ctx.fillStyle = cor;
    const colunas = picos.length / 2;
    for (let x = 0; x < w; x += 1) {
      const de = clipe.inicioNaMidia + (x / w) * clipe.duracao;
      const ate = clipe.inicioNaMidia + ((x + 1) / w) * clipe.duracao;
      let min = 0;
      let max = 0;
      for (let k = Math.floor(de / QUADROS_POR_PICO); k <= Math.min(colunas - 1, Math.floor(ate / QUADROS_POR_PICO)); k += 1) {
        min = Math.min(min, picos[k * 2]);
        max = Math.max(max, picos[k * 2 + 1]);
      }
      const y0 = ((1 - max) / 2) * h;
      const y1 = ((1 - min) / 2) * h;
      ctx.fillRect(x, y0, 1, Math.max(1, y1 - y0));
    }
  }, [picos, clipe.inicioNaMidia, clipe.duracao, largura, cor]);
  return <canvas ref={tela} className="musica__onda" aria-hidden />;
}

export default function LinhaDoTempoMusical({ projeto, picos, estado, zoom, mudar, irPara, selecao, selecionar, gravando, exportarFaixa, abrirEfeitos }: Props) {
  const [menu, setMenu] = useState<string | null>(null);
  const [encaixe, setEncaixe] = useState(true);
  const arraste = useRef<{ clipe: string; faixa: string; x0: number; inicio0: number } | null>(null);
  const taxa = projeto.taxa;
  const px = (quadro: number) => (quadro / taxa) * zoom;
  const quadroDe = (x: number) => (x / zoom) * taxa;
  const total = Math.max(duracaoEmQuadros(projeto) + taxa * 30, taxa * 60, estado.quadro + taxa * 10);
  const largura = px(total);
  const visiveis = projeto.faixas.filter((f) => !f.oculta);
  const ocultas = projeto.faixas.filter((f) => f.oculta);

  // Régua: compassos, com batidas quando o zoom deixa.
  const porCompasso = quadrosPorCompasso(projeto);
  const compassos = Math.ceil(total / porCompasso);
  const passoDosRotulos = Math.max(1, Math.ceil(40 / px(porCompasso)));

  const ajustarAoTempo = (quadro: number) => {
    if (!encaixe) return Math.max(0, Math.round(quadro));
    const batida = quadrosPorBatida(projeto);
    return Math.max(0, quadroDaBatida(projeto, Math.round(quadro / batida)));
  };

  const comecarArraste = (e: EventoDoPonteiro, faixa: Faixa, clipe: Clipe) => {
    e.stopPropagation();
    selecionar((s) => ({ ...s, faixa: faixa.id, clipe: clipe.id }));
    if (gravando) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    arraste.current = { clipe: clipe.id, faixa: faixa.id, x0: e.clientX, inicio0: clipe.inicio };
  };

  const moverArraste = (e: EventoDoPonteiro) => {
    const a = arraste.current;
    if (!a) return;
    const inicio = ajustarAoTempo(a.inicio0 + quadroDe(e.clientX - a.x0));
    mudarFaixa(mudar, a.faixa, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === a.clipe ? { ...c, inicio } : c)) }), `mover-${a.clipe}`);
  };

  const selecionado = selecao.clipe ? projeto.faixas.flatMap((f) => f.clipes.map((c) => ({ faixa: f, clipe: c }))).find((x) => x.clipe.id === selecao.clipe) : null;

  const dividir = () => {
    if (!selecionado) return;
    const { faixa, clipe } = selecionado;
    const corte = Math.round(estado.quadro);
    if (corte <= clipe.inicio || corte >= clipe.inicio + clipe.duracao) return;
    const esquerda = corte - clipe.inicio;
    const a: Clipe = { ...clipe, duracao: esquerda, saidaSuave: Math.min(clipe.saidaSuave, esquerda), entradaSuave: Math.min(clipe.entradaSuave, esquerda) };
    const b: Clipe = { ...clipe, id: novoId("clipe"), inicio: corte, inicioNaMidia: clipe.inicioNaMidia + esquerda, duracao: clipe.duracao - esquerda, entradaSuave: 0, saidaSuave: Math.min(clipe.saidaSuave, clipe.duracao - esquerda) };
    a.saidaSuave = 0;
    mudarFaixa(mudar, faixa.id, (f) => ({ ...f, clipes: f.clipes.flatMap((c) => (c.id === clipe.id ? [a, b] : [c])) }));
  };

  const acaoDaFaixa = (faixa: Faixa, acao: string) => {
    setMenu(null);
    if (acao === "duplicar") {
      mudar((p) => {
        const i = p.faixas.findIndex((f) => f.id === faixa.id);
        const copia: Faixa = { ...structuredClone(faixa), id: novoId("faixa"), nome: `${faixa.nome} (cópia)`, gravar: false, solo: false, clipes: faixa.clipes.map((c) => ({ ...c, id: novoId("clipe") })) };
        return { ...p, faixas: [...p.faixas.slice(0, i + 1), copia, ...p.faixas.slice(i + 1)] };
      });
    } else if (acao === "acima" || acao === "abaixo") {
      mudar((p) => {
        const i = p.faixas.findIndex((f) => f.id === faixa.id);
        const j = acao === "acima" ? i - 1 : i + 1;
        if (j < 0 || j >= p.faixas.length) return p;
        const faixas = [...p.faixas];
        [faixas[i], faixas[j]] = [faixas[j], faixas[i]];
        return { ...p, faixas };
      });
    } else if (acao === "ocultar") mudarFaixa(mudar, faixa.id, (f) => ({ ...f, oculta: true }));
    else if (acao === "remover") {
      if (confirm(`Remover a faixa “${faixa.nome}”? As gravações continuam guardadas e voltam com Desfazer.`)) mudar((p) => ({ ...p, faixas: p.faixas.filter((f) => f.id !== faixa.id) }));
    } else if (acao === "exportar") exportarFaixa(faixa.id);
    else if (acao === "efeitos") abrirEfeitos(faixa.id);
    else if (acao === "barramento") {
      mudar((p) => {
        const id = novoId("bus");
        return { ...p, barramentos: [...p.barramentos, { id, nome: `Bus ${faixa.nome}`, ganhoDb: 0, pan: 0, mudo: false, efeitos: [] }], faixas: p.faixas.map((f) => (f.id === faixa.id ? { ...f, saida: id } : f)) };
      });
    }
  };

  const armar = (faixa: Faixa) => mudar((p) => ({ ...p, faixas: p.faixas.map((f) => ({ ...f, gravar: f.id === faixa.id ? !f.gravar : false })) }));

  return (
    <div className="musica__tempo">
      <div className="musica__barra-do-tempo">
        <button type="button" className="button button--ghost" onClick={() => mudar((p) => ({ ...p, faixas: [...p.faixas, novaFaixa(novoId("faixa"), `Faixa ${p.faixas.length + 1}`, p.faixas.length)] }))}>+ Faixa</button>
        <label className="estudio__chave"><input type="checkbox" checked={encaixe} onChange={(e) => setEncaixe(e.target.checked)} /> Encaixar nas batidas</label>
        {ocultas.length > 0 && <span className="estudio__dica">Ocultas: {ocultas.map((f) => <button key={f.id} type="button" className="estudio__chip" onClick={() => mudarFaixa(mudar, f.id, (x) => ({ ...x, oculta: false }))}>{f.nome}</button>)}</span>}
      </div>
      <div className="musica__rolagem">
        <div className="musica__grade" style={{ width: LARGURA_DA_CABECA + largura }}>
          <div className="musica__regua" style={{ marginLeft: LARGURA_DA_CABECA, width: largura }} onPointerDown={(e) => irPara(ajustarAoTempo(quadroDe(e.clientX - (e.currentTarget as HTMLElement).getBoundingClientRect().left)))} role="slider" aria-label="Posição" aria-valuenow={Math.round(estado.quadro / taxa)} tabIndex={0}>
            {projeto.loop.fim > projeto.loop.inicio && <div className={`musica__loop${projeto.loop.ligado ? " musica__loop--ligado" : ""}`} style={{ left: px(projeto.loop.inicio), width: px(projeto.loop.fim - projeto.loop.inicio) }} />}
            {projeto.punch.ligado && <div className="musica__punch" style={{ left: px(projeto.punch.entrada), width: px(projeto.punch.saida - projeto.punch.entrada) }} title="Punch" />}
            {Array.from({ length: compassos }, (_, i) => i).filter((i) => i % passoDosRotulos === 0).map((i) => (
              <span key={i} className="musica__compasso" style={{ left: px(i * porCompasso) }}>{i + 1}</span>
            ))}
            {projeto.marcadores.map((m) => <span key={m.id} className="musica__marcador" style={{ left: px(m.quadro) }} title={m.nome}>◆ {m.nome}</span>)}
          </div>
          {visiveis.map((faixa) => {
            const medidor = estado.medidores[faixa.id];
            const nivel = medidor ? Math.max(medidor[0], medidor[1]) : 0;
            const ativa = selecao.faixa === faixa.id;
            return (
              <div key={faixa.id} className={`musica__faixa${ativa ? " musica__faixa--ativa" : ""}${faixa.mudo ? " musica__faixa--muda" : ""}`}>
                <div className={`musica__cabeca${menu === faixa.id ? " musica__cabeca--menu" : ""}`} role="group" aria-label={`Faixa ${faixa.nome}`} style={{ width: LARGURA_DA_CABECA, borderLeftColor: faixa.cor }} onFocusCapture={() => selecionar((s) => ({ ...s, faixa: faixa.id }))} onContextMenu={(e) => { e.preventDefault(); setMenu(faixa.id); }}>
                  <div className="musica__linha">
                    <input className="musica__nome" aria-label="Nome da faixa" value={faixa.nome} maxLength={60} onChange={(e) => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, nome: e.target.value }), `nome-${faixa.id}`)} />
                    <button type="button" className="estudio__icone" aria-label={`Mais ações de ${faixa.nome}`} aria-haspopup="menu" aria-expanded={menu === faixa.id} onClick={(e) => { e.stopPropagation(); setMenu(menu === faixa.id ? null : faixa.id); }}>⋯</button>
                  </div>
                  <div className="musica__linha">
                    <button type="button" className={`musica__mini musica__mini--rec${faixa.gravar ? " musica__mini--ligado" : ""}`} aria-pressed={faixa.gravar} onClick={() => armar(faixa)} title="Armar para gravar" disabled={!!gravando}>REC</button>
                    <button type="button" className={`musica__mini${faixa.monitorar ? " musica__mini--ligado" : ""}`} aria-pressed={faixa.monitorar} onClick={() => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, monitorar: !f.monitorar }))} title="Ouvir a entrada ao vivo (use fones)">MON</button>
                    <button type="button" className={`musica__mini musica__mini--solo${faixa.solo ? " musica__mini--ligado" : ""}`} aria-pressed={faixa.solo} onClick={() => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, solo: !f.solo }))}>S</button>
                    <button type="button" className={`musica__mini musica__mini--mudo${faixa.mudo ? " musica__mini--ligado" : ""}`} aria-pressed={faixa.mudo} onClick={() => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, mudo: !f.mudo }))}>M</button>
                    <button type="button" className="musica__mini" onClick={() => abrirEfeitos(faixa.id)} title="Efeitos da faixa">FX{faixa.efeitos.length ? ` ${faixa.efeitos.length}` : ""}</button>
                    <span className="musica__nivel" aria-hidden><span style={{ width: `${Math.min(100, Math.max(0, (20 * Math.log10(nivel + 1e-9) + 60) / 60) * 100)}%`, background: nivel >= 1 ? "#e53935" : undefined }} /></span>
                  </div>
                  <div className="musica__linha">
                    <label className="musica__rotulo" title="Volume">Vol
                      <input type="range" min={GANHO_MINIMO_DB} max={GANHO_MAXIMO_DB} step={0.5} value={faixa.ganhoDb} onChange={(e) => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, ganhoDb: Number(e.target.value) }), `vol-${faixa.id}`)} onDoubleClick={() => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, ganhoDb: 0 }))} aria-valuetext={`${faixa.ganhoDb} dB`} />
                    </label>
                    <label className="musica__rotulo" title="Pan (duplo clique centraliza)">Pan
                      <input type="range" min={-100} max={100} step={1} value={faixa.pan} onChange={(e) => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, pan: Number(e.target.value) }), `pan-${faixa.id}`)} onDoubleClick={() => mudarFaixa(mudar, faixa.id, (f) => ({ ...f, pan: 0 }))} aria-valuetext={faixa.pan === 0 ? "centro" : faixa.pan < 0 ? `${-faixa.pan} à esquerda` : `${faixa.pan} à direita`} />
                    </label>
                  </div>
                  {menu === faixa.id && (
                    <div className="musica__menu" role="menu">
                      {([["duplicar", "Duplicar"], ["acima", "Mover para cima"], ["abaixo", "Mover para baixo"], ["efeitos", "Efeitos"], ["barramento", "Criar barramento para ela"], ["exportar", "Exportar só esta faixa"], ["ocultar", "Ocultar"], ["remover", "Remover"]] as const).map(([id, rotulo]) => (
                        <button key={id} type="button" role="menuitem" onClick={(e) => { e.stopPropagation(); acaoDaFaixa(faixa, id); }}>{rotulo}</button>
                      ))}
                    </div>
                  )}
                </div>
                <div className="musica__pista" style={{ width: largura }} onPointerDown={(e) => { if (e.target === e.currentTarget) irPara(ajustarAoTempo(quadroDe(e.clientX - e.currentTarget.getBoundingClientRect().left))); }}>
                  {faixa.clipes.map((clipe) => (
                    <div key={clipe.id} className={`musica__clipe${clipe.mudo ? " musica__clipe--mudo" : ""}${selecao.clipe === clipe.id ? " musica__clipe--selecionado" : ""}`}
                      style={{ left: px(clipe.inicio), width: Math.max(2, px(clipe.duracao)), borderColor: faixa.cor }}
                      onPointerDown={(e) => comecarArraste(e, faixa, clipe)} onPointerMove={moverArraste} onPointerUp={() => { arraste.current = null; }}
                      role="button" tabIndex={0} aria-label={`${clipe.nome ?? "Clipe"} em ${(clipe.inicio / taxa).toFixed(2)} s`}>
                      <span className="musica__clipe-nome">{clipe.nome ?? "Clipe"}{clipe.mudo ? " (mudo)" : ""}</span>
                      <Onda picos={picos.get(clipe.midia)} clipe={clipe} largura={px(clipe.duracao)} cor={faixa.cor} />
                    </div>
                  ))}
                  {gravando === faixa.id && <div className="musica__gravacao" style={{ left: px(projeto.punch.ligado ? projeto.punch.entrada : estado.quadro), width: 4 }} />}
                </div>
              </div>
            );
          })}
          <div className="musica__cursor" style={{ left: LARGURA_DA_CABECA + px(estado.quadro) }} aria-hidden />
        </div>
      </div>
      {selecionado && (
        <div className="musica__clipe-editor">
          <strong>{selecionado.clipe.nome ?? "Clipe"}</strong>
          <label className="musica__campo">Início (s)<input type="number" step={0.001} min={0} value={(selecionado.clipe.inicio / taxa).toFixed(3)} onChange={(e) => mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === selecionado.clipe.id ? { ...c, inicio: Math.max(0, Math.round(Number(e.target.value) * taxa)) } : c)) }), "inicio")} /></label>
          <label className="musica__campo">Ganho (dB)<input type="number" step={0.5} min={-60} max={24} value={selecionado.clipe.ganhoDb} onChange={(e) => mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === selecionado.clipe.id ? { ...c, ganhoDb: Math.min(24, Number(e.target.value)) } : c)) }), "ganho")} /></label>
          <label className="musica__campo">Entrada suave (ms)<input type="number" step={10} min={0} value={Math.round((selecionado.clipe.entradaSuave / taxa) * 1000)} onChange={(e) => mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === selecionado.clipe.id ? { ...c, entradaSuave: Math.min(c.duracao - c.saidaSuave, Math.max(0, Math.round((Number(e.target.value) / 1000) * taxa))) } : c)) }), "entrada")} /></label>
          <label className="musica__campo">Saída suave (ms)<input type="number" step={10} min={0} value={Math.round((selecionado.clipe.saidaSuave / taxa) * 1000)} onChange={(e) => mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === selecionado.clipe.id ? { ...c, saidaSuave: Math.min(c.duracao - c.entradaSuave, Math.max(0, Math.round((Number(e.target.value) / 1000) * taxa))) } : c)) }), "saida")} /></label>
          <label className="estudio__chave"><input type="checkbox" checked={!selecionado.clipe.mudo} onChange={(e) => mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.map((c) => (c.id === selecionado.clipe.id ? { ...c, mudo: !e.target.checked } : c)) }))} /> Tomada ativa</label>
          <button type="button" className="button button--ghost" onClick={dividir}>Dividir no cursor</button>
          <button type="button" className="button button--ghost" onClick={() => { mudarFaixa(mudar, selecionado.faixa.id, (f) => ({ ...f, clipes: f.clipes.filter((c) => c.id !== selecionado.clipe.id) })); selecionar((s) => ({ ...s, clipe: null })); }}>Remover clipe</button>
          <span className="estudio__dica">A mídia original nunca muda: cortar, mover e remover mexem só no clipe.</span>
        </div>
      )}
      {selecionado === null && selecao.faixa && (() => {
        const f = projeto.faixas.find((x) => x.id === selecao.faixa);
        if (!f) return null;
        return (
          <div className="musica__clipe-editor">
            <strong>{f.nome}</strong>
            <label className="musica__campo">Ajuste L (dB)<input type="number" step={0.5} min={AJUSTE_MINIMO_DB} max={AJUSTE_MAXIMO_DB} value={f.ajusteEsquerdoDb} onChange={(e) => mudarFaixa(mudar, f.id, (x) => ({ ...x, ajusteEsquerdoDb: Math.max(AJUSTE_MINIMO_DB, Math.min(AJUSTE_MAXIMO_DB, Number(e.target.value))) }), "ajL")} /></label>
            <label className="musica__campo">Ajuste R (dB)<input type="number" step={0.5} min={AJUSTE_MINIMO_DB} max={AJUSTE_MAXIMO_DB} value={f.ajusteDireitoDb} onChange={(e) => mudarFaixa(mudar, f.id, (x) => ({ ...x, ajusteDireitoDb: Math.max(AJUSTE_MINIMO_DB, Math.min(AJUSTE_MAXIMO_DB, Number(e.target.value))) }), "ajR")} /></label>
            <label className="musica__campo">Entrada<select value={f.canaisDeEntrada} onChange={(e) => mudarFaixa(mudar, f.id, (x) => ({ ...x, canaisDeEntrada: Number(e.target.value) }))}><option value={1}>Mono</option><option value={2}>Estéreo</option></select></label>
            <span className="estudio__dica">O pan move a faixa no estéreo; o ajuste L/R corrige o nível de cada canal sozinho.</span>
          </div>
        );
      })()}
    </div>
  );
}
