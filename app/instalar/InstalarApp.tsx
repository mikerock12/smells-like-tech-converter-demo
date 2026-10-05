"use client";

import { useEffect, useState, useSyncExternalStore } from "react";

import { formatBytes } from "@/packages/converter-core/index.mjs";

import {
  acabouDeInstalar,
  acompanharOffline,
  ehAparelhoDaApple,
  emModoApp,
  instalar,
  ouvirInstalacao,
  podeInstalar,
  type EstadoOffline,
} from "@/lib/pwa/app";

const semAssinatura = () => () => {};

/** O botão de instalar (ou o passo a passo) e o estado do modo sem internet. */
export default function InstalarApp() {
  const noApp = useSyncExternalStore(semAssinatura, emModoApp, () => false);
  const apple = useSyncExternalStore(semAssinatura, ehAparelhoDaApple, () => false);
  const direto = useSyncExternalStore(ouvirInstalacao, podeInstalar, () => false);
  const instalado = useSyncExternalStore(ouvirInstalacao, acabouDeInstalar, () => false);

  return (
    <>
      <aside className={`instalacao ${noApp || instalado ? "instalacao--pronta" : ""}`} aria-live="polite">
        {noApp ? (
          <>
            <span className="instalacao__selo">no app</span>
            <h2>Você já está no app</h2>
            <p className="instalacao__detalhe">Ele abre pela tela inicial, em tela cheia. Abaixo, o que já está guardado para usar sem internet.</p>
          </>
        ) : instalado ? (
          <>
            <span className="instalacao__selo">instalado</span>
            <h2>Pronto: o Converter está na tela inicial</h2>
            <p className="instalacao__detalhe">Abra por lá. Na primeira abertura, ele baixa o que falta para funcionar sem internet.</p>
          </>
        ) : direto ? (
          <>
            <h2>Instalar como app</h2>
            <p className="instalacao__detalhe">Um toque e o Converter vai para a tela inicial. Sem loja, sem cadastro.</p>
            <button type="button" className="button button--primary" onClick={() => void instalar()}>
              Instalar o app
            </button>
          </>
        ) : apple ? (
          <>
            <h2>Instalar no iPhone ou iPad</h2>
            <ol className="instalar__passos">
              <li>Abra esta página no Safari.</li>
              <li>
                Toque em Compartilhar (o quadrado com a seta para cima) na barra de baixo.
              </li>
              <li>Escolha &ldquo;Adicionar à Tela de Início&rdquo; e confirme.</li>
            </ol>
          </>
        ) : (
          <>
            <h2>Instalar no celular</h2>
            <ol className="instalar__passos">
              <li>Abra esta página no Chrome, no Edge ou no Samsung Internet do celular.</li>
              <li>Toque no menu (⋮) e escolha &ldquo;Instalar app&rdquo; ou &ldquo;Adicionar à tela inicial&rdquo;.</li>
            </ol>
            <p className="instalacao__detalhe">
              No computador também dá: no Chrome e no Edge, o ícone de instalar aparece na barra de endereço.
            </p>
          </>
        )}
      </aside>

      <ModoOffline />
    </>
  );
}

function ModoOffline() {
  const [estado, setEstado] = useState<EstadoOffline | null>(null);
  const [pedido, setPedido] = useState(0);

  useEffect(() => acompanharOffline(setEstado, pedido > 0), [pedido]);

  const completo = estado?.preparado === true && !estado.erro;
  const preparando = estado?.preparando === true && !completo;

  return (
    <section className="plugin-page__secao">
      <h2 className="panel__title">Para usar sem internet</h2>
      <p className="panel__descricao">
        O site guarda automaticamente no aparelho os motores de imagem, áudio, vídeo, PDF, legenda e OCR, inclusive na aba comum.
        Aguarde a preparação completa antes de desconectar. Só os motores: seus arquivos nunca.
      </p>
      {estado === null ? (
        <p className="panel__descricao">Este navegador ainda não preparou o modo offline. Ele aparece depois do primeiro carregamento completo do site.</p>
      ) : completo ? (
        <p className="notice">
          Pronto: todos os {estado.total} arquivos dos motores estão guardados neste aparelho ({formatBytes(estado.bytes)}). O conversor
          funciona sem internet.
        </p>
      ) : (
        <>
          <div className="offline__barra" role="progressbar" aria-valuemin={0} aria-valuemax={estado.total} aria-valuenow={estado.guardados}>
            <span style={{ width: `${Math.round((estado.guardados / Math.max(1, estado.total)) * 100)}%` }} />
          </div>
          <p className="panel__descricao">
            {estado.guardados} de {estado.total} arquivos guardados.
          </p>
          <div className="plugin__acoes">
            <button type="button" className="button button--primary" disabled={preparando} onClick={() => setPedido((atual) => atual + 1)}>
              {preparando ? "Baixando…" : `Baixar tudo para usar sem internet (${formatBytes(estado.bytes)})`}
            </button>
          </div>
        </>
      )}
      <p className="panel__descricao">
        O OCR em português, inglês e espanhol já entra nessa preparação, antes do primeiro uso. <a href="#nota-offline">* Veja as condições no rodapé.</a>
      </p>
    </section>
  );
}
