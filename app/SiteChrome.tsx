import { ARTIGOS } from "@/packages/conteudo/artigos.mjs";

/**
 * Cabeçalho e rodapé compartilhados.
 *
 * Todas as páginas são o mesmo site: quem chega por qualquer porta precisa continuar
 * vendo a marca e ter caminho para as outras. O menu aponta para páginas, não para
 * âncoras, porque agora existem páginas de verdade para cada assunto.
 */

type Page = "home" | "converter" | "ferramentas" | "precos" | "plugin" | "aplicativo" | "chave" | "conta" | "outra";

const links: { href: string; rotulo: string; pagina: Page }[] = [
  { href: "/ferramentas", rotulo: "Ferramentas", pagina: "ferramentas" },
  { href: "/precos", rotulo: "Preços", pagina: "precos" },
  { href: "/plugin", rotulo: "Plugin", pagina: "plugin" },
  { href: "/aplicativo", rotulo: "Aplicativo", pagina: "aplicativo" },
  { href: "/conta", rotulo: "Minha conta", pagina: "conta" },
];

export function SiteHeader({ page }: { page: Page }) {
  return (
    <header className="site-header">
      {/* O lint pede <Link>. Não dá: este é um componente de servidor, e o Link usa hooks. */}
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
      <a href="/" className="brand" aria-label="Smells Like Tech Converter — início">
        <img className="brand-mark" src="/marca/icone-256.webp" alt="" width={46} height={46} />
        <span className="brand-copy">
          <strong>Smells Like Tech</strong>
          <small>Converter</small>
        </span>
      </a>
      <nav aria-label="Navegação principal">
        {links.map((link) => (
          <a key={link.href} href={link.href} aria-current={page === link.pagina ? "page" : undefined}>
            {link.rotulo}
          </a>
        ))}
        <a href="/#privacidade">Privacidade</a>
      </nav>
      {page === "converter" ? (
        // eslint-disable-next-line @next/next/no-html-link-for-pages
        <a className="header-cta header-cta--quiet" href="/">
          <span aria-hidden="true">←</span> Início
        </a>
      ) : (
        <a className="header-cta" href="/converter">
          Abrir a oficina
        </a>
      )}
    </header>
  );
}

/** Chamada para a página do aplicativo, usada onde o programa completo faz falta. */
export function LinkDoAplicativo({ texto = "Ver o aplicativo" }: { texto?: string }) {
  return (
    <a className="button button--primary" href="/aplicativo">
      {texto}
    </a>
  );
}

/** Chamada para a página do plugin, usada onde ele faz falta. */
export function LinkDoPlugin({ texto = "Baixar o plugin" }: { texto?: string }) {
  return (
    <a className="button button--primary" href="/plugin">
      {texto}
    </a>
  );
}

export function SiteFooter() {
  return (
    <footer>
      <div className="footer-brand">
        <div className="brand">
          <img className="brand-mark" src="/marca/icone-256.webp" alt="" width={46} height={46} loading="lazy" />
          <span className="brand-copy">
            <strong>Smells Like Tech</strong>
            <small>Converter</small>
          </span>
        </div>
        <p>Conversões úteis, simples e privadas.</p>
        <nav className="footer-links" aria-label="Rodapé">
          <a href="/ferramentas">Ferramentas</a>
          <a href="/precos">Preços</a>
          <a href="/conta">Minha conta</a>
          <a href="/chave">Ativar uma chave</a>
          <a href="/instalar">Instalar no celular</a>
          <a href="/android">App para Android</a>
          <a href="/privacidade">Privacidade</a>
          <a href="/termos">Termos</a>
          <a href="/codigo-aberto">Código aberto</a>
        </nav>
        <nav className="footer-links footer-links--guias" aria-label="Guias">
          {ARTIGOS.map((item) => (
            <a key={item.slug} href={`/${item.slug}`}>
              {item.titulo}
            </a>
          ))}
        </nav>
      </div>
      <div className="footer-credits">
        <p>
          Criado por <strong>Maicon Nunes</strong>
        </p>
        <p>Smells Like Tech Informática · CNPJ 30.054.253/0001-09</p>
        <p>
          <a href="https://www.smellsliketech.com.br" target="_blank" rel="noreferrer noopener">
            www.smellsliketech.com.br
          </a>
        </p>
      </div>
      <div className="footer-offline" id="nota-offline">
        <p><strong>* Uso sem internet:</strong> aguarde o aviso “Pronto para converter sem internet” na oficina. O site guarda os motores de imagem, áudio, vídeo, PDF, legendas e OCR (português, inglês e espanhol), inclusive os que você ainda não usou. A primeira preparação e as atualizações precisam de conexão para baixar esses recursos. Se o navegador não permitir o armazenamento, limpar ou remover o cache, será preciso prepará-lo novamente. Os formatos disponíveis continuam dependendo dos codecs do navegador.</p>
        <p>Narração Kokoro-82M precisa preparar o modelo neste navegador uma vez com internet; confira o tamanho no botão de preparo. Depois funciona offline. Limpar os dados do site ou perder o cache exige preparar novamente. Windows e Android já incluem as vozes. Transcrição e PDF → Word no site usam o plugin Windows com os modelos previamente instalados; depois processam localmente. Baixar o plugin, o aplicativo ou modelos novos exige internet. Entrar na conta, receber códigos por e-mail, recuperar compras/chaves, pagar e consultar/sincronizar o histórico online precisam acessar os respectivos servidores. Uma chave já salva ou colada é verificada no aparelho, sem internet. Seus arquivos nunca são enviados para conversão.</p>
      </div>
      <small>© 2026 Smells Like Tech</small>
    </footer>
  );
}
