"use client";

import { camposVisiveis, type Campo, type Opcoes } from "@/lib/converter/ferramentas";

/**
 * Desenha os campos de uma ferramenta a partir da tabela em lib/converter/ferramentas.
 * Um campo novo lá aparece aqui sem código novo.
 */
export default function CamposDeOpcoes({
  ferramentaId,
  opcoes,
  onChange,
  disabled = false,
}: {
  ferramentaId: string;
  opcoes: Opcoes;
  onChange: (opcoes: Opcoes) => void;
  disabled?: boolean;
}) {
  const campos = camposVisiveis(ferramentaId, opcoes);
  if (campos.length === 0) return null;

  const definir = (id: string, valor: string | number | boolean) => onChange({ ...opcoes, [id]: valor });

  return (
    <div className="grid">
      {campos.map((campo) => (
        <CampoDeOpcao key={campo.id} campo={campo} valor={opcoes[campo.id]} definir={definir} disabled={disabled} />
      ))}
    </div>
  );
}

function CampoDeOpcao({
  campo,
  valor,
  definir,
  disabled,
}: {
  campo: Campo;
  valor: string | number | boolean | undefined;
  definir: (id: string, valor: string | number | boolean) => void;
  disabled: boolean;
}) {
  switch (campo.tipo) {
    case "select":
      return (
        <label className={`field${campo.largo ? " field--wide" : ""}`}>
          <span className="field__label">{campo.rotulo}</span>
          <select value={String(valor ?? campo.padrao)} onChange={(evento) => definir(campo.id, evento.target.value)} disabled={disabled}>
            {campo.opcoes.map((opcao) => (
              <option key={opcao.valor} value={opcao.valor}>
                {opcao.rotulo}
              </option>
            ))}
          </select>
        </label>
      );
    case "numero":
      return (
        <label className="field">
          <span className="field__label">
            {campo.rotulo}
            {campo.sufixo ? <small> ({campo.sufixo})</small> : null}
          </span>
          <input
            type="number"
            min={campo.min}
            max={campo.max}
            step={campo.passo ?? 1}
            value={Number(valor ?? campo.padrao)}
            onChange={(evento) => definir(campo.id, Number(evento.target.value))}
            disabled={disabled}
          />
        </label>
      );
    case "faixa":
      return (
        <label className="field field--wide">
          <span className="field__label">
            {campo.rotulo}{" "}
            <strong>
              {Number(valor ?? campo.padrao)}
              {campo.sufixo ?? ""}
            </strong>
          </span>
          <input
            type="range"
            min={campo.min}
            max={campo.max}
            step={campo.passo ?? 1}
            value={Number(valor ?? campo.padrao)}
            onChange={(evento) => definir(campo.id, Number(evento.target.value))}
            disabled={disabled}
          />
        </label>
      );
    case "marcar":
      return (
        <label className="field field--check">
          <input type="checkbox" checked={Boolean(valor ?? campo.padrao)} onChange={(evento) => definir(campo.id, evento.target.checked)} disabled={disabled} />
          <span>{campo.rotulo}</span>
        </label>
      );
    case "tempo":
      return (
        <label className="field">
          <span className="field__label">{campo.rotulo}</span>
          <input
            type="text"
            inputMode="numeric"
            placeholder="mm:ss"
            value={String(valor ?? campo.padrao)}
            onChange={(evento) => definir(campo.id, evento.target.value)}
            disabled={disabled}
          />
          {campo.ajuda ? <small className="field__ajuda">{campo.ajuda}</small> : null}
        </label>
      );
    case "texto":
      return (
        <label className={`field${campo.largo ? " field--wide" : ""}`}>
          <span className="field__label">{campo.rotulo}</span>
          <input type="text" value={String(valor ?? campo.padrao)} onChange={(evento) => definir(campo.id, evento.target.value)} disabled={disabled} />
          {campo.ajuda ? <small className="field__ajuda">{campo.ajuda}</small> : null}
        </label>
      );
    case "cor":
      return (
        <label className="field">
          <span className="field__label">{campo.rotulo}</span>
          <input type="color" value={String(valor ?? campo.padrao)} onChange={(evento) => definir(campo.id, evento.target.value)} disabled={disabled} />
        </label>
      );
    default:
      return null;
  }
}
