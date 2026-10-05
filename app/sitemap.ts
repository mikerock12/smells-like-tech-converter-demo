import type { MetadataRoute } from "next";

import { paginaDeBusca, slugsEmOrdemDePrioridade } from "@/packages/converter-core/paginas.mjs";
import { ARTIGOS } from "@/packages/conteudo/artigos.mjs";

const origem = (process.env.NEXT_PUBLIC_SITE_URL ?? "https://converter.smellsliketech.com.br").replace(/\/$/, "");

/** Prioridade no sitemap: as páginas da lista A primeiro, como na ordem de publicação. */
const PESO = { A: 0.9, B: 0.8, C: 0.7 } as const;
/** Os artigos ficam um degrau abaixo das páginas de conversão da mesma prioridade. */
const PESO_DO_ARTIGO = { A: 0.8, B: 0.7, C: 0.6 } as const;

export default function sitemap(): MetadataRoute.Sitemap {
  const agora = new Date();
  const fixas = ["", "/converter", "/ferramentas", "/precos", "/plugin", "/aplicativo", "/android", "/instalar", "/privacidade", "/termos", "/codigo-aberto"].map((caminho) => ({
    url: `${origem}${caminho}`,
    lastModified: agora,
    changeFrequency: "weekly" as const,
    priority: caminho === "" ? 1 : 0.8,
  }));

  const conversoes = slugsEmOrdemDePrioridade().map((slug) => {
    const prioridade = paginaDeBusca(slug)?.prioridade;
    return {
      url: `${origem}/converter/${slug}`,
      lastModified: agora,
      changeFrequency: "monthly" as const,
      priority: prioridade ? PESO[prioridade] : 0.6,
    };
  });

  const artigos = ARTIGOS.map((item) => ({
    url: `${origem}/${item.slug}`,
    lastModified: agora,
    changeFrequency: "monthly" as const,
    priority: PESO_DO_ARTIGO[item.prioridade],
  }));

  return [...fixas, ...conversoes, ...artigos];
}
