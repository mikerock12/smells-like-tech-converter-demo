/**
 * Páginas de busca.
 *
 * Cada página existe para uma busca de quem já quer converter ("converter webp para
 * png"). O H1 e o título repetem a busca, a oficina abre na ferramenta certa logo
 * abaixo, e o topo diz em uma linha que o arquivo não sai do computador — com o teste
 * para conferir. A ordem de publicação e o texto de cada H1 vêm da lista priorizada
 * (docs/lista-priorizada.md): A é o que mais se busca e mais encaixa no produto.
 *
 * Módulo puro: roda no `node --test`, e o site só lê.
 */

import { FERRAMENTAS, PARES, resolverSlug, rotulo, todosOsSlugs } from "./catalogo.mjs";
import { LIMITES, formatarReais, produto } from "./precos.mjs";

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}

/** A linha que abre toda página de busca, logo abaixo do H1. */
export const PRIVACIDADE_NO_TOPO =
  "O arquivo fica no seu computador. Abra o DevTools → Network durante a conversão: zero upload.";

/**
 * Nas ferramentas do plugin, o navegador entrega o arquivo ao plugin por 127.0.0.1 — um
 * endereço que só existe dentro do computador. Quem abrir a aba Network vai ver essa
 * requisição, então a frase diz o que ela é em vez de prometer uma lista vazia.
 */
export const PRIVACIDADE_NO_TOPO_DO_PLUGIN =
  "O arquivo fica no seu computador. Abra o DevTools → Network durante a conversão: a única requisição vai para 127.0.0.1, o plugin na sua própria máquina. Zero upload.";

export function privacidadeNoTopo(ferramenta) {
  return ferramenta.navegador ? PRIVACIDADE_NO_TOPO : PRIVACIDADE_NO_TOPO_DO_PLUGIN;
}

/** O fim de toda meta description: o que diferencia, em ordem de peso. */
const FECHO_DA_META = "no seu computador. Sem upload, sem cadastro, sem marca d'água.";

/**
 * A lista priorizada, na ordem de publicação. `frase` é o começo da meta description
 * quando a página não é um par de formato ("Junte PDFs no seu computador…").
 */
