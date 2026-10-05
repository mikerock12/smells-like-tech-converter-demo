import type { MetadataRoute } from "next";

const origem = (process.env.NEXT_PUBLIC_SITE_URL ?? "https://converter.smellsliketech.com.br").replace(/\/$/, "");

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [{ userAgent: "*", allow: "/", disallow: ["/api/", "/obrigado", "/chave"] }],
    sitemap: `${origem}/sitemap.xml`,
  };
}
