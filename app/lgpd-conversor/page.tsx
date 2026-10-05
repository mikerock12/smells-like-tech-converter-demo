import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("lgpd-conversor");

export default function PaginaLgpd() {
  return (
    <Artigo
      slug="lgpd-conversor"
      abertura={
        <>
          <p>
            Se você trabalha com documento de cliente, paciente ou funcionário, a pergunta aparece cedo ou tarde: posso subir este PDF num
            conversor online? Pela LGPD, subir o arquivo já é tratar dado pessoal — e entregar esse tratamento a um terceiro que você não
            contratou.
          </p>
          <p>Converter no próprio computador tira esse terceiro da história. O PDF é juntado, dividido ou comprimido onde já estava.</p>
        </>
      }
      risco={{
        titulo: "O que a LGPD enxerga quando você sobe um PDF",
        corpo: (
          <>
            <ul>
              <li>
                <strong>Enviar é tratamento.</strong> A Lei 13.709/2018 chama de tratamento toda operação com dado pessoal, inclusive
                transmissão, armazenamento e processamento (art. 5º, X). O upload para um conversor faz as três coisas.
              </li>
              <li>
                <strong>O site passa a tratar dados em seu nome.</strong> Quem trata dado pessoal em nome do controlador é operador (art. 5º,
                VII) e deve seguir as instruções dele (art. 39). No conversor gratuito, quem dita as regras são os termos de uso do site, não
                você.
              </li>
              <li>
                <strong>Servidor fora do Brasil é transferência internacional.</strong> Ela só é permitida nas hipóteses do art. 33, hoje
                detalhadas pela Resolução CD/ANPD nº 19/2024.
              </li>
              <li>
                <strong>Dado de saúde é sensível.</strong> Exame, laudo e prontuário seguem regras mais estritas (art. 11).
              </li>
              <li>
                <strong>Se vazar, a conta é do controlador.</strong> Ele responde pela segurança dos dados (art. 46) e precisa comunicar à ANPD
                e aos titulares o incidente que possa causar risco ou dano relevante (art. 48).
              </li>
            </ul>
            <p>
              Um PDF que não sai do computador não é transmitido a ninguém, não fica em servidor de terceiro e não cruza fronteira. Continua
              sendo tratamento — o seu, no seu equipamento, como abrir o arquivo no leitor de PDF —, mas sem ninguém no meio.
            </p>
            <p className="artigo__nota">Este texto explica o raciocínio e não substitui orientação jurídica para o seu caso.</p>
          </>
        ),
      }}
      passos={{
        titulo: "Como mexer no PDF sem ele sair do computador",
        conversao: "juntar-pdf",
        itens: [
          <p key="1">
            <strong>Abra a ferramenta de PDF que você precisa.</strong> Juntar, dividir, girar, comprimir, PDF para imagem ou para texto:
            todas rodam no navegador, sem instalar nada.
          </p>,
          <p key="2">
            <strong>Solte o PDF e confira.</strong> Com a aba Network aberta (F12), nada sai durante a conversão. Se quiser registrar isso no
            seu procedimento interno, anote a data e guarde um print.
          </p>,
          <p key="3">
            <strong>Baixe o resultado.</strong> PDF para Word editável roda pelo plugin, também no seu computador — inclusive PDF escaneado,
            com reconhecimento de texto.
          </p>,
        ],
        outras: ["comprimir-pdf", "dividir-pdf", "pdf-para-word", "pdf-para-texto", "imagens-para-pdf", "pdf-para-jpg"],
      }}
      planos={
        <p>
          Um escritório pequeno costuma resolver no grátis: PDF até {PRECOS.pdf} páginas e {PRECOS.lote} arquivos por vez. Processo com
          centenas de páginas, lote diário ou PDF para Word pedem o <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias) ou o{" "}
          <strong>aplicativo vitalício</strong> ({PRECOS.app}, em até {PRECOS.maquinas} computadores do titular), que trabalha em fila e sem
          internet.
        </p>
      }
      faq={[
        {
          pergunta: "Converter no navegador ainda é tratamento pela LGPD?",
          resposta:
            "É tratamento feito por você, controlador, no seu equipamento — como abrir o PDF no leitor. O que desaparece é o terceiro: ninguém mais recebe o dado.",
        },
        {
          pergunta: "O site de vocês recebe alguma coisa do PDF?",
          resposta:
            "Não recebe o arquivo, o nome dele nem o conteúdo. A página só é entregue; no Pro, a chave é conferida no próprio navegador, sem consultar servidor.",
        },
        {
          pergunta: "Preciso de contrato de operador com vocês?",
          resposta: "Não tratamos os dados dos seus clientes, então não há tratamento para contratar.",
        },
        {
          pergunta: "Juntar ou comprimir um PDF assinado mantém a assinatura?",
          resposta:
            "Não. Qualquer alteração invalida a assinatura digital que o arquivo tinha. Faça essas operações antes de assinar, ou guarde o original assinado.",
        },
      ]}
    />
  );
}
