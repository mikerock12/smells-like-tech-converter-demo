import type { FormatId } from "@/packages/converter-core/index.mjs";
import { getFormat } from "@/packages/converter-core/index.mjs";
import { AUDIO_BITRATES } from "@/packages/converter-core/index.mjs";
import { ferramenta as buscarFerramenta, type Ferramenta } from "@/packages/converter-core/catalogo.mjs";

/**
 * As opções de cada ferramenta, descritas como campos.
 *
 * A oficina desenha os campos a partir daqui, e os motores recebem o objeto de opções
 * já com os padrões preenchidos. Um campo novo é uma linha nesta tabela: nem a tela nem
 * o motor precisam saber desenhar ou validar cada um à mão.
 */

export type Opcoes = Record<string, string | number | boolean>;

export type Campo =
  | { id: string; tipo: "select"; rotulo: string; opcoes: readonly { valor: string; rotulo: string }[]; padrao: string; largo?: boolean; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "numero"; rotulo: string; padrao: number; min: number; max: number; passo?: number; sufixo?: string; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "faixa"; rotulo: string; padrao: number; min: number; max: number; passo?: number; sufixo?: string; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "marcar"; rotulo: string; padrao: boolean; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "tempo"; rotulo: string; padrao: string; ajuda?: string; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "texto"; rotulo: string; padrao: string; ajuda?: string; largo?: boolean; quando?: (opcoes: Opcoes) => boolean }
  | { id: string; tipo: "cor"; rotulo: string; padrao: string; quando?: (opcoes: Opcoes) => boolean };

function formatos(ids: readonly FormatId[], padrao?: FormatId): Campo {
  return {
    id: "formato",
    tipo: "select",
    rotulo: "Formato de saída",
    opcoes: ids.map((id) => ({ valor: id, rotulo: getFormat(id)?.label ?? id.toUpperCase() })),
    padrao: padrao ?? ids[0],
  };
}

const QUALIDADE: Campo = {
  id: "qualidade",
  tipo: "faixa",
  rotulo: "Qualidade",
  padrao: 82,
  min: 1,
  max: 100,
  quando: (opcoes) => getFormat(String(opcoes.formato))?.supportsQuality === true,
};

const BITRATE: Campo = {
  id: "bitrate",
  tipo: "select",
  rotulo: "Qualidade do MP3",
  opcoes: AUDIO_BITRATES.map((taxa) => ({ valor: String(taxa), rotulo: `${taxa} kbps${taxa === 192 ? " — recomendado" : ""}` })),
  padrao: "192",
  quando: (opcoes) => opcoes.formato === "mp3",
};

const INICIO: Campo = { id: "inicio", tipo: "tempo", rotulo: "Começar em", padrao: "", ajuda: "mm:ss ou hh:mm:ss. Vazio = do início." };
const FIM: Campo = { id: "fim", tipo: "tempo", rotulo: "Terminar em", padrao: "", ajuda: "Vazio = até o fim." };

const REDIMENSIONAR: Campo[] = [
  {
    id: "redimensionar",
    tipo: "select",
    rotulo: "Redimensionar",
    opcoes: [
      { valor: "none", rotulo: "Manter o tamanho" },
      { valor: "width", rotulo: "Definir a largura" },
      { valor: "height", rotulo: "Definir a altura" },
      { valor: "percent", rotulo: "Porcentagem" },
      { valor: "exact", rotulo: "Largura e altura" },
    ],
    padrao: "none",
  },
  { id: "largura", tipo: "numero", rotulo: "Largura", padrao: 1920, min: 1, max: 20000, sufixo: "px", quando: (o) => o.redimensionar === "width" || o.redimensionar === "exact" },
  { id: "altura", tipo: "numero", rotulo: "Altura", padrao: 1080, min: 1, max: 20000, sufixo: "px", quando: (o) => o.redimensionar === "height" || o.redimensionar === "exact" },
  { id: "porcentagem", tipo: "numero", rotulo: "Porcentagem", padrao: 50, min: 1, max: 1000, sufixo: "%", quando: (o) => o.redimensionar === "percent" },
  {
    id: "ajuste",
    tipo: "select",
    rotulo: "Ajuste",
    opcoes: [
      { valor: "cover", rotulo: "Preencher e cortar" },
      { valor: "contain", rotulo: "Encaixar inteira" },
      { valor: "stretch", rotulo: "Esticar" },
    ],
    padrao: "cover",
    quando: (o) => o.redimensionar === "exact",
  },
];

