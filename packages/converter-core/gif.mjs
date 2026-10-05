/**
 * Leitor de GIF animado, sem depender do navegador.
 *
 * Existe para o "GIF → vídeo": o navegador mostra GIF animado, mas não entrega os
 * quadros um a um para a página (o ImageDecoder não existe em todos). Aqui cada quadro
 * sai já composto sobre os anteriores — do jeito que o GIF aparece na tela, com
 * transparência e descarte ("disposal") respeitados — junto com quanto tempo fica.
 *
 * Os quadros saem um de cada vez e reaproveitam o mesmo buffer: memória constante,
 * mesmo em GIF de centenas de quadros. Quem lê precisa usar o quadro antes de pedir o
 * próximo.
 *
 * Módulo puro: roda no Worker e no `node --test`.
 */

export class GifInvalido extends Error {}

/** Navegadores tratam atraso de 0 ou 10 ms como 100 ms; o vídeo segue o mesmo. */
const ATRASO_MINIMO_MS = 20;
const ATRASO_PADRAO_MS = 100;

function lerU16(bytes, posicao) {
  return bytes[posicao] | (bytes[posicao + 1] << 8);
}

/** Lê a estrutura toda sem decodificar pixels: dimensões, paleta e onde está cada quadro. */
export function lerGif(entrada) {
  const bytes = entrada instanceof Uint8Array ? entrada : new Uint8Array(entrada);
  const assinatura = String.fromCharCode(...bytes.subarray(0, 6));
  if (assinatura !== "GIF87a" && assinatura !== "GIF89a") throw new GifInvalido("Este arquivo não é um GIF.");

  const largura = lerU16(bytes, 6);
  const altura = lerU16(bytes, 8);
  if (largura === 0 || altura === 0) throw new GifInvalido("O GIF não tem tamanho.");
  const empacotado = bytes[10];
  let posicao = 13;

  let paletaGlobal = null;
  if (empacotado & 0x80) {
    const entradas = 1 << ((empacotado & 0x07) + 1);
    paletaGlobal = bytes.subarray(posicao, posicao + entradas * 3);
    posicao += entradas * 3;
  }

  const quadros = [];
  let controle = null;
  let repeticoes = null;

  const pularSubblocos = () => {
    while (posicao < bytes.length) {
      const tamanho = bytes[posicao];
      posicao += 1 + tamanho;
      if (tamanho === 0) return;
    }
  };

  while (posicao < bytes.length) {
    const marcador = bytes[posicao];
    posicao += 1;

    if (marcador === 0x3b) break; // fim do arquivo

    if (marcador === 0x21) {
      const rotulo = bytes[posicao];
      posicao += 1;
      if (rotulo === 0xf9 && bytes[posicao] >= 4) {
        const campos = bytes[posicao + 1];
        controle = {
          descarte: (campos >> 2) & 0x07,
          transparente: (campos & 0x01) === 1 ? bytes[posicao + 4] : -1,
          atraso: lerU16(bytes, posicao + 2) * 10,
        };
        posicao += 1 + bytes[posicao];
        pularSubblocos();
      } else if (rotulo === 0xff && bytes[posicao] === 11) {
        const aplicacao = String.fromCharCode(...bytes.subarray(posicao + 1, posicao + 12));
        posicao += 12;
        if ((aplicacao === "NETSCAPE2.0" || aplicacao === "ANIMEXTS1.0") && bytes[posicao] >= 3 && bytes[posicao + 1] === 1) {
          repeticoes = lerU16(bytes, posicao + 2);
        }
        pularSubblocos();
      } else {
        pularSubblocos();
      }
      continue;
    }

    if (marcador === 0x2c) {
      const esquerda = lerU16(bytes, posicao);
      const topo = lerU16(bytes, posicao + 2);
      const l = lerU16(bytes, posicao + 4);
      const a = lerU16(bytes, posicao + 6);
      const campos = bytes[posicao + 8];
      posicao += 9;

      let paleta = paletaGlobal;
      if (campos & 0x80) {
        const entradas = 1 << ((campos & 0x07) + 1);
        paleta = bytes.subarray(posicao, posicao + entradas * 3);
        posicao += entradas * 3;
      }

      const tamanhoMinimo = bytes[posicao];
      posicao += 1;
      const inicioDosDados = posicao;
      pularSubblocos();

      quadros.push({
        esquerda,
        topo,
        largura: l,
        altura: a,
        entrelacado: (campos & 0x40) !== 0,
        paleta,
        tamanhoMinimo,
        dados: [inicioDosDados, posicao],
        descarte: controle?.descarte ?? 0,
        transparente: controle?.transparente ?? -1,
        atraso: normalizarAtraso(controle?.atraso ?? 0),
      });
      controle = null;
      continue;
    }

    // Byte perdido entre blocos: alguns editores deixam lixo; o navegador ignora.
  }

  if (quadros.length === 0) throw new GifInvalido("O GIF não tem nenhum quadro.");
  return { bytes, largura, altura, quadros, repeticoes };
}

