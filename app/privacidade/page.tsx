import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";

export const metadata: Metadata = {
  title: "Política de privacidade — Smells Like Tech Converter",
  description: "O que o site sabe sobre você (quase nada) e o que faz com isso.",
};

export default function PaginaDePrivacidade() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page legal">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Política de privacidade</p>
          <h1>
            Seus arquivos
            <br />
            <em>nunca chegam até nós.</em>
          </h1>
          <p className="plugin-page__lead">
            Esta política é curta porque a arquitetura do produto é curta: a conversão acontece no seu dispositivo, e
            não existe servidor recebendo mídia. O que sobra para explicar é a loja e o que o navegador guarda.
          </p>
        </header>

        <section className="plugin-page__secao">
          <h2 className="panel__title">1. Arquivos e conteúdo</h2>
          <p>
            Imagens, áudios, vídeos, PDFs, legendas e textos que você converte, edita, transcreve ou narra{" "}
            <strong>não são enviados para nenhum servidor</strong> — nem nosso, nem de terceiros. O processamento
            acontece no seu navegador (WebAssembly e WebCodecs) ou, se você instalar o plugin ou o aplicativo, na sua
            própria máquina. Você pode conferir pela aba Rede do navegador ou desligando a internet: a conversão
            continua.
          </p>
          <p>
            Duas coisas são baixadas sob demanda, e nenhuma leva o seu arquivo: no reconhecimento de texto (OCR), o
            pacote do idioma (alguns megabytes), de um repositório público; e, na primeira foto HEIC, o leitor de HEIC
            (cerca de 2 MB), do próprio site. A imagem em si não sai do seu computador.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">2. O que fica no seu navegador</h2>
          <p>
            Guardamos no armazenamento local do navegador: a sua chave de acesso, se você ativar uma; os contadores
            diários do plano grátis; os arquivos de idioma do OCR; e, se você entrar na conta, o e-mail dela, para
            preencher a compra, e a contagem de uso ainda não enviada (ver o item 5). Nada disso é enviado a nós. Limpar os dados do site apaga tudo.
          </p>
          <p>
            Para abrir e converter sem internet, o navegador também guarda uma cópia do próprio site: as páginas e os
            motores de conversão, nunca os seus arquivos. Quem instala o app no celular guarda os motores inteiros (alguns
            megabytes). Um arquivo compartilhado com o app (no Android, pelo menu Compartilhar) passa pelo armazenamento do
            próprio aparelho até abrir na oficina, e é apagado em seguida; ele não é enviado a nenhum servidor.
          </p>
          <p>
            O único cookie é o da sessão da conta, e ele só existe para quem entra nela. Quem usa o site sem conta não
            recebe cookie nenhum. Não há pixel de rastreamento nem ferramenta de análise de terceiros.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">3. Quantas pessoas visitam cada página</h2>
          <p>
            Para saber se uma página ajuda quem procura um conversor, o servidor soma as visitas de cada página por dia, junto com a origem
            da visita: o nome do buscador que mandou a pessoa (Google, Bing), ou um rótulo nosso no endereço (por exemplo, quem chegou pelo
            aviso de limite ou pelo QR code). A soma é feita no momento em que a página é entregue; o navegador não faz nenhuma requisição a
            mais para isso.
          </p>
          <p>
            <strong>Não guardamos endereço IP, cookie, identificador do navegador, nome de arquivo nem nada que permita saber quem visitou.</strong>{" "}
            Uma linha dessa conta diz só “a página X teve N visitas no dia D, vindas de O”. Na compra, o pedido registra também de onde ela
            partiu (a página de preços ou o aviso de limite), para sabermos se esse aviso é útil.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">4. A loja</h2>
          <p>
            Ao comprar, pedimos o seu e-mail — é para onde vai a chave — e criamos um pedido com número, produto, valor
            e data. O pagamento acontece no Mercado Pago, que nos informa apenas se foi aprovado; não vemos dados de
            cartão. Guardamos o pedido, a chave emitida e o e-mail para que você possa recuperar a chave e para cumprir
            obrigações fiscais. Se configurado, enviamos a chave por e-mail através de um provedor de envio.
          </p>
          <p>
            Base legal (LGPD): execução de contrato e cumprimento de obrigação legal. Você pode pedir a exclusão dos seus
            dados de pedido a qualquer momento pelo e-mail abaixo; os registros fiscais são mantidos pelo prazo exigido
            em lei.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">5. A conta</h2>
          <p>
            A conta é opcional e serve para quem comprou encontrar as próprias chaves em qualquer navegador. Ela nasce
            quando você paga um pedido ou quando entra pela primeira vez com o código que mandamos ao seu e-mail. Não
            existe senha.
          </p>
          <p>
            Guardamos o e-mail, a data em que a conta foi criada e a do último acesso. Para cada navegador conectado, uma
            sessão com a data de validade, identificada por um resumo criptográfico do cookie, e não pelo cookie em si.
            O código de acesso fica guardado só como resumo e vale por 10 minutos. As compras e as chaves são as mesmas
            da loja, achadas pelo e-mail.
          </p>
          <p>
            <strong>Histórico de uso.</strong> Com a conta aberta, o site soma por dia quantos arquivos passaram por cada
            ferramenta e o tamanho total deles, para você acompanhar o seu uso em /conta. Nunca o nome, o conteúdo ou
            qualquer dado de dentro dos arquivos. A contagem fica no navegador e é enviada quando você sai da página, nunca
            durante a conversão; por isso, com a conta aberta, a aba Rede mostra esse envio ao fechar a página. O registro
            pode ser desligado e apagado em /conta a qualquer momento. Quem usa o site sem conta não gera registro nenhum.
          </p>
          <p>
            Base legal (LGPD): execução de contrato, para a conta e as compras; legítimo interesse, para o histórico de
            uso, que você pode desligar. “Sair” encerra a sessão daquele navegador. Para apagar a conta, escreva para o
            e-mail abaixo.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">6. O plugin e o aplicativo</h2>
          <p>
            Os dois rodam inteiramente no seu computador e não se conectam à internet, exceto quando você escolhe baixar
            um modelo de transcrição (do repositório público do modelo) ou quando o aplicativo verifica se há versão nova
            — uma consulta que não envia identificação alguma. Os registros de uso (logs) ficam na sua máquina e nunca
            contêm o conteúdo dos arquivos.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">7. O app para Android</h2>
          <p>
            O app converte, transcreve, lê texto (OCR) e narra no próprio celular: nenhum arquivo, áudio, texto ou dado de
            uso sai do aparelho, e não há anúncios, estatísticas de uso nem relatórios de erro. Ele lê só os arquivos que
            você escolhe ou compartilha com ele e grava os resultados em Downloads/SmellsLikeTech. A chave da licença e a
            data do começo do teste ficam guardadas no próprio app e são apagadas quando ele é desinstalado.
          </p>
          <p>
            O app usa a internet para uma coisa só: baixar um modelo de transcrição (Whisper), quando você pede, na tela
            &quot;Modelos de transcrição&quot;. O download é feito pelo gerenciador de downloads do Android, direto do
            repositório público do whisper.cpp no Hugging Face, que recebe, como qualquer site, o endereço IP e a
            identificação do aparelho que baixa. O pedido leva só o nome do arquivo, e o áudio transcrito nunca sai do
            celular.
          </p>
          <p>
            Quando o app é instalado pela Google Play, a compra é feita pelo Google Play, que trata o pagamento conforme a política de
            privacidade do Google. Não recebemos dados do seu cartão. O app só confere, no próprio celular, o recibo da compra
            que a Play Store entrega a ele.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">8. Contato</h2>
          <p>
            Smells Like Tech Informática · CNPJ 30.054.253/0001-09 · Porto Alegre/RS.
            <br />
            Dúvidas e pedidos sobre dados: <a className="converter__link" href="mailto:converter@smellsliketech.com.br">converter@smellsliketech.com.br</a>.
          </p>
          <p>Última atualização: setembro de 2026.</p>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