const FUNDO: Campo[] = [
  { id: "manterTransparencia", tipo: "marcar", rotulo: "Manter transparência", padrao: true, quando: (o) => getFormat(String(o.formato))?.supportsAlpha === true },
  { id: "fundo", tipo: "cor", rotulo: "Cor de fundo", padrao: "#ffffff", quando: (o) => getFormat(String(o.formato))?.supportsAlpha !== true || o.manterTransparencia === false },
];

const PROPORCOES = [
  { valor: "1:1", rotulo: "1:1 — quadrado (feed)" },
  { valor: "4:5", rotulo: "4:5 — retrato (Instagram)" },
  { valor: "9:16", rotulo: "9:16 — vertical (stories, Reels)" },
  { valor: "16:9", rotulo: "16:9 — capa, YouTube" },
  { valor: "4:3", rotulo: "4:3 — clássico" },
  { valor: "3:2", rotulo: "3:2 — foto" },
  { valor: "3:4", rotulo: "3:4 — retrato" },
  { valor: "21:9", rotulo: "21:9 — ultrawide" },
];

const VIDEO_RESOLUCAO: Campo = {
  id: "resolucao",
  tipo: "select",
  rotulo: "Resolução",
  opcoes: [
    { valor: "original", rotulo: "Manter a original" },
    { valor: "2160", rotulo: "2160p (4K)" },
    { valor: "1440", rotulo: "1440p" },
    { valor: "1080", rotulo: "1080p (Full HD)" },
    { valor: "720", rotulo: "720p (HD)" },
    { valor: "480", rotulo: "480p" },
    { valor: "360", rotulo: "360p" },
  ],
  padrao: "original",
};

const VIDEO_QUALIDADE: Campo = {
  id: "qualidadeDoVideo",
  tipo: "select",
  rotulo: "Qualidade",
  opcoes: [
    { valor: "muito-alta", rotulo: "Muito alta (arquivo grande)" },
    { valor: "alta", rotulo: "Alta" },
    { valor: "media", rotulo: "Média — recomendada" },
    { valor: "baixa", rotulo: "Baixa" },
    { valor: "muito-baixa", rotulo: "Muito baixa (arquivo pequeno)" },
  ],
  padrao: "media",
};

