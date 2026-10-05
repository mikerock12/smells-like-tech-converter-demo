import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("comprimir-video-whatsapp");

export default function PaginaComprimirVideoWhatsapp() {
  return (
    <Artigo
      slug="comprimir-video-whatsapp"
      abertura={
        <>
          <p>
            O vídeo ficou grande demais para o WhatsApp, ou chegou do outro lado todo borrado. Comprimir antes de mandar resolve os dois:
            você escolhe o tamanho, e o arquivo sai leve sem passar por site nenhum — a compressão roda no seu navegador.
          </p>
        </>
      }
      risco={{
        titulo: "Por que não usar um compressor de vídeo online",
        corpo: (
          <>
            <p>
              Vídeo é o arquivo mais pesado que existe no seu celular ou no seu computador. Subir 300 MB para um site leva minutos, gasta
              franquia e deixa uma cópia do vídeo — com rostos, vozes e lugares — no servidor de alguém. Depois, você ainda baixa o resultado.
            </p>
            <p>Comprimindo aqui, não há upload nem download: o vídeo sai do seu disco e volta para ele, mais leve.</p>
          </>
        ),
      }}
      extra={
        <section className="artigo__secao">
          <h2>Por que o WhatsApp estraga o vídeo</h2>
          <p>
            Quando o vídeo é grande, o WhatsApp reduz a resolução e a qualidade do jeito dele antes de enviar, para economizar dados. Texto na
            tela e rosto em movimento são os primeiros a sofrer. Mandando o vídeo já no tamanho certo, você decide o que perde — por exemplo,
            720p nítido em vez de 1080p borrado.
          </p>
        </section>
      }
      passos={{
        titulo: "Como comprimir para o WhatsApp, em 3 passos",
        conversao: "comprimir-video",
        itens: [
          <p key="1">
            <strong>Abra “Comprimir vídeo” e solte o arquivo</strong> — MP4, MOV, MKV ou WEBM.
          </p>,
          <p key="2">
            <strong>Em “Para onde vai”, escolha WhatsApp.</strong> O vídeo mira 16 MB e 720p. Vídeo longo demais para caber? Corte antes o
            trecho que importa, em “Cortar vídeo”.
          </p>,
          <p key="3">
            <strong>Baixe e mande.</strong> Para vídeo de gigabytes, converta pelo plugin, que usa a placa de vídeo do computador.
          </p>,
        ],
        outras: ["cortar-video", "mov-para-mp4", "mp4-para-mp3", "mp4-para-gif"],
      }}
      planos={
        <p>
          No grátis, vídeo até {PRECOS.video} minutos por arquivo no navegador — cobre quase todo vídeo de WhatsApp. Vídeo mais longo ou lote
          de vídeos pedem o <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias, sem limite de duração) ou o{" "}
          <strong>aplicativo</strong> ({PRECOS.app}, vitalício), que converte com o FFmpeg da máquina.
        </p>
      }
      faq={[
        {
          pergunta: "Mando como vídeo ou como documento?",
          resposta:
            "Como documento, o WhatsApp manda o arquivo inteiro, sem mexer. Como vídeo, ele pode comprimir de novo. Um vídeo que já sai leve daqui passa rápido dos dois jeitos.",
        },
        {
          pergunta: "Perde o som?",
          resposta: "Não. O áudio é mantido, em qualidade boa para voz (96 kbps).",
        },
        {
          pergunta: "Funciona no celular?",
          resposta: "Em celular recente, para vídeo curto, sim. Vídeo longo rende melhor no computador.",
        },
        {
          pergunta: "E se eu quiser outro tamanho?",
          resposta: "Use “Metade do tamanho”, “Um quarto do tamanho” ou o perfil de e-mail, que mira 25 MB.",
        },
      ]}
    />
  );
}
