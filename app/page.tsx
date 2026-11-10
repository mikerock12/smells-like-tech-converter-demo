import { CATEGORIAS, contagem, ferramentasDaCategoria } from "@/packages/converter-core/catalogo.mjs";
import { ATALHOS_DA_HOME, contagemDePaginas } from "@/packages/converter-core/paginas.mjs";
import { DIAS_DE_TESTE_DO_APP, MAQUINAS_POR_LICENCA, formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import { ANDROID_NA_PLAY } from "@/packages/converter-core/android.mjs";
import { DEPOIMENTOS } from "@/packages/conteudo/depoimentos.mjs";

import BotaoGooglePlay from "./BotaoGooglePlay";
import PluginBadge from "./PluginBadge";
import { SiteFooter, SiteHeader } from "./SiteChrome";

/**
 * Home.
 *
 * Quem chega quer um arquivo convertido. A página responde em três passos: o que é
 * (converter sem enviar para ninguém), como conferir em 15 segundos, e o atalho para o
 * que a pessoa veio fazer. A lista completa de ferramentas, os planos e os detalhes vêm
 * depois — nada de jargão técnico como argumento de venda; isso mora em /como-funciona.
 */

/** Onde a conversão pode acontecer. Os três são o mesmo produto, em formas diferentes. */
const lugares = [
  {
    nome: "No navegador",
    resumo: "Sem instalar nada · grátis",
    detalhe:
      "Abre a página e converte imagem, áudio, vídeo, PDF e legenda. Funciona em qualquer computador, inclusive no do trabalho onde você não instala programas.",
    acao: { texto: "Abrir a oficina", destino: "/converter" },
  },
  {
    nome: "Com o plugin",
    resumo: "Um programinha de 64 MB · grátis",
    detalhe:
      "Quem converte passa a ser a sua máquina: processador, placa de vídeo, codecs. Vídeo de gigabytes, transcrição, narração e PDF para Word.",
    destaque: true,
    acao: { texto: "Baixar o plugin", destino: "/plugin" },
  },
  {
    nome: "No aplicativo",
    resumo: `O programa completo · ${formatarReais(produto("app-vitalicio")!.centavos)} uma vez`,
    detalhe: "Para quem converte o dia inteiro: fila com histórico, lote a partir de pastas, presets e ajuste fino. Funciona sem internet.",
    acao: { texto: "Conhecer o aplicativo", destino: "/aplicativo" },
  },
  {
    nome: "No celular",
    resumo: `App para Android · ${DIAS_DE_TESTE_DO_APP} dias grátis`,
    detalhe: "O conversor inteiro no celular, pela Google Play: estúdio de vídeo, criação musical, figurinhas do WhatsApp, transcrição e narração. Funciona sem internet.",
    acao: { texto: "Conhecer o app para Android", destino: "/android" },
  },
];

/** O que o app para Android faz, para a seção da página inicial. */
const NO_ANDROID = [
  { icone: "⇄", titulo: "Converter tudo", texto: "Imagem, vídeo, áudio, PDF e documentos, inclusive HEIC do iPhone, direto da galeria ou do WhatsApp." },
  { icone: "✦", titulo: "Estúdio de vídeo", texto: "Reels, TikTok e Shorts com cortes, textos, efeitos, música e fundo removido." },
  { icone: "♫", titulo: "Criação musical", texto: "Grave faixas ouvindo as outras, separe voz, bateria e baixo por IA e mixe com efeitos." },
  { icone: "☺", titulo: "Figurinhas do WhatsApp", texto: "Foto, GIF ou vídeo viram figurinha, com recorte, texto e fundo removido, sem anúncios." },
  { icone: "✎", titulo: "Transcrever e narrar", texto: "Áudio vira texto ou legenda com o Whisper; texto vira áudio com vozes naturais em português." },
  { icone: "▣", titulo: "OCR", texto: "Texto de foto e de PDF escaneado, em português, inglês e espanhol." },
];

/**
 * As portas da página inicial, as mesmas da tela inicial dos apps: converter e criar são as
 * duas grandes; transcrever, narrar e comprimir para o WhatsApp são os pedidos mais comuns e
 * ninguém os acharia dentro de "converter".
 */
const CAMINHOS = [
  {
    icone: "⇄",
    titulo: "Converter uma mídia",
    descricao: "Troque o formato, comprima, corte ou junte vídeos, áudios, imagens, PDFs e documentos.",
    acao: "Abrir a oficina",
    destino: "/converter",
    destaque: true,
  },
  {
    icone: "✦",
    titulo: "Criar no estúdio",
    descricao: "Monte vídeo, GIF, imagem ou áudio no tamanho certo para Reels, Stories, TikTok, Shorts, Kwai e WhatsApp: cortes, textos e música.",
    acao: "Abrir o estúdio",
    destino: "/estudio",
    destaque: true,
  },
  {
    icone: "✎",
    titulo: "Transcrever áudio ou vídeo",
    descricao: "A fala vira texto ou legenda com o Whisper, no seu computador.",
    acao: "Transcrever",
    destino: "/converter/audio-para-texto",
  },
  {
    icone: "♪",
    titulo: "Narrar um texto",
    descricao: "Texto, PDF ou documento viram áudio com vozes naturais em português.",
    acao: "Narrar",
    destino: "/converter/texto-para-voz",
  },
  {
    icone: "⬇",
    titulo: "Comprimir vídeo para o WhatsApp",
    descricao: "O vídeo cabe no limite do WhatsApp sem virar borrão.",
    acao: "Comprimir",
    destino: "/comprimir-video-whatsapp",
  },
];

export default function Home() {
  const { total, noNavegador } = contagem();
  const { total: paginas } = contagemDePaginas();
  const mensal = produto("pro-mensal")!;
  const anual = produto("pro-anual")!;
  const app = produto("app-vitalicio")!;
  const completo = produto("completo-vitalicio")!;
  const android = produto("android-vitalicio")!;
  const androidMensal = produto("android-mensal")!;

  return (
    <main>
      <SiteHeader page="home" />

      <section className="hero" id="top">
        <div className="hero-figure">
          <img
            src="/marca/mascote.webp"
            alt="O guaxinim da Smells Like Tech, de moletom preto, sobre um fundo de placa de circuito."
            width={380}
            height={608}
            fetchPriority="high"
          />
        </div>

        <div className="hero-copy">
          <img className="hero-wordmark" src="/marca/titulo.webp" alt="Smells Like Tech Converter" width={420} height={155} fetchPriority="high" />
          <div className="hero-kicker">
            <span aria-hidden="true">●</span> Nenhum arquivo sai do seu computador
          </div>
          <h1>
            Converta arquivo
            <em>sem enviar para ninguém.</em>
          </h1>
          <p>Imagem, áudio, vídeo, PDF e texto. No seu computador. Sem cadastro, sem marca d&apos;água, sem servidor no meio.</p>
          <div className="hero-actions">
            <a className="primary-action" href="/converter">
              Converter uma mídia <span aria-hidden="true">→</span>
            </a>
            <a className="primary-action primary-action--secundaria" href="/estudio">
              Criar no estúdio <span aria-hidden="true">→</span>
            </a>
          </div>
        </div>
      </section>

      <section className="caminhos" aria-labelledby="caminhos-titulo">
        <div className="section-heading section-heading--compacta">
          <div>
            <span>Comece por aqui</span>
            <h2 id="caminhos-titulo">O que você quer fazer?</h2>
          </div>
        </div>
        <ul className="caminhos__grade">
          {CAMINHOS.map((caminho) => (
            <li key={caminho.destino} className={caminho.destaque ? "caminhos__item caminhos__item--destaque" : "caminhos__item"}>
              <a href={caminho.destino}>
                <span className="caminhos__icone" aria-hidden="true">{caminho.icone}</span>
                <strong>{caminho.titulo}</strong>
                <p>{caminho.descricao}</p>
                <span className="caminhos__ir">{caminho.acao} <span aria-hidden="true">→</span></span>
              </a>
            </li>
          ))}
        </ul>
      </section>

      {ANDROID_NA_PLAY && (
        <section className="app-android" aria-labelledby="app-android-titulo">
          <div className="app-android__topo">
            <span className="section-label">App para Android · Google Play</span>
            <h2 id="app-android-titulo">
              O conversor inteiro no celular. <em>Sem enviar nada para ninguém.</em>
            </h2>
            <p>
              Tudo acontece no próprio aparelho, mesmo sem internet: o arquivo não sai do celular. Escolha na galeria ou
              compartilhe do WhatsApp e de qualquer app, sem cadastro, sem anúncios e sem marca d&apos;água.
            </p>
          </div>
          <ul className="app-android__grade">
            {NO_ANDROID.map((item) => (
              <li key={item.titulo}>
                <span className="caminhos__icone" aria-hidden="true">{item.icone}</span>
                <strong>{item.titulo}</strong>
                <p>{item.texto}</p>
              </li>
            ))}
          </ul>
          <div className="app-android__teste">
            <div>
              <strong>{DIAS_DE_TESTE_DO_APP} dias grátis, com tudo liberado.</strong>
              <p>
                Instale e use todas as funções por {DIAS_DE_TESTE_DO_APP} dias, sem cartão e sem cadastro. Depois, se quiser
                continuar, escolha dentro do app: a assinatura de {formatarReais(androidMensal.centavos)} por mês, que você
                cancela na própria Google Play, ou a licença vitalícia de {formatarReais(android.centavos)}, paga uma vez.
                Quem leva o pacote completo ({formatarReais(completo.centavos)}) usa o Android, o Windows e o Pro do site com
                uma chave só.
              </p>
            </div>
            <div className="app-android__acoes">
              <BotaoGooglePlay />
              <a className="button button--ghost" href="/precos#android">
                Ver os preços e os pacotes
              </a>
            </div>
          </div>
        </section>
      )}

      <section className="prova-rapida" aria-labelledby="prova-titulo">
        <h2 id="prova-titulo" className="section-label">
          A prova, em 15 segundos
        </h2>
        <ol className="prova-rapida__lista">
          <li>
            <strong>Nada sai do seu computador.</strong>
            <p>O arquivo é lido direto do seu disco e convertido ali mesmo. Não existe botão de enviar porque não existe para onde enviar.</p>
          </li>
          <li>
            <strong>Abra o DevTools → aba Network enquanto converte.</strong>
            <p>F12 no navegador. Depois de preparar os motores, a conversão é local: zero upload. Dá para desligar a internet.<a href="#nota-offline" aria-label="Condições do uso sem internet"> *</a></p>
          </li>
          <li>
            <strong>Sem conta. Sem marca d&apos;água.</strong>
            <p>Grátis de verdade para o uso do dia a dia, com a mesma qualidade do plano pago.</p>
          </li>
        </ol>
      </section>

      <section className="atalhos" aria-labelledby="atalhos-titulo">
        <div className="section-heading section-heading--compacta">
          <div>
            <span>Direto ao ponto</span>
            <h2 id="atalhos-titulo">O que você veio fazer</h2>
          </div>
        </div>
        <ul className="atalhos__grade">
          {ATALHOS_DA_HOME.map((atalho) => (
            <li key={atalho.slug}>
              <a href={`/converter/${atalho.slug}`}>
                {atalho.rotulo} <span aria-hidden="true">→</span>
              </a>
            </li>
          ))}
        </ul>
        <p className="atalhos__mais">
          <a className="converter__link" href="/ferramentas">
            Ver as {total} ferramentas e as {paginas} páginas de conversão
          </a>
        </p>
      </section>

      <section className="para-quem" aria-labelledby="para-quem-titulo">
        <div className="section-heading section-heading--compacta">
          <div>
            <span>Para quem isso importa</span>
            <h2 id="para-quem-titulo">Quando o arquivo não é só seu.</h2>
          </div>
        </div>
        <ul className="para-quem__lista">
          <li>
            <strong>Arquivo de cliente</strong>
            <p>Contrato, exame, holerite, gravação de atendimento. Juntar, comprimir ou passar para Word sem entregar o documento a um site.</p>
            <a className="converter__link" href="/pdf-cliente">
              Converter arquivo de cliente sem vazar
            </a>
          </li>
          <li>
            <strong>Foto e vídeo pessoais</strong>
            <p>O que você não quer na nuvem de um site desconhecido, mesmo que ele prometa apagar em uma hora.</p>
            <a className="converter__link" href="/sem-upload">
              Por que não enviar arquivo para conversor online
            </a>
          </li>
          <li>
            <strong>Lote e arquivo grande</strong>
            <p>Centenas de arquivos, vídeo de gigabytes, transcrição de reunião: o plugin ou o aplicativo, ainda no seu computador.</p>
            <a className="converter__link" href="/plugin">
              Conhecer o plugin
            </a>
          </li>
        </ul>
      </section>

      <section className="planos-curtos" aria-labelledby="planos-titulo">
        <div className="section-heading section-heading--compacta">
          <div>
            <span>Planos</span>
            <h2 id="planos-titulo">Uma frase cada.</h2>
          </div>
        </div>
        <dl className="planos-curtos__lista">
          <div>
            <dt>Grátis</dt>
            <dd>
              No navegador, {noNavegador} ferramentas, limites de volume, mesma qualidade.
            </dd>
          </div>
          <div className="planos-curtos__destaque">
            <dt>
              Pro · {formatarReais(mensal.centavos)}/mês ou {formatarReais(anual.centavos)}/ano
            </dt>
            <dd>Sem teto, sem renovação automática. Você decide renovar.</dd>
          </div>
          <div>
            <dt>Aplicativo Windows · {formatarReais(app.centavos)} vitalício</dt>
            <dd>Lote, fila, offline, até {MAQUINAS_POR_LICENCA} PCs.</dd>
          </div>
          <div>
            <dt>App para Android · {formatarReais(androidMensal.centavos)}/mês ou {formatarReais(android.centavos)} vitalício</dt>
            <dd>{DIAS_DE_TESTE_DO_APP} dias grátis, depois pela Google Play.</dd>
          </div>
          <div>
            <dt>Tudo, para sempre · {formatarReais(completo.centavos)}</dt>
            <dd>Windows, Android e Pro no site e no plugin, uma vez só.</dd>
          </div>
        </dl>
        <a className="primary-action" href="/precos">
          Ver os planos <span aria-hidden="true">→</span>
        </a>
      </section>

      {DEPOIMENTOS.length > 0 && (
        <section className="depoimentos" aria-labelledby="depoimentos-titulo">
          <div className="section-heading section-heading--compacta">
            <div>
              <span>Quem usa</span>
              <h2 id="depoimentos-titulo">Nas palavras de quem converte aqui.</h2>
            </div>
          </div>
          <ul className="depoimentos__lista">
            {DEPOIMENTOS.map((item) => (
              <li key={item.texto}>
                <blockquote>“{item.texto}”</blockquote>
                <p>
                  — {item.autor}
                  {item.local ? `, ${item.local}` : ""}
                </p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="features" id="o-que-converte">
        <div className="section-heading">
          <span>Tudo o que dá para fazer</span>
          <h2>
            {total} ferramentas,
            <br />
            um lugar só.
          </h2>
        </div>
        <div className="capability-grid">
          {CATEGORIAS.map((categoria) => {
            const ferramentas = ferramentasDaCategoria(categoria.id);
            const noNav = ferramentas.filter((item) => item.navegador).length;
            return (
              <article key={categoria.id} className={`capability${noNav === ferramentas.length ? " capability--pronto" : ""}`}>
                <div className="capability__top">
                  <small>{categoria.titulo}</small>
                  <span className="capability__state">{ferramentas.length} ferramentas</span>
                </div>
                <h3>{categoria.chamada}</h3>
                <p>{categoria.resumo}</p>
                <ul className="capability__lista">
                  {ferramentas.slice(0, 5).map((item) => (
                    <li key={item.id}>
                      <a href={`/converter/${item.slug}`}>{item.titulo}</a>
                    </li>
                  ))}
                  {ferramentas.length > 5 && (
                    <li>
                      <a href={`/ferramentas#${categoria.id}`}>e mais {ferramentas.length - 5}…</a>
                    </li>
                  )}
                </ul>
                <dl className="onde">
                  <div className={`onde__linha onde__linha--${noNav > 0 ? "sim" : "ainda"}`}>
                    <dt>No navegador</dt>
                    <dd>{noNav === ferramentas.length ? "pronto" : noNav > 0 ? `${noNav} de ${ferramentas.length}` : "precisa do plugin"}</dd>
                  </div>
                  <div className="onde__linha onde__linha--sim">
                    <dt>Com o plugin</dt>
                    <dd>pronto</dd>
                  </div>
                </dl>
              </article>
            );
          })}
        </div>
        <p className="capability-note">
          <strong>No navegador</strong> é o que a página faz sozinha, sem instalar nada: {noNavegador} das {total} ferramentas.{" "}
          <strong>Com o plugin</strong> é tudo, porque ali quem converte é o motor completo rodando na sua máquina. Em nenhum dos
          dois o arquivo sai do seu computador.
        </p>
      </section>

      <section className="privacy-section" id="privacidade">
        <div className="privacy-copy">
          <span className="section-label">Privacidade de verdade</span>
          <h2>
            Não peça
            <br />
            para confiar.
            <br />
            <em>Confira.</em>
          </h2>
          <p>
            Toda promessa de privacidade na internet termina em &ldquo;prometemos apagar depois&rdquo;. Esta você testa sozinho, em dois
            minutos, sem acreditar em nada do que está escrito aqui.{" "}
            <a className="converter__link" href="/como-funciona">
              Como a conversão local funciona
            </a>
            .
          </p>
        </div>
        <ol className="privacy-tests">
          <li>
            <strong>Abra a aba Network</strong>
            <p>Nas ferramentas do desenvolvedor do navegador (F12). Converta um arquivo e olhe a lista: nenhuma requisição sai enquanto a conversão acontece.</p>
          </li>
          <li>
            <strong>Desligue a internet</strong>
            <p>Abra a oficina e aguarde “Pronto para converter sem internet”. Depois, tire o computador da rede e converta — inclusive com ferramentas que ainda não usou.<a href="#nota-offline" aria-label="Condições do uso sem internet"> *</a></p>
          </li>
          <li>
            <strong>Pergunte por que os outros não fazem</strong>
            <p>Porque o modelo deles é o servidor: o arquivo precisa subir para ser convertido lá. Aqui quem converte é o seu computador.</p>
          </li>
        </ol>
      </section>

      <section className="lugares" id="onde-converter">
        <div className="section-heading">
          <span>Onde a conversão acontece</span>
          <h2>
            Quatro formas.
            <br />
            Nenhuma manda seu
            <br />
            arquivo para longe.
          </h2>
        </div>

        <div className="lugares__grade">
          {lugares.map((lugar) => (
            <article key={lugar.nome} className={`lugar${lugar.destaque ? " lugar--destaque" : ""}`}>
              {lugar.destaque && <span className="lugar__fita">recomendado</span>}
              <h3>{lugar.nome}</h3>
              <p className="lugar__resumo">{lugar.resumo}</p>
              <p>{lugar.detalhe}</p>
              <a className={`button lugar__acao ${lugar.destaque ? "button--primary" : "button--ghost"}`} href={lugar.acao.destino}>
                {lugar.acao.texto}
              </a>
            </article>
          ))}
        </div>

        <PluginBadge />
      </section>

      <SiteFooter />
    </main>
  );
}
