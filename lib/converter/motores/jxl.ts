import criarEncoder from "@jsquash/jxl/codec/enc/jxl_enc";
import { defaultOptions } from "@jsquash/jxl/meta";
import { ConversionError } from "../protocol";

// O codec float usa vários buffers simultâneos antes de entregar o RGBA8.
const MAX_PIXELS = 16 * 1024 * 1024;

function conferirDimensoes(largura: number, altura: number) {
  if (largura < 1 || altura < 1 || largura * altura > MAX_PIXELS) {
    throw new ConversionError("jxl_memory_limit", "O limite de JPEG XL no navegador é de 16 megapixels.");
  }
}

function invalido(): never {
  throw new ConversionError("jxl_decode_failed", "O cabeçalho JPEG XL está incompleto ou inválido.");
}

function codestream(bytes: Uint8Array): Uint8Array {
  if (bytes[0] === 255 && bytes[1] === 10) return bytes;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (bytes.length < 12 || view.getUint32(0) !== 12 || view.getUint32(4) !== 0x4a584c20 ||
      view.getUint32(8) !== 0x0d0a870a) return invalido();
  for (let pos = 12; pos + 8 <= bytes.length;) {
    let tamanho = view.getUint32(pos);
    const tipo = view.getUint32(pos + 4);
    let inicio = pos + 8;
    if (tamanho === 0) tamanho = bytes.length - pos;
    else if (tamanho === 1) {
      if (inicio + 8 > bytes.length || view.getUint32(inicio) !== 0) return invalido();
      tamanho = view.getUint32(inicio + 4);
      inicio += 8;
    }
    const fim = pos + tamanho;
    if (fim < inicio || fim > bytes.length) return invalido();
    if (tipo === 0x6a786c70) { // jxlp: a primeira parte começa com um índice de 32 bits.
      if (fim - inicio < 4 || (view.getUint32(inicio) & 0x7fffffff) !== 0) return invalido();
      return bytes.subarray(inicio + 4, fim);
    }
    if (tipo === 0x6a786c63) return bytes.subarray(inicio, fim); // jxlc
    pos = fim;
  }
  return invalido();
}

function conferirCabecalho(bytes: Uint8Array) {
  const fluxo = codestream(bytes);
  if (fluxo[0] !== 255 || fluxo[1] !== 10) return invalido();
  let pos = 16;
  function bits(n: number) {
    if (pos + n > fluxo.length * 8) return invalido();
    let valor = 0;
    for (let i = 0; i < n; ++i, ++pos) valor += ((fluxo[pos >> 3] >> (pos % 8)) & 1) * 2 ** i;
    return valor;
  }
  function tamanho() {
    const pequeno = bits(1);
    const lado = () => pequeno ? (bits(5) + 1) * 8 : 1 + bits([9, 13, 18, 30][bits(2)]);
    const altura = lado();
    const razao = bits(3);
    const proporcoes = [[1, 1], [1, 1], [6, 5], [4, 3], [3, 2], [16, 9], [5, 4], [2, 1]];
    const [n, d] = proporcoes[razao];
    conferirDimensoes(razao ? Math.floor(altura * n / d) : lado(), altura);
  }
  tamanho();
  if (bits(1)) return; // Metadados padrão: 8 bits, sem animação.
  if (bits(1)) {
    bits(3); // orientação
    if (bits(1)) tamanho();
    if (bits(1) || bits(1)) throw new ConversionError("jxl_unsupported_feature",
      "Este leitor aceita JPEG XL estático sem prévia incorporada.");
  }
  const flutuante = bits(1);
  const classe = bits(2);
  const profundidade = classe === 3 ? 1 + bits(6) : [8, 10, 12][classe];
  if (flutuante || profundidade > 8) {
    throw new ConversionError("jxl_unsupported_feature", "Este leitor trabalha com JPEG XL de até 8 bits.");
  }
}

export async function decodificarJxl(buffer: ArrayBuffer) {
  conferirCabecalho(new Uint8Array(buffer));
  const { default: decode } = await import("@jsquash/jxl/decode");
  return decode(buffer);
}

export async function jxlDoCanvas(canvas: OffscreenCanvas, qualidade: number) {
  conferirDimensoes(canvas.width, canvas.height);
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new ConversionError("canvas_unavailable", "O navegador não disponibilizou o canvas.");
  return codificarJxl(ctx.getImageData(0, 0, canvas.width, canvas.height), qualidade);
}

// A oficina já distribui trabalhos entre Web Workers. Usar um encoder por
// worker evita iniciar uma segunda fila de threads a cada conversão.
let carregamento: ReturnType<typeof criarEncoder> | undefined;
export async function codificarJxl(imagem: ImageData, qualidade: number): Promise<ArrayBuffer> {
  conferirDimensoes(imagem.width, imagem.height);
  carregamento ??= criarEncoder({ noInitialRun: true }).catch((erro: unknown) => {
    carregamento = undefined;
    throw erro;
  });
  const codec = await carregamento;
  const bytes = codec.encode(imagem.data as Uint8ClampedArray<ArrayBuffer>, imagem.width, imagem.height,
    { ...defaultOptions, quality: qualidade, lossless: qualidade === 100 });
  if (!bytes) throw new ConversionError("jxl_encode_failed", "O codec JPEG XL não conseguiu gravar a imagem.");
  return bytes.slice().buffer;
}
