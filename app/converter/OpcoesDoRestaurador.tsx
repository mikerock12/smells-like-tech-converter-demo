"use client";

import { useEffect, useMemo, useState } from "react";

import {
  ETAPAS_DO_RESTAURADOR, FORMATOS_DO_RESTAURADOR, PERFIS_DO_RESTAURADOR, lerOpcoesDoRestaurador, resumoDoDiagnostico, textoDoAviso,
  type OpcoesDoRestaurador as Opcoes, type PlanoDaRestauracao,
} from "@/packages/converter-core/restaurador.mjs";
import { FORMATOS_DE_EXPORTACAO } from "@/packages/converter-core/musica-master.mjs";
import type { Opcoes as OpcoesDaConversao } from "@/lib/converter/ferramentas";
import { APOLLO } from "@/packages/converter-core/apollo.mjs";

/** O modelo Apollo neste navegador: baixar (74 MB, conferido) ou excluir, como os de separação. */
function ModeloApollo({ disabled }: { disabled: boolean }) {
  const [baixado, setBaixado] = useState<boolean | null>(null);
  const [andamento, setAndamento] = useState<number | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  useEffect(() => { void import("@/lib/musica/separacao").then((m) => m.modeloBaixado("apollo")).then(setBaixado); }, []);
  const mudar = async (excluir: boolean) => {
    if (excluir && !confirm("Excluir o modelo Apollo baixado? Ele volta a ser baixado quando precisar.")) return;
    setErro(null);
    try {
      const m = await import("@/lib/musica/separacao");
      if (excluir) await m.apagarModelo("apollo");
      else { setAndamento(0); await m.garantirModelo((f) => setAndamento(f), undefined, "apollo"); }
      setBaixado(await m.modeloBaixado("apollo"));
    } catch (e) { setErro(e instanceof Error ? e.message : String(e)); }
    finally { setAndamento(null); }
  };
  const mb = Math.round(APOLLO.bytes / 1e6);
  return (
    <div className="musica__linha">
      <span className="estudio__dica">Modelo Apollo ({mb} MB): {baixado === null ? "conferindo…" : baixado ? "baixado neste navegador" : "ainda não baixado (baixa sozinho na primeira vez)"}</span>
      {andamento !== null ? <span className="estudio__dica">Baixando… {Math.round(andamento * 100)}%</span>
        : baixado ? <button type="button" className="button button--ghost" disabled={disabled} onClick={() => void mudar(true)}>Excluir modelo</button>
          : <button type="button" className="button button--ghost" disabled={disabled || baixado === null} onClick={() => void mudar(false)}>Baixar agora</button>}
      {erro && <span className="notice notice--warning">{erro}</span>}
    </div>
  );
}

/**
 * Restaurar áudio com IA (experimental): tipo de gravação, intensidade e formato; no
 * Avançado, cada etapa. "Analisar qualidade" mostra o diagnóstico e o que vai ser feito,
 * antes de processar. Tudo neste aparelho.
 */
