/**
 * Comprimir PDF sem servidor.
 *
 * O que pesa num PDF quase sempre são as fotos e as páginas escaneadas. Então:
 *
 * 1. Cada foto (JPEG ou pixels crus em Flate) é recomprimida em JPEG e, se for maior do
 *    que a página precisa, reduzida. Quem recomprime é uma função recebida de fora —
 *    no navegador, o canvas; nos testes, uma função falsa. Só troca quando fica menor.
 * 2. Figura que não parece foto (poucas cores: gráfico, logotipo, print) fica como está:
 *    JPEG estraga linha e letra.
 * 3. Streams sem compressão nenhuma (páginas, fontes) ganham Flate.
 * 4. Objetos que nada mais usa — sobras de edições anteriores — saem.
 * 5. O arquivo é regravado com object streams, que encolhem a tabela de objetos.
 *
 * O texto continua texto: nada é rasterizado. Máscaras (SMask) e imagens com
 * transparência por cor ficam intocadas, porque mudar a máscara muda o desenho.
 *
 * Módulo puro: pdf-lib e fflate, sem DOM. Roda no Worker e no `node --test`.
 */

import { PDFArray, PDFDict, PDFDocument, PDFName, PDFNumber, PDFRawStream, PDFRef, PDFStream, decodePDFRawStream } from "pdf-lib";
import { zlibSync } from "fflate";

export class PdfNaoCompactavel extends Error {}

export const NIVEIS = Object.freeze({
  leve: Object.freeze({ ladoMaximo: 3000, qualidade: 0.85, fotosEmFlate: false }),
  equilibrado: Object.freeze({ ladoMaximo: 2000, qualidade: 0.72, fotosEmFlate: true }),
  forte: Object.freeze({ ladoMaximo: 1400, qualidade: 0.55, fotosEmFlate: true }),
});

/** Só troca a imagem quando a nova tem, no máximo, esta fração do tamanho da antiga. */
const GANHO_MINIMO = 0.9;
/** Imagem menor que isto (em pixels) não vale o trabalho. */
const AREA_MINIMA = 64 * 64;
/** Stream sem compressão menor que isto fica como está. */
const STREAM_MINIMO = 512;

const N = (nome) => PDFName.of(nome);

/**
 * `recomprimir(entrada)` recebe `{ tipo: "jpeg", bytes }` ou `{ tipo: "pixels", dados,
 * componentes }`, mais `largura`, `altura`, `alvoLargura`, `alvoAltura` e `qualidade`, e
 * devolve `{ bytes, largura, altura }` de um JPEG RGB — ou nulo para desistir daquela.
 */
export async function compactarPdf(bytes, { nivel = "equilibrado", recomprimir, progresso = () => {}, cancelado = () => false } = {}) {
  const ajuste = NIVEIS[nivel] ?? NIVEIS.equilibrado;
  const original = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);

  let documento;
  try {
    documento = await PDFDocument.load(original, { ignoreEncryption: true, updateMetadata: false });
  } catch {
    throw new PdfNaoCompactavel("Este arquivo não pôde ser lido como PDF.");
  }
  if (documento.isEncrypted) {
    throw new PdfNaoCompactavel("Este PDF é protegido (tem senha ou restrição de edição). Remova a proteção antes de comprimir.");
  }

  const contexto = documento.context;
  const orfaos = removerOrfaos(contexto);
  const mascaras = referenciasDeMascara(contexto);

  const imagens = contexto
    .enumerateIndirectObjects()
    .filter(([ref, objeto]) => objeto instanceof PDFRawStream && ehImagem(objeto.dict) && !mascaras.has(ref.tag));

  let trocadas = 0;
  for (const [indice, [ref, stream]] of imagens.entries()) {
    if (cancelado()) return null;
    progresso(indice / Math.max(1, imagens.length));
    const nova = await recomprimirImagem(stream, ajuste, recomprimir);
    if (nova) {
      contexto.assign(ref, nova);
      trocadas += 1;
    }
  }

  const comprimidos = comprimirStreamsCruas(contexto);

  progresso(1);
  const gravado = await documento.save({ useObjectStreams: true, addDefaultPage: false, updateFieldAppearances: false });
  const menor = gravado.length < original.length;
  return {
    bytes: menor ? gravado : original,
    antes: original.length,
    depois: menor ? gravado.length : original.length,
    imagens: trocadas,
    streams: comprimidos,
    orfaos,
    mudou: menor,
  };
}

