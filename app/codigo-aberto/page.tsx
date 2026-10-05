import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";

export const metadata: Metadata = {
  title: "Código aberto — Smells Like Tech Converter",
  description:
    "Os projetos de código aberto que fazem as conversões do site, do app para Android e do aplicativo para Windows, com a licença de cada um.",
};

type Componente = { nome: string; uso: string; licenca: string; link: string };

const site: Componente[] = [
  { nome: "pdf.js (Mozilla)", uso: "abre e desenha PDF", licenca: "Apache-2.0", link: "https://github.com/mozilla/pdf.js" },
  { nome: "pdf-lib", uso: "junta, divide e monta PDF", licenca: "MIT", link: "https://github.com/Hopding/pdf-lib" },
  { nome: "Tesseract.js", uso: "OCR, o texto de fotos e PDFs escaneados", licenca: "Apache-2.0", link: "https://github.com/naptha/tesseract.js" },
  { nome: "jSquash (MozJPEG, OxiPNG, libwebp)", uso: "JPG, PNG, WEBP e redimensionar", licenca: "Apache-2.0", link: "https://github.com/jamsinclair/jSquash" },
  { nome: "libheif-js", uso: "fotos HEIC do iPhone", licenca: "LGPL-3.0", link: "https://github.com/catdad-experiments/libheif-js" },
  { nome: "UTIF.js", uso: "imagens TIFF", licenca: "MIT", link: "https://github.com/photopea/UTIF.js" },
  { nome: "gifenc", uso: "GIF", licenca: "MIT", link: "https://github.com/mattdesl/gifenc" },
  { nome: "lamejs", uso: "MP3", licenca: "LGPL-3.0", link: "https://www.npmjs.com/package/@breezystack/lamejs" },
  { nome: "Mediabunny", uso: "áudio e vídeo no navegador", licenca: "MPL-2.0", link: "https://mediabunny.dev" },
  { nome: "fflate", uso: "arquivos ZIP", licenca: "MIT", link: "https://github.com/101arrowz/fflate" },
  { nome: "React", uso: "a interface", licenca: "MIT", link: "https://github.com/facebook/react" },
];

const android: Componente[] = [
  { nome: "FFmpeg 8.1.3", uso: "vídeo, áudio e o motor da transcrição", licenca: "LGPL-2.1+", link: "https://ffmpeg.org" },
  { nome: "whisper.cpp 1.9.4 e ggml", uso: "transcrição no aparelho", licenca: "MIT", link: "https://github.com/ggml-org/whisper.cpp" },
  { nome: "Modelos Whisper (OpenAI)", uso: "baixados só quando você pede", licenca: "MIT", link: "https://github.com/openai/whisper" },
  { nome: "LAME 3.100", uso: "MP3", licenca: "LGPL", link: "https://lame.sourceforge.io" },
  { nome: "Opus, libogg e libvorbis", uso: "OPUS e OGG", licenca: "BSD-3-Clause", link: "https://xiph.org" },
  { nome: "libvpx", uso: "WEBM (VP8 e VP9)", licenca: "BSD-3-Clause", link: "https://chromium.googlesource.com/webm/libvpx" },
  { nome: "libaom", uso: "AVIF", licenca: "BSD-2-Clause e patentes AOMedia", link: "https://aomedia.googlesource.com/aom" },
  { nome: "PdfBox-Android (Apache PDFBox)", uso: "juntar, dividir, girar e ler PDF", licenca: "Apache-2.0", link: "https://github.com/TomRoush/PdfBox-Android" },
  { nome: "jsoup", uso: "documentos HTML e EPUB", licenca: "MIT", link: "https://jsoup.org" },
  { nome: "Kotlin, AndroidX e Jetpack Compose", uso: "a base do app", licenca: "Apache-2.0", link: "https://developer.android.com/jetpack" },
];

