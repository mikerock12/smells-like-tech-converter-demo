"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { PluginClient } from "./cliente";
import type { EstadoDoPlugin } from "./protocolo";

/**
 * Descoberta do plugin, num lugar só.
 *
 * Estava repetida em cada componente que precisava saber se ele existe, e o resultado
 * era pior do que a duplicação: a área de arrastar do conversor não sabia do plugin, e
 * recusava um MP3 dizendo "formato não reconhecido" enquanto o plugin ligado, logo
 * abaixo na mesma página, sabia converter aquilo.
 */
export function usePlugin(opcoes: { insistir?: boolean } = {}) {
  const { insistir = false } = opcoes;
  const cliente = useMemo(() => new PluginClient(), []);
  const [estado, setEstado] = useState<EstadoDoPlugin>({ situacao: "procurando" });

  const procurar = useCallback(async () => {
    const resultado = await cliente.procurar();
    setEstado(resultado);
    return resultado;
  }, [cliente]);

  useEffect(() => {
    let vivo = true;
    let temporizador: number | undefined;

    async function tentar() {
      const resultado = await cliente.procurar();
      if (!vivo) return;

      setEstado(resultado);

      // Em algumas telas vale continuar olhando: a pessoa pode estar instalando agora.
      if (insistir && resultado.situacao !== "ligado") {
        temporizador = window.setTimeout(tentar, 4_000);
      }
    }

    void tentar();
    return () => {
      vivo = false;
      if (temporizador) window.clearTimeout(temporizador);
    };
  }, [cliente, insistir]);

  return {
    cliente,
    estado,
    procurar,
    ligado: estado.situacao === "ligado",
    apresentacao: estado.situacao === "ligado" ? estado.apresentacao : null,
  };
}