function ehImagem(dicionario) {
  return dicionario.get(N("Subtype")) === N("Image");
}

/** Tudo o que é usado como máscara de outra imagem: não pode virar JPEG colorido. */
function referenciasDeMascara(contexto) {
  const usadas = new Set();
  for (const [, objeto] of contexto.enumerateIndirectObjects()) {
    if (!(objeto instanceof PDFStream) || !ehImagem(objeto.dict)) continue;
    for (const chave of ["SMask", "Mask"]) {
      const valor = objeto.dict.get(N(chave));
      if (valor instanceof PDFRef) usadas.add(valor.tag);
    }
  }
  return usadas;
}

/** Nome único do filtro, ou nulo quando há cadeia de filtros (não mexemos). */
function filtroUnico(dicionario) {
  const filtro = dicionario.get(N("Filter"));
  if (filtro instanceof PDFName) return filtro.asString().slice(1);
  if (filtro instanceof PDFArray && filtro.size() === 1) {
    const unico = filtro.get(0);
    return unico instanceof PDFName ? unico.asString().slice(1) : null;
  }
  return filtro === undefined ? "" : null;
}

function numero(contexto, valor) {
  const resolvido = valor instanceof PDFRef ? contexto.lookup(valor) : valor;
  return resolvido instanceof PDFNumber ? resolvido.asNumber() : null;
}

/** Componentes de cor que o JPEG do canvas consegue representar: 1 ou 3. Nulo para o resto. */
function componentesDoEspaco(contexto, valor) {
  const espaco = valor instanceof PDFRef ? contexto.lookup(valor) : valor;
  if (espaco === N("DeviceRGB") || espaco === N("CalRGB")) return { componentes: 3, manter: true };
  if (espaco === N("DeviceGray") || espaco === N("CalGray")) return { componentes: 1, manter: false };
  if (espaco instanceof PDFArray && espaco.size() >= 2) {
    const familia = espaco.get(0);
    if (familia === N("CalRGB")) return { componentes: 3, manter: true };
    if (familia === N("CalGray")) return { componentes: 1, manter: false };
    if (familia === N("ICCBased")) {
      const perfil = contexto.lookup(espaco.get(1));
      const n = perfil instanceof PDFStream ? numero(contexto, perfil.dict.get(N("N"))) : null;
      if (n === 3) return { componentes: 3, manter: true };
      if (n === 1) return { componentes: 1, manter: false };
    }
  }
  return null; // CMYK, Indexed, Lab, Separation, DeviceN: fica como está.
}