function normalizarAtraso(ms) {
  return ms < ATRASO_MINIMO_MS ? ATRASO_PADRAO_MS : ms;
}

/** Junta os sub-blocos de dados de um quadro num buffer só. */
function dadosDoQuadro(bytes, [inicio, fim]) {
  const partes = [];
  let total = 0;
  let posicao = inicio;
  while (posicao < fim) {
    const tamanho = bytes[posicao];
    if (tamanho === 0) break;
    partes.push(bytes.subarray(posicao + 1, posicao + 1 + tamanho));
    total += tamanho;
    posicao += 1 + tamanho;
  }
  const junto = new Uint8Array(total);
  let escrito = 0;
  for (const parte of partes) {
    junto.set(parte, escrito);
    escrito += parte.length;
  }
  return junto;
}

/**
 * LZW do GIF: códigos de tamanho variável, bit menos significativo primeiro. Arquivo
 * truncado não derruba: os pixels que faltam ficam no índice 0, como no navegador.
 */
export function descomprimirLzw(tamanhoMinimo, dados, pixels) {
  const saida = new Uint8Array(pixels);
  if (tamanhoMinimo < 1 || tamanhoMinimo > 11) return saida;

  const limpar = 1 << tamanhoMinimo;
  const fim = limpar + 1;
  const prefixo = new Int16Array(4096);
  const sufixo = new Uint8Array(4096);
  const pilha = new Uint8Array(4097);
  for (let codigo = 0; codigo < limpar; codigo += 1) {
    prefixo[codigo] = -1;
    sufixo[codigo] = codigo;
  }

  let tamanho = tamanhoMinimo + 1;
  let mascara = (1 << tamanho) - 1;
  let proximo = limpar + 2;
  let anterior = -1;
  let primeiro = 0;
  let acumulado = 0;
  let bits = 0;
  let lido = 0;
  let escrito = 0;

  while (escrito < pixels) {
    while (bits < tamanho) {
      if (lido >= dados.length) return saida;
      acumulado |= dados[lido] << bits;
      lido += 1;
      bits += 8;
    }
    let codigo = acumulado & mascara;
    acumulado >>>= tamanho;
    bits -= tamanho;

    if (codigo === limpar) {
      tamanho = tamanhoMinimo + 1;
      mascara = (1 << tamanho) - 1;
      proximo = limpar + 2;
      anterior = -1;
      continue;
    }
    if (codigo === fim) break;

    if (anterior === -1) {
      if (codigo >= limpar) return saida; // corrompido
      saida[escrito] = sufixo[codigo];
      escrito += 1;
      anterior = codigo;
      primeiro = codigo;
      continue;
    }

    const lidoAgora = codigo;
    let topo = 0;
    if (codigo >= proximo) {
      if (codigo > proximo) return saida; // corrompido
      pilha[topo] = primeiro;
      topo += 1;
      codigo = anterior;
    }
    while (codigo >= limpar) {
      pilha[topo] = sufixo[codigo];
      topo += 1;
      codigo = prefixo[codigo];
    }
    primeiro = sufixo[codigo];
    pilha[topo] = primeiro;
    topo += 1;

    if (proximo < 4096) {
      prefixo[proximo] = anterior;
      sufixo[proximo] = primeiro;
      proximo += 1;
      if (proximo === 1 << tamanho && tamanho < 12) {
        tamanho += 1;
        mascara = (1 << tamanho) - 1;
      }
    }
    anterior = lidoAgora;

    while (topo > 0 && escrito < pixels) {
      topo -= 1;
      saida[escrito] = pilha[topo];
      escrito += 1;
    }
  }

  return saida;
}

