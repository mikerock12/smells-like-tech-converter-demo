"use client";

/**
 * Um controle deslizante com − e + ao lado, para o ajuste fino que o dedo e o mouse não acertam:
 * cada toque anda um passo. É o DeslizanteComAjusteFino do Android.
 */
export function Deslizante({ valor, min, max, passo, mudar, rotulo }: {
  valor: number; min: number; max: number; passo: number; mudar: (valor: number) => void; rotulo: string;
}) {
  const andar = (sinal: number) => mudar(arredondar(Math.max(min, Math.min(max, valor + sinal * passo)), passo));
  return <span className="estudio__deslizante">
    <button type="button" className="estudio__fino" onClick={() => andar(-1)} disabled={valor <= min} aria-label={`${rotulo}: diminuir`}>−</button>
    <input type="range" min={min} max={max} step={passo} value={valor} aria-label={rotulo} onChange={(e) => mudar(Number(e.target.value))} />
    <button type="button" className="estudio__fino" onClick={() => andar(1)} disabled={valor >= max} aria-label={`${rotulo}: aumentar`}>+</button>
  </span>;
}

/**
 * Um instante em segundos com milésimos, e − / + que andam um quadro do projeto. Serve para
 * cortar no quadro exato, como os campos de trecho do criador de figurinhas.
 */
export function CampoDeTempo({ rotulo, valor, min, max, fps, mudar }: {
  rotulo: string; valor: number; min: number; max: number; fps: number; mudar: (valor: number) => void;
}) {
  const quadro = 1 / Math.max(1, fps);
  const limitar = (v: number) => Math.round(Math.max(min, Math.min(max, v)) * 1000) / 1000;
  return <label className="estudio__campo">{rotulo}
    <span className="estudio__deslizante">
      <button type="button" className="estudio__fino" onClick={() => mudar(limitar(valor - quadro))} disabled={valor <= min} aria-label={`${rotulo}: um quadro antes`}>−</button>
      <input type="number" inputMode="decimal" min={min} max={max} step={0.001} value={Number(valor.toFixed(3))}
        onChange={(e) => { const v = Number(e.target.value); if (Number.isFinite(v)) mudar(limitar(v)); }} />
      <button type="button" className="estudio__fino" onClick={() => mudar(limitar(valor + quadro))} disabled={valor >= max} aria-label={`${rotulo}: um quadro depois`}>+</button>
    </span>
  </label>;
}

/** Evita 0,30000000000000004 depois de somar passos. */
function arredondar(valor: number, passo: number): number {
  const casas = Math.max(0, Math.min(6, Math.ceil(-Math.log10(passo)) + 1));
  return Number(valor.toFixed(casas));
}
