import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import Estudio from "./Estudio";

export const metadata: Metadata = {
  title: "Estúdio: crie vídeo, GIF e imagem para Instagram, TikTok e YouTube, sem upload — Smells Like Tech Converter",
  description:
    "Junte vídeos e fotos, corte, ponha texto e música e exporte no tamanho certo para Reels, Stories, TikTok, Shorts, Kwai, WhatsApp e outras redes. Tudo no seu navegador, sem enviar arquivo.",
};

export default function EstudioPage() {
  return (
    <>
      <SiteHeader page="estudio" />
      <Estudio />
      <SiteFooter />
    </>
  );
}
