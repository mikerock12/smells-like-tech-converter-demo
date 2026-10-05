/**
 * Catálogo único de ferramentas do produto.
 *
 * Tudo o que o site oferece nasce daqui: a landing, o hub de ferramentas, as páginas por
 * par de formato, a oficina de conversão e a tabela de preços leem esta lista. Uma
 * ferramenta que não está aqui não existe — e uma que está aqui diz, sem rodeio, onde
 * funciona: no navegador sozinho, ou pelo plugin (que é o motor do aplicativo).
 *
 * Módulo puro de propósito: nada de DOM, Worker ou WASM. Roda no `node --test`.
 */

import { FORMATS, getFormat } from "./index.mjs";

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}

/** Tipos de mídia que uma ferramenta aceita. Espelham `kind` do catálogo de formatos. */
export const TIPOS = deepFreeze({
  image: { id: "image", titulo: "Imagem", plural: "imagens" },
  audio: { id: "audio", titulo: "Áudio", plural: "áudios" },
  video: { id: "video", titulo: "Vídeo", plural: "vídeos" },
  pdf: { id: "pdf", titulo: "PDF", plural: "PDFs" },
  legenda: { id: "legenda", titulo: "Legenda", plural: "legendas" },
  texto: { id: "texto", titulo: "Texto", plural: "textos" },
  documento: { id: "documento", titulo: "Documento", plural: "documentos" },
});

export const CATEGORIAS = deepFreeze([
  {
    id: "imagem",
    titulo: "Imagem",
    chamada: "Converter, comprimir, recortar e girar",
    resumo: "JPG, PNG, WEBP, HEIC, AVIF, GIF, BMP, ICO e TIFF. Ícone para site, imagem para PDF, texto de foto.",
  },
  {
    id: "audio",
    titulo: "Áudio",
    chamada: "Trocar formato, cortar, normalizar",
    resumo: "MP3, WAV, OGG, M4A, FLAC e a trilha de qualquer vídeo. Corte, volume, fade e velocidade.",
  },
  {
    id: "video",
    titulo: "Vídeo",
    chamada: "Converter, comprimir, cortar, GIF",
    resumo: "MP4, WEBM, MKV e MOV. Resolução, compressão, corte, sem áudio, quadros, GIF animado e GIF virando vídeo.",
  },
  {
    id: "pdf",
    titulo: "PDF",
    chamada: "Juntar, dividir, comprimir, extrair",
    resumo: "Junte, separe, gire e comprima. PDF vira imagem, texto ou Word; imagens viram PDF.",
  },
  {
    id: "voz",
    titulo: "Voz e texto",
    chamada: "Transcrever, narrar, reconhecer",
    resumo: "Áudio vira texto e legenda, texto vira narração, foto e PDF escaneado viram texto.",
  },
]);

/**
 * Cada ferramenta:
 *
 * - `navegador`: funciona na página, sem instalar nada. Quando é falso, precisa do plugin.
 * - `plugin`: id da operação equivalente no plugin/aplicativo, ou `null` quando só o
 *   navegador faz (uma legenda SRT → VTT não precisa de motor nenhum).
 * - `plano`: "gratis" ou "pro". As gratuitas têm limites, descritos em `precos.mjs`.
 * - `limite`: qual contador do plano grátis a ferramenta consome.
 * - `pares`: combinações origem → destino que ganham página própria para busca.
 * - `formatosDeEntrada` (opcional): quando a ferramenta aceita só alguns formatos do
 *   tipo — "GIF → vídeo" é de imagem, mas só faz sentido com GIF.
 */
