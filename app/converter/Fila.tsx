"use client";

import { formatBytes, savingsPercent } from "@/packages/converter-core/index.mjs";

import type { Job, Resultado } from "@/lib/converter/queue";
import type { PluginClient } from "@/lib/plugin/cliente";
import { tempoRestante, type Trabalho } from "@/lib/plugin/protocolo";

/**
 * A fila, com as duas origens lado a lado: o que o navegador converte e o que o plugin
 * converte. Para quem usa, é uma lista só.
 */

export function duracaoLegivel(segundos: number): string {
  const inteiros = Math.round(segundos);
  const h = Math.floor(inteiros / 3600);
  const m = Math.floor((inteiros % 3600) / 60);
  const s = inteiros % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

export interface TrabalhoDoPlugin {
  readonly id: string;
  readonly titulo: string;
  readonly nome: string;
  readonly bytes: number;
  readonly trabalho: Trabalho | null;
  readonly erro: string | null;
}

export default function Fila({
  jobs,
  trabalhosDoPlugin,
  cliente,
  onCancel,
  onRemove,
  onRetry,
  onClear,
  onCancelPlugin,
  onRemovePlugin,
}: {
  jobs: readonly Job[];
  trabalhosDoPlugin: readonly TrabalhoDoPlugin[];
  cliente: PluginClient;
  onCancel: (id: string) => void;
  onRemove: (id: string) => void;
  onRetry: (id: string) => void;
  onClear: () => void;
  onCancelPlugin: (id: string) => void;
  onRemovePlugin: (id: string) => void;
}) {
  if (jobs.length === 0 && trabalhosDoPlugin.length === 0) return null;

  const prontos = jobs.filter((job) => job.status === "done");
  const rodando = jobs.filter((job) => job.status === "running").length + trabalhosDoPlugin.filter((item) => item.trabalho && !item.trabalho.encerrado).length;
  const totalIn = prontos.reduce((soma, job) => soma + job.inputSize, 0);
  const totalOut = prontos.reduce((soma, job) => soma + (job.resultados ?? []).reduce((s, r) => s + r.tamanho, 0), 0);
  const resultados = prontos.flatMap((job) => job.resultados ?? []);

  return (
    <section className="panel" aria-labelledby="fila-titulo">
      <div className="panel__head">
        <h2 id="fila-titulo" className="panel__title">
          Conversões
        </h2>
        <div className="panel__actions">
          {resultados.length > 1 && (
            <button type="button" className="button" onClick={() => baixarTodos(resultados)}>
              Baixar {resultados.length} arquivos
            </button>
          )}
          <button type="button" className="button" onClick={onClear}>
            Limpar encerrados
          </button>
        </div>
      </div>

      {prontos.length > 0 && totalOut > 0 && (
        <p className="summary">
          {prontos.length} pronto(s): {formatBytes(totalIn)} → {formatBytes(totalOut)}{" "}
          {totalOut < totalIn && <strong>({savingsPercent(totalIn, totalOut)}% menor)</strong>}
        </p>
      )}

      <ul className="jobs">
        {jobs.map((job) => (
          <JobRow key={job.id} job={job} onCancel={() => onCancel(job.id)} onRemove={() => onRemove(job.id)} onRetry={() => onRetry(job.id)} />
        ))}
        {trabalhosDoPlugin.map((item) => (
          <PluginRow key={item.id} item={item} cliente={cliente} onCancel={() => onCancelPlugin(item.id)} onRemove={() => onRemovePlugin(item.id)} />
        ))}
      </ul>

      {rodando > 0 && (
        <p className="summary summary--muted">Processando no seu computador — {rodando} em andamento. Nada está sendo enviado.</p>
      )}
    </section>
  );
}

function JobRow({ job, onCancel, onRemove, onRetry }: { job: Job; onCancel: () => void; onRemove: () => void; onRetry: () => void }) {
  const percent = Math.round(job.progress * 100);
  const ativo = job.status === "running" || job.status === "pending";
  const nome = job.nomes.length === 1 ? job.nomes[0] : `${job.nomes[0]} e mais ${job.nomes.length - 1}`;

  return (
    <li className={`job job--${job.status}`}>
      <div className="job__main">
        <p className="job__name" title={job.nomes.join(", ")}>
          {nome}
        </p>
        <p className="job__meta">
          <span className="job__ferramenta">{job.titulo}</span> · {formatBytes(job.inputSize)}
          {job.resultados && job.resultados.length === 1 && <ResumoDoResultado resultado={job.resultados[0]} entrada={job.inputSize} />}
          {job.resultados && job.resultados.length > 1 && <> → {job.resultados.length} arquivos</>}
          {job.nota ? <> · {job.nota}</> : null}
        </p>

        {ativo && (
          <div className="progress" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
            <div className="progress__bar" style={{ width: `${percent}%` }} />
            <span className="progress__label">
              {job.status === "pending" ? "na fila" : job.etapa} {job.status === "running" ? `${percent}%` : ""}
            </span>
          </div>
        )}

        {job.status === "failed" && <p className="job__error">{job.error}</p>}
        {job.status === "cancelled" && <p className="job__meta">Cancelado.</p>}
      </div>

      <div className="job__actions">
        {job.resultados?.map((resultado) => (
          <a key={resultado.url} className="button button--primary" href={resultado.url} download={resultado.nome}>
            Baixar{job.resultados && job.resultados.length > 1 ? ` ${resultado.nome.split(".").pop()?.toUpperCase()}` : ""}
          </a>
        ))}
        {ativo && (
          <button type="button" className="button" onClick={onCancel}>
            Cancelar
          </button>
        )}
        {job.status === "failed" && (
          <button type="button" className="button" onClick={onRetry}>
            Tentar de novo
          </button>
        )}
        {!ativo && (
          <button type="button" className="button button--ghost" onClick={onRemove} aria-label={`Remover ${nome}`}>
            Remover
          </button>
        )}
      </div>
    </li>
  );
}

function ResumoDoResultado({ resultado, entrada }: { resultado: Resultado; entrada: number }) {
  const dimensao =
    resultado.duracao !== undefined
      ? duracaoLegivel(resultado.duracao)
      : resultado.paginas !== undefined
        ? `${resultado.paginas} pág.`
        : resultado.largura && resultado.altura
          ? `${resultado.largura}×${resultado.altura}`
          : null;
  return (
    <>
      {" → "}
      {formatBytes(resultado.tamanho)}
      {dimensao ? ` · ${dimensao}` : ""}{" "}
      {entrada > 0 && resultado.tamanho < entrada && <strong>({savingsPercent(entrada, resultado.tamanho)}%)</strong>}
    </>
  );
}

function PluginRow({ item, cliente, onCancel, onRemove }: { item: TrabalhoDoPlugin; cliente: PluginClient; onCancel: () => void; onRemove: () => void }) {
  const trabalho = item.trabalho;
  const emAndamento = trabalho !== null && !trabalho.encerrado;
  const porcento = Math.round((trabalho?.progresso ?? 0) * 100);
  const falhou = Boolean(item.erro || trabalho?.erro);
  const pronto = trabalho?.encerrado && !falhou;

  return (
    <li className={`job job--plugin${pronto ? " job--done" : falhou ? " job--failed" : ""}`}>
      <div className="job__main">
        <p className="job__name" title={item.nome}>
          {item.nome}
        </p>
        <p className="job__meta">
          <span className="job__ferramenta">{item.titulo}</span> · {formatBytes(item.bytes)} · <span className="job__origem">pelo plugin</span>
          {trabalho && trabalho.bytesDeSaida > 0 && <> → {formatBytes(trabalho.bytesDeSaida)}</>}
        </p>

        {(emAndamento || (!trabalho && !item.erro)) && (
          <div className="progress" role="progressbar" aria-valuenow={porcento} aria-valuemin={0} aria-valuemax={100}>
            <div className="progress__bar" style={{ width: `${porcento}%` }} />
            <span className="progress__label">
              {trabalho ? `${trabalho.etapa} ${porcento}%` : "enviando ao plugin"}
              {trabalho?.velocidade ? ` · ${trabalho.velocidade.toFixed(1)}×` : ""}
              {tempoRestante(trabalho?.segundosRestantes) ? ` · ${tempoRestante(trabalho?.segundosRestantes)}` : ""}
            </span>
          </div>
        )}

        {trabalho?.mensagem && !falhou && <p className="job__meta">{trabalho.mensagem}</p>}
        {falhou && <p className="job__error">{item.erro ?? trabalho?.erro}</p>}
      </div>

      <div className="job__actions">
        {trabalho?.arquivos.map((produzido) => (
          <a key={produzido.indice} className="button button--primary" href={cliente.enderecoDoArquivo(trabalho.id, produzido.indice)} download={produzido.nome}>
            Baixar {trabalho.arquivos.length > 1 ? produzido.nome.split(".").pop()?.toUpperCase() : ""}
          </a>
        ))}
        {emAndamento && (
          <button type="button" className="button" onClick={onCancel}>
            Cancelar
          </button>
        )}
        {!emAndamento && (
          <button type="button" className="button button--ghost" onClick={onRemove}>
            Remover
          </button>
        )}
      </div>
    </li>
  );
}

/** Baixa vários resultados em sequência, sem passar por servidor. */
function baixarTodos(resultados: readonly Resultado[]): void {
  resultados.forEach((resultado, indice) => {
    window.setTimeout(() => {
      const link = document.createElement("a");
      link.href = resultado.url;
      link.download = resultado.nome;
      document.body.appendChild(link);
      link.click();
      link.remove();
    }, indice * 150);
  });
}