async function recomprimirImagem(stream, ajuste, recomprimir) {
  const { dict } = stream;
  const contexto = dict.context;

  if (dict.get(N("ImageMask")) !== undefined && String(dict.get(N("ImageMask"))) === "true") return null;
  if (dict.get(N("Decode")) !== undefined) return null;
  if (dict.get(N("Mask")) instanceof PDFArray) return null; // transparência por faixa de cor

  const largura = numero(contexto, dict.get(N("Width")));
  const altura = numero(contexto, dict.get(N("Height")));
  if (!largura || !altura || largura * altura < AREA_MINIMA) return null;

  const cor = componentesDoEspaco(contexto, dict.get(N("ColorSpace")));
  if (!cor) return null;

  const filtro = filtroUnico(dict);
  const comMascara = dict.get(N("SMask")) !== undefined || dict.get(N("Mask")) !== undefined;
  const escala = comMascara ? 1 : Math.min(1, ajuste.ladoMaximo / Math.max(largura, altura));
  const alvoLargura = Math.max(1, Math.round(largura * escala));
  const alvoAltura = Math.max(1, Math.round(altura * escala));

  let entrada;
  if (filtro === "DCTDecode") {
    entrada = { tipo: "jpeg", bytes: stream.contents };
  } else if (filtro === "FlateDecode" && ajuste.fotosEmFlate) {
    if (numero(contexto, dict.get(N("BitsPerComponent"))) !== 8) return null;
    const dados = pixelsDoFlate(stream, largura, altura, cor.componentes);
    if (!dados || !pareceFoto(dados, cor.componentes)) return null;
    entrada = { tipo: "pixels", dados, componentes: cor.componentes };
  } else {
    return null;
  }

  let nova;
  try {
    nova = await recomprimir({ ...entrada, largura, altura, alvoLargura, alvoAltura, qualidade: ajuste.qualidade });
  } catch {
    return null;
  }
  if (!nova || !nova.bytes || nova.bytes.length > stream.contents.length * GANHO_MINIMO) return null;

  const novoDicionario = PDFDict.withContext(contexto);
  for (const [chave, valor] of dict.entries()) {
    if (["Filter", "DecodeParms", "Length", "Width", "Height", "BitsPerComponent", "ColorSpace"].includes(chave.asString().slice(1))) continue;
    novoDicionario.set(chave, valor);
  }
  novoDicionario.set(N("Filter"), N("DCTDecode"));
  novoDicionario.set(N("Width"), PDFNumber.of(nova.largura));
  novoDicionario.set(N("Height"), PDFNumber.of(nova.altura));
  novoDicionario.set(N("BitsPerComponent"), PDFNumber.of(8));
  novoDicionario.set(N("ColorSpace"), cor.manter ? dict.get(N("ColorSpace")) : N("DeviceRGB"));
  return PDFRawStream.of(novoDicionario, nova.bytes);
}

/** Pixels crus de uma imagem em Flate, já sem o preditor PNG. Nulo se não bater o tamanho. */
function pixelsDoFlate(stream, largura, altura, componentes) {
  let bruto;
  try {
    bruto = decodePDFRawStream(stream).decode();
  } catch {
    return null;
  }

  const contexto = stream.dict.context;
  let parametros = stream.dict.lookup(N("DecodeParms"));
  if (parametros instanceof PDFArray) parametros = parametros.size() === 1 ? contexto.lookup(parametros.get(0)) : null;
  const preditor = parametros instanceof PDFDict ? numero(contexto, parametros.get(N("Predictor"))) ?? 1 : 1;
  const porLinha = largura * componentes;

  if (preditor >= 10) {
    const cores = parametros instanceof PDFDict ? numero(contexto, parametros.get(N("Colors"))) ?? 1 : 1;
    const colunas = parametros instanceof PDFDict ? numero(contexto, parametros.get(N("Columns"))) ?? 1 : 1;
    if (cores !== componentes || colunas !== largura) return null;
    return desfazerPreditorPng(bruto, porLinha, componentes, altura);
  }
  if (preditor !== 1) return null; // preditor TIFF: raro, fica como está
  return bruto.length >= porLinha * altura ? bruto.subarray(0, porLinha * altura) : null;
}

/**
 * Desfaz o preditor PNG (filtros None, Sub, Up, Average e Paeth, um por linha).
 * Nulo se os dados acabarem antes das linhas.
 */
