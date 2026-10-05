import type { Metadata } from "next";

import { ANDROID_NA_PLAY } from "@/packages/converter-core/android.mjs";
import { emMegabytes, lerPluginParaBaixar } from "@/lib/baixar";

import ConferenciaDoInstalador from "../ConferenciaDoInstalador";
import { SiteFooter, SiteHeader } from "../SiteChrome";
import EstadoDaInstalacao from "./EstadoDaInstalacao";

export const metadata: Metadata = {
  title: "Baixar o plugin — Smells Like Tech Converter",
  description:
    "O programinha gratuito que liga o seu computador ao site: vídeo de gigabytes, transcrição, narração, OCR e PDF para Word rodando na sua máquina, sem enviar arquivo para servidor nenhum.",
};

const passos = [
  {
    titulo: "Baixe e execute",
    corpo:
      "O Windows vai avisar que o programa não é conhecido — ele ainda não tem assinatura digital. Clique em “Mais informações” e depois em “Executar assim mesmo”.",
  },
  {
    titulo: "Instale sem senha de administrador",
    corpo:
      "Ele se instala só para o seu usuário, na sua pasta pessoal. Deixe marcado “Ligar o plugin junto com o Windows” para não precisar abrir toda vez.",
  },
  {
    titulo: "Procure o ícone perto do relógio",
    corpo:
      "É a única presença dele na tela. Clique com o botão direito ali para ver a pasta dos resultados ou para sair.",
  },
  {
    titulo: "Volte aqui",
    corpo:
      "Esta página reconhece o plugin sozinha. Depois disso, a oficina passa a oferecer vídeo de qualquer tamanho, transcrição, narração, OCR e PDF para Word — e um botão para escolher o arquivo direto do disco, sem copiar.",
  },
];

export default function PaginaDoPlugin() {
  const download = lerPluginParaBaixar();

  return (
    <>
      <SiteHeader page="plugin" />

      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Opcional, gratuito e reversível · o Pro libera os limites</p>
          <h1>
            O plugin liga este site
            <br />
            <em>ao seu computador.</em>
          </h1>
          <p className="plugin-page__lead">
            O site continua sendo o rosto. Quem converte passa a ser a sua máquina — o seu
            processador, a sua placa de vídeo, os seus codecs. Um vídeo de dez gigabytes entra na
            fila sem sequer passar pelo navegador. E continua valendo o de sempre:{" "}
            <strong>nenhum arquivo sai do seu computador.</strong>
          </p>

          {download ? (
            <>
              <a className="primary-action" href={download.endereco} download>
                Baixar para Windows <span aria-hidden="true">↓</span>
              </a>
              <p className="plugin-page__ficha">
                Versão {download.versao} · {emMegabytes(download.bytes)} · Windows 10 ou 11, 64 bits
              </p>
              <ConferenciaDoInstalador download={download} />
            </>
          ) : (
            <p className="notice notice--warning">
              O instalador ainda não foi publicado nesta cópia do site. Gere com{" "}
              <code>pwsh desktop/installer/build-plugin-installer.ps1</code>.
            </p>
          )}
        </header>

        <EstadoDaInstalacao />

        <section className="plugin-page__secao">
          <h2 className="panel__title">Como instalar</h2>
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
          <h2 className="panel__title">O que ele faz na sua máquina</h2>
          <div className="grid">
            <div className="cartao">
              <strong>Abre uma porta só para este site</strong>
              <p>
                Ele escuta em <code>127.0.0.1:5199</code>, um endereço que só existe dentro do seu
                computador. Nada vindo da internet alcança essa porta, e ele recusa qualquer site
                que não seja o nosso.
              </p>
            </div>
            <div className="cartao">
              <strong>Não abre janela, não pede cadastro</strong>
              <p>
                Sem conta, sem login, sem cookie. Ele não sabe quem você é e não tem para onde
                mandar nada.
              </p>
            </div>
            <div className="cartao">
              <strong>Usa os mesmos motores do aplicativo</strong>
              <p>
                É o programa completo rodando por baixo. Se uma conversão funciona no aplicativo,
                funciona aqui — com a mesma qualidade e a mesma velocidade.
              </p>
            </div>
            <div className="cartao">
              <strong>Sai quando você quiser</strong>
              <p>
                Botão direito no ícone perto do relógio → Sair. Para remover de vez, é em Aplicativos
                do Windows, como qualquer outro programa.
              </p>
            </div>
          </div>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">Antes de instalar, saiba</h2>
          <ul className="ressalvas">
            <li>
              <strong>O instalador ainda não é assinado.</strong> O Windows vai avisar que o
              programa é de origem desconhecida. É esperado — um certificado de assinatura é uma
              compra que ainda não fizemos. Se isso te incomoda, e é uma reação justa, confira o
              SHA-256 acima antes de executar.
            </li>
            <li>
              <strong>Vídeo, áudio e transcrição precisam do FFmpeg.</strong> Se ele não estiver
              instalado, o plugin liga e o site reconhece, mas essas conversões ficam indisponíveis.
              O instalador avisa se for o caso.
            </li>
            <li>
              <strong>Só Windows 10 ou 11, 64 bits. No celular, o plugin não funciona.</strong> Para ter no celular as
              funções pesadas, sem depender do computador, {ANDROID_NA_PLAY ? "existe o" : "chega em breve o"}{" "}
              <a href="/android">app para Android</a>, na Google Play.
            </li>
            <li>
              <strong>O plugin é grátis; os limites são do plano.</strong> No grátis, 5 conversões pesadas por dia e
              alguns minutos de transcrição para experimentar. O <a href="/precos">Pro</a> tira os contadores.
            </li>
            <li>
              <strong>Ele não se atualiza sozinho.</strong> Trocar de versão é baixar aqui e
              instalar por cima.
            </li>
          </ul>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}