export const FERRAMENTAS = deepFreeze([
  // ==================== imagem ====================
  {
    id: "imagem.converter",
    slug: "converter-imagem",
    categoria: "imagem",
    titulo: "Converter imagem",
    acao: "Converter",
    resumo: "Troque o formato, reduza o peso e ajuste o tamanho. Um PNG de 522 KB sai como WEBP de 7 KB.",
    entradas: ["image"],
    saidas: ["webp", "jpg", "png", "bmp", "ico"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["comprimir imagem", "redimensionar imagem", "converter foto", "reduzir tamanho de imagem"],
    pares: [
      ["webp", "png"], ["webp", "jpg"], ["png", "webp"], ["jpg", "webp"], ["png", "jpg"], ["jpg", "png"],
      ["heic", "jpg"], ["heic", "png"], ["avif", "jpg"], ["avif", "png"], ["gif", "png"], ["gif", "jpg"],
      ["bmp", "jpg"], ["bmp", "png"], ["tiff", "jpg"], ["tiff", "png"], ["ico", "png"], ["jpg", "bmp"],
    ],
  },
  {
    id: "imagem.comprimir",
    slug: "comprimir-imagem",
    categoria: "imagem",
    titulo: "Comprimir imagem",
    acao: "Comprimir",
    resumo: "Mesmo formato, menos peso. Escolha a qualidade e veja quanto encolheu antes de baixar.",
    entradas: ["image"],
    saidas: ["webp", "jpg", "png"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["comprimir jpg", "comprimir png", "diminuir foto", "otimizar imagem para site"],
    pares: [],
  },
  {
    id: "imagem.redimensionar",
    slug: "redimensionar-imagem",
    categoria: "imagem",
    titulo: "Redimensionar imagem",
    acao: "Redimensionar",
    resumo: "Largura, altura ou porcentagem. Preenche e corta, encaixa inteira ou estica, como você preferir.",
    entradas: ["image"],
    saidas: ["webp", "jpg", "png"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["diminuir imagem", "aumentar imagem", "imagem 1080x1080", "foto 3x4"],
    pares: [],
  },
  {
    id: "imagem.recortar",
    slug: "recortar-imagem",
    categoria: "imagem",
    titulo: "Recortar por proporção",
    acao: "Recortar",
    resumo: "Quadrado para o feed, 9:16 para stories, 16:9 para capa. Recorte centrado, sem deformar.",
    entradas: ["image"],
    saidas: ["webp", "jpg", "png"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["cortar imagem quadrada", "imagem 9:16", "recortar foto 16:9"],
    pares: [],
  },
  {
    id: "imagem.girar",
    slug: "girar-imagem",
    categoria: "imagem",
    titulo: "Girar e espelhar",
    acao: "Girar",
    resumo: "90°, 180°, 270°, espelho horizontal ou vertical. Sem perder qualidade quando a saída é PNG.",
    entradas: ["image"],
    saidas: ["png", "jpg", "webp"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["girar foto", "espelhar imagem", "virar imagem"],
    pares: [],
  },
  {
    id: "imagem.icone",
    slug: "criar-icone",
    categoria: "imagem",
    titulo: "Criar ícone (ICO)",
    acao: "Gerar ícone",
    resumo: "Favicon e ícone de programa com vários tamanhos num arquivo só: 16, 32, 48, 64, 128 e 256 px.",
    entradas: ["image"],
    saidas: ["ico"],
    navegador: true,
    plugin: "image.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["png para ico", "criar favicon", "converter para icone"],
    pares: [["png", "ico"], ["jpg", "ico"], ["webp", "ico"]],
  },
  {
    id: "imagem.para-pdf",
    slug: "imagens-para-pdf",
    categoria: "pdf",
    titulo: "Imagens → PDF",
    acao: "Montar PDF",
    resumo: "Várias fotos viram um PDF só, uma por página, na ordem em que você soltou. Ótimo para documentos fotografados.",
    entradas: ["image"],
    saidas: ["pdf"],
    navegador: true,
    plugin: "pdf.fromImage",
    plano: "gratis",
    limite: "pdf",
    buscas: ["jpg para pdf", "png para pdf", "foto para pdf", "juntar fotos em pdf"],
    pares: [["jpg", "pdf"], ["png", "pdf"], ["webp", "pdf"], ["heic", "pdf"]],
  },
  {
    id: "imagem.ocr",
    slug: "foto-para-texto",
    categoria: "voz",
    titulo: "Foto → texto (OCR)",
    acao: "Reconhecer texto",
    resumo: "Lê o texto de uma foto, print ou documento escaneado. Em português, inglês e espanhol.",
    entradas: ["image"],
    saidas: ["txt"],
    navegador: true,
    plugin: "ocr.imageToText",
    plano: "gratis",
    limite: "ocr",
    buscas: ["ocr online", "extrair texto de imagem", "foto para texto", "print para texto"],
    pares: [["jpg", "txt"], ["png", "txt"]],
  },

  // ==================== áudio ====================
  {
    id: "audio.converter",
    slug: "converter-audio",
    categoria: "audio",
    titulo: "Converter áudio",
    acao: "Converter",
    resumo: "MP3 e WAV saem do navegador; OGG, M4A e FLAC pelo plugin. O navegador já lê todos eles de graça.",
    entradas: ["audio"],
    saidas: ["mp3", "wav"],
    navegador: true,
    plugin: "audio.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["converter para mp3", "wav para mp3", "m4a para mp3", "ogg para mp3", "flac para mp3"],
    pares: [
      ["wav", "mp3"], ["m4a", "mp3"], ["ogg", "mp3"], ["flac", "mp3"], ["mp3", "wav"],
      ["m4a", "wav"], ["ogg", "wav"], ["flac", "wav"],
    ],
  },
  {
    id: "audio.editar",
    slug: "editar-audio",
    categoria: "audio",
    titulo: "Editar áudio",
    acao: "Editar",
    resumo: "Corte o trecho, ajuste o volume, normalize, coloque fade, mude a velocidade ou passe para mono.",
    entradas: ["audio"],
    saidas: ["mp3", "wav"],
    navegador: true,
    plugin: "audio.convert",
    plano: "gratis",
    limite: "lote",
    buscas: ["cortar mp3", "aumentar volume do áudio", "normalizar áudio", "acelerar áudio", "fade in fade out"],
    pares: [],
  },
  {
    id: "video.para-audio",
    slug: "video-para-audio",
    categoria: "audio",
    titulo: "Vídeo → áudio",
    acao: "Extrair áudio",
    resumo: "Tira a trilha sonora do vídeo em MP3 ou WAV. O vídeo de aula vira o áudio para ouvir no caminho.",
    entradas: ["video"],
    saidas: ["mp3", "wav"],
    navegador: true,
    plugin: "video.extractAudio",
    plano: "gratis",
    limite: "video",
    buscas: ["mp4 para mp3", "extrair áudio de vídeo", "tirar som do vídeo", "vídeo para mp3"],
    pares: [
      ["mp4", "mp3"], ["mp4", "wav"], ["mkv", "mp3"], ["mov", "mp3"], ["webm", "mp3"], ["avi", "mp3"],
    ],
  },

  // ==================== vídeo ====================
  {
    id: "video.converter",
    slug: "converter-video",
    categoria: "video",
    titulo: "Converter vídeo",
    acao: "Converter",
    resumo: "MP4, WEBM, MKV e MOV, com resolução e qualidade à escolha. Usa os codecs do seu navegador e da sua placa.",
    entradas: ["video"],
    saidas: ["mp4", "webm", "mkv", "mov"],
    navegador: true,
    plugin: "video.convert",
    plano: "gratis",
    limite: "video",
    buscas: ["converter vídeo", "mkv para mp4", "mov para mp4", "webm para mp4", "avi para mp4"],
    pares: [
      ["mkv", "mp4"], ["mov", "mp4"], ["webm", "mp4"], ["avi", "mp4"], ["mp4", "webm"], ["mp4", "mkv"],
      ["mp4", "mov"], ["mkv", "webm"], ["mov", "webm"],
    ],
  },
  {
    id: "video.comprimir",
    slug: "comprimir-video",
    categoria: "video",
    titulo: "Comprimir vídeo",
    acao: "Comprimir",
    resumo: "Para WhatsApp, e-mail ou site. Escolha o tamanho final aproximado e o resto é conta nossa.",
    entradas: ["video"],
    saidas: ["mp4", "webm"],
    navegador: true,
    plugin: "video.convert",
    plano: "gratis",
    limite: "video",
    buscas: ["comprimir vídeo", "diminuir vídeo para whatsapp", "reduzir tamanho de vídeo"],
    pares: [],
  },
  {
    id: "video.cortar",
    slug: "cortar-video",
    categoria: "video",
    titulo: "Cortar vídeo",
    acao: "Cortar",
    resumo: "Marque início e fim e leve só o trecho. Sem reprocessar o que não mudou, quando o formato permite.",
    entradas: ["video"],
    saidas: ["mp4", "webm", "mkv", "mov"],
    navegador: true,
    plugin: "video.convert",
    plano: "gratis",
    limite: "video",
    buscas: ["cortar vídeo online", "recortar trecho de vídeo", "aparar vídeo"],
    pares: [],
  },
  {
    id: "video.silenciar",
    slug: "remover-audio-do-video",
    categoria: "video",
    titulo: "Remover áudio do vídeo",
    acao: "Silenciar",
    resumo: "O vídeo sai sem a trilha sonora, no mesmo formato, sem perder qualidade de imagem.",
    entradas: ["video"],
    saidas: ["mp4", "webm", "mkv", "mov"],
    navegador: true,
    plugin: "video.convert",
    plano: "gratis",
    limite: "video",
    buscas: ["tirar áudio do vídeo", "vídeo sem som", "silenciar vídeo"],
    pares: [],
  },
  {
    id: "video.gif",
    slug: "video-para-gif",
    categoria: "video",
    titulo: "Vídeo → GIF",
    acao: "Fazer GIF",
    resumo: "Trecho curto em GIF animado, com paleta otimizada. Largura e quadros por segundo à escolha.",
    entradas: ["video"],
    saidas: ["gif"],
    navegador: true,
    plugin: "video.toGif",
    plano: "gratis",
    limite: "video",
    buscas: ["mp4 para gif", "criar gif de vídeo", "gif animado"],
    pares: [["mp4", "gif"], ["webm", "gif"], ["mov", "gif"]],
  },
  {
    id: "video.quadros",
    slug: "video-para-imagens",
    categoria: "video",
    titulo: "Vídeo → imagens",
    acao: "Extrair quadros",
    resumo: "Um quadro a cada tantos segundos, ou só o instante que você marcar, em PNG, JPG ou WEBP.",
    entradas: ["video"],
    saidas: ["png", "jpg", "webp"],
    navegador: true,
    plugin: "video.toFrames",
    plano: "gratis",
    limite: "video",
    buscas: ["extrair frame de vídeo", "tirar print de vídeo", "vídeo para jpg"],
    pares: [["mp4", "png"], ["mp4", "jpg"]],
  },
  {
    id: "video.de-gif",
    slug: "gif-para-video",
    categoria: "video",
    titulo: "GIF → vídeo",
    acao: "Fazer vídeo",
    resumo: "O GIF animado vira MP4 ou WEBM, muito mais leve e aceito em qualquer rede social. Repete quantas vezes você quiser.",
    entradas: ["image"],
    formatosDeEntrada: ["gif"],
    saidas: ["mp4", "webm"],
    navegador: true,
    plugin: null,
    plano: "gratis",
    limite: "lote",
    buscas: ["gif para mp4", "gif para vídeo", "converter gif em vídeo", "gif para instagram"],
    pares: [["gif", "mp4"], ["gif", "webm"]],
  },

  // ==================== PDF ====================
  {
    id: "pdf.juntar",
    slug: "juntar-pdf",
    categoria: "pdf",
    titulo: "Juntar PDF",
    acao: "Juntar",
    resumo: "Vários PDFs viram um só, na ordem que você definir. Arraste para reordenar antes de juntar.",
    entradas: ["pdf"],
    saidas: ["pdf"],
    navegador: true,
    plugin: "pdf.merge",
    plano: "gratis",
    limite: "pdf",
    buscas: ["juntar pdf", "unir pdf", "mesclar pdf", "combinar pdf"],
    pares: [],
  },
  {
    id: "pdf.dividir",
    slug: "dividir-pdf",
    categoria: "pdf",
    titulo: "Dividir PDF",
    acao: "Dividir",
    resumo: "Extraia páginas (1-3, 7, 10-12) ou separe em um arquivo por página. Sai num ZIP quando são muitos.",
    entradas: ["pdf"],
    saidas: ["pdf"],
    navegador: true,
    plugin: "pdf.split",
    plano: "gratis",
    limite: "pdf",
    buscas: ["dividir pdf", "separar páginas do pdf", "extrair página do pdf", "cortar pdf"],
    pares: [],
  },
  {
    id: "pdf.girar",
    slug: "girar-pdf",
    categoria: "pdf",
    titulo: "Girar PDF",
    acao: "Girar",
    resumo: "Gire todas as páginas ou só as que estão deitadas. Salva de novo sem recomprimir nada.",
    entradas: ["pdf"],
    saidas: ["pdf"],
    navegador: true,
    plugin: null,
    plano: "gratis",
    limite: "pdf",
    buscas: ["girar pdf", "virar página do pdf"],
    pares: [],
  },
  {
    id: "pdf.comprimir",
    slug: "comprimir-pdf",
    categoria: "pdf",
    titulo: "Comprimir PDF",
    acao: "Comprimir",
    resumo: "Recomprime as fotos e as páginas escaneadas e enxuga a estrutura do arquivo. O texto continua texto, selecionável.",
    entradas: ["pdf"],
    saidas: ["pdf"],
    navegador: true,
    plugin: null,
    plano: "gratis",
    limite: "pdf",
    buscas: ["comprimir pdf", "diminuir tamanho do pdf", "reduzir pdf", "pdf menor para enviar"],
    pares: [],
  },
  {
    id: "pdf.para-imagens",
    slug: "pdf-para-imagem",
    categoria: "pdf",
    titulo: "PDF → imagens",
    acao: "Converter páginas",
    resumo: "Cada página vira uma imagem, na resolução escolhida. Para mandar no WhatsApp ou colocar num slide.",
    entradas: ["pdf"],
    saidas: ["png", "jpg", "webp"],
    navegador: true,
    plugin: "pdf.toImage",
    plano: "gratis",
    limite: "pdf",
    buscas: ["pdf para jpg", "pdf para png", "pdf para imagem"],
    pares: [["pdf", "jpg"], ["pdf", "png"], ["pdf", "webp"]],
  },
  {
    id: "pdf.para-texto",
    slug: "pdf-para-texto",
    categoria: "pdf",
    titulo: "PDF → texto",
    acao: "Extrair texto",
    resumo: "O texto do PDF em TXT ou Markdown, página por página. PDF escaneado passa pelo reconhecimento de texto.",
    entradas: ["pdf"],
    saidas: ["txt", "md"],
    navegador: true,
    plugin: "pdf.toDocument",
    plano: "gratis",
    limite: "pdf",
    buscas: ["pdf para txt", "copiar texto de pdf", "extrair texto do pdf"],
    pares: [["pdf", "txt"], ["pdf", "md"]],
  },
  {
    id: "pdf.para-documento",
    slug: "pdf-para-word",
    categoria: "pdf",
    titulo: "PDF → Word",
    acao: "Converter",
    resumo: "PDF vira DOCX editável ou HTML. Documento escaneado é reconhecido página a página pelo motor do Windows.",
    entradas: ["pdf"],
    saidas: ["docx", "html"],
    navegador: false,
    plugin: "pdf.toDocument",
    plano: "pro",
    limite: "pesado",
    buscas: ["pdf para word", "pdf para docx", "converter pdf em word editável"],
    pares: [["pdf", "docx"], ["pdf", "html"]],
  },

  // ==================== voz e texto ====================
  {
    id: "voz.transcrever",
    slug: "audio-para-texto",
    categoria: "voz",
    titulo: "Áudio/vídeo → texto e legenda",
    acao: "Transcrever",
    resumo: "Transcrição em português com o Whisper rodando na sua máquina, em TXT, SRT ou VTT com tempos sincronizados.",
    entradas: ["audio", "video"],
    saidas: ["txt", "srt", "vtt"],
    navegador: false,
    plugin: "speech.transcribe",
    plano: "pro",
    limite: "transcricao",
    buscas: ["transcrever áudio", "gerar legenda", "áudio para texto", "legendar vídeo", "mp3 para txt"],
    pares: [["mp3", "txt"], ["mp4", "srt"], ["mp4", "txt"], ["mp3", "srt"], ["wav", "txt"], ["m4a", "txt"]],
  },
  {
    id: "voz.narrar",
    slug: "texto-para-voz",
    categoria: "voz",
    titulo: "Texto, PDF ou imagem → narração",
    acao: "Narrar",
    resumo: "Texto, PDF, imagem, DOCX, Markdown, HTML ou legenda viram MP3 ou WAV com Kokoro-82M em português. Prepare o modelo uma vez; a narração roda localmente, inclusive offline. PDF escaneado e foto passam por OCR.",
    entradas: ["texto", "pdf", "image", "documento", "legenda"],
    formatosDeEntrada: ["txt", "pdf", "jpg", "png", "webp", "avif", "tiff", "bmp", "gif", "ico", "heic", "docx", "md", "html", "srt", "vtt"],
    saidas: ["mp3", "wav"],
    navegador: true,
    plugin: "speech.synthesize",
    plano: "pro",
    limite: "narracao",
    buscas: ["texto para voz", "narrar texto", "gerar áudio de texto", "tts português", "pdf para áudio", "pdf em mp3", "ler pdf em voz alta", "imagem para áudio"],
    pares: [["txt", "mp3"], ["txt", "wav"], ["pdf", "mp3"], ["docx", "mp3"], ["jpg", "mp3"]],
  },
  {
    id: "legenda.converter",
    slug: "converter-legenda",
    categoria: "voz",
    titulo: "Converter legenda",
    acao: "Converter",
    resumo: "SRT vira VTT, VTT vira SRT, e qualquer um vira texto limpo, sem os tempos. Com ajuste de atraso.",
    entradas: ["legenda"],
    saidas: ["srt", "vtt", "txt"],
    navegador: true,
    plugin: null,
    plano: "gratis",
    limite: "lote",
    buscas: ["srt para vtt", "vtt para srt", "sincronizar legenda", "atrasar legenda"],
    pares: [["srt", "vtt"], ["vtt", "srt"], ["srt", "txt"], ["vtt", "txt"]],
  },
]);

export const FERRAMENTAS_POR_ID = deepFreeze(
  Object.fromEntries(FERRAMENTAS.map((ferramenta) => [ferramenta.id, ferramenta])),
);

export function ferramenta(id) {
  return FERRAMENTAS_POR_ID[id] ?? null;
}

export function ferramentasDaCategoria(categoria) {
  return FERRAMENTAS.filter((item) => item.categoria === categoria);
}

/**
 * Ferramentas que aceitam um tipo de mídia, na ordem do catálogo. Com a lista de
 * formatos dos arquivos, some também quem só aceita alguns formatos daquele tipo.
 */
export function ferramentasPara(tipo, formatos = null) {
  return FERRAMENTAS.filter((item) => item.entradas.includes(tipo) && aceitaOsFormatos(item, formatos));
}

/** Se a ferramenta aceita todos esses formatos. Sem formatos conhecidos, aceita. */
export function aceitaOsFormatos(item, formatos) {
  if (!item.formatosDeEntrada || !formatos || formatos.length === 0) return true;
  return formatos.every((formato) => formato && item.formatosDeEntrada.includes(formato));
}

/** Slug da página de um par de formatos: "mp4-para-mp3". */
export function slugDoPar(de, para) {
  return `${de}-para-${para}`;
}

/**
 * Todas as páginas por par de formato. Cada par aponta para a ferramenta que o
 * resolve; um mesmo par nunca aparece em duas ferramentas.
 */
export const PARES = deepFreeze(
  FERRAMENTAS.flatMap((item) =>
    item.pares.map(([de, para]) => ({
      slug: slugDoPar(de, para),
      de,
      para,
      ferramenta: item.id,
      titulo: `${rotulo(de)} para ${rotulo(para)}`,
    })),
  ),
);

/** Rótulo do formato como aparece na tela. */
export function rotulo(formato) {
  return getFormat(formato)?.label ?? String(formato).toUpperCase();
}

/**
 * Variantes: a mesma ferramenta, aberta com um ajuste pronto, para uma busca que pede
 * isso — "cortar áudio" é o editor de áudio; "gerar legenda SRT" é a transcrição já em
 * SRT. Cada uma é uma página própria, como um par de formato.
 */
export const VARIANTES = deepFreeze([
  { slug: "video-para-texto", ferramenta: "voz.transcrever", formato: "txt", opcoes: {}, entrada: "video" },
  { slug: "audio-para-srt", ferramenta: "voz.transcrever", formato: "srt", opcoes: {}, entrada: "audio" },
  { slug: "cortar-audio", ferramenta: "audio.editar", formato: null, opcoes: {}, entrada: "audio" },
  { slug: "normalizar-audio", ferramenta: "audio.editar", formato: null, opcoes: { normalizar: true }, entrada: "audio" },
]);

/**
 * Endereços que mudaram de nome para bater com a busca. O Worker responde com
 * redirecionamento permanente, para link antigo e buscador chegarem no lugar novo.
 */
export const SLUGS_ANTIGOS = deepFreeze({
  "imagem-para-pdf": "imagens-para-pdf",
  "imagem-para-texto": "foto-para-texto",
  "transcrever-audio": "audio-para-texto",
});

/**
 * Resolve o que uma URL `/converter/<slug>` quer dizer: uma ferramenta pelo slug dela,
 * um par "de-para-para" ou uma variante. Devolve nulo para slug desconhecido.
 */
export function resolverSlug(slug) {
  const porFerramenta = FERRAMENTAS.find((item) => item.slug === slug);
  if (porFerramenta) return { ferramenta: porFerramenta, de: null, para: null, formato: null, opcoes: {} };

  const par = PARES.find((item) => item.slug === slug);
  if (par) return { ferramenta: FERRAMENTAS_POR_ID[par.ferramenta], de: par.de, para: par.para, formato: par.para, opcoes: {} };

  const variante = VARIANTES.find((item) => item.slug === slug);
  if (variante) {
    return { ferramenta: FERRAMENTAS_POR_ID[variante.ferramenta], de: null, para: null, formato: variante.formato, opcoes: variante.opcoes };
  }

  return null;
}

/** Todos os slugs publicados, para o sitemap e para a geração estática. */
export function todosOsSlugs() {
  return [...FERRAMENTAS.map((item) => item.slug), ...PARES.map((item) => item.slug), ...VARIANTES.map((item) => item.slug)];
}

/** Formatos de entrada, por extensão, que a oficina aceita. */
export function extensoesAceitas() {
  return FORMATS.filter((format) => format.canDecodeLocally || format.kind === "video" || format.kind === "image")
    .flatMap((format) => format.extensions)
    .map((extension) => `.${extension}`);
}

/**
 * Busca simples, para a caixa de pesquisa do hub: compara com título, resumo, termos de
 * busca e pares. Sem acento e sem maiúscula.
 */
export function buscarFerramentas(consulta) {
  const termo = normalizar(consulta);
  if (!termo) return [...FERRAMENTAS];

  return FERRAMENTAS.filter((item) => {
    const texto = normalizar(
      [item.titulo, item.resumo, ...item.buscas, ...item.pares.map(([de, para]) => `${de} para ${para}`)].join(" "),
    );
    return termo.split(/\s+/).every((palavra) => texto.includes(palavra));
  });
}

function normalizar(texto) {
  return String(texto ?? "")
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .trim();
}

/** Quantas ferramentas existem em cada lugar — para a landing não inventar número. */
export function contagem() {
  const total = FERRAMENTAS.length;
  const noNavegador = FERRAMENTAS.filter((item) => item.navegador).length;
  const soPlugin = total - noNavegador;
  return { total, noNavegador, soPlugin, pares: PARES.length };
}
