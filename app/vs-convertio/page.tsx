import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("vs-convertio");

export default function PaginaVsConvertio() {
  return (
    <Artigo
      slug="vs-convertio"
      abertura={
        <>
          <p>
            Se você está procurando uma alternativa ao Convertio, a diferença principal cabe numa frase: no Convertio, o arquivo sobe para o
            servidor deles e é convertido lá; aqui, a conversão acontece no seu computador, e o arquivo não sai dele.
          </p>
          <p>O resto — limite, fila, espera pelo upload — é consequência disso.</p>
        </>
      }
      risco={{
        titulo: "O que o Convertio faz com o seu arquivo",
        corpo: (
          <>
            <p>
              Segundo a própria política de privacidade do Convertio, o arquivo enviado é apagado logo depois da conversão, e o resultado fica
              disponível por até 24 horas, quando é apagado automaticamente (ou antes, se você mesmo apagar). O serviço também registra dados
              da conversão, como endereço IP, tipos de arquivo e horário.
            </p>
            <p>
              É uma política razoável, de um serviço sério. Mas, durante esse tempo, existe uma cópia do seu arquivo fora do seu computador, e
              você depende da palavra de alguém para saber que ela sumiu. Para foto de viagem, tudo bem. Para documento de cliente, não
              deveria ser preciso.
            </p>
            <p className="artigo__nota">
              Conferido em setembro de 2026 na página de privacidade do Convertio (convertio.co/privacy). Políticas mudam: vale conferir a
              versão atual.
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
                  <th scope="col">Convertio</th>
                  <th scope="col">Smells Like Tech Converter</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row">Onde converte</th>
                  <td>No servidor do Convertio</td>
                  <td>No seu navegador, no seu computador</td>
                </tr>
                <tr>
                  <th scope="row">O arquivo sai do computador</th>
                  <td>Sim, por upload</td>
                  <td>Não</td>
                </tr>
                <tr>
                  <th scope="row">Quanto tempo fica fora</th>
                  <td>O resultado, até 24 horas (pela política deles)</td>
                  <td>Nunca sai</td>
                </tr>
                <tr>
                  <th scope="row">Funciona sem internet</th>
                  <td>Não</td>
                  <td>Sim, depois de abrir a página</td>
                </tr>
                <tr>
                  <th scope="row">Formatos</th>
                  <td>Centenas — é o ponto forte dele</td>
                  <td>Os de imagem, áudio, vídeo, PDF e legenda que mais se usa</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p>
            <strong>Quando o Convertio faz mais sentido:</strong> formato raro que não está aqui (e-book, fonte, CAD). <strong>Quando este
            faz mais sentido:</strong> arquivo sensível, arquivo grande (sem esperar upload) e uso sem internet.
          </p>
        </section>
      }
      passos={{
        titulo: "Como converter aqui, em 3 passos",
        itens: [
          <p key="1">
            <strong>Abra a conversão.</strong> Cada busca comum tem página própria — WEBP para PNG, MP4 para MP3, MOV para MP4 — e ela já
            abre pronta.
          </p>,
          <p key="2">
            <strong>Solte o arquivo.</strong> Sem upload, não há barra de envio: a conversão começa na hora, no seu processador.
          </p>,
          <p key="3">
            <strong>Baixe o resultado.</strong> Quer conferir que nada saiu? F12, aba Network, durante a conversão.
          </p>,
        ],
        outras: ["webp-para-png", "mp4-para-mp3", "mov-para-mp4", "png-para-jpg", "pdf-para-jpg", "comprimir-video"],
      }}
      planos={
        <p>
          O grátis converte {PRECOS.lote} arquivos por vez, vídeo até {PRECOS.video} minutos e PDF até {PRECOS.pdf} páginas, sem marca
          d&apos;água e sem cadastro. O <strong>Pro</strong> custa {PRECOS.pro} por {PRECOS.diasDoPro} dias ou {PRECOS.proAnual} por ano, sem
          renovação automática, e tira esses tetos. Para lote diário, o <strong>aplicativo</strong> é vitalício: {PRECOS.app}, uma vez.
        </p>
      }
      faq={[
        {
          pergunta: "Tem os mesmos formatos do Convertio?",
          resposta:
            "Não. O Convertio converte centenas de formatos; aqui estão os de imagem, áudio, vídeo, PDF e legenda que as pessoas mais usam. Para formato raro, um conversor na nuvem ainda pode ser o caminho — só evite mandar arquivo sensível.",
        },
        {
          pergunta: "É mais rápido?",
          resposta:
            "Para arquivo grande, costuma ser: não há upload, download nem fila. A velocidade depende do seu computador, não do servidor de ninguém.",
        },
        {
          pergunta: "Preciso criar conta?",
          resposta: "Não. Nem no grátis, nem para usar o Pro: o Pro é uma chave que você cola no site.",
        },
        {
          pergunta: "Tem marca d'água?",
          resposta: "Não, em plano nenhum.",
        },
      ]}
    />
  );
}
