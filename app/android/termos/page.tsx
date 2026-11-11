import type { Metadata } from "next";

import { LINK_DA_GOOGLE_PLAY, PACOTE_DO_ANDROID } from "@/packages/converter-core/android.mjs";
import { DIAS_DE_TESTE_DO_APP } from "@/packages/converter-core/precos.mjs";

import { SiteFooter, SiteHeader } from "../../SiteChrome";

export const metadata: Metadata = {
  title: "Termos de Serviço do app Android — Smells Like Tech Converter",
  description: "Termos do Smells Like Tech Converter para Android: instalação, teste grátis, licenças, assinaturas, cancelamento, reembolso e suporte.",
  alternates: { canonical: "https://converter.smellsliketech.com.br/android/termos" },
};

export default function TermosDoAndroid() {
  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page legal">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Termos de Serviço · Android</p>
          <h1>Smells Like Tech Converter para Android</h1>
          <p className="plugin-page__lead">
            Estes termos se aplicam ao app Android Smells Like Tech Converter, pacote <code>{PACOTE_DO_ANDROID}</code>.
            Para o site, o plugin e o aplicativo Windows, veja os <a href="/termos">termos gerais do Converter</a>.
          </p>
          <p>Última atualização: <time dateTime="2026-11-11">11 de novembro de 2026</time>.</p>
        </header>

        <section className="plugin-page__secao">
          <h2 className="panel__title">1. Desenvolvedor e suporte</h2>
          <p>
            O aplicativo é oferecido pela Smells Like Tech Informática, CNPJ 30.054.253/0001-09, Porto Alegre/RS, Brasil.
            Para dúvidas sobre o app, licenças, compras ou reclamações, escreva para{" "}
            <a href="mailto:converter@smellsliketech.com.br">converter@smellsliketech.com.br</a>.
            Os demais dados de contato do desenvolvedor estão na <a href={LINK_DA_GOOGLE_PLAY}>ficha do app no Google Play</a>.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">2. Instalação, compatibilidade e teste</h2>
          <p>
            A distribuição pública oficial do app é pelo <a href={LINK_DA_GOOGLE_PLAY}>Google Play</a>. A loja instala e atualiza
            o aplicativo compatível com o aparelho. O site não oferece APK para instalação direta. O app requer Android 9 ou
            posterior e um aparelho compatível. Arquivos grandes e alguns recursos podem precisar de espaço livre, memória,
            microfone, ou de um modelo baixado separadamente.
          </p>
          <p>
            O teste do Converter dá acesso por {DIAS_DE_TESTE_DO_APP} dias e não inicia cobrança automaticamente. Ao terminar
            o teste, o app solicita uma licença para continuar usando os recursos pagos.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">3. Assinatura, licença e restauração</h2>
          <ul className="ressalvas">
            <li>
              <strong>Assinatura mensal pelo Google Play.</strong> Renova automaticamente até ser cancelada. O valor, a moeda,
              o período e as condições vigentes aparecem no Google Play antes da confirmação.
            </li>
            <li>
              <strong>Licença vitalícia para Android.</strong> Compra única, sem mensalidade ou vencimento periódico.
              Ela é separada da licença do Windows.
            </li>
            <li>
              <strong>Chave emitida pelo site.</strong> Se você já tem uma chave de um plano que inclui Android, pode ativá-la
              em “Licença” dentro do app. Valem o prazo e as condições apresentados na compra pelo site.
            </li>
          </ul>
          <p>
            Para recuperar uma compra pelo Google Play, use a mesma Conta do Google que fez a compra e escolha
            “Já comprei: restaurar a compra” na tela Licença. Chaves emitidas pelo site são restauradas com a própria chave.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">4. Cancelamento, reembolso e direitos do consumidor</h2>
          <p>
            Cancele a assinatura em <a href="https://play.google.com/store/account/subscriptions">Assinaturas do Google Play</a>,
            com a conta usada na compra. Desinstalar o app não cancela a assinatura. O cancelamento impede novas renovações;
            normalmente o acesso continua até o fim do período pago, salvo reembolso ou outra condição informada na loja.
          </p>
          <p>
            Para compras pela loja, você pode <a href="https://support.google.com/googleplay/answer/15574897?hl=pt-BR">solicitar
            reembolso pelo Google Play</a> ou falar com nosso suporte. Para compras no site, use o contato acima com o número
            do pedido. O procedimento da loja não limita seus direitos legais.
          </p>
          <p>
            Permanecem válidos os direitos obrigatórios de desistência, reembolso, garantia e reparação da legislação aplicável,
            inclusive no Brasil, no Espaço Econômico Europeu e no Reino Unido. Estes termos não exigem que o consumidor abra
            mão desses direitos ou recorra a um foro diferente do assegurado pela lei.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">5. Arquivos, conexão e permissões</h2>
          <p>
            O app faz conversão de mídia e documentos, edição de vídeo, criação musical, figurinhas, transcrição, OCR e
            narração. O processamento dos arquivos selecionados é feito no aparelho. O microfone é usado quando você escolhe
            gravar. Exporte os arquivos e mantenha cópias dos projetos importantes antes de apagar dados ou desinstalar o app.
          </p>
          <p>
            Conexão é necessária para compras e restauração pela loja, para baixar modelos sob demanda e para buscar e baixar
            conteúdo de catálogos online, como músicas e efeitos do Openverse. Esses fluxos não enviam suas gravações,
            arquivos de entrada ou projetos para conversão. Para detalhes, consulte a <a href="/privacidade">política de privacidade</a>.
          </p>
        </section>

        <section className="plugin-page__secao">
          <h2 className="panel__title">6. Uso de conteúdo e licenças de terceiros</h2>
          <p>
            Use apenas arquivos e materiais que você tem direito de utilizar. Para conteúdo de catálogos, consulte a origem
            e a licença exibidas para cada faixa ou item. O app não é endossado nem certificado pelo Openverse.
            As licenças dos componentes do aplicativo e os fontes disponíveis estão na página de <a href="/codigo-aberto">código aberto</a>.
          </p>
        </section>
      </main>
      <SiteFooter />
    </>
  );
}
