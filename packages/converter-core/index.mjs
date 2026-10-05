/**
 * Núcleo de conversão do site: catálogo de formatos, detecção de tipo por conteúdo e
 * validação de opções.
 *
 * Este módulo é puro de propósito — nada de DOM, Worker ou WASM. Assim ele roda
 * igual no navegador e no `node --test`, e é aqui que ficam as regras que não
 * podem quebrar: allowlist de formatos e limites de cada opção.
 */

/** Um formato reconhecido pelo produto. */
const FORMAT_LIST = [
  {
    id: "jpg",
    label: "JPG",
    kind: "image",
    mime: "image/jpeg",
    extensions: ["jpg", "jpeg", "jpe"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: true,
  },
  {
    id: "png",
    label: "PNG",
    kind: "image",
    mime: "image/png",
    extensions: ["png"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: true,
    supportsQuality: false,
  },
  {
    id: "webp",
    label: "WEBP",
    kind: "image",
    mime: "image/webp",
    extensions: ["webp"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: true,
    supportsQuality: true,
  },
  {
    id: "avif",
    label: "AVIF",
    kind: "image",
    mime: "image/avif",
    extensions: ["avif"],
    // Entra como origem pelo decodificador do navegador. Ainda não sai como destino:
    // o codificador AVIF disponível quebra dentro de um Web Worker (o código gerado
    // pelo emscripten depende de `window`) e derruba a conversão inteira junto.
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: true,
    supportsQuality: true,
  },
  // Entram como origem usando o decodificador do próprio navegador.
  {
    id: "gif",
    label: "GIF",
    kind: "image",
    mime: "image/gif",
    extensions: ["gif"],
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: true,
    supportsQuality: false,
  },
  {
    id: "bmp",
    label: "BMP",
    kind: "image",
    mime: "image/bmp",
    extensions: ["bmp"],
    canDecodeLocally: true,
    // BMP sem compressao e um cabecalho e os pixels: gravamos sem codec nenhum.
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "ico",
    label: "ICO",
    kind: "image",
    mime: "image/x-icon",
    extensions: ["ico"],
    canDecodeLocally: true,
    // ICO e um container: dentro vai um PNG, que ja sabemos gravar.
    canEncodeLocally: true,
    supportsAlpha: true,
    supportsQuality: false,
  },
  {
    id: "tiff",
    label: "TIFF",
    kind: "image",
    mime: "image/tiff",
    extensions: ["tif", "tiff"],
    // Nenhum navegador comum abre TIFF sozinho: o UTIF.js decodifica no worker.
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: true,
    supportsQuality: false,
  },
  {
    id: "heic",
    label: "HEIC",
    kind: "image",
    mime: "image/heic",
    extensions: ["heic", "heif"],
    // Só o Safari abre HEIC sozinho. Nos outros, o libheif (WebAssembly) decodifica no
    // worker, baixado só quando aparece um HEIC.
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: true,
    supportsQuality: false,
  },
  {
    id: "mp3",
    label: "MP3",
    kind: "audio",
    mime: "audio/mpeg",
    extensions: ["mp3"],
    // O navegador decodifica com os codecs que ele ja tem; a codificacao e nossa.
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "wav",
    label: "WAV",
    kind: "audio",
    mime: "audio/wav",
    extensions: ["wav", "wave"],
    canDecodeLocally: true,
    // PCM de 16 bits: e so escrever o cabecalho RIFF na frente das amostras.
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "m4a",
    label: "M4A",
    kind: "audio",
    mime: "audio/mp4",
    extensions: ["m4a", "m4b", "aac"],
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "ogg",
    label: "OGG",
    kind: "audio",
    mime: "audio/ogg",
    extensions: ["ogg", "oga", "opus"],
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "flac",
    label: "FLAC",
    kind: "audio",
    mime: "audio/flac",
    extensions: ["flac"],
    canDecodeLocally: true,
    canEncodeLocally: false,
    supportsAlpha: false,
    supportsQuality: false,
  },
  // ==================== video ====================
  // O navegador le e grava video com os codecs que ja tem (WebCodecs) e o mediabunny
  // cuida dos containers. Quando o navegador nao tem WebCodecs, o plugin assume.
  {
    id: "mp4",
    label: "MP4",
    kind: "video",
    mime: "video/mp4",
    extensions: ["mp4", "m4v"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: true,
  },
  {
    id: "webm",
    label: "WEBM",
    kind: "video",
    mime: "video/webm",
    extensions: ["webm"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: true,
  },
  {
    id: "mkv",
    label: "MKV",
    kind: "video",
    mime: "video/x-matroska",
    extensions: ["mkv"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: true,
  },
  {
    id: "mov",
    label: "MOV",
    kind: "video",
    mime: "video/quicktime",
    extensions: ["mov", "qt"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: true,
  },
  {
    id: "avi",
    label: "AVI",
    kind: "video",
    mime: "video/x-msvideo",
    extensions: ["avi"],
    // AVI antigo costuma trazer codecs que o navegador nao decodifica; fica para o plugin.
    canDecodeLocally: false,
    canEncodeLocally: false,
    supportsAlpha: false,
    supportsQuality: false,
  },
  // ==================== documentos ====================
  {
    id: "pdf",
    label: "PDF",
    kind: "pdf",
    mime: "application/pdf",
    extensions: ["pdf"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "srt",
    label: "SRT",
    kind: "legenda",
    mime: "application/x-subrip",
    extensions: ["srt"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "vtt",
    label: "VTT",
    kind: "legenda",
    mime: "text/vtt",
    extensions: ["vtt"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "txt",
    label: "TXT",
    kind: "texto",
    mime: "text/plain",
    extensions: ["txt"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "md",
    label: "Markdown",
    kind: "texto",
    mime: "text/markdown",
    extensions: ["md", "markdown"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "docx",
    label: "DOCX",
    kind: "documento",
    mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    extensions: ["docx"],
    canDecodeLocally: false,
    canEncodeLocally: false,
    supportsAlpha: false,
    supportsQuality: false,
  },
  {
    id: "html",
    label: "HTML",
    kind: "texto",
    mime: "text/html",
    extensions: ["html", "htm"],
    canDecodeLocally: true,
    canEncodeLocally: true,
    supportsAlpha: false,
    supportsQuality: false,
  },
];

export const FORMATS = Object.freeze(FORMAT_LIST.map((format) => Object.freeze(format)));

export const FORMAT_REGISTRY = Object.freeze(
  Object.fromEntries(FORMATS.map((format) => [format.id, format])),
);

/** Formatos que o produto aceita como saída de imagem. */
export const IMAGE_OUTPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "image" && format.canEncodeLocally).map((format) => format.id),
);

/** Formatos de imagem aceitos como entrada. */
export const IMAGE_INPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "image").map((format) => format.id),
);

/**
 * Saidas de audio que o navegador consegue produzir sozinho. So MP3: decodificar e
 * de graca (o navegador ja tem os codecs), mas codificar exige um encoder nosso, e
 * carregar um por formato encheria a pagina de peso morto.
 */
export const AUDIO_OUTPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "audio" && format.canEncodeLocally).map((format) => format.id),
);

/** Formatos de audio aceitos como entrada. */
export const AUDIO_INPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "audio").map((format) => format.id),
);

/** Bitrates oferecidos, em kbps. Fora desta lista nao vira job. */
export const AUDIO_BITRATES = Object.freeze([64, 96, 128, 160, 192, 256, 320]);

export const RESIZE_MODES = Object.freeze(["none", "width", "height", "percent", "exact"]);
export const FIT_MODES = Object.freeze(["cover", "contain", "stretch"]);

export const LIMITS = Object.freeze({
  minDimension: 1,
  maxDimension: 20000,
  minPercent: 1,
  maxPercent: 1000,
  minQuality: 1,
  maxQuality: 100,
  /** Acima disso o navegador costuma ficar instável; a interface avisa antes. */
  warnBytes: 250 * 1024 * 1024,
});

export const ERRORS = Object.freeze({
  UNSUPPORTED_INPUT: "unsupported_input",
  UNSUPPORTED_OUTPUT: "unsupported_output",
  INVALID_QUALITY: "invalid_quality",
  INVALID_RESIZE: "invalid_resize",
  INVALID_FIT: "invalid_fit",
  INVALID_COLOR: "invalid_color",
  DECODE_UNAVAILABLE: "decode_unavailable",
  INVALID_BITRATE: "invalid_bitrate",
});

/** Assinaturas de arquivo. A extensão nunca decide sozinha o que é o arquivo. */
const SIGNATURES = [
  { id: "jpg", offset: 0, bytes: [0xff, 0xd8, 0xff] },
  { id: "png", offset: 0, bytes: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a] },
  { id: "gif", offset: 0, text: "GIF87a" },
  { id: "gif", offset: 0, text: "GIF89a" },
  { id: "bmp", offset: 0, text: "BM" },
  { id: "tiff", offset: 0, bytes: [0x49, 0x49, 0x2a, 0x00] },
  { id: "tiff", offset: 0, bytes: [0x4d, 0x4d, 0x00, 0x2a] },
  { id: "ico", offset: 0, bytes: [0x00, 0x00, 0x01, 0x00] },
  { id: "flac", offset: 0, text: "fLaC" },
  { id: "ogg", offset: 0, text: "OggS" },
  // MP3 aparece de duas formas: com tag ID3 na frente, ou direto no primeiro quadro.
  { id: "mp3", offset: 0, text: "ID3" },
  { id: "mp3", offset: 0, bytes: [0xff, 0xfb] },
  { id: "mp3", offset: 0, bytes: [0xff, 0xf3] },
  { id: "mp3", offset: 0, bytes: [0xff, 0xf2] },
  { id: "mp3", offset: 0, bytes: [0xff, 0xfa] },
  { id: "pdf", offset: 0, text: "%PDF" },
  { id: "vtt", offset: 0, text: "WEBVTT" },
];

/** Marcas da caixa ftyp que identificam video MP4/MOV. */
const FTYP_VIDEO = Object.freeze({
  mov: ["qt  "],
  mp4: ["isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "m4v ", "mmp4", "3gp4", "3gp5", "hvc1", "av01"],
});

/** Marcas dentro da caixa ftyp, usada por AVIF e HEIC. */
const FTYP_BRANDS = Object.freeze({
  avif: ["avif", "avis"],
  // "mp42" e "isom" ficam de fora: video MP4 tambem os usa, e confundir os dois
  // faria o site prometer audio para um arquivo de video.
  m4a: ["m4a ", "m4b "],
  heic: ["heic", "heix", "hevc", "hevx", "mif1", "msf1", "heif"],
});

function matchesAt(view, offset, bytes) {
  if (offset + bytes.length > view.length) return false;
  for (let index = 0; index < bytes.length; index += 1) {
    if (view[offset + index] !== bytes[index]) return false;
  }
  return true;
}

function textAt(view, offset, length) {
  if (offset + length > view.length) return "";
  let result = "";
  for (let index = 0; index < length; index += 1) {
    result += String.fromCharCode(view[offset + index]);
  }
  return result;
}

/**
 * Descobre o formato pelo conteúdo do arquivo.
 * Devolve o id do formato ou `null` quando não reconhece.
 */
export function detectFormat(bytes) {
  if (!bytes || bytes.length < 4) return null;
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);

  for (const signature of SIGNATURES) {
    if (signature.bytes && matchesAt(view, signature.offset, signature.bytes)) return signature.id;
    if (signature.text) {
      const expected = [...signature.text].map((character) => character.charCodeAt(0));
      if (matchesAt(view, signature.offset, expected)) return signature.id;
    }
  }

  // RIFF serve aos dois: o que decide e a marca do oitavo byte em diante.
  if (textAt(view, 0, 4) === "RIFF") {
    const marca = textAt(view, 8, 4);
    if (marca === "WEBP") return "webp";
    if (marca === "WAVE") return "wav";
  }

  // RIFF de video.
  if (textAt(view, 0, 4) === "RIFF" && textAt(view, 8, 4) === "AVI ") return "avi";

  // Caixa ISO-BMFF: tamanho, "ftyp", marca.
  if (textAt(view, 4, 4) === "ftyp") {
    const brand = textAt(view, 8, 4).toLowerCase();
    for (const [id, brands] of Object.entries(FTYP_BRANDS)) {
      if (brands.includes(brand)) return id;
    }
    for (const [id, brands] of Object.entries(FTYP_VIDEO)) {
      if (brands.includes(brand)) return id;
    }
    // Qualquer outra marca ftyp que nao seja imagem nem audio e, na pratica, video MP4.
    return "mp4";
  }

  // EBML: MKV e WEBM tem o mesmo cabecalho; o DocType dentro dele diz qual dos dois.
  if (matchesAt(view, 0, [0x1a, 0x45, 0xdf, 0xa3])) {
    const cabecalho = textAt(view, 0, Math.min(view.length, 64));
    if (cabecalho.includes("webm")) return "webm";
    return "mkv";
  }

  // Legenda SRT: comeca com o numero 1 e um tempo "00:00:00,000".
  if (/^\ufeff?1\r?\n\d\d:\d\d:\d\d,\d{3}/.test(textAt(view, 0, Math.min(view.length, 24)))) {
    return "srt";
  }

  return null;
}

/** Formato deduzido do nome do arquivo. Serve de apoio, nunca de prova. */
export function formatFromFileName(fileName) {
  const match = /\.([a-z0-9]{1,8})$/i.exec(fileName ?? "");
  if (!match) return null;
  const extension = match[1].toLowerCase();
  const found = FORMATS.find((format) => format.extensions.includes(extension));
  return found ? found.id : null;
}

export function getFormat(id) {
  return FORMAT_REGISTRY[id] ?? null;
}

export function isImageFormat(id) {
  const format = getFormat(id);
  return Boolean(format) && format.kind === "image";
}

export function isAudioFormat(id) {
  const format = getFormat(id);
  return Boolean(format) && format.kind === "audio";
}

export function isVideoFormat(id) {
  const format = getFormat(id);
  return Boolean(format) && format.kind === "video";
}

export function isPdfFormat(id) {
  return getFormat(id)?.kind === "pdf";
}

export function isSubtitleFormat(id) {
  return getFormat(id)?.kind === "legenda";
}

export function isTextFormat(id) {
  const kind = getFormat(id)?.kind;
  return kind === "texto" || kind === "legenda";
}

/** Tipo de midia do formato: "image", "audio", "video", "pdf", "legenda", "texto", "documento" ou null. */
export function kindOf(id) {
  return getFormat(id)?.kind ?? null;
}

/** Formatos de video aceitos como saida no navegador. */
export const VIDEO_OUTPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "video" && format.canEncodeLocally).map((format) => format.id),
);

/** Formatos de video aceitos como entrada. */
export const VIDEO_INPUT_FORMATS = Object.freeze(
  FORMATS.filter((format) => format.kind === "video").map((format) => format.id),
);

/** Sugere um destino útil: WEBP para tudo, e JPG quando a origem já é WEBP. */
export function suggestOutputFormat(inputFormat) {
  if (inputFormat === "webp") return "jpg";
  if (inputFormat === "png") return "webp";
  return "webp";
}

export const DEFAULT_IMAGE_OPTIONS = Object.freeze({
  format: "webp",
  quality: 82,
  resizeMode: "none",
  width: null,
  height: null,
  percent: null,
  fit: "cover",
  background: "#ffffff",
  keepTransparency: true,
});

/**
 * Taxas que o MP3 aceita. Fora desta lista o encoder nao tem como gravar, entao
 * reamostrar deixa de ser perda de fidelidade e passa a ser a unica saida.
 */
export const MP3_SAMPLE_RATES = Object.freeze([
  8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000,
]);

const MP3_RATES_BY_VERSION = Object.freeze({
  // Os bits de versao do quadro: 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5.
  3: [44100, 48000, 32000],
  2: [22050, 24000, 16000],
  0: [11025, 12000, 8000],
});

function u32le(view, offset) {
  return view[offset] | (view[offset + 1] << 8) | (view[offset + 2] << 16) | (view[offset + 3] << 24);
}

/** Percorre os blocos do WAV ate achar o "fmt ". A ordem deles nao e garantida. */
function taxaDoWav(view) {
  let posicao = 12;
  while (posicao + 8 <= view.length) {
    const bloco = textAt(view, posicao, 4);
    const tamanho = u32le(view, posicao + 4);
    if (bloco === "fmt ") return u32le(view, posicao + 12) || null;
    if (tamanho <= 0) return null;
    posicao += 8 + tamanho + (tamanho % 2);
  }
  return null;
}

/** STREAMINFO do FLAC: 20 bits de taxa, depois dos tamanhos de bloco e de quadro. */
function taxaDoFlac(view) {
  const inicio = 4 + 4 + 10;
  if (inicio + 3 > view.length) return null;
  const taxa = (view[inicio] << 12) | (view[inicio + 1] << 4) | (view[inicio + 2] >> 4);
  return taxa > 0 ? taxa : null;
}

/** Primeiro quadro do MP3, pulando a tag ID3v2 quando existir. */
function taxaDoMp3(view) {
  let posicao = 0;
  if (textAt(view, 0, 3) === "ID3" && view.length > 10) {
    // O tamanho da tag vem em sete bits por byte, com o oitavo sempre zero.
    const tamanho =
      (view[6] << 21) | (view[7] << 14) | (view[8] << 7) | view[9];
    posicao = 10 + tamanho;
  }

  const limite = Math.min(view.length - 4, posicao + 8192);
  for (; posicao <= limite; posicao += 1) {
    if (view[posicao] !== 0xff || (view[posicao + 1] & 0xe0) !== 0xe0) continue;

    const versao = (view[posicao + 1] >> 3) & 0x03;
    const indice = (view[posicao + 2] >> 2) & 0x03;
    const tabela = MP3_RATES_BY_VERSION[versao];
    if (tabela && indice < 3) return tabela[indice];
  }

  return null;
}

/**
 * Cabecalho de identificacao dentro da primeira pagina Ogg.
 * Opus sempre entrega 48 kHz na saida, independente do que diz o cabecalho.
 */
function taxaDoOgg(view) {
  if (view.length < 28) return null;
  const segmentos = view[26];
  const pacote = 27 + segmentos;
  if (pacote + 16 > view.length) return null;

  if (textAt(view, pacote, 8) === "OpusHead") return 48000;
  if (textAt(view, pacote + 1, 6) === "vorbis") return u32le(view, pacote + 12) || null;
  return null;
}

/**
 * Taxa de amostragem original do arquivo, lida do cabecalho.
 *
 * Existe por causa de um detalhe do navegador: `decodeAudioData` reamostra para a taxa
 * do AudioContext, que costuma ser 48 kHz. Sem saber a taxa de origem antes de
 * decodificar, um MP3 de 44,1 kHz sairia convertido em 48 kHz sem ninguem pedir.
 *
 * Devolve nulo quando nao da para saber -- M4A exigiria percorrer as caixas do MP4, e
 * nesse caso vale mais deixar o navegador decidir do que arriscar um palpite errado.
 */
export function sampleRateDoArquivo(bytes, formato) {
  if (!bytes) return null;
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (view.length < 32) return null;

  switch (formato) {
    case "wav":
      return taxaDoWav(view);
    case "flac":
      return taxaDoFlac(view);
    case "mp3":
      return taxaDoMp3(view);
    case "ogg":
      return taxaDoOgg(view);
    default:
      return null;
  }
}

/**
 * A taxa em que vale a pena decodificar: a do arquivo, quando o MP3 a aceita.
 * Quando nao aceita (96 kHz, por exemplo), reamostrar e obrigatorio, e 48 kHz e a
 * mais alta que o formato permite.
 */
export function sampleRateParaMp3(taxaDeOrigem) {
  if (!taxaDeOrigem || !Number.isFinite(taxaDeOrigem)) return null;
  return MP3_SAMPLE_RATES.includes(taxaDeOrigem) ? taxaDeOrigem : 48000;
}

export const DEFAULT_AUDIO_OPTIONS = Object.freeze({
  format: "mp3",
  /** kbps. 192 e o ponto em que a maioria para de ouvir diferenca. */
  bitrate: 192,
});

function fail(code, message) {
  return { ok: false, code, message };
}

/**
 * Valida as opcoes de conversao de audio.
 * Devolve `{ ok: true, options }` normalizado ou `{ ok: false, code, message }`.
 */
export function validateAudioOptions(input) {
  const options = { ...DEFAULT_AUDIO_OPTIONS, ...(input ?? {}) };

  const destino = getFormat(options.format);
  if (!destino || destino.kind !== "audio" || !destino.canEncodeLocally) {
    return fail(ERRORS.UNSUPPORTED_OUTPUT, "Este formato de audio nao pode ser gerado no navegador.");
  }

  const bitrate = Number(options.bitrate);
  if (!AUDIO_BITRATES.includes(bitrate)) {
    return fail(ERRORS.INVALID_BITRATE, `Bitrate deve ser um de: ${AUDIO_BITRATES.join(", ")} kbps.`);
  }

  return { ok: true, options: Object.freeze({ format: destino.id, bitrate }) };
}

/**
 * Valida as opções de conversão de imagem.
 * Devolve `{ ok: true, options }` já normalizado ou `{ ok: false, code, message }`.
 */
export function validateImageOptions(input) {
  const options = { ...DEFAULT_IMAGE_OPTIONS, ...(input ?? {}) };

  if (!IMAGE_OUTPUT_FORMATS.includes(options.format)) {
    return fail(ERRORS.UNSUPPORTED_OUTPUT, `Formato de saída não suportado: ${options.format}.`);
  }

  const quality = Number(options.quality);
  if (!Number.isFinite(quality) || quality < LIMITS.minQuality || quality > LIMITS.maxQuality) {
    return fail(ERRORS.INVALID_QUALITY, "A qualidade deve ficar entre 1 e 100.");
  }

  if (!RESIZE_MODES.includes(options.resizeMode)) {
    return fail(ERRORS.INVALID_RESIZE, "Modo de redimensionamento inválido.");
  }

  if (!FIT_MODES.includes(options.fit)) {
    return fail(ERRORS.INVALID_FIT, "Modo de ajuste inválido.");
  }

  if (!/^#[0-9a-f]{6}$/i.test(options.background)) {
    return fail(ERRORS.INVALID_COLOR, "A cor de fundo deve estar no formato #RRGGBB.");
  }

  const dimension = (value, name) => {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < LIMITS.minDimension || parsed > LIMITS.maxDimension) {
      return fail(ERRORS.INVALID_RESIZE, `${name} deve ficar entre 1 e ${LIMITS.maxDimension} pixels.`);
    }
    return null;
  };

  if (options.resizeMode === "width") {
    const error = dimension(options.width, "A largura");
    if (error) return error;
  }

  if (options.resizeMode === "height") {
    const error = dimension(options.height, "A altura");
    if (error) return error;
  }

  if (options.resizeMode === "exact") {
    const widthError = dimension(options.width, "A largura");
    if (widthError) return widthError;
    const heightError = dimension(options.height, "A altura");
    if (heightError) return heightError;
  }

  if (options.resizeMode === "percent") {
    const percent = Number(options.percent);
    if (!Number.isFinite(percent) || percent < LIMITS.minPercent || percent > LIMITS.maxPercent) {
      return fail(ERRORS.INVALID_RESIZE, "A porcentagem deve ficar entre 1 e 1000.");
    }
  }

  return {
    ok: true,
    options: Object.freeze({
      ...options,
      quality: Math.round(quality),
      width: options.width == null ? null : Number(options.width),
      height: options.height == null ? null : Number(options.height),
      percent: options.percent == null ? null : Number(options.percent),
    }),
  };
}

