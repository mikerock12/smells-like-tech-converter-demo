import type { Metadata } from "next";

import { MENSAGENS_DO_WHATSAPP } from "@/packages/conteudo/divulgacao.mjs";

import CopiarTexto from "../CopiarTexto";
import { SiteFooter, SiteHeader } from "../SiteChrome";

/**
 * Material de indicação para a assistência: as mensagens prontas do WhatsApp e o QR
 * code para o balcão. Não é página de busca — fica fora do Google.
 */

export const metadata: Metadata = {
  title: "Indicar o conversor — Smells Like Tech Converter",
  description: "Mensagens prontas para o WhatsApp e QR code para imprimir.",
  robots: { index: false, follow: false },
};

export default function PaginaDeIndicar() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Para a assistência</p>
          <h1>
            Indicar o conversor,
            <br />
            <em>sem discurso de vendedor.</em>
          </h1>
          <p className="plugin-page__lead">
            Duas mensagens prontas para mandar no WhatsApp depois do serviço, na entrega ou no orçamento, e um QR code para deixar no
            balcão.
          </p>
        </header>

        <section className="plugin-page__secao indicar">
          {MENSAGENS_DO_WHATSAPP.map((mensagem) => (
            <article key={mensagem.id} className="indicar__mensagem">
              <h2 className="panel__title">{mensagem.titulo}</h2>
              <p className="indicar__uso">{mensagem.uso}</p>
              <pre className="indicar__texto">{mensagem.texto}</pre>
              <div className="plugin__acoes">
                <CopiarTexto texto={mensagem.texto} rotulo="Copiar a mensagem" classe="button button--primary" />
                <a className="button button--ghost" href={`https://wa.me/?text=${encodeURIComponent(mensagem.texto)}`} target="_blank" rel="noreferrer noopener">
                  Abrir no WhatsApp
                </a>
              </div>
            </article>
          ))}
        </section>

        <section className="plugin-page__secao indicar__qr">
          <h2 className="panel__title">QR code para o balcão</h2>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/marca/qr-converter.svg" alt="QR code que abre converter.smellsliketech.com.br" width={220} height={220} />
          <p>
            Aponte a câmera e abre o conversor. Imprima com uns 4 cm de lado, ao lado de uma frase curta: “Converta arquivo sem enviar
            para ninguém”.
          </p>
          <a className="button" href="/marca/qr-converter.svg" download="qr-converter.svg">
            Baixar o QR (SVG, para imprimir)
          </a>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
