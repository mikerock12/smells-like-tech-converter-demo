import { CATEGORIAS, contagem, ferramentasDaCategoria } from "@/packages/converter-core/catalogo.mjs";
import { ATALHOS_DA_HOME, contagemDePaginas } from "@/packages/converter-core/paginas.mjs";
import { MAQUINAS_POR_LICENCA, formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import { DEPOIMENTOS } from "@/packages/conteudo/depoimentos.mjs";

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
];

export default function Home() {
  const { total, noNavegador } = contagem();
  const { total: paginas } = contagemDePaginas();
  const mensal = produto("pro-mensal")!;
  const anual = produto("pro-anual")!;
  const app = produto("app-vitalicio")!;
  const completo = produto("completo-vitalicio")!;

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
              Abrir a oficina <span aria-hidden="true">→</span>
            </a>
            <a className="hero-link" href="/ferramentas">
              Ver as ferramentas <span aria-hidden="true">→</span>
            </a>
          </div>
        </div>
      </section>

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
            <dt>Pacote · {formatarReais(completo.centavos)}</dt>
            <dd>Aplicativo + Pro, para sempre.</dd>
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
            Três formas.
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
