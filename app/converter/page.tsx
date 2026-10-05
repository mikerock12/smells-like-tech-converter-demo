import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import Oficina from "./Oficina";

export const metadata: Metadata = {
  title: "Converter e editar imagem, áudio, vídeo e PDF grátis, sem upload — Smells Like Tech Converter",
  description:
    "Converta e edite imagem, áudio, vídeo, PDF e legenda direto no seu navegador. Sem enviar arquivo, sem cadastro e sem marca d'água.",
};

export default function ConverterPage() {
  return (
    <>
      <SiteHeader page="converter" />
      <Oficina />
      <SiteFooter />
    </>
  );
}
