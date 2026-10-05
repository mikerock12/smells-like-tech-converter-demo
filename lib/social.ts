/**
 * A imagem de compartilhamento (WhatsApp, redes). O layout já a coloca na home; as
 * páginas que definem o próprio título de compartilhamento precisam repetir a imagem,
 * porque o Next troca o bloco `openGraph` inteiro em vez de mesclar.
 */
const ORIGEM = (process.env.NEXT_PUBLIC_SITE_URL ?? "https://converter.smellsliketech.com.br").replace(/\/$/, "");

export const IMAGEM_SOCIAL = {
  url: `${ORIGEM}/og.png`,
  width: 1200,
  height: 630,
  alt: "Converta seus arquivos sem entregá-los a ninguém.",
} as const;
