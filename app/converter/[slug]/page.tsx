import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { getFormat } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";
import { PARES, TIPOS, ferramentasPara, rotulo, todosOsSlugs } from "@/packages/converter-core/catalogo.mjs";
import { paginaDeBusca, perguntasFrequentes, privacidadeNoTopo } from "@/packages/converter-core/paginas.mjs";
import { artigosDaConversao } from "@/packages/conteudo/artigos.mjs";
import { IMAGEM_SOCIAL } from "@/lib/social";

import { SiteFooter, SiteHeader } from "../../SiteChrome";
import Oficina from "../Oficina";

/**
 * Uma página por busca: /converter/webp-para-png, /converter/juntar-pdf.
 *
 * O H1 e o título da aba são a própria busca ("Converter WEBP para PNG sem enviar
 * arquivo"). Logo abaixo, uma linha diz que o arquivo não sai do computador e como
 * conferir; a oficina vem em seguida, já na ferramenta e no formato certos — quem chegou
 * do Google converte sem rolar a página. O texto de apoio e o FAQ ficam embaixo.
 */

export const dynamicParams = false;

export function generateStaticParams() {
  return todosOsSlugs().map((slug) => ({ slug }));
}

export async function generateMetadata({ params }: { params: Promise<{ slug: string }> }): Promise<Metadata> {
  const { slug } = await params;
  const pagina = paginaDeBusca(slug);
  if (!pagina) return {};

  return {
    title: pagina.titulo,
    description: pagina.descricao,
    alternates: { canonical: `/converter/${slug}` },
    openGraph: {
      title: pagina.titulo,
      description: pagina.descricao,
      type: "website",
      locale: "pt_BR",
      siteName: "Smells Like Tech Converter",
      images: [IMAGEM_SOCIAL],
    },
    twitter: { card: "summary_large_image", title: pagina.titulo, description: pagina.descricao, images: [IMAGEM_SOCIAL.url] },
  };
}

export default async function PaginaDaFerramenta({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const pagina = paginaDeBusca(slug);
  if (!pagina) notFound();

  const { ferramenta, de, formato } = pagina;
  const parecidos = PARES.filter((par) => par.ferramenta === ferramenta.id && par.slug !== slug).slice(0, 12);
  const mesmoTipo = ferramentasPara(ferramenta.entradas[0]).filter((item) => item.id !== ferramenta.id).slice(0, 6);
  const entradaDescritor = de ? getFormat(de) : null;
  const saidaDescritor = formato ? getFormat(formato) : null;
  const perguntas = pagina.comFaq ? perguntasFrequentes(ferramenta) : [];
  const artigos = artigosDaConversao(slug);

  // Dados estruturados do FAQ, com "<" escapado para nada fechar o <script> antes da hora.
  const faqEstruturado =
    perguntas.length > 0
      ? JSON.stringify({
          "@context": "https://schema.org",
          "@type": "FAQPage",
          mainEntity: perguntas.map((item) => ({
            "@type": "Question",
            name: item.pergunta,
            acceptedAnswer: { "@type": "Answer", text: item.resposta },
          })),
        }).replace(/</g, "\\u003c")
      : null;

  return (
    <>
      <SiteHeader page="converter" />
      <Oficina
        ferramentaInicial={ferramenta.id}
        formatoInicial={(formato as FormatId | null) ?? null}
        opcoesIniciais={pagina.opcoes}
        titulo={pagina.h1}
        privacidade={privacidadeNoTopo(ferramenta)}
      />

      <section className="seo" aria-labelledby="seo-titulo">
        <div className="seo__coluna">
          <h2 id="seo-titulo">Como fazer, em três passos</h2>
          <ol className="passos">
            <li>
              <span>1</span>
              <div>
                <strong>Solte o arquivo na área acima</strong>
                <p>
                  {entradaDescritor
                    ? `Aceitamos ${entradaDescritor.label} (.${entradaDescritor.extensions.join(", .")})`
                    : `Aceitamos ${ferramenta.entradas.map((tipo) => TIPOS[tipo].titulo.toLowerCase()).join(", ")}`}
                  . Vários de uma vez, se quiser.
                </p>
              </div>
            </li>
            <li>
              <span>2</span>
              <div>
                <strong>Confira as opções</strong>
                <p>
                  {saidaDescritor ? `A saída já vem em ${saidaDescritor.label}.` : `A ferramenta “${ferramenta.titulo}” já vem escolhida.`} Ajuste o
                  que precisar e clique em {ferramenta.acao.toLowerCase()}.
                </p>
              </div>
            </li>
            <li>
              <span>3</span>
              <div>
                <strong>Baixe o resultado</strong>
                <p>
                  {ferramenta.navegador
                    ? "A conversão roda dentro do seu navegador. Nada sobe para servidor: dá para desligar a internet no meio e ela continua."
                    : "A conversão roda pelo plugin, na sua máquina, com o seu processador e a sua placa de vídeo. Nada sobe para servidor."}
                </p>
              </div>
            </li>
          </ol>

          {perguntas.length > 0 && (
            <>
              <h2 className="seo__faq-titulo">Perguntas frequentes</h2>
              <dl className="faq">
                {perguntas.map((item) => (
                  <div key={item.pergunta} className="faq__item">
                    <dt>{item.pergunta}</dt>
                    <dd>{item.resposta}</dd>
                  </div>
                ))}
              </dl>
              <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: faqEstruturado ?? "" }} />
            </>
          )}
        </div>

        <div className="seo__coluna">
          <h2>Por que aqui</h2>
          <ul className="ressalvas">
            <li>
              <strong>Sem upload.</strong> Conversor online manda o seu arquivo para um servidor e promete apagar depois. Este não tem para
              onde mandar.
            </li>
            <li>
              <strong>Sem marca d&apos;água, sem cadastro, sem espera.</strong> No plano grátis também.
            </li>
            <li>
              <strong>{ferramenta.navegador ? "Sem instalar nada." : "Com o plugin gratuito."}</strong>{" "}
              {ferramenta.navegador
                ? "Funciona no computador do trabalho, no Chromebook, em qualquer lugar com um navegador atual."
                : "Um programinha de 64 MB que liga o site à sua máquina e usa os mesmos motores do aplicativo completo."}
            </li>
            <li>
              <strong>Sai em {ferramenta.saidas.map((saida) => rotulo(saida)).join(", ")}.</strong> {ferramenta.resumo}
            </li>
          </ul>

          {artigos.length > 0 && (
            <>
              <h3 className="seo__sub">Para ler antes de decidir</h3>
              <ul className="artigos-relacionados">
                {artigos.map((item) => (
                  <li key={item.slug}>
                    <a href={`/${item.slug}`}>{item.titulo}</a>
                  </li>
                ))}
              </ul>
            </>
          )}

          {parecidos.length > 0 && (
            <>
              <h3 className="seo__sub">Conversões parecidas</h3>
              <ul className="pares pares--compacta">
                {parecidos.map((par) => (
                  <li key={par.slug}>
                    <a href={`/converter/${par.slug}`}>
                      {rotulo(par.de)} <span aria-hidden="true">→</span> {rotulo(par.para)}
                    </a>
                  </li>
                ))}
              </ul>
            </>
          )}

          {mesmoTipo.length > 0 && (
            <>
              <h3 className="seo__sub">Mais para {TIPOS[ferramenta.entradas[0]].plural}</h3>
              <ul className="pares pares--compacta">
                {mesmoTipo.map((item) => (
                  <li key={item.id}>
                    <a href={`/converter/${item.slug}`}>{item.titulo}</a>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </section>

      <SiteFooter />
    </>
  );
}
