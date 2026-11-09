"use client";

import { ajusteFino } from "@/packages/converter-core/musica.mjs";

type Props = {
  valor: number;
  min: number;
  max: number;
  /** Passo dos botões − e + (ou fração relativa, nas frequências). */
  passo: number;
  relativo?: boolean;
  /** Passo da própria barra; por padrão, o dos botões. */
  passoDaBarra?: number;
  rotulo: string;
  mudar: (valor: number) => void;
  /** Valor do duplo clique na barra. */
  padrao?: number;
  vertical?: boolean;
  textoDoValor?: string;
};

/**
 * Barra de ajuste com − e + nas pontas: a barra para o gesto largo, os botões para o passo fino,
 * encaixado na grade do passo (ver ajusteFino em musica.mjs). Na vertical, o + fica em cima.
 */
export function AjusteMusical({ valor, min, max, passo, relativo = false, passoDaBarra, rotulo, mudar, padrao, vertical = false, textoDoValor }: Props) {
  const menos = (
    <button type="button" className="musica__passo" aria-label={`Diminuir ${rotulo}`} disabled={valor <= min} onClick={() => mudar(ajusteFino(valor, -1, passo, min, max, relativo))}>−</button>
  );
  const mais = (
    <button type="button" className="musica__passo" aria-label={`Aumentar ${rotulo}`} disabled={valor >= max} onClick={() => mudar(ajusteFino(valor, 1, passo, min, max, relativo))}>+</button>
  );
  const barra = (
    <input type="range" className={vertical ? "musica__vertical" : undefined} min={min} max={max} step={passoDaBarra ?? (relativo ? "any" : passo)} value={valor}
      aria-label={rotulo} aria-valuetext={textoDoValor} onChange={(e) => mudar(Number(e.target.value))} onDoubleClick={padrao === undefined ? undefined : () => mudar(padrao)} />
  );
  return <span className={`musica__ajuste${vertical ? " musica__ajuste--vertical" : ""}`}>{vertical ? <>{mais}{barra}{menos}</> : <>{menos}{barra}{mais}</>}</span>;
}