export const PAGINAS_PRIORIZADAS = deepFreeze([
  // ==================== A — maior busca, encaixa no produto ====================
  { slug: "webp-para-png", prioridade: "A", h1: "Converter WEBP para PNG sem enviar arquivo" },
  { slug: "webp-para-jpg", prioridade: "A", h1: "Converter WEBP para JPG sem enviar arquivo" },
  { slug: "heic-para-jpg", prioridade: "A", h1: "Converter HEIC para JPG sem enviar arquivo" },
  { slug: "png-para-jpg", prioridade: "A", h1: "Converter PNG para JPG sem enviar arquivo" },
  { slug: "jpg-para-png", prioridade: "A", h1: "Converter JPG para PNG sem enviar arquivo" },
  { slug: "png-para-webp", prioridade: "A", h1: "Converter PNG para WEBP sem enviar arquivo" },
  { slug: "jpg-para-webp", prioridade: "A", h1: "Converter JPG para WEBP sem enviar arquivo" },
  { slug: "mp4-para-mp3", prioridade: "A", h1: "Converter MP4 para MP3 sem enviar arquivo" },
  { slug: "mp4-para-gif", prioridade: "A", h1: "Converter MP4 para GIF sem enviar arquivo" },
  { slug: "mp4-para-webm", prioridade: "A", h1: "Converter MP4 para WEBM sem enviar arquivo" },
  { slug: "mov-para-mp4", prioridade: "A", h1: "Converter MOV para MP4 sem enviar arquivo" },
  { slug: "mkv-para-mp4", prioridade: "A", h1: "Converter MKV para MP4 sem enviar arquivo" },
  { slug: "pdf-para-jpg", prioridade: "A", h1: "Converter PDF para JPG sem enviar arquivo" },
  { slug: "pdf-para-png", prioridade: "A", h1: "Converter PDF para PNG sem enviar arquivo" },
  { slug: "imagens-para-pdf", prioridade: "A", h1: "Converter imagens para PDF sem enviar arquivo", frase: "Transforme imagens em PDF" },
  { slug: "juntar-pdf", prioridade: "A", h1: "Juntar PDF sem enviar arquivo", frase: "Junte PDFs" },
  { slug: "dividir-pdf", prioridade: "A", h1: "Dividir PDF sem enviar arquivo", frase: "Divida PDFs" },
  { slug: "comprimir-pdf", prioridade: "A", h1: "Comprimir PDF sem enviar arquivo", frase: "Comprima PDFs" },
  { slug: "comprimir-imagem", prioridade: "A", h1: "Comprimir imagem sem enviar arquivo", frase: "Comprima imagens" },
  { slug: "comprimir-video", prioridade: "A", h1: "Comprimir vídeo sem enviar arquivo", frase: "Comprima vídeos" },

  // ==================== B — volume e ticket mais alto ====================
  { slug: "pdf-para-word", prioridade: "B", h1: "Converter PDF para Word sem enviar arquivo", frase: "Converta PDF para Word" },
  { slug: "pdf-para-texto", prioridade: "B", h1: "Converter PDF para texto sem enviar arquivo", frase: "Extraia o texto do PDF" },
  { slug: "audio-para-texto", prioridade: "B", h1: "Transcrever áudio para texto sem enviar arquivo", frase: "Transcreva áudio para texto" },
  { slug: "video-para-texto", prioridade: "B", h1: "Transcrever vídeo para texto e legenda sem enviar arquivo", frase: "Transcreva vídeo para texto e legenda" },
  { slug: "audio-para-srt", prioridade: "B", h1: "Gerar legenda SRT sem enviar o áudio", frase: "Gere a legenda SRT do seu áudio" },
  { slug: "foto-para-texto", prioridade: "B", h1: "Extrair texto de foto (OCR) sem enviar arquivo", frase: "Extraia o texto de fotos e prints" },
  { slug: "wav-para-mp3", prioridade: "B", h1: "Converter WAV para MP3 sem enviar arquivo" },
  { slug: "m4a-para-mp3", prioridade: "B", h1: "Converter M4A para MP3 sem enviar arquivo" },
  { slug: "video-para-audio", prioridade: "B", h1: "Extrair áudio de vídeo sem enviar arquivo", frase: "Extraia o áudio de vídeos" },
  { slug: "cortar-video", prioridade: "B", h1: "Cortar vídeo sem enviar arquivo", frase: "Corte vídeos" },
  { slug: "cortar-audio", prioridade: "B", h1: "Cortar áudio sem enviar arquivo", frase: "Corte áudios" },
  { slug: "girar-pdf", prioridade: "B", h1: "Girar páginas de PDF sem enviar arquivo", frase: "Gire páginas de PDF" },
  { slug: "avif-para-jpg", prioridade: "B", h1: "Converter AVIF para JPG sem enviar arquivo" },
  { slug: "avif-para-png", prioridade: "B", h1: "Converter AVIF para PNG sem enviar arquivo" },
  { slug: "gif-para-mp4", prioridade: "B", h1: "Converter GIF para MP4 sem enviar arquivo" },

  // ==================== C — completar a malha ====================
  { slug: "heic-para-png", prioridade: "C", h1: "Converter HEIC para PNG sem enviar arquivo" },
  { slug: "bmp-para-png", prioridade: "C", h1: "Converter BMP para PNG sem enviar arquivo" },
  { slug: "tiff-para-jpg", prioridade: "C", h1: "Converter TIFF para JPG sem enviar arquivo" },
  { slug: "ico-para-png", prioridade: "C", h1: "Converter ICO para PNG sem enviar arquivo" },
  { slug: "criar-icone", prioridade: "C", h1: "Criar ícone ICO sem enviar arquivo", frase: "Crie ícones ICO" },
  { slug: "flac-para-mp3", prioridade: "C", h1: "Converter FLAC para MP3 sem enviar arquivo" },
  { slug: "ogg-para-mp3", prioridade: "C", h1: "Converter OGG para MP3 sem enviar arquivo" },
  { slug: "webm-para-mp4", prioridade: "C", h1: "Converter WEBM para MP4 sem enviar arquivo" },
  { slug: "mkv-para-mp3", prioridade: "C", h1: "Extrair MP3 de MKV sem enviar arquivo", frase: "Extraia o MP3 de vídeos MKV" },
  { slug: "mov-para-mp3", prioridade: "C", h1: "Extrair MP3 de MOV sem enviar arquivo", frase: "Extraia o MP3 de vídeos MOV" },
  { slug: "pdf-para-webp", prioridade: "C", h1: "Converter PDF para WEBP sem enviar arquivo" },
  { slug: "redimensionar-imagem", prioridade: "C", h1: "Redimensionar imagem sem enviar arquivo", frase: "Redimensione imagens" },
  { slug: "recortar-imagem", prioridade: "C", h1: "Recortar imagem sem enviar arquivo", frase: "Recorte imagens" },
  { slug: "girar-imagem", prioridade: "C", h1: "Girar e espelhar imagem sem enviar arquivo", frase: "Gire e espelhe imagens" },
  { slug: "normalizar-audio", prioridade: "C", h1: "Normalizar volume de áudio sem enviar arquivo", frase: "Normalize o volume do áudio" },
  { slug: "srt-para-vtt", prioridade: "C", h1: "Converter SRT para VTT sem enviar arquivo" },
  { slug: "texto-para-voz", prioridade: "C", h1: "Transformar texto em narração sem enviar arquivo", frase: "Transforme texto em narração" },
  { slug: "remover-audio-do-video", prioridade: "C", h1: "Remover áudio do vídeo sem enviar arquivo", frase: "Remova o áudio do vídeo" },
  { slug: "video-para-imagens", prioridade: "C", h1: "Extrair quadros de vídeo sem enviar arquivo", frase: "Extraia quadros de vídeo" },
]);

