import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import AtivarChave from "./AtivarChave";

export const metadata: Metadata = {
  title: "Minha chave — Smells Like Tech Converter",
  description: "Ative a chave do Pro neste navegador ou recupere a chave de uma compra.",
  robots: { index: false },
};

export default function PaginaDaChave() {
  return (
    <>
      <SiteHeader page="chave" />
      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Ativar uma chave</p>
          <h1>
            Sua chave funciona
            <br />
            <em>sem internet.</em>
          </h1>
          <p className="plugin-page__lead">
            A chave é um texto assinado digitalmente. Colada aqui, ela fica neste navegador, não consulta servidor nenhum
            e não sabe nada sobre os seus arquivos. Quem comprou também pode{" "}
            <a className="converter__link" href="/conta">
              entrar na conta
            </a>{" "}
            com o e-mail da compra: a chave vem sozinha.
          </p>
        </header>
        <AtivarChave />
      </main>
      <SiteFooter />
    </>
  );
}