export default function OpcoesDoRestaurador({ opcoes, onChange, disabled, arquivo }: { opcoes: OpcoesDaConversao; onChange: (o: OpcoesDaConversao) => void; disabled: boolean; arquivo?: File }) {
  const atual = useMemo(() => lerOpcoesDoRestaurador(String(opcoes.restauracao || "{}")), [opcoes.restauracao]);
  const gravar = (novo: Opcoes) => onChange({ ...opcoes, restauracao: JSON.stringify(novo) });
  useEffect(() => { if (!opcoes.restauracao) gravar(atual); });
  const [analise, setAnalise] = useState<{ texto: string; plano: PlanoDaRestauracao } | null>(null);
  const [analisando, setAnalisando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  const analisar = async () => {
    if (!arquivo) return;
    setAnalisando(true);
    setErro(null);
    try {
      const [{ decodificarAudio }, { diagnosticarAudio }] = await Promise.all([import("@/lib/converter/pagina/audio"), import("@/lib/musica/restaurador")]);
      const pcm = await decodificarAudio(arquivo, null);
      const { diagnostico, plano } = await diagnosticarAudio(pcm.canais, pcm.sampleRate, atual);
      setAnalise({ texto: resumoDoDiagnostico(diagnostico), plano });
    } catch (e) {
      setErro(e instanceof Error ? e.message : String(e));
    } finally {
      setAnalisando(false);
    }
  };

  return (
    <div className="faixas-do-pacote">
      <p className="notice">Experimental. O áudio é processado neste aparelho. A restauração estima e limpa; não recria o arquivo master original nem devolve o que a compressão ou a gravação cortaram. O original não muda.</p>
      <fieldset className="field field--wide" disabled={disabled}>
        <legend className="field__label">Tipo de gravação</legend>
        {PERFIS_DO_RESTAURADOR.map((p) => (
          <label key={p.id} className="field field--check">
            <input type="radio" name="perfil-do-restaurador" checked={atual.perfil === p.id} onChange={() => gravar({ ...atual, perfil: p.id })} />
            <span><strong>{p.rotulo}</strong> — {p.resumo}</span>
          </label>
        ))}
      </fieldset>
      {atual.perfil !== "manual" ? (
        <label className="field">
          <span className="field__label">Intensidade ({atual.intensidade}%)</span>
          <input type="range" min={0} max={100} value={atual.intensidade} disabled={disabled} onChange={(e) => gravar({ ...atual, intensidade: Number(e.target.value) })} />
        </label>
      ) : (
        <fieldset className="field field--wide" disabled={disabled}>
          <legend className="field__label">Etapas (0 desliga)</legend>
          {ETAPAS_DO_RESTAURADOR.map((e) => (
            <label key={e.id} className="field">
              <span className="field__label">{e.rotulo} ({atual.etapas[e.id] ?? 0}{e.id === "brilho" ? "" : "%"})</span>
              <input type="range" min={e.id === "brilho" ? -100 : 0} max={100} value={atual.etapas[e.id] ?? 0}
                onChange={(ev) => gravar({ ...atual, etapas: { ...atual.etapas, [e.id]: Number(ev.target.value) } })} />
            </label>
          ))}
        </fieldset>
      )}
      {atual.perfil !== "manual" && (
        <label className="field field--check">
          <input type="checkbox" checked={atual.ia} disabled={disabled} onChange={(e) => gravar({ ...atual, ia: e.target.checked })} />
          <span><strong>Reparo de compressão por IA (Apollo, experimental)</strong> — recria os agudos que o MP3/AAC cortou, mantendo os graves do original. Lento: no PC, cerca de 7 vezes a duração do áudio.</span>
        </label>
      )}
      {(atual.ia || (atual.perfil === "manual" && (atual.etapas.compressao ?? 0) > 0)) && (
        <>
          <ModeloApollo disabled={disabled} />
          <p className="estudio__dica">{APOLLO.atribuicao}</p>
        </>
      )}
      {atual.perfil === "live" && (
        <label className="field">
          <span className="field__label">Preservar plateia e ambiência ({atual.preservarAmbiencia}%)</span>
          <input type="range" min={0} max={100} value={atual.preservarAmbiencia} disabled={disabled} onChange={(e) => gravar({ ...atual, preservarAmbiencia: Number(e.target.value) })} />
        </label>
      )}
      <label className="field">
        <span className="field__label">Formato do arquivo restaurado</span>
        <select value={atual.formato} disabled={disabled} onChange={(e) => gravar({ ...atual, formato: e.target.value })}>
          {FORMATOS_DE_EXPORTACAO.filter((f) => FORMATOS_DO_RESTAURADOR.includes(f.id)).map((f) => <option key={f.id} value={f.id}>{f.rotulo}</option>)}
        </select>
      </label>
      <p className="estudio__dica">Salvar em WAV ou FLAC não melhora a qualidade por si: só evita perder mais na hora de gravar.</p>
      <label className="field field--check">
        <input type="checkbox" checked={atual.relatorio} disabled={disabled} onChange={(e) => gravar({ ...atual, relatorio: e.target.checked })} />
        <span>Baixar também o relatório técnico (.json com o diagnóstico e as etapas; sem o áudio)</span>
      </label>
      {arquivo && (
        <div className="musica__linha">
          <button type="button" className="button button--ghost" disabled={disabled || analisando} onClick={() => void analisar()}>{analisando ? "Analisando…" : "Analisar qualidade"}</button>
        </div>
      )}
      {erro && <p className="notice notice--warning">{erro}</p>}
      {analise && (
        <div className="notice" role="status">
          <p><strong>Diagnóstico:</strong> {analise.texto}</p>
          <p><strong>O que será feito:</strong> {analise.plano.semMudanca ? "nada (o áudio sai como entrou)" : analise.plano.etapas.map((e) => ETAPAS_DO_RESTAURADOR.find((x) => x.id === e.id)?.rotulo ?? e.id).join(", ")}</p>
          <ul>{analise.plano.avisos.map((a) => <li key={a}>{textoDoAviso(a)}</li>)}</ul>
        </div>
      )}
    </div>
  );
}