/** As ferramentas que não estão na lista também ganham H1 de busca, escrito à mão. */
const FERRAMENTAS_FORA_DA_LISTA = deepFreeze({
  "converter-imagem": { h1: "Converter imagem sem enviar arquivo", frase: "Converta imagens" },
  "converter-audio": { h1: "Converter áudio sem enviar arquivo", frase: "Converta áudios" },
  "editar-audio": { h1: "Editar áudio sem enviar arquivo", frase: "Edite áudios" },
  "converter-video": { h1: "Converter vídeo sem enviar arquivo", frase: "Converta vídeos" },
  "video-para-gif": { h1: "Transformar vídeo em GIF sem enviar arquivo", frase: "Transforme vídeo em GIF" },
  "pdf-para-imagem": { h1: "Converter PDF em imagem sem enviar arquivo", frase: "Converta PDF em imagem" },
  "converter-legenda": { h1: "Converter legenda SRT e VTT sem enviar arquivo", frase: "Converta legendas" },
  "gif-para-video": { h1: "Converter GIF em vídeo sem enviar arquivo", frase: "Converta GIF em vídeo" },
});

const POR_SLUG = new Map(PAGINAS_PRIORIZADAS.map((pagina) => [pagina.slug, pagina]));

/** As páginas de uma prioridade, na ordem da lista. */
export function paginasDaPrioridade(prioridade) {
  return PAGINAS_PRIORIZADAS.filter((pagina) => pagina.prioridade === prioridade);
}

/**
 * Tudo o que a página de um slug precisa para ser montada: H1 (que também é o título
 * da aba), meta description, prioridade e a ferramenta com o ajuste pronto.
 * Nulo para slug desconhecido.
 */
export function paginaDeBusca(slug) {
  const resolvido = resolverSlug(slug);
  if (!resolvido) return null;

  const { ferramenta, de, para } = resolvido;
  const listada = POR_SLUG.get(slug) ?? null;
  const fora = FERRAMENTAS_FORA_DA_LISTA[slug] ?? null;

  const h1 =
    listada?.h1 ??
    fora?.h1 ??
    (de && para ? `Converter ${rotulo(de)} para ${rotulo(para)} sem enviar arquivo` : `${ferramenta.titulo} sem enviar arquivo`);
  const frase =
    listada?.frase ?? fora?.frase ?? (de && para ? `Converta ${rotulo(de)} para ${rotulo(para)}` : ferramenta.acao);

  return {
    slug,
    h1,
    titulo: h1,
    descricao: `${frase} ${FECHO_DA_META}`,
    prioridade: listada?.prioridade ?? null,
    comFaq: listada ? listada.prioridade !== "C" : false,
    ...resolvido,
  };
}

