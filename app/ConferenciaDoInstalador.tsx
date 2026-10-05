import type { ParaBaixar } from "@/lib/baixar";

import CopiarTexto from "./CopiarTexto";

/**
 * O que vem junto do botão de baixar: o aviso do Windows com o caminho exato e o
 * SHA-256 à vista. O instalador ainda não é assinado; em vez de esconder isso, a página
 * diz o que vai aparecer e dá o número para conferir que o arquivo é o nosso.
 */
export default function ConferenciaDoInstalador({ download }: { download: ParaBaixar }) {
  return (
    <div className="conferencia">
      <div className="conferencia__aviso">
        <strong>O Windows vai avisar. É esperado.</strong>
        <p>
          O instalador ainda não tem assinatura digital, então aparece a tela azul “O Windows protegeu o computador”. Clique em{" "}
          <kbd>Mais informações</kbd> <span aria-hidden="true">→</span> <kbd>Executar assim mesmo</kbd>.
        </p>
      </div>
      <div className="conferencia__hash">
        <span className="conferencia__rotulo">SHA-256 de {download.arquivo}</span>
        <code>{download.sha256}</code>
        <CopiarTexto texto={download.sha256} rotulo="Copiar o SHA-256" />
      </div>
      <p className="conferencia__como">
        Para conferir antes de executar, rode no PowerShell, na pasta Downloads:{" "}
        <code>Get-FileHash .\{download.arquivo} -Algorithm SHA256</code>. Número igual quer dizer arquivo idêntico ao que publicamos.
      </p>
    </div>
  );
}
