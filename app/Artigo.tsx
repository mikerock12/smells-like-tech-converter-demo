import type { Metadata } from "next";
import type { ReactNode } from "react";

import { artigo as buscarArtigo } from "@/packages/conteudo/artigos.mjs";
import { paginaDeBusca } from "@/packages/converter-core/paginas.mjs";
import { LIMITES, MAQUINAS_POR_LICENCA, formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import { IMAGEM_SOCIAL } from "@/lib/social";

import { SiteFooter, SiteHeader } from "./SiteChrome";

/**
 * O molde de todo artigo, na estrutura da lista priorizada:
 *
 * 1. a busca da pessoa, no primeiro parágrafo;
 * 2. o risco do upload (mesmo com "apagamos em 1 hora");
 * 3. como fazer aqui, em 3 passos, com o link da ferramenta certa;
 * 4. quando o grátis basta e quando o Pro ou o aplicativo fazem sentido;
 * 5. FAQ.
 *
 * O molde garante a ordem e o visual; cada página escreve só o conteúdo.
 */

/** Preços e limites como texto, lidos da tabela única: nenhum artigo fica com número velho. */
export const PRECOS = {
  pro: formatarReais(produto("pro-mensal")!.centavos),
  diasDoPro: produto("pro-mensal")!.dias,
  proAnual: formatarReais(produto("pro-anual")!.centavos),
  app: formatarReais(produto("app-vitalicio")!.centavos),
  completo: formatarReais(produto("completo-vitalicio")!.centavos),
  maquinas: MAQUINAS_POR_LICENCA,
  lote: LIMITES.gratis.lote,
  video: LIMITES.gratis.video,
  pdf: LIMITES.gratis.pdf,
  transcricao: LIMITES.gratis.transcricao,
  ocr: LIMITES.gratis.ocr,
} as const;

export interface Pergunta {
  readonly pergunta: string;
  readonly resposta: string;
}

export function metadadosDoArtigo(slug: string): Metadata {
  const artigo = buscarArtigo(slug);
  if (!artigo) return {};
  return {
    title: `${artigo.titulo} — Smells Like Tech Converter`,
    description: artigo.resumo,
    alternates: { canonical: `/${slug}` },
    openGraph: {
      title: artigo.titulo,
      description: artigo.resumo,
      type: "article",
      locale: "pt_BR",
      siteName: "Smells Like Tech Converter",
      images: [IMAGEM_SOCIAL],
    },
    twitter: { card: "summary_large_image", title: artigo.titulo, description: artigo.resumo, images: [IMAGEM_SOCIAL.url] },
  };
}

export default function Artigo({
  slug,
  abertura,
  risco,
  passos,
  planos,
  faq,
  extra,
}: {
  slug: string;
  /** A busca da pessoa, respondida de cara. */
  abertura: ReactNode;
  risco: { titulo: string; corpo: ReactNode };
  /** `conversao` é a página de conversão do botão principal; sem ela, o botão abre a oficina. */
  passos: { titulo: string; conversao?: string; itens: readonly [ReactNode, ReactNode, ReactNode]; outras?: readonly string[] };
  planos: ReactNode;
  faq: readonly Pergunta[];
  /** Um bloco a mais, entre o risco e os passos (tabela de comparação, verificação ao vivo). */
  extra?: ReactNode;
}) {
  const artigo = buscarArtigo(slug);
  if (!artigo) throw new Error(`Artigo desconhecido: ${slug}`);

  const principal = passos.conversao ? paginaDeBusca(passos.conversao) : null;
  const outras = (passos.outras ?? []).map((item) => paginaDeBusca(item)).filter((item) => item !== null);

  const dados = JSON.stringify([
    {
      "@context": "https://schema.org",
      "@type": "Article",
      headline: artigo.titulo,
      description: artigo.resumo,
      inLanguage: "pt-BR",
      dateModified: "2026-09-25",
      author: { "@type": "Organization", name: "Smells Like Tech Informática" },
    },
    {
      "@context": "https://schema.org",
      "@type": "FAQPage",
      mainEntity: faq.map((item) => ({ "@type": "Question", name: item.pergunta, acceptedAnswer: { "@type": "Answer", text: item.resposta } })),
    },
  ]).replace(/</g, "\\u003c");

  return (
    <>
      <SiteHeader page="outra" />
      <main className="artigo">
        <article>
          <header className="artigo__topo">
            <p className="converter__eyebrow">Guia · atualizado em setembro de 2026</p>
            <h1>{artigo.titulo}</h1>
            <div className="artigo__abertura">{abertura}</div>
            {principal ? (
              <a className="primary-action" href={`/converter/${principal.slug}`}>
                {semOFinal(principal.h1)} agora <span aria-hidden="true">→</span>
              </a>
            ) : (
              <a className="primary-action" href="/converter">
                Abrir a oficina <span aria-hidden="true">→</span>
              </a>
            )}
          </header>

          <section className="artigo__secao">
            <h2>{risco.titulo}</h2>
            {risco.corpo}
          </section>

          {extra}

          <section className="artigo__secao">
            <h2>{passos.titulo}</h2>
            <ol className="passos">
              {passos.itens.map((item, indice) => (
                <li key={indice}>
                  <span>{indice + 1}</span>
                  <div>{item}</div>
                </li>
              ))}
            </ol>
            {outras.length > 0 && (
              <ul className="pares pares--compacta artigo__outras">
                {outras.map((item) => (
                  <li key={item.slug}>
                    <a href={`/converter/${item.slug}`}>{semOFinal(item.h1)}</a>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="artigo__secao">
            <h2>Quando o grátis basta, e quando não</h2>
            {planos}
          </section>

          <section className="artigo__secao">
            <h2>Perguntas frequentes</h2>
            <dl className="faq">
              {faq.map((item) => (
                <div key={item.pergunta} className="faq__item">
                  <dt>{item.pergunta}</dt>
                  <dd>{item.resposta}</dd>
                </div>
              ))}
            </dl>
          </section>

          <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: dados }} />
        </article>
      </main>
      <SiteFooter />
    </>
  );
}

/** "Juntar PDF sem enviar arquivo" → "Juntar PDF", para caber num botão. */
function semOFinal(h1: string): string {
  return h1.replace(/ sem enviar (o )?(arquivo|áudio)$/, "");
}
