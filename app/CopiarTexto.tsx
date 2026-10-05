"use client";

import { useState } from "react";

/** Botão que copia um texto (hash, mensagem pronta) e confirma por dois segundos. */
export default function CopiarTexto({ texto, rotulo = "Copiar", classe = "button button--ghost" }: { texto: string; rotulo?: string; classe?: string }) {
  const [copiado, setCopiado] = useState(false);

  return (
    <button
      type="button"
      className={classe}
      onClick={() => {
        void navigator.clipboard
          .writeText(texto)
          .then(() => {
            setCopiado(true);
            window.setTimeout(() => setCopiado(false), 2000);
          })
          .catch(() => setCopiado(false));
      }}
    >
      {copiado ? "Copiado" : rotulo}
    </button>
  );
}
