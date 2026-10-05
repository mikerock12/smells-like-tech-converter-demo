import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("pdf-cliente");

export default function PaginaPdfCliente() {
  return (
    <Artigo
      slug="pdf-cliente"
      abertura={
        <>
          <p>
            Quem atende cliente vive juntando, dividindo e comprimindo PDF: a procuração com os documentos, o holerite para o
            financiamento, o exame para o convênio. A ferramenta mais à mão costuma ser um site de PDF — e o documento do cliente vai parar
            no servidor de alguém.
          </p>
          <p>Tudo isso dá para fazer no navegador, sem o arquivo sair do computador do escritório.</p>
        </>
      }
      risco={{
        titulo: "O documento é do cliente. O risco é seu.",
        corpo: (
          <>
            <ul>
              <li>
                <strong>Advocacia.</strong> O Estatuto da Advocacia (Lei 8.906/1994, art. 7º, II) protege a inviolabilidade dos arquivos e
                dados do escritório, e o sigilo profissional é dever ético. Subir peça e documento de cliente em site gratuito entrega esses
                dados a um terceiro que não tem sigilo nenhum com você.
              </li>
              <li>
                <strong>Contabilidade.</strong> Holerite, declaração e extrato têm CPF, salário e patrimônio — o tipo de dado que alimenta
                golpe. O sigilo do contador vale também para onde os arquivos passam.
              </li>
              <li>
                <strong>Clínica e consultório.</strong> Laudo, exame e prontuário são dado de saúde, que a LGPD trata como sensível (art. 11),
                além do sigilo médico.
              </li>
            </ul>
            <p>
              Em todos os casos, a pergunta é a mesma: por que o documento precisa sair do computador para ser juntado? Não precisa. O
              raciocínio completo, pela lei, está em <a href="/lgpd-conversor">conversor de PDF e LGPD</a>.
            </p>
          </>
        ),
      }}
      passos={{
        titulo: "Como tratar o PDF do cliente sem ele sair do escritório",
        conversao: "juntar-pdf",
        itens: [
          <p key="1">
            <strong>Juntar, dividir, girar e comprimir:</strong> abra a ferramenta, solte os PDFs, baixe. Tudo no navegador, sem instalar
            nada — funciona até em computador onde você não pode instalar programa.
          </p>,
          <p key="2">
            <strong>Documento em papel?</strong> Fotografe as páginas e use “Imagens para PDF”. Para copiar o texto de uma foto ou de um
            print, “Foto para texto”.
          </p>,
          <p key="3">
            <strong>PDF para Word editável</strong> roda pelo plugin, no próprio computador — inclusive PDF escaneado, com reconhecimento de
            texto.
          </p>,
        ],
        outras: ["comprimir-pdf", "dividir-pdf", "pdf-para-word", "imagens-para-pdf", "foto-para-texto", "girar-pdf"],
      }}
      planos={
        <p>
          O grátis atende o dia a dia: {PRECOS.lote} arquivos por vez e PDF até {PRECOS.pdf} páginas. Processo grande, lote de holerites ou
          PDF para Word pedem o <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias, sem renovação automática). Para o escritório,
          o <strong>aplicativo vitalício</strong> ({PRECOS.app}) vale em até {PRECOS.maquinas} computadores do titular e trabalha em fila,
          sem internet.
        </p>
      }
      faq={[
        {
          pergunta: "Posso usar no computador do escritório sem instalar nada?",
          resposta: "Sim. Juntar, dividir, girar, comprimir e converter PDF em imagem ou texto rodam no navegador.",
        },
        {
          pergunta: "Fica algum registro do documento?",
          resposta: "Nenhum arquivo, nome de arquivo ou conteúdo chega até nós. O navegador guarda só os contadores do plano grátis.",
        },
        {
          pergunta: "Juntar ou comprimir mantém a assinatura digital?",
          resposta:
            "Não. Qualquer alteração invalida a assinatura digital (ICP-Brasil ou outra) que o PDF tinha. Faça essas operações antes de assinar, ou guarde o original assinado.",
        },
        {
          pergunta: "Serve para PDF com senha?",
          resposta: "Remova a senha antes, no programa em que ela foi colocada. PDF protegido precisa ser desbloqueado para ser juntado, dividido ou comprimido.",
        },
      ]}
    />
  );
}
