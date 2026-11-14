"use client";

import { Suspense, lazy, useEffect, useMemo, useState } from "react";

import {
  MODELOS_DO_PACOTE, MODOS_DA_VOZ_NO_VIDEO, combinacoesProntas, configuracaoDoPacote, grupoDoVideo, modeloDoPacote,
  nomeDoGrupo, problemasDoPacote, rotuloDaFaixaDoPacote, type GrupoDoPacote, type IdDoModeloDoPacote, type PedidoDoPacote,
} from "@/packages/converter-core/musica-pacote.mjs";
import { FORMATOS_DE_EXPORTACAO, PRESETS_DE_MASTER } from "@/packages/converter-core/musica-master.mjs";
import { AVISO_DA_SEPARACAO } from "@/packages/converter-core/musica-separacao.mjs";
import type { ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import type { Opcoes } from "@/lib/converter/ferramentas";
import { definirPlacaDeVideo, temPlacaDeVideo, usarPlacaDeVideo } from "@/lib/musica/separacao";

// O editor de efeitos é o do estúdio musical; só carrega quando esta ferramenta aparece.
const EfeitosMusicais = lazy(() => import("@/app/estudio/musica/MixerMusical").then((m) => ({ default: m.EfeitosMusicais })));

type Rascunho = Omit<PedidoDoPacote, "versao"> & { manter?: "voz" | "resto" };

function padrao(video: boolean): Rascunho {
  return video
    ? { modelo: "spleeter", grupos: [grupoDoVideo("spleeter", "resto")], configuracao: configuracaoDoPacote("spleeter"), formato: "mp4", master: "sem-alvo", manter: "resto" }
    : { modelo: "spleeter", grupos: combinacoesProntas("spleeter")[0].grupos, configuracao: configuracaoDoPacote("spleeter"), formato: "wav-24", master: "sem-alvo" };
}

function ler(texto: unknown, video: boolean): Rascunho {
  try {
    const r = JSON.parse(String(texto || "")) as Rascunho;
    if (modeloDoPacote(r.modelo) && Array.isArray(r.grupos) && r.configuracao?.faixas) return r;
  } catch { /* opções vazias: começa do padrão */ }
  return padrao(video);
}

/**
 * O que gerar e como soar, antes de separar: o modelo, os arquivos (cada um junta as faixas
 * que a pessoa marcar) e os ajustes de cada faixa — volume, pan, equalização e os mesmos
 * efeitos do estúdio musical. No vídeo, o que fica no áudio e os efeitos dele.
 */
export default function OpcoesDasFaixas({ ferramentaId, opcoes, onChange, disabled }: { ferramentaId: string; opcoes: Opcoes; onChange: (opcoes: Opcoes) => void; disabled: boolean }) {
  const video = ferramentaId === "video.voz";
  const rascunho = useMemo(() => ler(opcoes.pacote, video), [opcoes.pacote, video]);
  const modelo = modeloDoPacote(rascunho.modelo)!;
  const [alvo, setAlvo] = useState(video ? "master" : "vozes");
  const problemas = video ? [] : problemasDoPacote(modelo.id, rascunho.grupos);

  const gravar = (novo: Rascunho) => onChange({ ...opcoes, pacote: JSON.stringify(novo) });
  // Grava o padrão uma vez, para o pedido sair completo mesmo sem mexer em nada.
  useEffect(() => { if (!opcoes.pacote) gravar(rascunho); });

  const trocarModelo = (id: IdDoModeloDoPacote) => {
    const configuracao = configuracaoDoPacote(id);
    // Os ajustes das faixas que existem nos dois modelos continuam.
    configuracao.faixas = configuracao.faixas.map((f) => rascunho.configuracao.faixas.find((x) => x.id === f.id) ?? f);
    configuracao.master = rascunho.configuracao.master;
    const grupos = video ? [grupoDoVideo(id, rascunho.manter ?? "resto")] : rascunho.grupos
      .map((g) => ({ ...g, faixas: g.faixas.filter((f) => configuracao.faixas.some((x) => x.id === f)) }))
      .filter((g) => g.faixas.length > 0);
    gravar({ ...rascunho, modelo: id, configuracao, grupos: grupos.length ? grupos : combinacoesProntas(id)[0].grupos });
  };
  const mudarGrupos = (grupos: GrupoDoPacote[]) => gravar({ ...rascunho, grupos });
  const mudarProjeto = (f: (p: ProjetoMusical) => ProjetoMusical) => gravar({ ...rascunho, configuracao: f(rascunho.configuracao) });
  const alternar = (i: number, faixa: string) => {
    const grupo = rascunho.grupos[i];
    const faixas = grupo.faixas.includes(faixa) ? grupo.faixas.filter((f) => f !== faixa) : [...grupo.faixas, faixa];
    // O nome acompanha as faixas enquanto a pessoa não escreveu um nome próprio.
    const automatico = grupo.nome === nomeDoGrupo(grupo.faixas, modelo);
    mudarGrupos(rascunho.grupos.map((g, k) => (k === i ? { nome: automatico ? nomeDoGrupo(faixas, modelo) : g.nome, faixas } : g)));
  };

  return (
    <div className="faixas-do-pacote">
      <p className="notice">Recurso experimental: a separação por IA nunca é perfeita e pode levar vários minutos (o RoFormer, bem mais). Nada é enviado: o modelo é baixado uma vez e tudo roda neste aparelho.</p>

      <fieldset className="field field--wide" disabled={disabled}>
        <legend className="field__label">Modelo de separação</legend>
        {MODELOS_DO_PACOTE.map((m) => (
          <label key={m.id} className="field field--check">
            <input type="radio" name={`modelo-${ferramentaId}`} checked={modelo.id === m.id} onChange={() => trocarModelo(m.id)} />
            <span><strong>{m.rotulo}</strong> — {m.resumo}</span>
          </label>
        ))}
      </fieldset>

      {modelo.id === "spleeter" && <PlacaDeVideo disabled={disabled} />}

      {video ? (
        <fieldset className="field field--wide" disabled={disabled}>
          <legend className="field__label">O que fica no áudio do vídeo</legend>
          {MODOS_DA_VOZ_NO_VIDEO.map((m) => (
            <label key={m.id} className="field field--check">
              <input type="radio" name={`manter-${ferramentaId}`} checked={(rascunho.manter ?? "resto") === m.id}
                onChange={() => gravar({ ...rascunho, manter: m.id, grupos: [grupoDoVideo(modelo.id, m.id)] })} />
              <span>{m.rotulo}</span>
            </label>
          ))}
        </fieldset>
      ) : (
        <div className="field field--wide">
          <label className="field">
            <span className="field__label">Começar de uma combinação pronta</span>
            <select value="" disabled={disabled} onChange={(e) => { const c = combinacoesProntas(modelo.id).find((x) => x.id === e.target.value); if (c) mudarGrupos(c.grupos); }}>
              <option value="">Escolha…</option>
              {combinacoesProntas(modelo.id).map((c) => <option key={c.id} value={c.id}>{c.rotulo}</option>)}
            </select>
          </label>
          <p className="field__label">Arquivos dentro do .zip ({rascunho.grupos.length})</p>
          <ol className="faixas-do-pacote__arquivos">
            {rascunho.grupos.map((grupo, i) => (
              <li key={i} className="faixas-do-pacote__arquivo">
                <input type="text" aria-label={`Nome do arquivo ${i + 1}`} value={grupo.nome} disabled={disabled} maxLength={60}
                  onChange={(e) => mudarGrupos(rascunho.grupos.map((g, k) => (k === i ? { ...g, nome: e.target.value } : g)))} />
                <span className="faixas-do-pacote__marcas">
                  {modelo.faixas.map((f) => (
                    <label key={f} className="field--check">
                      <input type="checkbox" checked={grupo.faixas.includes(f)} disabled={disabled} onChange={() => alternar(i, f)} /> {rotuloDaFaixaDoPacote(f)}
                    </label>
                  ))}
                </span>
                <button type="button" className="button button--ghost" disabled={disabled || rascunho.grupos.length === 1} aria-label={`Tirar o arquivo ${grupo.nome}`}
                  onClick={() => mudarGrupos(rascunho.grupos.filter((_, k) => k !== i))}>Tirar</button>
              </li>
            ))}
          </ol>
          <button type="button" className="button" disabled={disabled || rascunho.grupos.length >= 12}
            onClick={() => mudarGrupos([...rascunho.grupos, { nome: `Arquivo ${rascunho.grupos.length + 1}`, faixas: [] }])}>+ Arquivo</button>
          {problemas.length > 0 && <ul className="notice notice--warning">{problemas.map((p) => <li key={p}>{p}</li>)}</ul>}
          <label className="field">
            <span className="field__label">Formato dos arquivos</span>
            <select value={rascunho.formato} disabled={disabled} onChange={(e) => gravar({ ...rascunho, formato: e.target.value })}>
              {FORMATOS_DE_EXPORTACAO.filter((f) => ["wav-24", "wav-16", "mp3", "flac"].includes(f.id)).map((f) => <option key={f.id} value={f.id}>{f.rotulo}</option>)}
            </select>
          </label>
        </div>
      )}

      <label className="field">
        <span className="field__label">Volume final</span>
        <select value={rascunho.master} disabled={disabled} onChange={(e) => gravar({ ...rascunho, master: e.target.value })}>
          {PRESETS_DE_MASTER.map((p) => <option key={p.id} value={p.id}>{p.rotulo}</option>)}
        </select>
      </label>

      <details className="field field--wide faixas-do-pacote__efeitos" open={false}>
        <summary>{video ? "Efeitos, equalização e plugins no áudio que fica" : "Efeitos, equalização e plugins de cada faixa"}</summary>
        <p className="estudio__dica">{video
          ? "Escolha “Master” para aplicar no áudio inteiro que fica no vídeo, ou uma faixa para mexer só nela."
          : "Cada faixa leva os próprios efeitos para todos os arquivos em que entra. O Master vale para todos os arquivos."}</p>
        {!disabled && (
          <Suspense fallback={<p className="estudio__dica">Carregando os efeitos…</p>}>
            <EfeitosMusicais projeto={rascunho.configuracao} alvo={alvo} escolher={setAlvo} mudar={mudarProjeto} />
          </Suspense>
        )}
      </details>

      <p className="estudio__dica">{modelo.id === "roformer" ? "Pode haver vazamento entre as faixas. Comece por um trecho curto: em CPU de PC, 8 s de áudio levam cerca de 2 min." : AVISO_DA_SEPARACAO}</p>
      <p className="estudio__dica">Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar.</p>
    </div>
  );
}

/** Opção experimental: separar na placa de vídeo (WebGPU), com volta automática ao processador. */
export function PlacaDeVideo({ disabled }: { disabled: boolean }) {
  // Só aparece depois de a pessoa escolher um arquivo, no navegador: sem renderização no servidor.
  const [ligada, setLigada] = useState(() => typeof window !== "undefined" && usarPlacaDeVideo());
  if (!temPlacaDeVideo()) return null;
  return (
    <label className="field field--check">
      <input type="checkbox" checked={ligada} disabled={disabled} onChange={(e) => { definirPlacaDeVideo(e.target.checked); setLigada(e.target.checked); }} />
      <span>Separar na placa de vídeo (WebGPU, experimental). Mesmo resultado; baixa 26 MB na primeira vez. Em placas integradas antigas pode ficar mais lento; se o navegador recusar, separa no processador.</span>
    </label>
  );
}
