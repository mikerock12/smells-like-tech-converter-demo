import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("transcrever-sem-nuvem");

export default function PaginaTranscreverSemNuvem() {
  return (
    <Artigo
      slug="transcrever-sem-nuvem"
      abertura={
        <>
          <p>
            Transcrever uma reunião, uma entrevista ou uma consulta costuma significar mandar o áudio para um serviço na nuvem — e, com ele,
            tudo o que foi dito ali. Dá para fazer diferente: o Whisper, o modelo de reconhecimento de fala de código aberto, roda no seu
            Windows pelo plugin, e o áudio não sai da máquina.
          </p>
          <p>O resultado sai em texto corrido para ler, ou em legenda SRT ou VTT com o tempo de cada fala.</p>
        </>
      }
      risco={{
        titulo: "O problema de mandar a reunião para a nuvem",
        corpo: (
          <>
            <p>
              Uma gravação de reunião carrega nomes, números, estratégia e, às vezes, dado de saúde ou de cliente. Um serviço de transcrição
              na nuvem precisa receber o áudio inteiro para funcionar. Alguns guardam o arquivo e o texto na sua conta por tempo
              indeterminado, e o que pode ser feito com esses dados fica nos termos de uso de cada serviço.
            </p>
            <p>
              Mesmo quando tudo é apagado, o áudio passou por um servidor que não é seu. Com a transcrição local, ele vai do seu disco para o
              seu processador e volta como texto.
            </p>
          </>
        ),
      }}
      passos={{
        titulo: "Como transcrever no seu computador, em 3 passos",
        conversao: "audio-para-texto",
        itens: [
          <p key="1">
            <strong>Instale o plugin gratuito</strong> (Windows 10 ou 11, 64 bits). Ele liga o site ao seu computador e lê o áudio com o
            FFmpeg da própria máquina.
          </p>,
          <p key="2">
            <strong>Abra “Áudio para texto” e solte a gravação</strong> — MP3, WAV, M4A ou o próprio vídeo. Escolha o idioma e o modelo. Na
            primeira vez, o modelo escolhido é baixado do repositório público dele; depois, fica na máquina.
          </p>,
          <p key="3">
            <strong>Baixe em TXT, SRT ou VTT.</strong> Na aba Network (F12) você vê só a conversa com 127.0.0.1, que é o plugin na sua
            própria máquina. Nada vai para a internet.
          </p>,
        ],
        outras: ["video-para-texto", "audio-para-srt", "mp4-para-mp3"],
      }}
      planos={
        <p>
          O grátis transcreve {PRECOS.transcricao} minutos por dia: dá para testar a qualidade com um trecho da sua reunião. Para reunião
          inteira, o <strong>Pro</strong> ({PRECOS.pro} por {PRECOS.diasDoPro} dias, sem renovação automática) transcreve sem limite. O{" "}
          <strong>aplicativo vitalício</strong> ({PRECOS.app}) transcreve em lote, a partir de pastas.
        </p>
      }
      faq={[
        {
          pergunta: "Qual modelo escolher?",
          resposta:
            "O Small é o equilíbrio. O Base é mais rápido; o Medium e o Large são mais fiéis e mais lentos. Em computador sem placa de vídeo dedicada, comece pelo Small.",
        },
        {
          pergunta: "Funciona em português?",
          resposta: "Sim, é o padrão. Também inglês, espanhol, francês, alemão e italiano, ou detecção automática do idioma.",
        },
        {
          pergunta: "Identifica quem falou?",
          resposta: "Não. A transcrição sai em texto corrido ou em legenda com tempo, sem separar as vozes.",
        },
        {
          pergunta: "Preciso de internet?",
          resposta: "Só para baixar o modelo na primeira vez. Depois, a transcrição funciona com o computador fora da rede.",
        },
      ]}
    />
  );
}
