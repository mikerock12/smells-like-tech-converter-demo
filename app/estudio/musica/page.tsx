import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../../SiteChrome";
import CriacaoMusical from "./CriacaoMusical";

export const metadata: Metadata = {
  title: "Criação musical: grave em várias faixas, mixe e separe voz e instrumentos, sem upload — Smells Like Tech Converter",
  description:
    "Grave várias faixas, separe instrumentos por IA, mude a velocidade com ou sem alterar o tom, transponha por semitons, identifique BPM e tonalidade e iguale ritmos. Tudo no navegador, sem enviar arquivo.",
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
