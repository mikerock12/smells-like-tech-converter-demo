"use client";

import { useMemo } from "react";

import { formatBytes, type FormatId } from "@/packages/converter-core/index.mjs";
import { TIPOS, ferramentasPara, type Ferramenta, type TipoDeMidia } from "@/packages/converter-core/catalogo.mjs";

import { FERRAMENTAS_DE_LOTE } from "@/lib/converter/queue";
import type { Opcoes } from "@/lib/converter/ferramentas";
import type { Bloqueio, ItemParaConverter } from "@/lib/converter/limites";

import CamposDeOpcoes from "./CamposDeOpcoes";
import Paywall from "./Paywall";

export interface Item extends ItemParaConverter {
  readonly id: string;
  /** Formato identificado pelo conteúdo, quando deu para saber. */
  readonly formato?: FormatId | null;
}

export interface Grupo {
  readonly id: string;
  readonly tipo: TipoDeMidia;
  readonly itens: readonly Item[];
  readonly ferramenta: string;
  readonly opcoes: Opcoes;
  /** Quando o navegador e o plugin fazem, a pessoa escolhe. */
  readonly peloPlugin: boolean;
  readonly aviso: string | null;
  /** O limite do grátis que travou esta conversão; mostra o aviso com o Pro. */
  readonly bloqueio: Bloqueio | null;
  readonly convertendo: boolean;
}

/**
 * Um cartão por tipo de arquivo. Cada cartão faz uma pergunta só: o que fazer com
 * estes arquivos. As opções aparecem embaixo, e o botão converte o grupo inteiro.
 */
