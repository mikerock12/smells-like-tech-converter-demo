import type { Metadata } from "next";

import { ANDROID_NA_PLAY } from "@/packages/converter-core/android.mjs";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import InstalarApp from "./InstalarApp";

export const metadata: Metadata = {
  title: "Conversor de arquivos no celular, como app e sem internet — Smells Like Tech Converter",
  description:
    "Instale o conversor na tela inicial do Android ou do iPhone. Converte imagem, áudio, vídeo e PDF no próprio aparelho, sem enviar arquivo e até sem internet.",
};

export default function PaginaDeInstalar() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">No celular</p>
          <h1>
            O conversor na tela inicial,
            <br />
            <em>até sem internet.</em>
          </h1>
          <p className="plugin-page__lead">
            É o mesmo site, instalado como app: abre em tela cheia, converte no próprio aparelho e, depois de preparado,
            funciona sem conexão. Não passa por loja de aplicativos e não pede cadastro.
          </p>
        </header>

        <InstalarApp />

        <section className="plugin-page__secao">
          <h2 className="panel__title">Mande arquivos direto para o app</h2>
          <div className="grid">
            <div className="cartao">
              <strong>Compartilhar, no Android</strong>
              <p>
                Na galeria, no WhatsApp ou no gerenciador de arquivos, toque em Compartilhar e escolha Converter. O arquivo
                abre na oficina, pronto para converter.
              </p>
            </div>
            <div className="cartao">
              <strong>Escolher arquivo, no iPhone</strong>
              <p>
                O iPhone ainda não deixa um app instalado pelo Safari aparecer no Compartilhar. Abra o Converter e toque em
                Escolher arquivos: dá para pegar da Fotos ou do app Arquivos.
              </p>
            </div>
            <div className="cartao">
              <strong>O arquivo continua no aparelho</strong>
              <p>
                Instalado ou não, a conversão acontece no celular. O compartilhar entrega o arquivo ao app dentro do próprio
                aparelho, sem passar por servidor nenhum.
              </p>
            </div>
          </div>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">O que muda no celular</h2>
          <p className="panel__descricao">
            Imagem, PDF e áudio curto convertem bem em qualquer celular recente. Vídeo longo depende da memória do aparelho:
            para arquivos de vários gigabytes, o computador (com o plugin ou o aplicativo para Windows) continua sendo o
            lugar certo. O plugin é só para Windows e não funciona no celular: para converter tudo no celular, sem o
            navegador, {ANDROID_NA_PLAY ? "existe o" : "chega em breve o"}{" "}
            <a className="converter__link" href="/android">app para Android</a>, na Google Play.
          </p>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