/** Os campos de cada ferramenta. Ferramentas do plugin não têm campos aqui: o painel do plugin cuida. */
export const CAMPOS: Readonly<Record<string, readonly Campo[]>> = {
  "imagem.converter": [formatos(["webp", "jpg", "png", "bmp", "ico"], "webp"), QUALIDADE, ...REDIMENSIONAR, ...FUNDO],
  "imagem.comprimir": [formatos(["webp", "jpg", "png"], "webp"), { ...QUALIDADE, padrao: 70 } as Campo, ...FUNDO],
  "imagem.redimensionar": [
    formatos(["webp", "jpg", "png"], "webp"),
    QUALIDADE,
    ...REDIMENSIONAR.map((campo) => (campo.id === "redimensionar" ? ({ ...campo, padrao: "width" } as Campo) : campo)),
    ...FUNDO,
  ],
  "imagem.recortar": [
    { id: "proporcao", tipo: "select", rotulo: "Proporção", opcoes: PROPORCOES, padrao: "1:1" },
    formatos(["webp", "jpg", "png"], "jpg"),
    { ...QUALIDADE, padrao: 88 } as Campo,
    ...FUNDO,
  ],
  "imagem.girar": [
    {
      id: "rotacao",
      tipo: "select",
      rotulo: "Girar",
      opcoes: [
        { valor: "0", rotulo: "Não girar" },
        { valor: "90", rotulo: "90° para a direita" },
        { valor: "180", rotulo: "180°" },
        { valor: "270", rotulo: "90° para a esquerda" },
      ],
      padrao: "90",
    },
    { id: "espelharH", tipo: "marcar", rotulo: "Espelhar na horizontal", padrao: false },
    { id: "espelharV", tipo: "marcar", rotulo: "Espelhar na vertical", padrao: false },
    formatos(["png", "jpg", "webp"], "png"),
    QUALIDADE,
    ...FUNDO,
  ],
  "imagem.icone": [
    {
      id: "tamanhos",
      tipo: "select",
      rotulo: "Tamanhos no arquivo",
      opcoes: [
        { valor: "completo", rotulo: "16, 32, 48, 64, 128 e 256 px (recomendado)" },
        { valor: "favicon", rotulo: "16, 32 e 48 px (favicon)" },
        { valor: "256", rotulo: "Só 256 px" },
      ],
      padrao: "completo",
    },
    { id: "fundo", tipo: "cor", rotulo: "Cor de fundo (só se a imagem não for quadrada)", padrao: "#ffffff" },
  ],
  "imagem.para-pdf": [
    {
      id: "pagina",
      tipo: "select",
      rotulo: "Página",
      opcoes: [
        { valor: "imagem", rotulo: "Do tamanho da imagem" },
        { valor: "a4-retrato", rotulo: "A4 em pé" },
        { valor: "a4-paisagem", rotulo: "A4 deitado" },
      ],
      padrao: "a4-retrato",
    },
    { id: "margem", tipo: "select", rotulo: "Margem", opcoes: [{ valor: "0", rotulo: "Sem margem" }, { valor: "10", rotulo: "10 mm" }, { valor: "20", rotulo: "20 mm" }], padrao: "10", quando: (o) => o.pagina !== "imagem" },
    { id: "qualidade", tipo: "faixa", rotulo: "Qualidade das fotos", padrao: 85, min: 30, max: 100 },
  ],
  "imagem.ocr": [
    {
      id: "idioma",
      tipo: "select",
      rotulo: "Idioma do texto",
      opcoes: [
        { valor: "por", rotulo: "Português" },
        { valor: "eng", rotulo: "Inglês" },
        { valor: "spa", rotulo: "Espanhol" },
        { valor: "por+eng", rotulo: "Português e inglês" },
      ],
      padrao: "por",
    },
  ],

  "audio.converter": [formatos(["mp3", "wav"], "mp3"), BITRATE],
  "audio.editar": [
    INICIO,
    FIM,
    { id: "volume", tipo: "faixa", rotulo: "Volume", padrao: 100, min: 0, max: 300, sufixo: "%" },
    { id: "normalizar", tipo: "marcar", rotulo: "Normalizar o volume (pico em -1 dB)", padrao: false },
    { id: "fadeIn", tipo: "numero", rotulo: "Fade in", padrao: 0, min: 0, max: 30, passo: 0.5, sufixo: "s" },
    { id: "fadeOut", tipo: "numero", rotulo: "Fade out", padrao: 0, min: 0, max: 30, passo: 0.5, sufixo: "s" },
    { id: "velocidade", tipo: "faixa", rotulo: "Velocidade", padrao: 1, min: 0.5, max: 2, passo: 0.05, sufixo: "×" },
    { id: "mono", tipo: "marcar", rotulo: "Passar para mono", padrao: false },
    formatos(["mp3", "wav"], "mp3"),
    BITRATE,
  ],
  "video.para-audio": [formatos(["mp3", "wav"], "mp3"), BITRATE, INICIO, FIM],

  "video.converter": [
    formatos(["mp4", "webm", "mkv", "mov"], "mp4"),
    VIDEO_RESOLUCAO,
    VIDEO_QUALIDADE,
    {
      id: "fps",
      tipo: "select",
      rotulo: "Quadros por segundo",
      opcoes: [
        { valor: "original", rotulo: "Manter" },
        { valor: "24", rotulo: "24" },
        { valor: "30", rotulo: "30" },
        { valor: "60", rotulo: "60" },
      ],
      padrao: "original",
    },
    {
      id: "rotacao",
      tipo: "select",
      rotulo: "Girar",
      opcoes: [
        { valor: "0", rotulo: "Não girar" },
        { valor: "90", rotulo: "90° para a direita" },
        { valor: "180", rotulo: "180°" },
        { valor: "270", rotulo: "90° para a esquerda" },
      ],
      padrao: "0",
    },
    { id: "semAudio", tipo: "marcar", rotulo: "Remover o áudio", padrao: false },
  ],
  "video.comprimir": [
    formatos(["mp4", "webm"], "mp4"),
    {
      id: "alvo",
      tipo: "select",
      rotulo: "Para onde vai",
      opcoes: [
        { valor: "whatsapp", rotulo: "WhatsApp — até 16 MB, 720p" },
        { valor: "email", rotulo: "E-mail — até 25 MB" },
        { valor: "metade", rotulo: "Metade do tamanho" },
        { valor: "quarto", rotulo: "Um quarto do tamanho" },
      ],
      padrao: "whatsapp",
    },
  ],
  "video.cortar": [INICIO, FIM, formatos(["mp4", "webm", "mkv", "mov"], "mp4")],
  "video.silenciar": [formatos(["mp4", "webm", "mkv", "mov"], "mp4")],
  "video.gif": [
    INICIO,
    { id: "duracao", tipo: "numero", rotulo: "Duração", padrao: 5, min: 0.5, max: 30, passo: 0.5, sufixo: "s" },
    { id: "largura", tipo: "select", rotulo: "Largura", opcoes: ["240", "320", "480", "640"].map((valor) => ({ valor, rotulo: `${valor} px` })), padrao: "480" },
    { id: "fps", tipo: "select", rotulo: "Quadros por segundo", opcoes: ["8", "10", "12", "15", "20"].map((valor) => ({ valor, rotulo: valor })), padrao: "12" },
  ],
  "video.de-gif": [
    formatos(["mp4", "webm"], "mp4"),
    {
      id: "voltas",
      tipo: "select",
      rotulo: "Repetir o GIF",
      opcoes: [
        { valor: "1", rotulo: "Uma vez" },
        { valor: "2", rotulo: "2 vezes" },
        { valor: "3", rotulo: "3 vezes" },
        { valor: "5", rotulo: "5 vezes" },
        { valor: "10", rotulo: "10 vezes" },
      ],
      padrao: "1",
    },
    { id: "fundo", tipo: "cor", rotulo: "Cor no lugar da transparência", padrao: "#ffffff" },
  ],
  "video.quadros": [
    {
      id: "modo",
      tipo: "select",
      rotulo: "Quais quadros",
      opcoes: [
        { valor: "instante", rotulo: "Só um instante" },
        { valor: "intervalo", rotulo: "Um a cada tantos segundos" },
        { valor: "distribuidos", rotulo: "Tantos quadros, espalhados pelo vídeo" },
      ],
      padrao: "instante",
    },
    { id: "instante", tipo: "tempo", rotulo: "Instante", padrao: "00:01", quando: (o) => o.modo === "instante" },
    { id: "intervalo", tipo: "numero", rotulo: "Intervalo", padrao: 5, min: 0.5, max: 600, passo: 0.5, sufixo: "s", quando: (o) => o.modo === "intervalo" },
    { id: "quantidade", tipo: "numero", rotulo: "Quantidade", padrao: 10, min: 1, max: 200, quando: (o) => o.modo === "distribuidos" },
    formatos(["png", "jpg", "webp"], "png"),
  ],

  "pdf.juntar": [],
  "pdf.dividir": [
    {
      id: "modo",
      tipo: "select",
      rotulo: "Como dividir",
      opcoes: [
        { valor: "paginas", rotulo: "Extrair páginas escolhidas" },
        { valor: "cada", rotulo: "Um arquivo por página" },
        { valor: "blocos", rotulo: "Blocos de N páginas" },
      ],
      padrao: "paginas",
    },
    { id: "paginas", tipo: "texto", rotulo: "Páginas", padrao: "1", ajuda: "Ex.: 1-3, 7, 10-12", quando: (o) => o.modo === "paginas" },
    { id: "porBloco", tipo: "numero", rotulo: "Páginas por bloco", padrao: 10, min: 1, max: 500, quando: (o) => o.modo === "blocos" },
  ],
  "pdf.girar": [
    { id: "angulo", tipo: "select", rotulo: "Girar", opcoes: [{ valor: "90", rotulo: "90° para a direita" }, { valor: "180", rotulo: "180°" }, { valor: "270", rotulo: "90° para a esquerda" }], padrao: "90" },
    { id: "paginas", tipo: "texto", rotulo: "Quais páginas", padrao: "", ajuda: "Vazio = todas. Ex.: 2, 5-8" },
  ],
  "pdf.comprimir": [
    {
      id: "nivel",
      tipo: "select",
      rotulo: "Quanto comprimir",
      opcoes: [
        { valor: "equilibrado", rotulo: "Equilibrado — recomendado" },
        { valor: "forte", rotulo: "Forte — menor arquivo, fotos mais simples" },
        { valor: "leve", rotulo: "Leve — só o que não perde nada visível" },
      ],
      padrao: "equilibrado",
    },
  ],
  "pdf.para-imagens": [
    formatos(["png", "jpg", "webp"], "png"),
    { id: "dpi", tipo: "select", rotulo: "Resolução", opcoes: ["72", "96", "150", "200", "300"].map((valor) => ({ valor, rotulo: `${valor} DPI` })), padrao: "150" },
    { id: "paginas", tipo: "texto", rotulo: "Quais páginas", padrao: "", ajuda: "Vazio = todas. Ex.: 1-3" },
    QUALIDADE,
  ],
  "pdf.para-texto": [
    formatos(["txt", "md"], "txt"),
    { id: "marcarPaginas", tipo: "marcar", rotulo: "Marcar onde cada página começa", padrao: true },
  ],
  // Ferramentas do plugin: os campos viram opções do JobOptions do aplicativo.
  "pdf.para-documento": [
    formatos(["docx", "html"], "docx"),
    { id: "ocr", tipo: "marcar", rotulo: "Reconhecer texto quando a página for imagem (OCR)", padrao: true },
  ],
  "voz.transcrever": [
    formatos(["txt", "srt", "vtt"], "srt"),
    {
      id: "idioma",
      tipo: "select",
      rotulo: "Idioma da fala",
      opcoes: [
        { valor: "pt", rotulo: "Português" },
        { valor: "auto", rotulo: "Detectar automaticamente" },
        { valor: "en", rotulo: "Inglês" },
        { valor: "es", rotulo: "Espanhol" },
        { valor: "fr", rotulo: "Francês" },
        { valor: "de", rotulo: "Alemão" },
        { valor: "it", rotulo: "Italiano" },
      ],
      padrao: "pt",
    },
    {
      id: "modelo",
      tipo: "select",
      rotulo: "Modelo",
      opcoes: [
        { valor: "small", rotulo: "Small — equilíbrio (recomendado)" },
        { valor: "base", rotulo: "Base — mais rápido" },
        { valor: "medium", rotulo: "Medium — mais preciso" },
        { valor: "large", rotulo: "Large — o mais fiel, e o mais lento" },
      ],
      padrao: "small",
    },
  ],
  "voz.narrar": [
    { id: "voz", tipo: "select", rotulo: "Voz Kokoro-82M", padrao: "pf_dora", opcoes: [{ valor: "pf_dora", rotulo: "Dora" }, { valor: "pm_alex", rotulo: "Alex" }, { valor: "pm_santa", rotulo: "Santa" }] },
    formatos(["mp3", "wav"], "mp3"),
    { id: "velocidade", tipo: "faixa", rotulo: "Velocidade", padrao: 0, min: -10, max: 10, passo: 1 },
    { id: "volume", tipo: "faixa", rotulo: "Volume", padrao: 100, min: 0, max: 100, sufixo: "%" },
  ],
  "legenda.converter": [
    formatos(["vtt", "srt", "txt"], "vtt"),
    { id: "atraso", tipo: "numero", rotulo: "Atrasar", padrao: 0, min: -3600, max: 3600, passo: 0.1, sufixo: "s" },
  ],
};