/**
 * Tamanho final da imagem a partir das dimensões de origem e das opções.
 * Devolve `null` quando não há redimensionamento a fazer.
 */
export function resolveTargetSize(sourceWidth, sourceHeight, options) {
  if (!sourceWidth || !sourceHeight) return null;

  switch (options.resizeMode) {
    case "width": {
      const width = options.width;
      return { width, height: Math.max(1, Math.round((sourceHeight * width) / sourceWidth)) };
    }
    case "height": {
      const height = options.height;
      return { width: Math.max(1, Math.round((sourceWidth * height) / sourceHeight)), height };
    }
    case "exact":
      return { width: options.width, height: options.height };
    case "percent": {
      const factor = options.percent / 100;
      return {
        width: Math.max(1, Math.round(sourceWidth * factor)),
        height: Math.max(1, Math.round(sourceHeight * factor)),
      };
    }
    default:
      return null;
  }
}

/** Nome do arquivo de saída, preservando o nome original e trocando a extensão. */
export function outputFileName(inputName, outputFormat) {
  const base = String(inputName ?? "arquivo").replace(/\.[^./\\]+$/, "") || "arquivo";
  const safe = base.replace(/[\\/:*?"<>|]/g, "_").slice(0, 120);
  return `${safe}.${outputFormat}`;
}

/** Tamanho legível, usado na interface e nos relatórios. */
export function formatBytes(bytes) {
  const value = Number(bytes) || 0;
  if (value < 1024) return `${value} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = value / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${size.toFixed(size >= 100 ? 0 : 1).replace(".", ",")} ${units[unit]}`;
}

/** Quanto o arquivo encolheu, em porcentagem. Negativo significa que cresceu. */
export function savingsPercent(inputBytes, outputBytes) {
  if (!inputBytes) return 0;
  return Math.round(((inputBytes - outputBytes) / inputBytes) * 100);
}
