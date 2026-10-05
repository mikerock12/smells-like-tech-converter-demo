import type { Metadata } from "next";

import { COMPARATIVO, DIAS_DE_TESTE_DO_APP, MAQUINAS_POR_LICENCA, PLANOS, economiaDoAnual, formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import { ANDROID_NA_PLAY, LINK_DA_GOOGLE_PLAY } from "@/packages/converter-core/android.mjs";
import { contagem } from "@/packages/converter-core/catalogo.mjs";
import { origemValida } from "@/packages/loja/index.mjs";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import BotaoDeCompra from "./BotaoDeCompra";

export const metadata: Metadata = {
  title: "Preços — grátis de verdade, Pro por R$ 19,90 e aplicativo vitalício — Smells Like Tech Converter",
  description:
    "Plano grátis sem marca d'água e sem cadastro. Pro por R$ 19,90/mês ou R$ 149/ano. Aplicativo para Windows com licença vitalícia por R$ 249. Pix, cartão e boleto pelo Mercado Pago.",
};

const perguntas = [
  {
    pergunta: "O grátis tem marca d'água ou limite de qualidade?",
    resposta:
      "Nunca. O grátis converte com a mesma qualidade do Pro. Os limites são de volume: 5 arquivos por vez, vídeo até 10 minutos no navegador, PDF até 50 páginas e 5 conversões pesadas por dia pelo plugin. E dá para desligar a internet e continuar convertendo.",
  },
  {
    pergunta: "Por que não tem assinatura automática?",
    resposta:
      "Porque cancelar assinatura é a reclamação número um dos conversores por aí. Aqui cada pagamento libera um período: 31 dias ou 366 dias. Acabou, você decide se paga de novo. Não existe o que cancelar, e ninguém cobra sem você clicar.",
  },
  {
    pergunta: "Preciso criar conta?",
    resposta:
      "Não. Depois do pagamento você recebe uma chave. Cola no site (ou no aplicativo) e pronto. A chave se prova sozinha, com assinatura digital: não consulta servidor nenhum, funciona sem internet e não sabe nada sobre os seus arquivos.",
  },
  {
    pergunta: "Como pago?",
    resposta:
      "Pelo Mercado Pago: Pix, cartão de crédito, cartão de débito ou boleto. O pagamento acontece no site deles; nós nunca vemos o número do seu cartão. Pix libera a chave em segundos.",
  },
  {
    pergunta: "O que é 'vitalício' exatamente?",
    resposta:
      `Pagou uma vez, é seu. A licença do aplicativo vale em até ${MAQUINAS_POR_LICENCA} computadores seus, não vence, e inclui todas as atualizações. O pacote “Tudo, para sempre” estende o vitalício também ao Pro do site e do plugin e ao app para Android.`,
  },
  {
    pergunta: "E se eu perder a chave?",
    resposta:
      "Ela fica na página de confirmação, vai por e-mail e pode ser recuperada a qualquer momento com o e-mail da compra e o número do pedido, em /chave.",
  },
  {
    pergunta: "Tem garantia?",
    resposta:
      "Sete dias. Se não servir, mande um e-mail com o número do pedido e devolvemos. O aplicativo ainda tem 7 dias de teste completo antes de pedir licença, então dá para conferir tudo antes de pagar.",
  },
];

export default async function PaginaDePrecos({ searchParams }: { searchParams: Promise<{ origem?: string }> }) {
  // Quem chega pelo aviso de limite traz "?origem=limite:lote"; a compra herda a origem.
  const origem = origemValida((await searchParams).origem) ?? "precos";
  const mensal = produto("pro-mensal")!;
  const anual = produto("pro-anual")!;
  const app = produto("app-vitalicio")!;
  const completo = produto("completo-vitalicio")!;
  const android = produto("android-vitalicio")!;
  // O app para Android é vendido dentro dele, pela Google Play: aqui só é apresentado, e
  // só depois de estar publicado na loja.
  const { total, noNavegador } = contagem();

  return (
    <>
      <SiteHeader page="precos" />

      <main className="precos">
        <header className="precos__topo">
          <p className="converter__eyebrow">Preços</p>
          <h1>
            O grátis é grátis de verdade.
            <br />
            <em>O pago é para quem vive disso.</em>
          </h1>
          <p className="precos__lead">
            Como a conversão acontece no seu computador, não pagamos servidor por arquivo — e não repassamos um custo
            que não existe. Cobramos pelo que economiza o tempo de quem trabalha com mídia todo dia: lote sem limite,
            transcrição, narração e o programa completo para Windows.
          </p>
          <ul className="precos__selos" aria-label="Formas de pagamento">
            <li>Pix</li>
            <li>Cartão</li>
            <li>Boleto</li>
            <li>Sem assinatura automática</li>
          </ul>
          <p className="precos__processado">
            <span>Pagamentos processados pelo</span>
            {/* SVG oficial do Mercado Pago, sem edição: o next/image não acrescenta nada a um vetor. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/marca/mercado-pago-branco.svg" alt="Mercado Pago" width={109} height={44} />
          </p>
        </header>

        <section className="planos" aria-label="Planos">
          <article className="plano plano--gratis">
            <p className="plano__nome">{PLANOS.gratis.nome}</p>
            <p className="plano__preco">
              <strong>R$ 0</strong>
              <span>para sempre</span>
            </p>
            <p className="plano__chamada">{PLANOS.gratis.chamada}</p>
            <ul className="plano__lista">
              <li>{noNavegador} ferramentas no navegador, sem instalar nada</li>
              <li>Sem marca d&apos;água, sem cadastro, sem anúncio</li>
              <li>5 arquivos por vez · vídeo até 10 min · PDF até 50 páginas</li>
              <li>Plugin gratuito: 5 conversões pesadas por dia</li>
              <li>Transcrição, narração e OCR para experimentar</li>
            </ul>
            <a className="button button--ghost plano__acao" href="/converter">
              Usar agora, sem conta
            </a>
          </article>

          <article className="plano plano--pro">
            <span className="plano__fita">mais escolhido</span>
            <p className="plano__nome">{PLANOS.pro.nome}</p>
            <p className="plano__preco">
              <strong>{formatarReais(mensal.centavos)}</strong>
              <span>por mês · ou {formatarReais(anual.centavos)} por ano ({economiaDoAnual()}% menos)</span>
            </p>
            <p className="plano__chamada">{PLANOS.pro.chamada}</p>
            <ul className="plano__lista">
              <li>Tudo do grátis, sem nenhum limite de volume</li>
              <li>Transcrição e legenda em português, sem contador</li>
              <li>Narração, OCR em lote e PDF → Word</li>
              <li>Presets salvos e fila prioritária</li>
              <li>Vale no site e no plugin, neste e em qualquer computador</li>
            </ul>
            <div className="plano__acoes">
              <BotaoDeCompra produto={mensal.id} rotulo={`Pro mensal · ${formatarReais(mensal.centavos)}`} destaque origem={origem} />
              <BotaoDeCompra produto={anual.id} rotulo={`Pro anual · ${formatarReais(anual.centavos)}`} origem={origem} />
            </div>
            <p className="plano__nota">{mensal.nota}</p>
          </article>

          <article className="plano plano--app">
            <p className="plano__nome">{PLANOS.app.nome}</p>
            <p className="plano__preco">
              <strong>{formatarReais(app.centavos)}</strong>
              <span>pagamento único · licença vitalícia</span>
            </p>
            <p className="plano__chamada">{PLANOS.app.chamada}</p>
            <ul className="plano__lista">
              <li>Todas as {total} ferramentas, com janela, sem depender do site</li>
              <li>Vídeo de qualquer tamanho com a sua placa de vídeo</li>
              <li>Fila com histórico, lote a partir de pastas, presets</li>
              <li>Funciona sem internet, para sempre</li>
              <li>Até {MAQUINAS_POR_LICENCA} computadores · {DIAS_DE_TESTE_DO_APP} dias de teste antes de pagar</li>
            </ul>
            <div className="plano__acoes">
              <BotaoDeCompra produto={app.id} rotulo={`Comprar a licença · ${formatarReais(app.centavos)}`} destaque origem={origem} />
              <a className="button button--ghost" href="/aplicativo">
                Baixar e testar por {DIAS_DE_TESTE_DO_APP} dias
              </a>
            </div>
            <p className="plano__nota">{app.nota}</p>
          </article>
        </section>

        {ANDROID_NA_PLAY && (
          <section className="combo combo--celular" aria-labelledby="android-titulo">
            <div>
              <p className="converter__eyebrow">{PLANOS.android.nome}</p>
              <h2 id="android-titulo">
                {PLANOS.android.chamada}. <em>{formatarReais(android.centavos)}, uma vez.</em>
              </h2>
              <p>
                {PLANOS.android.descricao} {DIAS_DE_TESTE_DO_APP} dias de teste completo; depois, uma compra única dentro do app,
                pela Google Play.
              </p>
            </div>
            <div className="combo__acao">
              <a className="button button--primary" href={LINK_DA_GOOGLE_PLAY} rel="noopener">
                Instalar pela Google Play
              </a>
              <a className="button button--ghost" href="/android">
                Ver o que o app faz
              </a>
            </div>
          </section>
        )}

        <section className="combo" aria-labelledby="combo-titulo">
          <div>
            <p className="converter__eyebrow">{PLANOS.completo.nome}</p>
            <h2 id="combo-titulo">
              {PLANOS.completo.chamada}. <em>{formatarReais(completo.centavos)}, uma vez.</em>
            </h2>
            <p>
              {PLANOS.completo.descricao} Sai mais barato que o aplicativo mais um ano de Pro — e nunca mais aparece uma
              cobrança.
            </p>
          </div>
          <div className="combo__acao">
            <BotaoDeCompra produto={completo.id} rotulo={`Levar tudo · ${formatarReais(completo.centavos)}`} destaque origem={origem} />
            <small>{completo.nota}</small>
          </div>
        </section>

        <section className="comparativo" aria-labelledby="comparativo-titulo">
          <div className="section-heading section-heading--compacta">
            <div>
              <span>Lado a lado</span>
              <h2 id="comparativo-titulo">O que muda do grátis para o Pro</h2>
            </div>
          </div>
          <table>
            <thead>
              <tr>
                <th scope="col">&nbsp;</th>
                <th scope="col">Grátis</th>
                <th scope="col">Pro</th>
              </tr>
            </thead>
            <tbody>
              {COMPARATIVO.map((linha) => (
                <tr key={linha.item}>
                  <th scope="row">{linha.item}</th>
                  <td>{linha.gratis}</td>
                  <td>{linha.pro}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="perguntas" aria-labelledby="perguntas-titulo">
          <div className="section-heading section-heading--compacta">
            <div>
              <span>Sem letra miúda</span>
              <h2 id="perguntas-titulo">Perguntas que a gente faria</h2>
            </div>
          </div>
          <dl>
            {perguntas.map((item) => (
              <div key={item.pergunta}>
                <dt>{item.pergunta}</dt>
                <dd>{item.resposta}</dd>
              </div>
            ))}
          </dl>
        </section>
      </main>

      <SiteFooter />
    </>
  );
}