/** Opções padrão de uma ferramenta, com o formato de saída pedido quando houver. */
export function opcoesPadrao(ferramentaId: string, formatoDeSaida?: string | null): Opcoes {
  const campos = CAMPOS[ferramentaId] ?? [];
  const opcoes: Opcoes = {};
  for (const campo of campos) opcoes[campo.id] = campo.padrao;

  const ferramenta = buscarFerramenta(ferramentaId);
  if (formatoDeSaida && ferramenta?.saidas.includes(formatoDeSaida as FormatId)) {
    opcoes.formato = formatoDeSaida;
  }
  return opcoes;
}

/** Só os campos que fazem sentido com as opções atuais. */
export function camposVisiveis(ferramentaId: string, opcoes: Opcoes): Campo[] {
  return (CAMPOS[ferramentaId] ?? []).filter((campo) => !campo.quando || campo.quando(opcoes));
}

/** O formato de saída escolhido, ou o primeiro que a ferramenta produz. */
export function formatoDeSaida(ferramenta: Ferramenta, opcoes: Opcoes): FormatId {
  const escolhido = String(opcoes.formato ?? "");
  return (ferramenta.saidas.includes(escolhido as FormatId) ? escolhido : ferramenta.saidas[0]) as FormatId;
}

/** "mm:ss", "hh:mm:ss" ou segundos → segundos. Vazio devolve nulo. */
export function lerTempoEmSegundos(texto: string | number | boolean | undefined): number | null {
  const valor = String(texto ?? "").trim();
  if (!valor) return null;
  const partes = valor.replace(",", ".").split(":").map(Number);
  if (partes.some((parte) => !Number.isFinite(parte))) return null;
  let segundos = 0;
  for (const parte of partes) segundos = segundos * 60 + parte;
  return segundos >= 0 ? segundos : null;
}

/** "1-3, 7, 10-12" → [1,2,3,7,10,11,12], limitado ao total. Vazio = todas. */
export function lerPaginas(texto: string | number | boolean | undefined, total: number): number[] {
  const valor = String(texto ?? "").trim();
  if (!valor) return Array.from({ length: total }, (_, indice) => indice + 1);

  const paginas = new Set<number>();
  for (const trecho of valor.split(/[,;\s]+/)) {
    if (!trecho) continue;
    const faixa = trecho.match(/^(\d+)\s*-\s*(\d+)$/);
    if (faixa) {
      const de = Math.max(1, Number(faixa[1]));
      const ate = Math.min(total, Number(faixa[2]));
      for (let pagina = de; pagina <= ate; pagina += 1) paginas.add(pagina);
      continue;
    }
    const unica = Number(trecho);
    if (Number.isInteger(unica) && unica >= 1 && unica <= total) paginas.add(unica);
  }
  return [...paginas].sort((a, b) => a - b);
}
