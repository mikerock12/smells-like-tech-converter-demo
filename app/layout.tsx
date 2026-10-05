import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { headers } from "next/headers";
import "./globals.css";

import RegistroDoApp from "./RegistroDoApp";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const title = "Smells Like Tech Converter — converta sem enviar seus arquivos";
const description =
  "Converta imagens e arquivos dentro do seu próprio navegador. Nada é enviado para servidor: a conversão acontece no seu computador, sem upload e sem cadastro.";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const forwardedHost = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host") ?? "";
  const isLocal = /^(localhost|127\.0\.0\.1)(:\d+)?$/i.test(forwardedHost);
  const configuredOrigin = process.env.NEXT_PUBLIC_SITE_URL;
  let origin = "https://converter.smellsliketech.com.br";
  if (configuredOrigin) {
    try { origin = new URL(configuredOrigin).origin; } catch { /* keep the canonical origin */ }
  } else if (isLocal) {
    origin = `http://${forwardedHost}`;
  }
  const socialImage = `${origin}/og.png`;

  return {
    // Resolve o canonical das páginas ("/converter/heic-para-jpg") para o endereço inteiro.
    // Visto pelo workers.dev, o canonical continua apontando para o domínio oficial.
    metadataBase: new URL(origin),
    title,
    description,
    icons: {
      icon: [{ url: "/marca/icone-256.png", type: "image/png", sizes: "256x256" }],
      shortcut: "/marca/icone.ico",
    },
    openGraph: {
      title,
      description,
      type: "website",
      locale: "pt_BR",
      siteName: "Smells Like Tech Converter",
      images: [{ url: socialImage, width: 1200, height: 630, alt: "Converta seus arquivos sem entregá-los a ninguém." }],
    },
    twitter: { card: "summary_large_image", title, description, images: [socialImage] },
  };
}

/** A barra do navegador e a do app instalado na cor do fundo do site. */
export const viewport: Viewport = { themeColor: "#0e0e12" };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    // O site é escuro por definição, como o aplicativo. Avisar o navegador faz a
    // barra de rolagem e os controles nativos nascerem escuros também.
    <html lang="pt-BR" style={{ colorScheme: "dark" }}>
      {/*
        O app instalável. Estas tags vão aqui, e não no generateMetadata: o vinext entrega
        o que o generateMetadata devolve dentro do <body>, e o Chrome só lê o manifesto no
        <head>. Fora dele, o site não é instalável ("Não é possível instalar o app").
      */}
      <head>
        <link rel="manifest" href="/manifest.webmanifest" />
        <link rel="apple-touch-icon" href="/marca/icone-apple-180.png" sizes="180x180" />
        <meta name="mobile-web-app-capable" content="yes" />
        <meta name="apple-mobile-web-app-capable" content="yes" />
        <meta name="apple-mobile-web-app-title" content="Converter" />
        <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
      </head>
      <body className={`${geistSans.variable} ${geistMono.variable}`}>
        {children}
        <RegistroDoApp />
      </body>
    </html>
  );
}
