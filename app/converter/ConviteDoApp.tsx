"use client";

import { useSyncExternalStore } from "react";

import { emModoApp, instalar, ouvirInstalacao, podeInstalar } from "@/lib/pwa/app";

const semAssinatura = () => () => {};
const noCelularForaDoApp = () => window.matchMedia("(pointer: coarse)").matches && !emModoApp();

/**
 * No celular, fora do app: convida a instalar. Onde o navegador oferece o botão (Chrome e
 * Edge no Android), instala na hora; no iPhone, leva ao passo a passo de /instalar.
 */
export default function ConviteDoApp() {
  const mostrar = useSyncExternalStore(semAssinatura, noCelularForaDoApp, () => false);
  const direto = useSyncExternalStore(ouvirInstalacao, podeInstalar, () => false);
  if (!mostrar) return null;

  return (
    <p className="convite-do-app">
      <span>No celular? Instale como app e converta até sem internet.</span>
      {direto ? (
        <button type="button" className="button button--ghost" onClick={() => void instalar()}>
          Instalar o app
        </button>
      ) : (
        <a className="converter__link" href="/instalar">
          Como instalar
        </a>
      )}
    </p>
  );
}
