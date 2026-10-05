/**
 * Medir o funil sem rastrear ninguém.
 *
 * A pergunta da lista priorizada é por página: a página de busca recebe gente? Essa gente
 * chega em /precos? Chega no Pix? Para responder, o Worker soma visitas por página, por
 * dia e por origem ("google", "limite:lote", "qr") — no momento em que entrega a página.
 *
 * O que NÃO existe aqui, de propósito: IP, cookie, identificador, user-agent guardado,
 * requisição extra do navegador. Quem abre a aba Network durante a conversão continua
 * vendo zero requisição. O preço disso é não medir "usou a ferramenta" para quem não
 * tem conta. Quem tem conta e deixou o registro ligado manda a contagem ao sair da
 * página, nunca durante a conversão (lib/conta/uso.ts).
 *
 * Módulo puro: o Worker usa, e o `node --test` confere.
 */

import { origemValida } from "../loja/index.mjs";

/** Páginas fixas que entram no funil. As de conversão e os artigos entram pelo formato. */
const FIXAS = new Set(["/", "/converter", "/ferramentas", "/precos", "/plugin", "/aplicativo", "/android", "/obrigado", "/chave", "/conta", "/instalar", "/indicar"]);

/** Robôs, pré-visualizações e ferramentas de linha de comando não são gente procurando conversor. */
const ROBO = /bot|crawl|spider|slurp|facebookexternalhit|whatsapp|telegram|embedly|preview|monitor|curl|wget|python|httpclient|headless|lighthouse|pingdom|uptime/i;

/**
 * O caminho, como entra na conta, ou nulo quando a página não interessa ao funil.
 * `artigos` é a lista de slugs dos artigos (para contar /sem-upload e companhia).
 */
export function caminhoContavel(url, artigos = []) {
  const caminho = url.pathname.length > 1 ? url.pathname.replace(/\/+$/, "") : "/";
  if (FIXAS.has(caminho)) return caminho;
  if (/^\/converter\/[a-z0-9-]{1,80}$/.test(caminho)) return caminho;
  if (artigos.includes(caminho.slice(1))) return caminho;
  return null;
}

/**
 * De onde veio a visita. O `?origem=` explícito (paywall, QR) vence; sem ele, o
 * domínio de quem mandou — só o domínio, nunca o endereço inteiro.
 */
export function origemDaVisita(url, referer) {
  const pedida = origemValida(url.searchParams.get("origem"));
  if (pedida) return pedida;
  if (!referer) return "";

  let host;
  try {
    host = new URL(referer).hostname.toLowerCase();
  } catch {
    return "";
  }
  if (host === url.hostname.toLowerCase()) return "";
  if (/(^|\.)google\.[a-z.]+$/.test(host)) return "google";
  if (/(^|\.)bing\.com$/.test(host)) return "bing";
  if (/(^|\.)duckduckgo\.com$/.test(host)) return "duckduckgo";
  if (/(^|\.)(facebook\.com|instagram\.com|t\.co|x\.com|linkedin\.com)$/.test(host)) return "social";
  if (/(^|\.)smellsliketech\.com\.br$/.test(host)) return "smellsliketech";
  return "externo";
}

/** Uma navegação de pessoa para uma página: GET de documento, sem pré-carregamento, sem robô. */
export function ehVisitaDePessoa(request) {
  if (request.method !== "GET") return false;
  const destino = request.headers.get("sec-fetch-dest");
  if (destino ? destino !== "document" : !(request.headers.get("accept") ?? "").includes("text/html")) return false;
  const proposito = `${request.headers.get("sec-purpose") ?? ""} ${request.headers.get("purpose") ?? ""}`;
  if (/prefetch|prerender/i.test(proposito)) return false;
  return !ROBO.test(request.headers.get("user-agent") ?? "");
}

/** "2026-09-25", no fuso de Brasília: o dia vira à meia-noite de quem vende. */
export function diaEmBrasilia(agora = new Date()) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit", day: "2-digit" }).format(agora);
}

/** Soma uma visita. A linha nasce com 1 e só incrementa: nada além do total é guardado. */
export const SQL_DA_VISITA =
  "INSERT INTO visitas (dia, caminho, origem, total) VALUES (?1, ?2, ?3, 1) ON CONFLICT (dia, caminho, origem) DO UPDATE SET total = total + 1";

/**
 * O que contar desta requisição: `{ dia, caminho, origem }` ou nulo. Só página que deu
 * certo (200) entra — 404 e redirecionamento não contam.
 */
export function visitaDaRequisicao(request, status, artigos = [], agora = new Date()) {
  if (status !== 200 || !ehVisitaDePessoa(request)) return null;
  const url = new URL(request.url);
  const caminho = caminhoContavel(url, artigos);
  if (!caminho) return null;
  return { dia: diaEmBrasilia(agora), caminho, origem: origemDaVisita(url, request.headers.get("referer")) };
}
