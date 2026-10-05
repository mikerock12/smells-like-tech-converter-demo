"use client";

import { useCallback, useEffect, useState } from "react";

import { avaliarChave, guardarChave, lerChaveGuardada, type EstadoDaChave } from "./chave";

/**
 * O plano de quem está usando o site, num lugar só.
 *
 * Começa como "gratis" enquanto a chave guardada é conferida (é rápido: uma verificação
 * de assinatura). Ativar e remover a chave passam por aqui para todas as telas verem a
 * mudança ao mesmo tempo.
 */

type Ouvinte = () => void;
const ouvintes = new Set<Ouvinte>();
let estadoAtual: EstadoDaChave = { situacao: "sem-chave" };
let carregado = false;

function avisar() {
  for (const ouvinte of ouvintes) ouvinte();
}

async function carregar() {
  estadoAtual = await avaliarChave(lerChaveGuardada());
  carregado = true;
  avisar();
}

export function usePlano() {
  const [estado, setEstado] = useState<EstadoDaChave>(estadoAtual);
  const [pronto, setPronto] = useState(carregado);

  useEffect(() => {
    const ouvinte = () => {
      setEstado(estadoAtual);
      setPronto(carregado);
    };
    ouvintes.add(ouvinte);
    if (!carregado) void carregar();
    else ouvinte();
    return () => {
      ouvintes.delete(ouvinte);
    };
  }, []);

  const ativar = useCallback(async (chave: string): Promise<EstadoDaChave> => {
    const avaliada = await avaliarChave(chave);
    if (avaliada.situacao === "valida") {
      guardarChave(chave);
      estadoAtual = avaliada;
      avisar();
    }
    return avaliada;
  }, []);

  const remover = useCallback(() => {
    guardarChave(null);
    estadoAtual = { situacao: "sem-chave" };
    avisar();
  }, []);

  const plano: "gratis" | "pro" = estado.situacao === "valida" ? estado.plano : "gratis";

  return { estado, plano, pro: plano === "pro", pronto, ativar, remover };
}
