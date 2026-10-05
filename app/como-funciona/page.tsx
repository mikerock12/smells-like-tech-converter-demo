import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";
import LiveProof from "../LiveProof";

export const metadata = metadadosDoArtigo("como-funciona");

export default function PaginaComoFunciona() {
  return (
    <Artigo
      slug="como-funciona"
      abertura={
        <>
          <p>
            “Converter sem enviar” parece truque. Não é: o navegador que você está usando agora tem os mesmos ingredientes de um programa de
            conversão instalado. A página só entrega a receita; quem cozinha é o seu computador.
          </p>
          <p>Este texto explica, sem jargão desnecessário, o que roda aqui dentro — e como conferir.</p>
        </>
      }
      risco={{
        titulo: "O jeito tradicional, e por que ele existe",
        corpo: (
          <>
            <p>
              O conversor online nasceu quando o navegador não sabia fazer quase nada: o arquivo precisava subir para um servidor que tinha os
              programas certos, ser convertido lá e voltar. Hoje o navegador sabe. Mas, para muitos serviços, o modelo continua sendo o
              servidor — e cada upload é um arquivo seu fora do seu controle, por um tempo que só eles sabem.
            </p>
          </>
        ),
      }}
      extra={
        <>
          <section className="artigo__secao">
            <h2>O que roda no seu computador</h2>
            <ul>
              <li>
                <strong>WebAssembly.</strong> É código de programa de verdade, compilado para rodar dentro do navegador, quase na velocidade de
                um programa instalado. Os codificadores de JPG, PNG e WEBP são os do Squoosh, projeto do Google; o leitor de HEIC é o
                libheif.
              </li>
              <li>
                <strong>WebCodecs.</strong> É o navegador emprestando à página os codecs de vídeo dele — e a placa de vídeo, quando existe. É
                assim que MP4, WEBM, MKV e MOV são convertidos sem servidor.
              </li>
              <li>
                <strong>Web Worker.</strong> A conversão roda numa linha separada do navegador, para a página não travar enquanto trabalha.
              </li>
              <li>
                <strong>PDF.</strong> O pdf.js, o leitor de PDF do Firefox, e o pdf-lib leem, desenham e montam o PDF aqui mesmo.
              </li>
              <li>
                <strong>Reconhecimento de texto.</strong> O Tesseract lê o texto de fotos. Na primeira vez, baixa o pacote do idioma de um
                repositório público — só isso, a imagem não sai.
              </li>
            </ul>
            <p>
              Para o que o navegador não dá conta — vídeo de gigabytes, transcrição, PDF para Word —, o <a href="/plugin">plugin</a> roda na
              sua máquina e conversa com a página por 127.0.0.1, um endereço que só existe dentro do computador.
            </p>
          </section>
          <section className="artigo__secao">
            <h2>O seu navegador, verificado agora</h2>
            <LiveProof />
          </section>
        </>
      }
      passos={{
        titulo: "Como conferir, em 3 passos",
        itens: [
          <p key="1">
            <strong>Abra qualquer conversão</strong> e aperte F12 para abrir as ferramentas do desenvolvedor, na aba Network (Rede).
          </p>,
          <p key="2">
            <strong>Solte um arquivo e converta.</strong> A lista de requisições fica parada durante a conversão: nenhum byte do arquivo sai.
          </p>,
          <p key="3">
            <strong>Desligue a internet e converta de novo.</strong> Continua funcionando, porque a conversão nunca dependeu de servidor.
          </p>,
        ],
        outras: ["webp-para-png", "mp4-para-mp3", "comprimir-pdf", "heic-para-jpg"],
      }}
      planos={
        <p>
          Tudo o que roda no navegador está no grátis, com limites de volume ({PRECOS.lote} arquivos por vez, vídeo até {PRECOS.video}{" "}
          minutos, PDF até {PRECOS.pdf} páginas) e a mesma qualidade do pago. O <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro}{" "}
          dias) tira os tetos; o <strong>aplicativo</strong> ({PRECOS.app}, vitalício) é o motor completo numa janela, sem precisar do site.
        </p>
      }
      faq={[
        {
          pergunta: "É mais lento que um conversor na nuvem?",
          resposta:
            "Para a maioria dos arquivos, é mais rápido: não há upload, fila nem download. Um computador muito antigo pode demorar mais em vídeo; aí o plugin ajuda, porque usa a placa de vídeo.",
        },
        {
          pergunta: "Por que algumas ferramentas pedem o plugin?",
          resposta:
            "Transcrição e PDF para Word usam motores do plugin Windows. A narração Kokoro-82M também funciona no navegador: prepare o modelo uma vez com internet e depois narre offline no aparelho.",
        },
        {
          pergunta: "O que o site recebe de mim?",
          resposta:
            "O pedido da página, como qualquer site. O servidor conta visitas por página e por dia, sem IP e sem cookie; nenhum arquivo, nome de arquivo ou conteúdo.",
        },
        {
          pergunta: "Funciona em qualquer navegador?",
          resposta: "Nos atuais, sim: Chrome, Edge, Firefox e Safari. Navegador muito antigo pode não ter os codecs de vídeo abertos para páginas.",
        },
      ]}
    />
  );
}
