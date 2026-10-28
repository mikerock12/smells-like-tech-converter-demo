"use client";

import { useEffect, useState } from "react";
import type { Guia } from "@/packages/converter-core/guias.mjs";

/**
 * As guias que a prévia mostra piscando por um instante. Quem mexe na camada (o gesto na prévia
 * ou os controles do painel) avisa aqui, e a prévia escuta, sem passar estado pela árvore toda.
 */
const ouvintes = new Set<(guias: readonly Guia[]) => void>();
let ultimas = "";

export function mostrarGuias(guias: readonly Guia[], vibrar = false): void {
  const chave = guias.map((g) => `${g.vertical}${g.posicao.toFixed(3)}`).sort().join("|");
  // Vibra só quando uma linha nova encaixa, como no criador de figurinhas do Android.
  if (vibrar && chave && chave !== ultimas) navigator.vibrate?.(12);
  ultimas = chave;
  for (const ouvinte of ouvintes) ouvinte(guias);
}

export function useGuiasNaTela(): readonly Guia[] {
  const [guias, setGuias] = useState<readonly Guia[]>([]);
  useEffect(() => {
    let apagar: ReturnType<typeof setTimeout> | undefined;
    const ouvir = (novas: readonly Guia[]) => {
      setGuias(novas);
      clearTimeout(apagar);
      apagar = setTimeout(() => setGuias([]), 1200);
    };
    ouvintes.add(ouvir);
    return () => { ouvintes.delete(ouvir); clearTimeout(apagar); };
  }, []);
  return guias;
}
