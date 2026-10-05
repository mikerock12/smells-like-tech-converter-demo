"use client";

import { useSyncExternalStore } from "react";

/**
 * Verificação ao vivo do navegador de quem está lendo.
 *
 * A landing afirma que a conversão acontece no dispositivo do usuário. Em vez de
 * pedir confiança, este bloco confere na hora se o navegador dele tem o que a
 * conversão local exige — e diz a verdade quando não tem.
 */

type CheckStatus = "ok" | "faltando";

type Check = {
  label: string;
  detail: string;
  status: CheckStatus;
};

type Report = {
  checks: Check[];
  ready: boolean;
};

function inspect(): Report {
  const cores = typeof navigator.hardwareConcurrency === "number" ? navigator.hardwareConcurrency : 0;

  const checks: Check[] = [
    {
      label: "WebAssembly",
      detail:
        typeof WebAssembly === "object"
          ? "os codecs rodam aqui, na velocidade de um programa instalado"
          : "sem isto o navegador não consegue converter nada",
      status: typeof WebAssembly === "object" ? "ok" : "faltando",
    },
    {
      label: "Processamento paralelo",
      detail:
        cores > 0
          ? `${cores} núcleo${cores > 1 ? "s" : ""} disponíve${cores > 1 ? "is" : "l"} para converter vários arquivos ao mesmo tempo`
          : "o navegador não informou quantos núcleos existem",
      status: typeof Worker === "function" ? "ok" : "faltando",
    },
    {
      label: "Isolamento de origem cruzada",
      detail: crossOriginIsolated
        ? "ativo — libera memória compartilhada entre os núcleos"
        : "inativo — a conversão funciona, mas mais devagar",
      status: crossOriginIsolated ? "ok" : "faltando",
    },
    {
      label: "Área de trabalho privada",
      detail:
        typeof navigator.storage?.getDirectory === "function"
          ? "arquivos grandes ficam guardados no seu disco, não na memória"
          : "arquivos grandes vão precisar caber na memória",
      status: typeof navigator.storage?.getDirectory === "function" ? "ok" : "faltando",
    },
  ];

  return { checks, ready: checks.every((check) => check.status === "ok") };
}

/**
 * O resultado não muda durante a visita: o navegador é o que é. Guardamos a primeira
 * leitura e devolvemos sempre a mesma, para o React não entrar em laço.
 */
let leitura: Report | null = null;

function assinar() {
  // Nada para observar: nenhuma destas capacidades aparece ou some com a página aberta.
  return () => {};
}

function noNavegador(): Report {
  leitura ??= inspect();
  return leitura;
}

/** No servidor não existe navegador para examinar — daí o nulo, que vira "verificando…". */
function noServidor(): Report | null {
  return null;
}

export default function LiveProof() {
  const report = useSyncExternalStore(assinar, noNavegador, noServidor);

  return (
    <aside className="proof" aria-live="polite">
      <div className="proof__head">
        <span className="proof__label">Verificado neste navegador, agora</span>
        <span className={`proof__verdict${report ? (report.ready ? " proof__verdict--ok" : " proof__verdict--partial") : ""}`}>
          {report ? (report.ready ? "Tudo pronto" : "Funciona com ressalvas") : "Verificando…"}
        </span>
      </div>

      <ul className="proof__list">
        {(report?.checks ?? PLACEHOLDER).map((check) => (
          <li key={check.label} className={`proof__item proof__item--${check.status}`}>
            <span className="proof__mark" aria-hidden="true">
              {report ? (check.status === "ok" ? "✓" : "!") : "·"}
            </span>
            <span className="proof__text">
              <strong>{check.label}</strong>
              <small>{report ? check.detail : "conferindo…"}</small>
            </span>
          </li>
        ))}
      </ul>
    </aside>
  );
}

/** Estrutura renderizada no servidor, antes de o navegador se apresentar. */
const PLACEHOLDER: Check[] = [
  { label: "WebAssembly", detail: "", status: "ok" },
  { label: "Processamento paralelo", detail: "", status: "ok" },
  { label: "Isolamento de origem cruzada", detail: "", status: "ok" },
  { label: "Área de trabalho privada", detail: "", status: "ok" },
];
