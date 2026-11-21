import type { Metadata } from "next";
import { SiteFooter, SiteHeader } from "../../SiteChrome";
import Editor from "../../editor/imagem/Editor";
import "../../editor/imagem/imagem.css";

export const metadata: Metadata = {
  title: "Abrir JPEG XL no navegador | Smells Like Tech Converter",
  description: "Visualize os pixels e a transparência de uma imagem JXL no dispositivo. Abra a foto no editor e exporte em PNG, JPEG, WebP ou JXL.",
  alternates: { canonical: "/converter/abrir-jxl" },
};

export default function AbrirJxl() {
  return <><SiteHeader page="converter" /><Editor titulo="Abrir JPEG XL no navegador" />
    <section className="seo" aria-label="Sobre a leitura de JPEG XL"><div className="seo__coluna">
      <h2>Onde a foto é aberta?</h2>
      <p>O navegador baixa o codec e lê a imagem no dispositivo. O editor trabalha em sRGB de 8 bits e mostra a transparência da foto.</p>
      <h2>Como compartilhar a imagem?</h2>
      <p>Escolha PNG para manter a transparência ou JPEG para usar um fundo branco. Você também pode <a href="/converter/jxl-para-pdf">criar um PDF</a> ou <a href="/converter/redimensionar-jxl">ajustar as dimensões do JXL</a>.</p>
    </div></section><SiteFooter /></>;
}
