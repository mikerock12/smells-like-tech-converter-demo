import type { Metadata } from "next";

import { COMPARATIVO, DIAS_DE_TESTE_DO_APP, MAQUINAS_POR_LICENCA, PLANOS, economiaDoAnual, formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import { ANDROID_NA_PLAY } from "@/packages/converter-core/android.mjs";
import { contagem } from "@/packages/converter-core/catalogo.mjs";
import { origemValida } from "@/packages/loja/index.mjs";

import BotaoGooglePlay from "../BotaoGooglePlay";
import { SiteFooter, SiteHeader } from "../SiteChrome";
import BotaoDeCompra from "./BotaoDeCompra";

export const metadata: Metadata = {
  title: "Preços — grátis de verdade, Pro, aplicativos para Windows e Android — Smells Like Tech Converter",
  description:
    "Plano grátis sem marca d'água e sem cadastro. Pro por R$ 19,90/mês ou R$ 149/ano. Aplicativo para Windows e app para Android com 7 dias grátis, mensal ou vitalício. Ou tudo junto, para sempre, por R$ 349.",
};

const perguntas = [
  {
    pergunta: "O grátis tem marca d'água ou limite de qualidade?",
    resposta:
      "Nunca. O grátis converte com a mesma qualidade do Pro. Os limites são de volume: 5 arquivos por vez, vídeo até 10 minutos no navegador, PDF até 50 páginas e 5 conversões pesadas por dia pelo plugin. No estúdio, 3 exportações por dia, projetos de até 1 minuto e remoção de fundo em fotos, stickers e GIFs. E dá para desligar a internet e continuar convertendo.",
  },
  {
    pergunta: "Por que não tem assinatura automática?",
    resposta:
      "Porque cancelar assinatura é a reclamação número um dos conversores por aí. Aqui cada pagamento libera um período: 31 dias ou 366 dias, no Pro e também no aplicativo para Windows. Acabou, você decide se paga de novo. Não existe o que cancelar, e ninguém cobra sem você clicar. A única exceção é a assinatura mensal do app para Android, que a Google Play renova e que você cancela na própria Play, a qualquer momento.",
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
  const appMensal = produto("app-mensal")!;
  const completo = produto("completo-vitalicio")!;
  const android = produto("android-vitalicio")!;
  const androidMensal = produto("android-mensal")!;
  // O app para Android é vendido dentro dele, pela Google Play: aqui é apresentado, com o
  // caminho da compra, e o "Tudo, para sempre" (vendido aqui) também o libera.
  const { total, noNavegador } = contagem();
  // Quanto o pacote completo economiza em relação a comprar cada parte para sempre (o Pro, que
  // não tem vitalício avulso, entra com um ano).
  const separado = app.centavos + android.centavos + anual.centavos;
  const economia = separado - completo.centavos;
  const opcoes: { nome: string; site: string; windows: string; celular: string; preco: string; destaque?: boolean }[] = [
    { nome: PLANOS.completo.nome, site: "Pro vitalício", windows: "Vitalício", celular: "Vitalício", preco: `${formatarReais(completo.centavos)}, uma vez`, destaque: true },
    { nome: "Só o celular", site: "Grátis", windows: "—", celular: "Vitalício ou mensal", preco: `${formatarReais(android.centavos)} uma vez, ou ${formatarReais(androidMensal.centavos)}/mês` },
    { nome: "Só o Windows", site: "Grátis", windows: "Vitalício ou mensal", celular: "—", preco: `${formatarReais(app.centavos)} uma vez, ou ${formatarReais(appMensal.centavos)} por 31 dias` },
    { nome: "Só o site e o plugin", site: "Pro", windows: "—", celular: "—", preco: `${formatarReais(mensal.centavos)}/mês ou ${formatarReais(anual.centavos)}/ano` },
    { nome: PLANOS.gratis.nome, site: "Com limites de volume", windows: `${DIAS_DE_TESTE_DO_APP} dias de teste`, celular: `${DIAS_DE_TESTE_DO_APP} dias de teste`, preco: "R$ 0" },
  ];

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
            estúdio sem contador, transcrição, narração e o programa completo para Windows.
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
              <li>Estúdio: 3 exportações por dia, projetos até 1 min</li>
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
              <li>Estúdio sem limite: exportações, duração e fundo removido também em vídeo</li>
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
              <span>pagamento único · ou {formatarReais(appMensal.centavos)} por mês</span>
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
              <BotaoDeCompra produto={app.id} rotulo={`Licença vitalícia · ${formatarReais(app.centavos)}`} destaque origem={origem} />
              <BotaoDeCompra produto={appMensal.id} rotulo={`Mensal · ${formatarReais(appMensal.centavos)} por 31 dias`} origem={origem} />
              <a className="button button--ghost" href="/aplicativo">
                Baixar e testar por {DIAS_DE_TESTE_DO_APP} dias
              </a>
            </div>
            <p className="plano__nota">{app.nota} Mensal: {appMensal.nota}</p>
          </article>
        </section>

        {ANDROID_NA_PLAY && (
          <section className="android-planos" id="android" aria-labelledby="android-titulo">
            <div className="android-planos__topo">
              <p className="converter__eyebrow">{PLANOS.android.nome} · Google Play</p>
              <h2 id="android-titulo">
                O conversor inteiro no celular. <em>{DIAS_DE_TESTE_DO_APP} dias grátis para testar.</em>
              </h2>
              <p>
                Converter, estúdio de vídeo, criação musical, figurinhas do WhatsApp, transcrição, narração e OCR, no próprio
                celular e sem internet. O plugin é só para Windows: no celular, é este app que faz tudo.
              </p>
            </div>
            <div className="android-planos__opcoes">
              <article className="plano">
                <p className="plano__nome">Assinatura mensal</p>
                <p className="plano__preco">
                  <strong>{formatarReais(androidMensal.centavos)}</strong>
                  <span>por mês, pela Google Play</span>
                </p>
                <p className="plano__chamada">Para começar pagando pouco.</p>
                <ul className="plano__lista">
                  <li>Renova sozinha todo mês, pela Google Play</li>
                  <li>Cancele quando quiser, na própria Play</li>
                  <li>Troque pela vitalícia a qualquer momento</li>
                </ul>
              </article>
              <article className="plano plano--pro">
                <span className="plano__fita">sem mensalidade</span>
                <p className="plano__nome">Licença vitalícia</p>
                <p className="plano__preco">
                  <strong>{formatarReais(android.centavos)}</strong>
                  <span>uma vez, pela Google Play</span>
                </p>
                <p className="plano__chamada">Para quem vai usar sempre.</p>
                <ul className="plano__lista">
                  <li>Compra única, que não vence</li>
                  <li>Todas as atualizações incluídas</li>
                  <li>Vale nos celulares com a mesma conta Google</li>
                </ul>
              </article>
            </div>
            <div className="android-planos__como">
              <h3>Como assinar ou comprar</h3>
              <ol>
                <li><strong>Instale pela Google Play.</strong> O app sai só pela loja oficial, e as atualizações chegam sozinhas.</li>
                <li><strong>Use tudo por {DIAS_DE_TESTE_DO_APP} dias.</strong> Sem cartão, sem cadastro, sem marca d&apos;água.</li>
                <li>
                  <strong>No app, abra Licença</strong> e toque em “Assinar por mês” ou “Licença vitalícia, uma vez”. O pagamento é
                  pela Google Play, com a forma de pagamento da sua conta Google.
                </li>
                <li>
                  <strong>Já tem o “{PLANOS.completo.nome}”?</strong> Em Licença, toque em “Ativar uma chave” e cole a sua chave:
                  não paga de novo. Trocou de celular? “Já comprei: restaurar a compra”.
                </li>
              </ol>
              <div className="android-planos__acoes">
                <BotaoGooglePlay />
                <a className="button button--ghost" href="/android">
                  Ver o que o app faz
                </a>
              </div>
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
              {PLANOS.completo.descricao} Comprando cada parte para sempre (Windows {formatarReais(app.centavos)}, Android{" "}
              {formatarReais(android.centavos)} e um ano de Pro {formatarReais(anual.centavos)}), seriam{" "}
              {formatarReais(separado)}: o pacote economiza <strong>{formatarReais(economia)}</strong>, e nunca mais aparece uma
              cobrança. No celular, a chave é ativada no app, em Licença → “Ativar uma chave”.
            </p>
          </div>
          <div className="combo__acao">
            <BotaoDeCompra produto={completo.id} rotulo={`Levar tudo · ${formatarReais(completo.centavos)}`} destaque origem={origem} />
            <small>{completo.nota}</small>
          </div>
        </section>

        <section className="comparativo comparativo--opcoes" aria-labelledby="opcoes-titulo">
          <div className="section-heading section-heading--compacta">
            <div>
              <span>Monte do seu jeito</span>
              <h2 id="opcoes-titulo">Qual combinação escolher</h2>
            </div>
          </div>
          <div className="comparativo__rolagem">
            <table>
              <thead>
                <tr>
                  <th scope="col">Opção</th>
                  <th scope="col">Site e plugin</th>
                  <th scope="col">Windows</th>
                  <th scope="col">Android</th>
                  <th scope="col">Preço</th>
                </tr>
              </thead>
              <tbody>
                {opcoes.map((opcao) => (
                  <tr key={opcao.nome} className={opcao.destaque ? "comparativo__destaque" : undefined}>
                    <th scope="row">{opcao.nome}</th>
                    <td data-rotulo="Site e plugin">{opcao.site}</td>
                    <td data-rotulo="Windows">{opcao.windows}</td>
                    <td data-rotulo="Android">{opcao.celular}</td>
                    <td data-rotulo="Preço" className="comparativo__preco">{opcao.preco}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="estudio__dica">
            Dá para combinar: quem só usa o celular assina o Android; quem trabalha no computador fica com o Windows ou com o Pro;
            quem usa tudo economiza {formatarReais(economia)} com o pacote completo. O site é sempre grátis, com limites de volume.
          </p>
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
