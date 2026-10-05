import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("sem-upload");

export default function PaginaSemUpload() {
  return (
    <Artigo
      slug="sem-upload"
      abertura={
        <>
          <p>
            Você procurou um conversor online, achou dez, e todos pedem a mesma coisa: “envie seu arquivo”. Para uma foto de gato, tanto
            faz. Para o contrato, o exame, a foto do documento ou a gravação de uma reunião, é outra conversa: o arquivo sai do seu
            computador, atravessa a internet e fica, por algum tempo, no servidor de alguém que você não conhece.
          </p>
          <p>
            Dá para converter sem nada disso. Aqui a conversão roda no seu navegador, no seu computador. O arquivo não é enviado — e você
            confere isso sozinho, em 15 segundos.
          </p>
        </>
      }
      risco={{
        titulo: "O que acontece quando você envia um arquivo",
        corpo: (
          <>
            <ul>
              <li>
                <strong>O arquivo vira cópia.</strong> Depois do upload existem pelo menos duas: a sua e a do servidor. Às vezes mais, entre
                cópias de segurança e o armazenamento de quem hospeda o serviço.
              </li>
              <li>
                <strong>“Apagamos em 1 hora” é promessa, não prova.</strong> Você não tem como conferir quando — nem se — o arquivo foi
                apagado, nem o que ficou nos registros: nome do arquivo, seu endereço IP, horário.
              </li>
              <li>
                <strong>O servidor pode estar em outro país.</strong> Aí o arquivo segue as leis de lá. Se o documento tem dado pessoal de
                outra pessoa, isso entra na conta da LGPD — veja <a href="/lgpd-conversor">conversor de PDF e LGPD</a>.
              </li>
              <li>
                <strong>Todo servidor pode ser invadido.</strong> Arquivo que nunca foi enviado não vaza de servidor nenhum.
              </li>
            </ul>
            <p className="artigo__nota">
              Os bons serviços apagam mesmo e levam segurança a sério. O ponto não é desconfiar deles: é não precisar confiar em ninguém para
              converter um arquivo.
            </p>
          </>
        ),
      }}
      passos={{
        titulo: "Como converter sem enviar, em 3 passos",
        itens: [
          <p key="1">
            <strong>Abra a conversão que você precisa.</strong> WEBP para PNG, HEIC para JPG, juntar PDF, MP4 para MP3: cada uma tem página
            própria, que já abre pronta.
          </p>,
          <p key="2">
            <strong>Aperte F12 e deixe a aba Network aberta.</strong> Solte o arquivo e converta. Durante a conversão, nenhuma requisição sai:
            zero upload.
          </p>,
          <p key="3">
            <strong>Baixe o resultado.</strong> Para a prova final, desligue o Wi-Fi e converta de novo. Continua funcionando, porque nunca
            dependeu de servidor.
          </p>,
        ],
        outras: ["webp-para-png", "heic-para-jpg", "juntar-pdf", "mp4-para-mp3", "comprimir-pdf", "comprimir-video"],
      }}
      planos={
        <p>
          Para o dia a dia, o grátis basta: {PRECOS.lote} arquivos por vez, vídeo até {PRECOS.video} minutos, PDF até {PRECOS.pdf} páginas,
          sem marca d&apos;água e com a mesma qualidade do pago. O <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias, sem
          renovação automática) tira esses tetos e libera transcrição e OCR sem contador. Se você converte todo dia e em lote, o{" "}
          <strong>aplicativo para Windows</strong> ({PRECOS.app}, vitalício, até {PRECOS.maquinas} computadores) trabalha em fila, a partir
          de pastas, e sem internet.
        </p>
      }
      faq={[
        {
          pergunta: "Se não envia, como converte?",
          resposta:
            "O navegador de hoje tem os mesmos ingredientes de um programa de conversão instalado. A página entrega o código da conversão, e ele roda no seu processador. A explicação completa está em “Como a conversão local funciona”.",
        },
        {
          pergunta: "Funciona sem internet?",
          resposta: "Sim, depois de aparecer “Pronto para converter sem internet” na oficina. Aguarde a preparação dos motores antes de desconectar; veja as exceções no rodapé.",
        },
        {
          pergunta: "Vocês guardam alguma coisa?",
          resposta:
            "Nenhum arquivo, nenhum nome de arquivo. O navegador guarda, só para você, a chave do Pro (se você tiver) e os contadores do plano grátis.",
        },
        {
          pergunta: "E os conversores que dizem apagar o arquivo em uma hora?",
          resposta:
            "Podem apagar mesmo. Mas o arquivo saiu do seu computador, e você depende da palavra de alguém. Aqui não existe o que apagar.",
        },
      ]}
    />
  );
}
