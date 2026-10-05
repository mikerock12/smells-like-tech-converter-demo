import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import MinhaConta from "./MinhaConta";

export const metadata: Metadata = {
  title: "Minha conta — Smells Like Tech Converter",
  description: "Entre com o e-mail da compra para ver as suas licenças e levar a chave para este navegador.",
  robots: { index: false },
};

export default function PaginaDaConta() {
  return (
    <>
      <SiteHeader page="conta" />
      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Minha conta</p>
          <h1>
            Suas licenças,
            <br />
            <em>em qualquer navegador.</em>
          </h1>
          <p className="plugin-page__lead">
            Entre com o e-mail da compra: mandamos um código, sem senha nenhuma. As suas chaves ficam aqui, e a do Pro
            vai sozinha para este navegador. A conta é só para quem comprou; converter continua sem cadastro.
          </p>
        </header>
        <MinhaConta />
      </main>
      <SiteFooter />
    </>
  );
}
