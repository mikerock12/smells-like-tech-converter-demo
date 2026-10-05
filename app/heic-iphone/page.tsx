import Artigo, { PRECOS, metadadosDoArtigo } from "../Artigo";

export const metadata = metadadosDoArtigo("heic-iphone");

export default function PaginaHeicIphone() {
  return (
    <Artigo
      slug="heic-iphone"
      abertura={
        <>
          <p>
            Você passou as fotos do iPhone para o Windows e elas vieram em .HEIC — e o computador não abre, ou pede para instalar extensão.
            O caminho mais curto: converter HEIC para JPG aqui, no navegador, sem instalar nada e sem mandar as fotos para site nenhum.
          </p>
        </>
      }
      risco={{
        titulo: "Por que não mandar as fotos para um conversor online",
        corpo: (
          <>
            <p>
              Foto de celular carrega mais do que a imagem: o lugar onde foi tirada, a data e o modelo do aparelho vão gravados dentro do
              arquivo. E o rolo da câmera costuma ter documento, criança, casa. Subir isso para um site desconhecido é entregar tudo junto,
              mesmo que ele prometa apagar depois.
            </p>
            <p>
              Aqui a foto é lida e convertida no seu computador. E o JPG que sai não leva a localização nem os outros metadados da foto
              original.
            </p>
          </>
        ),
      }}
      extra={
        <section className="artigo__secao">
          <h2>Por que o Windows não abre HEIC</h2>
          <p>
            HEIC guarda a imagem com o codec HEVC, o mesmo de vídeo. Para abrir no aplicativo Fotos, o Windows precisa de duas extensões da
            Microsoft Store: a de imagens HEIF e a de vídeo HEVC — esta, paga. Convertendo para JPG, a foto abre em qualquer lugar, sem
            extensão nenhuma.
          </p>
          <h3>Para as próximas fotos</h3>
          <ul>
            <li>
              No iPhone, <strong>Ajustes → Câmera → Formatos → Mais Compatível</strong> faz a câmera salvar em JPG.
            </li>
            <li>
              <strong>Ajustes → Fotos → Transferir para Mac ou PC → Automático</strong> converte na hora de passar as fotos pelo cabo.
            </li>
          </ul>
        </section>
      }
      passos={{
        titulo: "Como converter HEIC para JPG, em 3 passos",
        conversao: "heic-para-jpg",
        itens: [
          <p key="1">
            <strong>Abra “HEIC para JPG”.</strong> A página já vem com a saída em JPG.
          </p>,
          <p key="2">
            <strong>Solte as fotos, várias de uma vez.</strong> No Safari, o próprio navegador lê o HEIC; no Chrome, no Edge e no Firefox, o
            leitor de HEIC (cerca de 2 MB) entra na preparação offline e roda no seu computador.
          </p>,
          <p key="3">
            <strong>Baixe os JPGs.</strong> Com a aba Network aberta (F12), dá para ver que nenhuma foto sai durante a conversão.
          </p>,
        ],
        outras: ["heic-para-png", "imagens-para-pdf", "comprimir-imagem"],
      }}
      planos={
        <p>
          No grátis são {PRECOS.lote} fotos por vez — dá para converter em levas. Para o rolo inteiro de uma vez, o <strong>Pro</strong> (
          {PRECOS.pro} por {PRECOS.diasDoPro} dias) não tem limite de arquivos, e o <strong>aplicativo</strong> ({PRECOS.app}, vitalício)
          converte pastas inteiras.
        </p>
      }
      faq={[
        {
          pergunta: "Perde qualidade?",
          resposta:
            "O JPG comprime, mas na qualidade padrão a diferença não aparece a olho nu. Se precisar de zero perda, escolha PNG, que gera arquivo maior.",
        },
        {
          pergunta: "E a Live Photo?",
          resposta: "O HEIC traz a imagem principal, que é a que sai convertida. O vídeo curto da Live Photo vem em outro arquivo, .MOV.",
        },
        {
          pergunta: "Funciona no celular?",
          resposta: "Sim, no navegador do celular também.",
        },
        {
          pergunta: "Funciona sem internet?",
          resposta: "Sim, depois de aparecer “Pronto para converter sem internet” na oficina. O leitor de HEIC já entra na preparação, antes da primeira foto. Veja as condições no rodapé.",
        },
      ]}
    />
  );
}
