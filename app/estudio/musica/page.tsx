import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../../SiteChrome";
import CriacaoMusical from "./CriacaoMusical";

export const metadata: Metadata = {
  title: "Criação musical: grave em várias faixas, mixe e separe voz e instrumentos, sem upload — Smells Like Tech Converter",
  description:
    "Grave uma faixa ouvindo as outras, importe WAV, MP3, FLAC, M4A e OGG, separe voz, bateria, baixo e outros por IA, aplique efeitos, mixe e exporte em WAV, FLAC ou MP3. Tudo no seu navegador, sem enviar arquivo.",
};

export default function CriacaoMusicalPage() {
  return (
    <>
      <SiteHeader page="estudio" />
      <CriacaoMusical />
      <SiteFooter />
    </>
  );
}
