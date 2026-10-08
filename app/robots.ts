import type { MetadataRoute } from "next";

const origem = (process.env.NEXT_PUBLIC_SITE_URL ?? "https://converter.smellsliketech.com.br").replace(/\/$/, "");

export default function robots(): MetadataRoute.Robots {
  return {
    // /chave, /obrigado, /conta e /admin ficam fora da busca pelo noindex de cada página.
    // Bloqueá-las aqui impediria o Google de ler esse noindex ("Bloqueada pelo robots.txt").
    rules: [{ userAgent: "*", allow: "/", disallow: ["/api/"] }],
    sitemap: `${origem}/sitemap.xml`,
  };
}
