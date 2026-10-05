import type { Metadata } from "next";

import { DIAS_DE_TESTE_DO_APP, MAQUINAS_POR_LICENCA } from "@/packages/converter-core/precos.mjs";

import { SiteFooter, SiteHeader } from "../SiteChrome";

export const metadata: Metadata = {
  title: "Termos de uso — Smells Like Tech Converter",
  description: "As regras do uso do site, do plugin e do aplicativo, e das licenças.",
};

export default function PaginaDeTermos() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page legal">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Termos de uso</p>
          <h1>
            Regras simples,
            <br />
            <em>escritas para gente.</em>
          </h1>
        </header>

        <section className="plugin-page__secao">
          <h2 className="panel__title">1. O serviço</h2>
          <p>
            O Smells Like Tech Converter é um conjunto de ferramentas de conversão e edição de mídia que roda no seu
            navegador, no plugin e no aplicativo para Windows, todos operados pela Smells Like Tech Informática
            (CNPJ 30.054.253/0001-09). O plano grátis pode ser usado sem cadastro, para qualquer fim, inclusive
            comercial.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">2. Chaves e licenças</h2>
          <p>
            O plano Pro é liberado por uma chave de acesso válida pelo período comprado (31 ou 366 dias), no site e no
            plugin, em qualquer computador seu. Não há renovação automática: ao fim do período, a chave passa a valer
            como plano grátis até que você compre outra.
          </p>
          <p>
            A licença vitalícia do aplicativo é pessoal, não vence, inclui as atualizações e pode ser ativada em até{" "}
            {MAQUINAS_POR_LICENCA} computadores de uso do titular. O aplicativo pode ser usado por completo por{" "}
            {DIAS_DE_TESTE_DO_APP} dias antes de pedir a licença.
          </p>
          <p>A chave não pode ser revendida nem publicada. Chaves compartilhadas publicamente podem ser desativadas nas versões seguintes.</p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">3. Pagamento, garantia e reembolso</h2>
          <p>
            Os pagamentos são processados pelo Mercado Pago. Você tem 7 dias corridos após a compra para pedir
            reembolso integral, por qualquer motivo, informando o número do pedido. Após o reembolso, a chave
            correspondente deixa de ser válida.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">4. Responsabilidade</h2>
          <p>
            Você é responsável pelo conteúdo que converte e pelos direitos sobre ele. As ferramentas são fornecidas
            como estão; fazemos o possível para que funcionem em todos os navegadores modernos, mas capacidades como
            WebCodecs dependem do navegador e do hardware de cada um — quando algo não é possível no navegador, o site
            diz e aponta o plugin. Não nos responsabilizamos por perdas decorrentes de arquivos corrompidos na origem
            ou de conversões interrompidas.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">5. Software de terceiros</h2>
          <p>
            O produto usa componentes de código aberto, entre eles FFmpeg (LGPL, no aplicativo e no plugin), whisper.cpp,
            Tesseract, pdf.js, pdf-lib, mediabunny, jSquash, lamejs, UTIF.js (MIT) e libheif (LGPL-3.0), cada um sob a
            própria licença. O libheif, que lê fotos HEIC no navegador, é um módulo separado, baixado à parte só quando
            aparece um HEIC; o código-fonte dele é público (github.com/strukturag/libheif) e a versão usada é a do pacote
            libheif-js 1.23.2. As atribuições acompanham o instalador e estão disponíveis mediante pedido.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">6. Foro</h2>
          <p>Fica eleito o foro de Porto Alegre/RS. Última atualização: setembro de 2026.</p>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