export default function GrupoDeArquivos({
  grupo,
  plano,
  pluginLigado,
  onChange,
  onRemoveItem,
  onMoveItem,
  onConverter,
  onFechar,
}: {
  grupo: Grupo;
  plano: "gratis" | "pro";
  pluginLigado: boolean;
  onChange: (mudanca: Partial<Grupo>) => void;
  onRemoveItem: (itemId: string) => void;
  onMoveItem: (itemId: string, direcao: -1 | 1) => void;
  onConverter: () => void;
  onFechar: () => void;
}) {
  const formatos = useMemo(() => grupo.itens.map((item) => item.formato ?? null), [grupo.itens]);
  const ferramentas = useMemo(() => ordenar(ferramentasPara(grupo.tipo, formatos)), [grupo.tipo, formatos]);
  const ferramenta = ferramentas.find((item) => item.id === grupo.ferramenta) ?? ferramentas[0];
  const tipo = TIPOS[grupo.tipo];
  const total = grupo.itens.reduce((soma, item) => soma + item.bytes, 0);
  const lote = FERRAMENTAS_DE_LOTE.has(ferramenta.id);
  const doDisco = grupo.itens.some((item) => item.caminho);
  const precisaDoPlugin = !ferramenta.navegador || doDisco;
  const podeEscolherOnde = ferramenta.navegador && ferramenta.plugin !== null && pluginLigado && !doDisco;
  const viaPlugin = precisaDoPlugin || (podeEscolherOnde && grupo.peloPlugin);
  const bloqueado = precisaDoPlugin && !pluginLigado;
  const ehPro = ferramenta.plano === "pro";

  return (
    <section className={`grupo grupo--${grupo.tipo}`} aria-label={`${tipo.plural}`}>
      <header className="grupo__topo">
        <div>
          <p className="grupo__tipo">
            {grupo.itens.length === 1 ? tipo.titulo : `${grupo.itens.length} ${tipo.plural}`}
            <small> · {formatBytes(total)}</small>
          </p>
        </div>
        <button type="button" className="button button--ghost" onClick={onFechar} aria-label={`Remover ${tipo.plural}`}>
          Remover todos
        </button>
      </header>

      <ul className="grupo__itens">
        {grupo.itens.map((item, indice) => (
          <li key={item.id} className="grupo__item">
            <span className="grupo__nome" title={item.nome}>
              {item.nome}
            </span>
            <small>
              {formatBytes(item.bytes)}
              {item.caminho ? " · do disco, pelo plugin" : ""}
            </small>
            {lote && grupo.itens.length > 1 && (
              <span className="grupo__ordem">
                <button type="button" onClick={() => onMoveItem(item.id, -1)} disabled={indice === 0} aria-label="Mover para cima">
                  ↑
                </button>
                <button type="button" onClick={() => onMoveItem(item.id, 1)} disabled={indice === grupo.itens.length - 1} aria-label="Mover para baixo">
                  ↓
                </button>
              </span>
            )}
            <button type="button" className="grupo__tirar" onClick={() => onRemoveItem(item.id)} aria-label={`Tirar ${item.nome}`}>
              ×
            </button>
          </li>
        ))}
      </ul>

      <div className="grupo__ferramenta">
        <label className="field field--wide">
          <span className="field__label">O que fazer</span>
          <select
            value={ferramenta.id}
            onChange={(evento) => onChange({ ferramenta: evento.target.value, aviso: null })}
            disabled={grupo.convertendo}
          >
            {ferramentas.map((item) => (
              <option key={item.id} value={item.id}>
                {item.titulo}
                {item.navegador ? "" : " — pelo plugin"}
                {item.plano === "pro" ? " · Pro" : ""}
              </option>
            ))}
          </select>
        </label>
        <p className="grupo__resumo">{ferramenta.resumo}</p>
      </div>

      <CamposDeOpcoes ferramentaId={ferramenta.id} opcoes={grupo.opcoes} onChange={(opcoes) => onChange({ opcoes })} disabled={grupo.convertendo} />

      {podeEscolherOnde && (
        <label className="field field--check grupo__onde">
          <input type="checkbox" checked={grupo.peloPlugin} onChange={(evento) => onChange({ peloPlugin: evento.target.checked })} disabled={grupo.convertendo} />
          <span>Converter pelo plugin (usa a placa de vídeo e o FFmpeg da sua máquina; melhor para arquivos grandes)</span>
        </label>
      )}

      {lote && grupo.itens.length < 2 && ferramenta.id === "pdf.juntar" && (
        <p className="notice">Para juntar, solte pelo menos dois PDFs neste cartão. A ordem da lista é a ordem do arquivo final.</p>
      )}

      {bloqueado && (
        <p className="notice">
          “{ferramenta.titulo}” roda pelo plugin, que não está ligado neste computador.{" "}
          <a className="converter__link" href="/plugin">
            Instale o plugin
          </a>{" "}
          (grátis, 64 MB) e volte aqui: a página reconhece sozinha.
        </p>
      )}

      {ehPro && plano !== "pro" && !bloqueado && ferramenta.limite === "pesado" && !grupo.bloqueio && (
        <p className="notice">
          Esta ferramenta faz parte do <strong>Pro</strong>.{" "}
          <a className="converter__link" href="/precos?origem=ferramenta-pro">
            Ver os planos
          </a>
          .
        </p>
      )}

      {grupo.bloqueio && <Paywall bloqueio={grupo.bloqueio} ferramenta={ferramenta} />}

      {grupo.aviso && <p className="notice notice--warning">{grupo.aviso}</p>}

      <div className="grupo__acoes">
        <button type="button" className="button button--primary" onClick={onConverter} disabled={grupo.convertendo || bloqueado || (lote && ferramenta.id === "pdf.juntar" && grupo.itens.length < 2)}>
          {grupo.convertendo ? "Preparando…" : `${ferramenta.acao} ${grupo.itens.length === 1 ? "" : `${grupo.itens.length} ${lote ? "em um" : "arquivos"}`}`.trim()}
        </button>
        <span className="grupo__onde-roda">{viaPlugin ? "pelo plugin, na sua máquina" : "no seu navegador"}</span>
      </div>
    </section>
  );
}

/** Navegador primeiro, depois plugin; dentro de cada, a ordem do catálogo. */
function ordenar(lista: Ferramenta[]): Ferramenta[] {
  return [...lista].sort((a, b) => Number(b.navegador) - Number(a.navegador));
}