/** Todos os slugs que viram página, com os priorizados primeiro (para o sitemap). */
export function slugsEmOrdemDePrioridade() {
  const priorizados = PAGINAS_PRIORIZADAS.map((pagina) => pagina.slug);
  const resto = todosOsSlugs().filter((slug) => !POR_SLUG.has(slug));
  return [...priorizados, ...resto];
}

/**
 * O FAQ padrão da lista, com a resposta ajustada ao que a ferramenta realmente faz —
 * limite certo, onde roda e se abre no celular. Nunca promete o que a ferramenta não faz.
 */
export function perguntasFrequentes(ferramenta) {
  const gratis = LIMITES.gratis;
  const pro = formatarReais(produto("pro-mensal").centavos);
  const peloPlugin = !ferramenta.navegador;

  const envio = peloPlugin
    ? "Não. Esta ferramenta roda pelo plugin, no seu computador. O site conversa com o plugin dentro da sua própria máquina; nada vai para a internet."
    : "Não. A conversão roda no seu navegador ou no plugin, no seu dispositivo.";

  let limite;
  if (ferramenta.plano === "pro" && ferramenta.limite === "pesado") {
    limite = `${ferramenta.titulo} faz parte do Pro (${pro} por 31 dias, sem renovação automática). As outras ferramentas têm plano grátis.`;
  } else if (ferramenta.limite === "transcricao") {
    limite = `${gratis.transcricao} minutos de transcrição por dia, para testar. O Pro transcreve sem limite, com a mesma qualidade.`;
  } else if (ferramenta.limite === "narracao") {
    limite = `${gratis.narracao.toLocaleString("pt-BR")} caracteres de narração por dia. O Pro narra sem limite, com a mesma qualidade.`;
  } else if (ferramenta.limite === "ocr") {
    limite = `${gratis.ocr} páginas de reconhecimento de texto por dia. Qualidade igual à do Pro.`;
  } else {
    limite = `${gratis.lote} arquivos por vez, vídeo até ${gratis.video} min, PDF até ${gratis.pdf} páginas. Qualidade igual à do Pro.`;
  }

  const grande = ferramenta.plugin
    ? "Use o plugin ou o aplicativo. Nada sobe para a internet."
    : "O Pro tira o teto de arquivos por vez e de páginas, e a conversão continua no navegador. Nada sobe para a internet.";

  let celular;
  if (peloPlugin) {
    celular = "Não. Esta ferramenta roda pelo plugin, que é para Windows 10 e 11.";
  } else if (ferramenta.categoria === "video" || ferramenta.entradas.includes("video")) {
    celular = "Vídeo curto, sim, em celular recente. Vídeo pesado rende melhor no computador.";
  } else if (ferramenta.categoria === "audio" || ferramenta.entradas.includes("legenda")) {
    celular = "Sim, no navegador do celular. Áudio longo rende melhor no computador.";
  } else {
    celular = "Sim, as ferramentas de imagem e PDF no navegador. Vídeo pesado rende melhor no computador.";
  }

  return [
    { pergunta: "Meu arquivo é enviado para algum servidor?", resposta: envio },
    { pergunta: "Tem marca d'água?", resposta: "Não." },
    { pergunta: "Preciso criar conta?", resposta: "Não." },
    { pergunta: "Qual o limite no grátis?", resposta: limite },
    { pergunta: "E se o arquivo for grande ou o lote for grande?", resposta: grande },
    { pergunta: "Funciona no celular?", resposta: celular },
  ];
}

/** Os atalhos da home: o que a pessoa veio fazer, cada um caindo na página certa. */
export const ATALHOS_DA_HOME = deepFreeze([
  { slug: "webp-para-png", rotulo: "WEBP → PNG" },
  { slug: "heic-para-jpg", rotulo: "HEIC → JPG" },
  { slug: "mp4-para-mp3", rotulo: "MP4 → MP3" },
  { slug: "juntar-pdf", rotulo: "Juntar PDF" },
  { slug: "comprimir-video", rotulo: "Comprimir vídeo" },
  { slug: "audio-para-texto", rotulo: "Transcrever áudio" },
]);

/** Quantas páginas de busca existem, para nenhum texto inventar número. */
export function contagemDePaginas() {
  return {
    priorizadas: PAGINAS_PRIORIZADAS.length,
    total: todosOsSlugs().length,
    pares: PARES.length,
    ferramentas: FERRAMENTAS.length,
  };
}
