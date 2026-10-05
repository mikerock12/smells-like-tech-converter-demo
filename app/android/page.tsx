import type { Metadata } from "next";

import { ANDROID_NA_PLAY, LINK_DA_GOOGLE_PLAY } from "@/packages/converter-core/android.mjs";
import { DIAS_DE_TESTE_DO_APP, formatarReais, produto } from "@/packages/converter-core/precos.mjs";

import { SiteFooter, SiteHeader } from "../SiteChrome";

export const metadata: Metadata = {
  title: "App para Android: converta tudo no celular, sem enviar arquivo — Smells Like Tech Converter",
  description:
    "O conversor completo no celular: imagem, vídeo, áudio, PDF, documentos, transcrição com Whisper, OCR e narração, sem enviar arquivo. Na Google Play, com 7 dias de teste completo e uma compra única.",
};

const passos = [
  {
    titulo: "Instale pela Google Play",
    corpo: "O app sai só pela loja oficial do Android. As atualizações chegam sozinhas, como em qualquer app da Play.",
  },
  {
    titulo: "Use tudo por 7 dias",
    corpo:
      "Escolha arquivos no app ou compartilhe da galeria, do WhatsApp ou do app Arquivos e escolha Converter. Os resultados vão para Downloads/SmellsLikeTech. O teste é completo, sem marca d'água nem qualidade reduzida.",
  },
  {
    titulo: "Gostou? Compre uma vez",
    corpo:
      "A licença vitalícia é uma compra única dentro do app, pelo Google Play, e vale nos celulares com a mesma conta Google. Quem já tem o “Tudo, para sempre” ativa a chave no app e não paga de novo.",
  },
];

export default function PaginaDoAndroid() {
  const licenca = produto("android-vitalicio")!;

  return (
    <>
      <SiteHeader page="outra" />

      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">
            {ANDROID_NA_PLAY ? "Na Google Play" : "Em breve na Google Play"} · {DIAS_DE_TESTE_DO_APP} dias de teste · Android 10 ou mais novo
          </p>
          <h1>
            O conversor completo no celular,
            <br />
            <em>pago uma vez só.</em>
          </h1>
          <p className="plugin-page__lead">
            O plugin é um programa para Windows e <strong>não funciona no celular</strong>. No celular, quem faz tudo é este
            app: as mesmas conversões do aplicativo para Windows (imagem, vídeo, áudio, PDF e documentos), transcrição com o
            Whisper, OCR e narração, no próprio aparelho e sem o site. Nenhum arquivo sai do celular. Teste tudo por{" "}
            {DIAS_DE_TESTE_DO_APP} dias; se ficar, a licença vitalícia custa <strong>{formatarReais(licenca.centavos)}</strong>,
            numa compra única dentro do app.
          </p>

          {ANDROID_NA_PLAY ? (
            <a className="primary-action" href={LINK_DA_GOOGLE_PLAY} rel="noopener">
              Instalar pela Google Play <span aria-hidden="true">→</span>
            </a>
          ) : (
            <p className="notice">
              O app está nos testes finais da Google Play e chega em breve. Quando sair, o link da loja aparece aqui.
              Enquanto isso, o site funciona no celular e{" "}
              <a className="converter__link" href="/instalar">
                instala como app
              </a>
              , com imagem, áudio, vídeo curto e PDF no navegador.
            </p>
          )}
        </header>

        <section className="plugin-page__secao">
          <h2 className="panel__title">{ANDROID_NA_PLAY ? "Como funciona" : "Como vai funcionar"}</h2>
          <ol className="passos">
            {passos.map((passo, indice) => (
              <li key={passo.titulo}>
                <span>{indice + 1}</span>
                <div>
                  <strong>{passo.titulo}</strong>
                  <p>{passo.corpo}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">O que ele faz</h2>
          <div className="grid">
            <div className="cartao">
              <strong>Imagem</strong>
              <p>
                Converter entre JPG, PNG, WEBP, AVIF, TIFF, BMP, GIF e ICO, comprimir, redimensionar, mudar a proporção, girar e
                juntar em PDF. Abre HEIC, a foto do iPhone.
              </p>
            </div>
            <div className="cartao">
              <strong>Vídeo</strong>
              <p>
                MP4, MKV, MOV, WEBM e AVI: converter, comprimir, cortar, tirar o áudio, virar GIF ou imagens. O H.264 sai do
                processador de vídeo do próprio celular.
              </p>
            </div>
            <div className="cartao">
              <strong>Áudio e transcrição</strong>
              <p>
                MP3, WAV, FLAC, AAC, M4A, OGG, OPUS e WMA, com bitrate e corte. O áudio do WhatsApp vira texto ou legenda (TXT,
                SRT, VTT) com o Whisper, no aparelho.
              </p>
            </div>
            <div className="cartao">
              <strong>PDF e documentos</strong>
              <p>
                Juntar, dividir, girar, virar imagens ou Word com OCR. DOC, DOCX, ODT, RTF, planilhas, apresentações, EPUB e
                HTML viram PDF, DOCX, TXT, HTML, Markdown ou CSV.
              </p>
            </div>
            <div className="cartao">
              <strong>OCR e narração</strong>
              <p>Texto de foto e de PDF escaneado, e texto ou legenda virando áudio com a voz do celular, em português.</p>
            </div>
          </div>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">Antes de instalar, saiba</h2>
          <ul className="ressalvas">
            <li>
              <strong>A internet só baixa os modelos de transcrição.</strong> A conversão, a transcrição, o OCR e a narração
              acontecem no aparelho. O Whisper precisa de um modelo, baixado uma vez quando você pede (de 74 MB a 1 GB); o
              Small, recomendado, tem 465 MB.
            </li>
            <li>
              <strong>Transcrever pesa.</strong> Quanto maior o modelo, mais preciso e mais lento; num celular simples, o Medium
              e o Large demoram bastante. Para horas de gravação, o <a href="/aplicativo">aplicativo para Windows</a> é mais
              rápido.
            </li>
            <li>
              <strong>Depois de {DIAS_DE_TESTE_DO_APP} dias, pede a licença.</strong> O teste é completo, sem marca d&apos;água nem
              qualidade reduzida. A compra do Android é separada da licença do Windows; o “Tudo, para sempre” vale nos dois.
            </li>
          </ul>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}
