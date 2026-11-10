import { LINK_DA_GOOGLE_PLAY } from "@/packages/converter-core/android.mjs";

/**
 * Botão para a página do app na Google Play: o logotipo da Play nas cores originais (sem
 * alteração, como pedem as diretrizes de marca do Google) num botão escuro com a borda e o
 * brilho laranja do site. Abre a loja em outra aba.
 */
export default function BotaoGooglePlay({ className = "" }: { className?: string }) {
  return (
    <a className={`botao-play ${className}`.trim()} href={LINK_DA_GOOGLE_PLAY} target="_blank" rel="noopener noreferrer" aria-label="Disponível no Google Play: abrir a página do app">
      <svg className="botao-play__logo" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path fill="#00d7fe" d="M3.61 1.81C3.22 2.22 3 2.85 3 3.66v16.68c0 .81.22 1.44.61 1.85l.1.09 9.35-9.35v-.22L3.71 1.71z" />
        <path fill="#ffce00" d="M16.18 15.98l-3.12-3.12v-.22l3.12-3.12.07.04 3.69 2.1c1.06.6 1.06 1.58 0 2.18l-3.69 2.1z" />
        <path fill="#ff3a44" d="M16.25 15.94l-3.19-3.19-9.45 9.45c.35.37.93.42 1.58.05l11.06-6.31" />
        <path fill="#00f076" d="M16.25 9.56L5.19 3.25c-.65-.37-1.23-.32-1.58.05l9.45 9.45z" />
      </svg>
      <span className="botao-play__texto">
        <small>Disponível no</small>
        <strong>Google Play</strong>
      </span>
    </a>
  );
}
