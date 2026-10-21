"use client";

import { TRANSFORMACAO_PADRAO, type Transformacao } from "@/packages/converter-core/camadas.mjs";

const filtros = { original: "Original", vivido: "Vívido", pb: "P&B", sepia: "Sépia", vintage: "Vintage", frio: "Frio", quente: "Quente", negativo: "Negativo" };
const mesclas = { normal: "Normal", multiplicar: "Multiplicar", clarear: "Clarear", sobrepor: "Sobrepor" };
const faixas: [keyof Transformacao, string, number, number, number][] = [
  ["x", "Posição horizontal", -.5, 1.5, .01], ["y", "Posição vertical", -.5, 1.5, .01],
  ["escala", "Tamanho", .05, 3, .01], ["giro", "Girar (Z)", -180, 180, .1],
  ["giroX", "Inclinar para frente / trás (X)", -180, 180, .1],
  ["giroY", "Inclinar para esquerda / direita (Y)", -180, 180, .1],
  ["opacidade", "Opacidade", 0, 1, .01], ["brilho", "Brilho", -1, 1, .01],
  ["contraste", "Contraste", -.8, 1, .01], ["saturacao", "Saturação", -1, 1, .01],
];

export default function ControlesDeCamada({ valor, mudar, permitirRecorte = true }: { valor?: Partial<Transformacao>; mudar: (valor: Partial<Transformacao>) => void; permitirRecorte?: boolean }) {
  const t = { ...TRANSFORMACAO_PADRAO, ...valor };
  return <details className="estudio__detalhe"><summary>Posição, giro 3D, filtros e mesclagem</summary>
    {faixas.map(([campo, nome, min, max, step]) => <label className="estudio__campo" key={campo}>
      {nome}: {Number(t[campo]).toLocaleString("pt-BR", { maximumFractionDigits: 2 })}{campo.startsWith("giro") ? "°" : ""}
      <input type="range" min={min} max={max} step={step} value={Number(t[campo])} onChange={e => mudar({ ...t, [campo]: Number(e.target.value) })} />
    </label>)}
    <div className="estudio__linha">
      <button type="button" className="button" onClick={() => mudar({ ...t, espelharX: !t.espelharX })}>Espelhar na horizontal</button>
      <button type="button" className="button" onClick={() => mudar({ ...t, espelharY: !t.espelharY })}>Espelhar na vertical</button>
    </div>
    <label className="estudio__campo">Filtro
      <select value={t.filtro} onChange={e => mudar({ ...t, filtro: e.target.value })}>{Object.entries(filtros).map(([id, nome]) => <option key={id} value={id}>{nome}</option>)}</select>
    </label>
    <label className="estudio__campo">Mesclagem
      <select value={t.mescla} onChange={e => mudar({ ...t, mescla: e.target.value })}>{Object.entries(mesclas).map(([id, nome]) => <option key={id} value={id}>{nome}</option>)}</select>
    </label>
    {permitirRecorte && <label className="estudio__campo">
      <span><input type="checkbox" checked={t.removerFundo} onChange={e => mudar({ ...t, removerFundo: e.target.checked })} /> Remover o fundo desta mídia</span>
      <small>Processado no dispositivo. Em vídeos, a remoção é feita quadro a quadro e pode demorar.</small>
    </label>}
    <button type="button" className="button" onClick={() => mudar({ ...TRANSFORMACAO_PADRAO })}>Redefinir esta camada</button>
  </details>;
}
