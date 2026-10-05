import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("vs-cloudconvert");

export default function PaginaVsCloudConvert() {
  return (
    <Artigo
      slug="vs-cloudconvert"
      abertura={
        <>
          <p>
            O CloudConvert é um dos conversores na nuvem mais conhecidos — e um dos mais cuidadosos. Se você está comparando, a diferença que
            importa é onde a conversão acontece: lá, nos servidores deles; aqui, no seu computador.
          </p>
        </>
      }
      risco={{
        titulo: "O que acontece com o arquivo no CloudConvert",
        corpo: (
          <>
            <p>
              Segundo a política de privacidade do CloudConvert, os arquivos são processados em servidores na Alemanha e apagados logo depois
              da conversão, no máximo em 24 horas; a empresa tem certificação ISO 27001. É um cuidado acima da média.
            </p>
            <p>
              Ainda assim, o arquivo precisa subir e ficar, por um tempo, fora do seu computador. Se ele tem dado pessoal de outra pessoa,
              isso é transferência internacional pela LGPD — veja <a href="/lgpd-conversor">conversor de PDF e LGPD</a>.
            </p>
            <p className="artigo__nota">
              Conferido em setembro de 2026 na página de privacidade do CloudConvert (cloudconvert.com/privacy). Políticas mudam: vale conferir
              a versão atual.
            </p>
          </>
        ),
      }}
      extra={
        <section className="artigo__secao">
          <h2>Lado a lado</h2>
          <div className="tabela-rolagem">
            <table className="tabela-comparacao">
              <thead>
                <tr>
                  <th scope="col"> </th>
                  <th scope="col">CloudConvert</th>
                  <th scope="col">Smells Like Tech Converter</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row">Onde converte</th>
                  <td>Em servidores na Alemanha</td>
                  <td>No seu navegador, no seu computador</td>
                </tr>
                <tr>
                  <th scope="row">O arquivo sai do computador</th>
                  <td>Sim, por upload</td>
                  <td>Não</td>
                </tr>
                <tr>
                  <th scope="row">Quanto tempo fica fora</th>
                  <td>Até 24 horas, pela política deles</td>
                  <td>Nunca sai</td>
                </tr>
                <tr>
                  <th scope="row">Funciona sem internet</th>
                  <td>Não</td>
                  <td>Sim, depois de abrir a página</td>
                </tr>
                <tr>
                  <th scope="row">API para sistemas</th>
                  <td>Sim, é um ponto forte</td>
                  <td>Não: é feito para pessoas</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p>
            <strong>Quando o CloudConvert faz mais sentido:</strong> formato que não está aqui (e-book, planilha, apresentação) e automação
            por API. <strong>Quando este faz mais sentido:</strong> arquivo sensível, arquivo grande sem esperar upload, e uso sem internet.
          </p>
        </section>
      }
      passos={{
        titulo: "Como converter aqui, em 3 passos",
        conversao: "mkv-para-mp4",
        itens: [
          <p key="1">
            <strong>Abra a conversão.</strong> MKV para MP4, WEBM para MP4, WAV para MP3, AVIF para JPG: cada uma tem página própria, pronta
            para usar.
          </p>,
          <p key="2">
            <strong>Solte o arquivo.</strong> Sem upload, a conversão começa na hora, no seu processador e na sua placa de vídeo.
          </p>,
          <p key="3">
            <strong>Baixe o resultado.</strong> Com a aba Network aberta (F12), dá para ver que nada sai durante a conversão.
          </p>,
        ],
        outras: ["webm-para-mp4", "wav-para-mp3", "avif-para-jpg", "comprimir-pdf", "mov-para-mp4"],
      }}
      planos={
        <p>
          O grátis converte {PRECOS.lote} arquivos por vez, vídeo até {PRECOS.video} minutos e PDF até {PRECOS.pdf} páginas, sem cadastro.
          O <strong>Pro</strong> custa {PRECOS.pro} por {PRECOS.diasDoPro} dias ou {PRECOS.proAnual} por ano, sem renovação automática; o{" "}
          <strong>aplicativo</strong> é vitalício, por {PRECOS.app}.
        </p>
      }
      faq={[
        {
          pergunta: "O CloudConvert é inseguro?",
          resposta:
            "Não é isso. É um serviço sério, com certificação e política de exclusão clara. A diferença é de arquitetura: lá o arquivo precisa sair do computador; aqui não.",
        },
        {
          pergunta: "Tem API?",
          resposta: "Não. Este conversor é para pessoas convertendo arquivos, não para integrar sistemas.",
        },
        {
          pergunta: "Converte documento do Office?",
          resposta: "Não. Aqui estão imagem, áudio, vídeo, PDF (inclusive PDF para Word, pelo plugin) e legenda.",
        },
        {
          pergunta: "Preciso criar conta?",
          resposta: "Não, nem para usar o Pro.",
        },
      ]}
    />
  );
}