const windows: Componente[] = [
  { nome: "FFmpeg", uso: "vídeo, áudio e transcrição (o instalado no computador)", licenca: "LGPL ou GPL, conforme a build", link: "https://ffmpeg.org/legal.html" },
  { nome: "whisper.cpp", uso: "transcrição, dentro do FFmpeg", licenca: "MIT", link: "https://github.com/ggml-org/whisper.cpp" },
  { nome: "ImageMagick e Magick.NET", uso: "imagens", licenca: "ImageMagick License e Apache-2.0", link: "https://imagemagick.org/script/license.php" },
  { nome: "PdfPig", uso: "ler PDF", licenca: "Apache-2.0", link: "https://github.com/UglyToad/PdfPig" },
  { nome: "PDFtoImage e PDFium", uso: "PDF para imagem", licenca: "MIT e BSD-3-Clause", link: "https://github.com/sungaila/PDFtoImage" },
  { nome: "Open XML SDK", uso: "documentos do Word", licenca: "MIT", link: "https://github.com/dotnet/Open-XML-SDK" },
  { nome: ".NET e Windows App SDK", uso: "a base do aplicativo", licenca: "MIT", link: "https://github.com/dotnet/runtime" },
  { nome: "SQLite", uso: "histórico e preferências", licenca: "domínio público", link: "https://www.sqlite.org/copyright.html" },
];

function Lista({ itens }: { itens: Componente[] }) {
  return (
    <ul className="ressalvas">
      {itens.map((item) => (
        <li key={item.nome}>
          <strong>
            <a href={item.link} rel="noopener noreferrer" target="_blank">
              {item.nome}
            </a>
          </strong>{" "}
          — {item.uso}. <em>{item.licenca}</em>
        </li>
      ))}
    </ul>
  );
}

export default function PaginaDoCodigoAberto() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page legal">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Código aberto</p>
          <h1>
            Feito com
            <br />
            <em>software livre.</em>
          </h1>
          <p className="plugin-page__lead">
            O Smells Like Tech Converter é um produto pago, mas as conversões se apoiam em projetos de código aberto mantidos
            por muita gente. Cada um continua com a própria licença. Aqui está o que usamos, para quê, e onde está o código.
          </p>
        </header>

        <section className="plugin-page__secao">
          <h2 className="panel__title">No site</h2>
          <p>Rodam no seu navegador: os arquivos não saem do aparelho.</p>
          <Lista itens={site} />
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">No app para Android</h2>
          <Lista itens={android} />
          <p>
            O FFmpeg vai dentro do app como um programa à parte, compilado dos códigos-fonte oficiais. Os textos completos das
            licenças e a receita exata da compilação estão em{" "}
            <a href="/codigo-aberto/licencas-android.txt">licencas-android.txt</a>, e também dentro do próprio app. O OCR usa o
            ML Kit do Google, que não é de código aberto e segue os termos dele.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">No aplicativo para Windows e no plugin</h2>
          <Lista itens={windows} />
          <p>
            Os avisos completos ficam na pasta Licenses, ao lado do programa instalado. A narração Kokoro-82M usa
            o modelo Apache-2.0, Sherpa-ONNX Apache-2.0 e o fonemizador eSpeak-NG GPLv3.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">Código-fonte e contato</h2>
          <p>
            Os clientes integrados com Kokoro-82M são software livre sob GNU GPLv3 ou posterior, sem garantia.
            Os pesos Kokoro e o Sherpa-ONNX são Apache-2.0; o fonemizador eSpeak-NG é GPLv3.
            O <a href="https://github.com/mikerock12/smells-like-tech-converter-demo">código dos clientes</a>,
            as licenças e as receitas estão no repositório. Os fontes correspondentes aos binários
            ficam nas <a href="https://github.com/mikerock12/smells-like-tech-converter-demo/releases">releases públicas</a>,
            em um repositório de demonstração separado do backend comercial e dos dados privados.
          </p>
          <p>
            Os componentes LGPL (FFmpeg, LAME, libheif-js e lamejs) podem ser obtidos nos links acima e substituídos. Se quiser
            uma cópia do código-fonte exato que usamos, ou achar um erro nesta lista, escreva para{" "}
            <a href="mailto:converter@smellsliketech.com.br">converter@smellsliketech.com.br</a>.
          </p>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
