"use client";

import { useEffect, useRef, useState } from "react";
import { analisarMusica, type AnaliseMusical } from "@/packages/converter-core/musica-analise.mjs";
import { clipeComVelocidade, velocidadeParaIgualar } from "@/packages/converter-core/musica-transformacao.mjs";
import type { Clipe, Faixa, ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import type { FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { exportarMusica } from "@/lib/musica/exportar";
import type { Mudar } from "./CriacaoMusical";
import { AjusteMusical } from "./AjusteMusical";

type Par = { faixa: Faixa; clipe: Clipe };

export default function TempoETom({ projeto, mudar, canaisDe, fontes, parar, gravando }: {
  projeto: ProjetoMusical; mudar: Mudar; canaisDe: (id: string) => Float32Array[] | undefined;
  fontes: () => Promise<Map<string, FonteDeAudio>>; parar: () => void; gravando: boolean;
}) {
  const clipes: Par[] = projeto.faixas.flatMap((faixa) => faixa.clipes.filter((c) => !c.mudo).map((clipe) => ({ faixa, clipe })));
  const [escolhido, setEscolhido] = useState<string | null>(null);
  const [origem, setOrigem] = useState<string | null>(null);
  const [destino, setDestino] = useState<string | null>(null);
  const [analises, setAnalises] = useState<Record<string, AnaliseMusical>>({});
  const [processando, setProcessando] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const [previa, setPrevia] = useState<{ url: string; projeto: ProjetoMusical } | null>(null);
  const [preparando, setPreparando] = useState(false);
  const geracao = useRef<AbortController | null>(null);
  useEffect(() => () => { geracao.current?.abort(); }, [projeto, gravando]);
  useEffect(() => () => { if (previa) URL.revokeObjectURL(previa.url); }, [previa]);

  const ouvirMaster = async () => {
    if (gravando || geracao.current) return;
    const controle = new AbortController();
    geracao.current = controle;
    setPrevia(null);
    setPreparando(true);
    setAviso(null);
    try {
      const [resultado] = await exportarMusica(projeto, await fontes(), { formato: "wav-16", master: "sem-alvo", alcance: "projeto", cadaFaixa: false, cauda: true }, () => {}, () => controle.signal.aborted);
      if (controle.signal.aborted) return;
      parar();
      setPrevia({ url: URL.createObjectURL(resultado.blob), projeto });
    } catch (erro) {
      if (!controle.signal.aborted) setAviso(erro instanceof Error ? erro.message : "Não foi possível montar a prévia da master.");
    } finally {
      geracao.current = null;
      setPreparando(false);
    }
  };
  if (!clipes.length) return <p className="estudio__dica">Importe ou grave um áudio para ajustar tempo e tom.</p>;
  const atual = clipes.find((p) => p.clipe.id === escolhido) ?? clipes[0];
  const referencia = clipes.find((p) => p.clipe.id === origem) ?? clipes[0];
  const alvo = clipes.find((p) => p.clipe.id === destino) ?? clipes[1] ?? clipes[0];
  const midia = projeto.midias.find((m) => m.id === atual.clipe.midia)!;

  const mudarClipe = (par: Par, f: (c: Clipe) => Clipe, juntar?: string) => mudar((p) => ({
    ...p,
    faixas: p.faixas.map((faixa) => faixa.id === par.faixa.id ? { ...faixa, clipes: faixa.clipes.map((c) => c.id === par.clipe.id ? f(c) : c) } : faixa),
  }), juntar);

  const analisar = async (par: Par) => {
    setProcessando(true);
    setAviso(null);
    try {
      await new Promise<void>((resolve) => setTimeout(resolve, 30));
      const canais = canaisDe(par.clipe.midia);
      if (!canais) throw new Error("A mídia ainda está sendo carregada. Tente de novo em alguns segundos.");
      const resultado = analisarMusica(canais, projeto.taxa);
      setAnalises((anterior) => ({ ...anterior, [par.clipe.id]: resultado }));
      if (!resultado.bpm && !resultado.tonalidade) setAviso("Não foi possível estimar este áudio. Tente um trecho com ritmo ou notas mais claros.");
    } catch (e) {
      setAviso(e instanceof Error ? e.message : String(e));
    } finally {
      setProcessando(false);
    }
  };

  const igualar = () => {
    const a = analises[referencia.clipe.id]?.bpm;
    const b = analises[alvo.clipe.id]?.bpm;
    if (!a || !b) { setAviso("Identifique o BPM dos dois clipes antes de igualar."); return; }
    const velocidade = velocidadeParaIgualar(a, b);
    const midiaDoAlvo = projeto.midias.find((m) => m.id === alvo.clipe.midia)!;
    mudarClipe(alvo, (c) => ({ ...clipeComVelocidade(c, midiaDoAlvo, velocidade), preservarTom: true }));
    setAviso(`${alvo.clipe.nome ?? "O clipe"} agora toca a ${(velocidade * 100).toFixed(1)}% para acompanhar ${a} BPM, sem mudar o tom.`);
  };

  return (
    <div className="musica__tempo-tom">
      <div className="musica__colunas">
        <section>
          <h3>Master · mistura completa</h3>
          <p className="estudio__dica">Aplicado à prévia da master e à exportação da mistura. A linha do tempo mantém o ritmo da edição.</p>
          <label className="musica__rotulo">Velocidade da master · {Math.round((projeto.master.velocidade ?? 1) * 100)}%
            <AjusteMusical valor={(projeto.master.velocidade ?? 1) * 100} min={25} max={400} passo={1} rotulo="Velocidade da master" padrao={100}
              mudar={(v) => mudar((p) => ({ ...p, master: { ...p.master, velocidade: v / 100 } }), "velocidade-master")} />
          </label>
          <button type="button" className="button" disabled={gravando || preparando} onClick={() => void ouvirMaster()}>{preparando ? "Montando a prévia…" : "Ouvir a master"}</button>
          {/* eslint-disable-next-line jsx-a11y/media-has-caption -- Prévia da mistura criada pela própria pessoa. */}
          {!gravando && previa?.projeto === projeto && <audio controls autoPlay src={previa.url} onPlay={parar} aria-label="Prévia da master" />}
          <label className="estudio__chave"><input type="checkbox" checked={projeto.master.preservarTom ?? true}
            onChange={(e) => mudar((p) => ({ ...p, master: { ...p.master, preservarTom: e.target.checked } }))} /> Preservar o tom da master</label>
          <label className="musica__rotulo">Transposição da master · {projeto.master.semitons ?? 0} semitons
            <AjusteMusical valor={projeto.master.semitons ?? 0} min={-12} max={12} passo={1} rotulo="Transposição da master" padrao={0}
              mudar={(v) => mudar((p) => ({ ...p, master: { ...p.master, semitons: v } }), "tom-master")} />
          </label>
        </section>
      </div>
      <label className="musica__campo">Clipe para editar ou analisar
        <select value={atual.clipe.id} onChange={(e) => setEscolhido(e.target.value)}>{clipes.map((p) => <option key={p.clipe.id} value={p.clipe.id}>{p.faixa.nome} · {p.clipe.nome ?? "Clipe"}</option>)}</select>
      </label>
      {aviso && <p className="musica__aviso" role="status">{aviso}</p>}
      <div className="musica__colunas">
        <section>
          <h3>1. Identificar o áudio</h3>
          <button type="button" className="button button--primary" onClick={() => void analisar(atual)} disabled={processando}>Identificar BPM e tonalidade</button>
          {analises[atual.clipe.id] && <strong>{analises[atual.clipe.id].bpm ? `${analises[atual.clipe.id].bpm} BPM` : "BPM não identificado"} · {analises[atual.clipe.id].tonalidade ?? "Tonalidade não identificada"}</strong>}
          <p className="estudio__dica">São estimativas feitas neste aparelho. Em músicas com tempo livre ou muitos instrumentos, confira de ouvido.</p>
        </section>
        <section>
          <h3>2. Mais rápida ou mais lenta</h3>
          <label className="musica__rotulo">Velocidade: {Math.round((atual.clipe.velocidade ?? 1) * 100)}% · {(atual.clipe.duracao / projeto.taxa).toFixed(2)} s
            <AjusteMusical valor={(atual.clipe.velocidade ?? 1) * 100} min={25} max={400} passo={1} rotulo={`Velocidade de ${atual.clipe.nome ?? atual.faixa.nome}`} textoDoValor={`${Math.round((atual.clipe.velocidade ?? 1) * 100)}%`} padrao={100} mudar={(v) => mudarClipe(atual, (c) => clipeComVelocidade(c, midia, v / 100), `velocidade-${atual.clipe.id}`)} />
          </label>
          <label className="estudio__chave"><input type="checkbox" checked={atual.clipe.preservarTom ?? true} onChange={(e) => mudarClipe(atual, (c) => ({ ...c, preservarTom: e.target.checked }))} /> Preservar o tom ao mudar a velocidade</label>
          <p className="estudio__dica">Ligado: muda a duração sem mudar a afinação. Desligado: velocidade e tom mudam juntos, como numa fita.</p>
        </section>
        <section>
          <h3>3. Subir ou descer a tonalidade</h3>
          <label className="musica__rotulo">Transposição: {(atual.clipe.semitons ?? 0) > 0 ? "+" : ""}{Math.round(atual.clipe.semitons ?? 0)} semitons
            <AjusteMusical valor={atual.clipe.semitons ?? 0} min={-12} max={12} passo={1} rotulo={`Transposição de ${atual.clipe.nome ?? atual.faixa.nome}`} padrao={0} mudar={(v) => mudarClipe(atual, (c) => ({ ...c, semitons: v }), `tom-${atual.clipe.id}`)} />
          </label>
          <p className="estudio__dica">Uma oitava tem 12 semitons. A duração não muda ao transpor.</p>
        </section>
        <section>
          <h3>4. Igualar o ritmo de outra faixa</h3>
          <label className="musica__campo">Ritmo de referência
            <select value={referencia.clipe.id} onChange={(e) => setOrigem(e.target.value)}>{clipes.map((p) => <option key={p.clipe.id} value={p.clipe.id}>{p.faixa.nome} · {p.clipe.nome ?? "Clipe"}</option>)}</select>
          </label>
          <label className="musica__campo">Clipe que vai mudar
            <select value={alvo.clipe.id} onChange={(e) => setDestino(e.target.value)}>{clipes.map((p) => <option key={p.clipe.id} value={p.clipe.id}>{p.faixa.nome} · {p.clipe.nome ?? "Clipe"}</option>)}</select>
          </label>
          <button type="button" className="button button--ghost" disabled={processando} onClick={() => { void analisar(referencia); void analisar(alvo); }}>Identificar o BPM dos dois</button>
          <span>Referência: {analises[referencia.clipe.id]?.bpm ? `${analises[referencia.clipe.id].bpm} BPM` : "—"} · destino: {analises[alvo.clipe.id]?.bpm ? `${analises[alvo.clipe.id].bpm} BPM` : "—"}</span>
          <button type="button" className="button button--primary" disabled={referencia.clipe.id === alvo.clipe.id || !analises[referencia.clipe.id]?.bpm || !analises[alvo.clipe.id]?.bpm} onClick={igualar}>Aplicar o ritmo da referência</button>
          <p className="estudio__dica">O destino é acelerado ou desacelerado com o tom preservado. Escolha qualquer clipe como origem e qualquer outro como destino.</p>
        </section>
      </div>
    </div>
  );
}