/** Linha real de cada linha decodificada, no GIF entrelaçado (4 passadas). */
function ordemEntrelacada(altura) {
  const ordem = [];
  for (const [inicio, passo] of [[0, 8], [4, 8], [2, 4], [1, 2]]) {
    for (let linha = inicio; linha < altura; linha += passo) ordem.push(linha);
  }
  return ordem;
}

/**
 * Os quadros, compostos como aparecem na tela. Cada item traz `rgba` (a tela inteira,
 * largura × altura × 4, reaproveitada entre quadros), `atraso` em milissegundos e o
 * índice do quadro.
 */
export function* quadrosDoGif(gif) {
  const { bytes, largura, altura, quadros } = gif;
  const tela = new Uint8ClampedArray(largura * altura * 4);
  let guardada = null;
  let anterior = null;

  for (const [indice, quadro] of quadros.entries()) {
    // O descarte do quadro anterior acontece antes de desenhar este.
    if (anterior) {
      if (anterior.descarte === 2) {
        limparRetangulo(tela, largura, altura, anterior);
      } else if (anterior.descarte === 3 && guardada) {
        tela.set(guardada);
      }
    }
    if (quadro.descarte === 3) {
      guardada ??= new Uint8ClampedArray(tela.length);
      guardada.set(tela);
    }

    desenhar(tela, largura, altura, quadro, bytes);
    yield { rgba: tela, atraso: quadro.atraso, indice };
    anterior = quadro;
  }
}

function limparRetangulo(tela, largura, altura, quadro) {
  const x1 = Math.min(largura, quadro.esquerda + quadro.largura);
  const y1 = Math.min(altura, quadro.topo + quadro.altura);
  for (let y = quadro.topo; y < y1; y += 1) {
    tela.fill(0, (y * largura + quadro.esquerda) * 4, (y * largura + x1) * 4);
  }
}

function desenhar(tela, largura, altura, quadro, bytes) {
  const { paleta, transparente } = quadro;
  if (!paleta) return; // sem paleta nenhuma: nada a desenhar
  const indices = descomprimirLzw(quadro.tamanhoMinimo, dadosDoQuadro(bytes, quadro.dados), quadro.largura * quadro.altura);
  const linhas = quadro.entrelacado ? ordemEntrelacada(quadro.altura) : null;
  const cores = paleta.length / 3;

  for (let linhaLida = 0; linhaLida < quadro.altura; linhaLida += 1) {
    const y = quadro.topo + (linhas ? linhas[linhaLida] : linhaLida);
    if (y >= altura) continue;
    const origem = linhaLida * quadro.largura;
    for (let coluna = 0; coluna < quadro.largura; coluna += 1) {
      const x = quadro.esquerda + coluna;
      if (x >= largura) break;
      const cor = indices[origem + coluna];
      if (cor === transparente || cor >= cores) continue;
      const destino = (y * largura + x) * 4;
      tela[destino] = paleta[cor * 3];
      tela[destino + 1] = paleta[cor * 3 + 1];
      tela[destino + 2] = paleta[cor * 3 + 2];
      tela[destino + 3] = 255;
    }
  }
}

/** Duração de uma volta do GIF, em milissegundos. */
export function duracaoDoGif(gif) {
  return gif.quadros.reduce((soma, quadro) => soma + quadro.atraso, 0);
}
