"use client";

import { useMemo, useState } from "react";

import { buscarFerramentas, rotulo } from "@/packages/converter-core/catalogo.mjs";

/** Caixa de busca do hub: filtra na hora, sem servidor, pelo catálogo inteiro. */
export default function BuscaDeFerramentas() {
  const [consulta, setConsulta] = useState("");
  const resultados = useMemo(() => (consulta.trim() ? buscarFerramentas(consulta).slice(0, 8) : []), [consulta]);

  return (
    <div className="busca">
      <label className="busca__campo">
        <span className="sr-only">Buscar ferramenta</span>
        <input
          type="search"
          value={consulta}
          onChange={(evento) => setConsulta(evento.target.value)}
          placeholder="O que você precisa fazer? Ex.: mp4 para mp3, juntar pdf, comprimir vídeo…"
          autoComplete="off"
        />
      </label>
      {consulta.trim() && (
        <ul className="busca__resultados" aria-live="polite">
          {resultados.length === 0 && <li className="busca__vazio">Nada com esse nome. Abra a oficina e solte o arquivo: ela mostra o que dá para fazer.</li>}
          {resultados.map((ferramenta) => (
            <li key={ferramenta.id}>
              <a href={`/converter/${ferramenta.slug}`}>
                <strong>{ferramenta.titulo}</strong>
                <small>
                  {ferramenta.navegador ? "no navegador" : "pelo plugin"} · sai em {ferramenta.saidas.map((saida) => rotulo(saida)).join(", ")}
                </small>
              </a>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