export function desfazerPreditorPng(dados, porLinha, bytesPorPixel, linhas) {
  if (dados.length < (porLinha + 1) * linhas) return null;
  const saida = new Uint8Array(porLinha * linhas);
  for (let linha = 0; linha < linhas; linha += 1) {
    const tipo = dados[linha * (porLinha + 1)];
    const origem = linha * (porLinha + 1) + 1;
    const destino = linha * porLinha;
    const acima = linha > 0 ? destino - porLinha : -1;
    for (let i = 0; i < porLinha; i += 1) {
      const x = dados[origem + i];
      const a = i >= bytesPorPixel ? saida[destino + i - bytesPorPixel] : 0;
      const b = acima >= 0 ? saida[acima + i] : 0;
      const c = acima >= 0 && i >= bytesPorPixel ? saida[acima + i - bytesPorPixel] : 0;
      let valor;
      switch (tipo) {
        case 0: valor = x; break;
        case 1: valor = x + a; break;
        case 2: valor = x + b; break;
        case 3: valor = x + ((a + b) >> 1); break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          valor = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
          break;
        }
        default: return null;
      }
      saida[destino + i] = valor & 0xff;
    }
  }
  return saida;
}

/**
 * Foto tem muitas cores; gráfico, logotipo e print têm poucas. Olha uma amostra
 * espalhada pela imagem e conta as cores distintas.
 */
export function pareceFoto(dados, componentes) {
  const pixels = Math.floor(dados.length / componentes);
  const amostras = Math.min(4096, pixels);
  const passo = Math.max(1, Math.floor(pixels / amostras));
  const cores = new Set();
  for (let pixel = 0; pixel < pixels && cores.size <= 512; pixel += passo) {
    const i = pixel * componentes;
    cores.add(componentes === 1 ? dados[i] : (dados[i] << 16) | (dados[i + 1] << 8) | dados[i + 2]);
  }
  // Em cinza cabem só 256 tons: basta ter a maior parte deles.
  return componentes === 1 ? cores.size > 160 : cores.size > 512;
}

/** Streams gravados sem compressão (páginas, fontes) ganham Flate. Metadados XMP ficam legíveis. */
function comprimirStreamsCruas(contexto) {
  let comprimidos = 0;
  for (const [ref, objeto] of contexto.enumerateIndirectObjects()) {
    if (!(objeto instanceof PDFRawStream)) continue;
    const { dict, contents } = objeto;
    if (dict.get(N("Filter")) !== undefined || dict.get(N("DecodeParms")) !== undefined) continue;
    if (dict.get(N("Type")) === N("Metadata") || contents.length < STREAM_MINIMO) continue;
    const comprimido = zlibSync(contents, { level: 9 });
    if (comprimido.length >= contents.length * GANHO_MINIMO) continue;
    const novo = PDFDict.withContext(contexto);
    for (const [chave, valor] of dict.entries()) if (chave !== N("Length")) novo.set(chave, valor);
    novo.set(N("Filter"), N("FlateDecode"));
    contexto.assign(ref, PDFRawStream.of(novo, comprimido));
    comprimidos += 1;
  }
  return comprimidos;
}

/**
 * Remove os objetos que nada alcança a partir do catálogo e das informações do
 * documento: versões antigas de páginas, imagens apagadas numa edição. Devolve quantos.
 */
function removerOrfaos(contexto) {
  const alcancados = new Set();
  const pilha = [contexto.trailerInfo.Root, contexto.trailerInfo.Info].filter(Boolean);

  while (pilha.length > 0) {
    const atual = pilha.pop();
    if (atual instanceof PDFRef) {
      if (alcancados.has(atual.tag)) continue;
      alcancados.add(atual.tag);
      const alvo = contexto.lookup(atual);
      if (alvo) pilha.push(alvo);
    } else if (atual instanceof PDFDict) {
      for (const [, valor] of atual.entries()) pilha.push(valor);
    } else if (atual instanceof PDFArray) {
      for (let i = 0; i < atual.size(); i += 1) pilha.push(atual.get(i));
    } else if (atual instanceof PDFStream) {
      pilha.push(atual.dict);
    }
  }

  // Sem catálogo alcançável, não arriscamos apagar nada.
  if (alcancados.size === 0) return 0;

  let removidos = 0;
  for (const [ref] of contexto.enumerateIndirectObjects()) {
    if (!alcancados.has(ref.tag)) {
      contexto.delete(ref);
      removidos += 1;
    }
  }
  return removidos;
}
