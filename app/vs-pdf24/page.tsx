import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("vs-pdf24");

export default function PaginaVsPdf24() {
  return (
    <Artigo
      slug="vs-pdf24"
      abertura={
        <>
          <p>
            O PDF24 tem duas caras: o PDF24 Tools, que roda no site deles, e o PDF24 Creator, um programa gratuito para Windows. Se você quer
            mexer em PDF sem instalar uma suíte e sem mandar o arquivo para um servidor, existe um terceiro caminho: o navegador.
          </p>
        </>
      }
      risco={{
        titulo: "O que acontece com o PDF em cada um",
        corpo: (
          <>
            <p>
              No <strong>PDF24 Tools</strong>, o arquivo sobe para servidores na União Europeia. Segundo a política do próprio PDF24, os
              arquivos e os resultados costumam ser apagados cerca de uma hora depois. É um serviço alemão estabelecido, que segue a GDPR —
              mas, nessa hora, o PDF está fora do seu computador.
            </p>
            <p>
              O <strong>PDF24 Creator</strong> é um programa para Windows. Se você pode instalar programas e quer uma suíte completa de PDF,
              é uma boa opção gratuita. No computador do trabalho, onde instalar nem sempre é permitido, o navegador resolve.
            </p>
            <p className="artigo__nota">
              Conferido em setembro de 2026 na página de privacidade do PDF24 (pdf24.org). Políticas mudam: vale conferir a versão atual.
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
                  <th scope="col">PDF24 Tools (online)</th>
                  <th scope="col">Smells Like Tech Converter</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row">Precisa instalar</th>
                  <td>Não</td>
                  <td>Não</td>
                </tr>
                <tr>
                  <th scope="row">O PDF sai do computador</th>
                  <td>Sim, por upload</td>
                  <td>Não</td>
                </tr>
                <tr>
                  <th scope="row">Quanto tempo fica fora</th>
                  <td>Cerca de 1 hora, pela política deles</td>
                  <td>Nunca sai</td>
                </tr>
                <tr>
                  <th scope="row">Funciona sem internet</th>
                  <td>Não</td>
                  <td>Sim, depois de abrir a página</td>
                </tr>
                <tr>
                  <th scope="row">Ferramentas de PDF</th>
                  <td>Muitas, inclusive editar, assinar e proteger</td>
                  <td>As do dia a dia: juntar, dividir, girar, comprimir, converter</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      }
      passos={{
        titulo: "Como mexer no PDF pelo navegador, em 3 passos",
        conversao: "juntar-pdf",
        itens: [
          <p key="1">
            <strong>Abra a ferramenta:</strong> juntar, dividir, girar, comprimir, PDF para JPG ou imagens para PDF.
          </p>,
          <p key="2">
            <strong>Solte os PDFs.</strong> Para juntar, arraste para pôr na ordem antes. Nada é enviado: confira na aba Network (F12).
          </p>,
          <p key="3">
            <strong>Baixe o resultado.</strong> Para PDF para Word editável, o plugin gratuito faz no seu computador.
          </p>,
        ],
        outras: ["comprimir-pdf", "dividir-pdf", "girar-pdf", "pdf-para-jpg", "imagens-para-pdf", "pdf-para-word"],
      }}
      planos={
        <p>
          O grátis trabalha com PDF até {PRECOS.pdf} páginas e {PRECOS.lote} arquivos por vez, sem marca d&apos;água. PDF maior, lote e PDF
          para Word ficam no <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias); o <strong>aplicativo</strong> ({PRECOS.app},
          vitalício) faz tudo em fila, sem internet.
        </p>
      }
      faq={[
        {
          pergunta: "O PDF24 é seguro?",
          resposta:
            "É um serviço alemão estabelecido, que segue a GDPR e informa quando apaga os arquivos. A diferença é que, no online, o PDF sai do computador; aqui, não.",
        },
        {
          pergunta: "Faz tudo o que o PDF24 faz?",
          resposta:
            "Não. O PDF24 tem mais ferramentas de PDF, como editar, assinar e proteger com senha. Aqui estão as do dia a dia: juntar, dividir, girar, comprimir, PDF para imagem, texto e Word, e imagem para PDF.",
        },
        {
          pergunta: "Comprimir aqui estraga o texto?",
          resposta: "Não. A compressão mexe nas fotos e na estrutura do arquivo; o texto continua texto, selecionável e pesquisável.",
        },
        {
          pergunta: "Preciso instalar alguma coisa?",
          resposta: "Não, para as ferramentas de PDF no navegador. Só PDF para Word pede o plugin.",
        },
      ]}
    />
  );
}
