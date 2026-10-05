import type { Metadata } from "next";

import { emMegabytes, lerAplicativoParaBaixar } from "@/lib/baixar";
import { DIAS_DE_TESTE_DO_APP, MAQUINAS_POR_LICENCA, formatarReais, produto } from "@/packages/converter-core/precos.mjs";

import ConferenciaDoInstalador from "../ConferenciaDoInstalador";
import { SiteFooter, SiteHeader } from "../SiteChrome";

export const metadata: Metadata = {
  title: "Aplicativo para Windows com licença vitalícia — Smells Like Tech Converter",
  description:
    "O programa completo para Windows: vídeo, áudio, imagem, PDF, transcrição e narração, com fila, histórico e lote. Pague uma vez, use para sempre. 7 dias de teste completo.",
};

const passos = [
  {
    titulo: "Baixe e execute",
    corpo:
      "O Windows vai avisar que o programa não é conhecido — ele ainda não tem assinatura digital. Clique em “Mais informações” e depois em “Executar assim mesmo”.",
  },
  {
    titulo: "Escolha onde instalar",
    corpo:
      "Para todos os usuários, em Arquivos de Programas, o que pede senha de administrador; ou só para você, na sua pasta pessoal, sem senha nenhuma.",
  },
  {
    titulo: "Instale o FFmpeg, se ainda não tiver",
    corpo:
      "Vídeo, áudio e transcrição dependem dele. O instalador avisa se não encontrar. Imagem, PDF e reconhecimento de texto já vêm embutidos e funcionam sem ele.",
  },
  {
    titulo: "Abra e arraste um arquivo",
    corpo:
      "O tipo é detectado sozinho, e só as conversões que fazem sentido para aquele arquivo aparecem na tela. Os primeiros 7 dias são completos, sem chave.",
  },
  {
    titulo: "Gostou? Ative a licença",
    corpo:
      "Compre a licença vitalícia no site e cole a chave em Configurações → Licença. Vale em até 3 computadores seus e nunca vence.",
  },
];

export default function PaginaDoAplicativo() {
  const download = lerAplicativoParaBaixar();
  const licenca = produto("app-vitalicio")!;
  const completo = produto("completo-vitalicio")!;

  return (
    <>
      <SiteHeader page="aplicativo" />

      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Licença vitalícia · {DIAS_DE_TESTE_DO_APP} dias de teste · Windows 10 e 11</p>
          <h1>
            O programa completo,
            <br />
            <em>pago uma vez só.</em>
          </h1>
          <p className="plugin-page__lead">
            É o mesmo motor que o plugin usa, com uma janela em volta: fila com histórico, lote a partir de pastas,
            presets e ajuste fino de cada motor. Depois de instalado, <strong>funciona sem internet</strong> — e
            continua valendo o de sempre: nenhum arquivo sai do seu computador. Teste tudo por {DIAS_DE_TESTE_DO_APP}{" "}
            dias; se ficar, a licença custa <strong>{formatarReais(licenca.centavos)}</strong>, para sempre, em até{" "}
            {MAQUINAS_POR_LICENCA} computadores.
          </p>

          {download ? (
            <>
              <a className="primary-action" href={download.endereco} download>
                Baixar para Windows <span aria-hidden="true">↓</span>
              </a>
              <p className="plugin-page__ficha">
                Versão {download.versao} · {emMegabytes(download.bytes)} · Windows 10 ou 11, 64 bits · teste completo por {DIAS_DE_TESTE_DO_APP} dias
              </p>
              <p className="plugin__acoes">
                <a className="button button--primary" href="/precos">
                  Comprar a licença vitalícia · {formatarReais(licenca.centavos)}
                </a>
                <a className="button" href="/precos">
                  Ou tudo, para sempre · {formatarReais(completo.centavos)}
                </a>
              </p>
              <ConferenciaDoInstalador download={download} />
            </>
          ) : (
            <p className="notice notice--warning">
              O instalador ainda não foi publicado nesta cópia do site. Gere com{" "}
              <code>pwsh desktop/installer/build-installer.ps1</code>.
            </p>
          )}
        </header>

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
          <h2 className="panel__title">O que ele faz</h2>
          <div className="grid">
            <div className="cartao">
              <strong>Vídeo e áudio</strong>
              <p>
                Formato, resolução, proporção, FPS, compressão, corte e velocidade. Vídeo vira MP3,
                WAV, FLAC ou GIF. Presets prontos para Reels, YouTube e WhatsApp.
              </p>
            </div>
            <div className="cartao">
              <strong>Vários arquivos de uma vez</strong>
              <p>
                Solte quantos quiser: eles são agrupados por tipo, e cada grupo escolhe o que fazer.
                Todas as músicas para o mesmo formato com o volume normalizado, todos os PDFs para
                DOCX — ou um por um, se algum precisar de tratamento diferente.
              </p>
            </div>
            <div className="cartao">
              <strong>PDF e reconhecimento de texto</strong>
              <p>
                PDF vira DOCX, TXT, Markdown, HTML ou imagens. PDF escaneado passa pelo
                reconhecimento do próprio Windows, sem instalar mais nada.
              </p>
            </div>
            <div className="cartao">
              <strong>Fala vira texto, texto vira fala</strong>
              <p>
                Transcrição local com Whisper em TXT, SRT ou VTT, e narração Kokoro-82M com
                Dora, Alex e Santa já incluídas. Os modelos de transcrição são baixados quando você escolhe usá-los.
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
              <strong>Vídeo, áudio e transcrição precisam do FFmpeg.</strong> Sem ele o aplicativo
              abre e converte imagem, PDF e texto, mas essas conversões ficam indisponíveis.
            </li>
            <li>
              <strong>Só Windows 10 ou 11, 64 bits, por enquanto.</strong>
            </li>
            <li>
              <strong>Ele não se atualiza sozinho.</strong> Trocar de versão é baixar aqui e
              instalar por cima. Desinstalar não apaga suas conversões, seu histórico nem seus
              modelos baixados.
            </li>
            <li>
              <strong>Depois de {DIAS_DE_TESTE_DO_APP} dias, pede a licença.</strong> Os limites do teste são só de
              tempo: nada de marca d&apos;água nem qualidade reduzida. A chave vale em até {MAQUINAS_POR_LICENCA}{" "}
              computadores seus e não vence; reinstalar o Windows não gasta ativação.
            </li>
            <li>
              <strong>Não é o mesmo que o plugin.</strong> O aplicativo é um programa com janela,
              que funciona sozinho. O <a href="/plugin">plugin</a> não tem janela e serve para o
              site usar a sua máquina. Instalar um não obriga a instalar o outro.
            </li>
          </ul>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}
