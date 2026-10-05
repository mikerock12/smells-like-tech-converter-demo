/**
 * Dois containers simples que gravamos à mão, sem codec nenhum.
 *
 * BMP: cabeçalho de 54 bytes e os pixels em BGR, de baixo para cima, com cada linha
 * alinhada em 4 bytes. ICO: um diretório e, para cada tamanho, um PNG inteiro — o
 * Windows aceita PNG dentro de ICO desde o Vista, e é assim que os ícones de 256 px
 * cabem num arquivo razoável.
 */

export function codificarBmp(imagem: ImageData): ArrayBuffer {
  const { width, height, data } = imagem;
  const linha = Math.ceil((width * 3) / 4) * 4;
  const tamanhoDosPixels = linha * height;
  const buffer = new ArrayBuffer(54 + tamanhoDosPixels);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);

  // Cabeçalho do arquivo.
  bytes[0] = 0x42; // B
  bytes[1] = 0x4d; // M
  view.setUint32(2, buffer.byteLength, true);
  view.setUint32(10, 54, true);
  // Cabeçalho da imagem (BITMAPINFOHEADER).
  view.setUint32(14, 40, true);
  view.setInt32(18, width, true);
  view.setInt32(22, height, true);
  view.setUint16(26, 1, true);
  view.setUint16(28, 24, true);
  view.setUint32(30, 0, true);
  view.setUint32(34, tamanhoDosPixels, true);
  view.setInt32(38, 2835, true);
  view.setInt32(42, 2835, true);

  let posicao = 54;
  for (let y = height - 1; y >= 0; y -= 1) {
    const inicio = posicao;
    for (let x = 0; x < width; x += 1) {
      const origem = (y * width + x) * 4;
      // Sem canal alfa: a transparência já foi achatada antes de chegar aqui.
      bytes[posicao] = data[origem + 2];
      bytes[posicao + 1] = data[origem + 1];
      bytes[posicao + 2] = data[origem];
      posicao += 3;
    }
    posicao = inicio + linha;
  }

  return buffer;
}

export function codificarIco(camadas: readonly { imagem: ImageData; png: ArrayBuffer }[]): ArrayBuffer {
  const cabecalho = 6 + camadas.length * 16;
  const total = cabecalho + camadas.reduce((soma, camada) => soma + camada.png.byteLength, 0);
  const buffer = new ArrayBuffer(total);
  const view = new DataView(buffer);
  const bytes = new Uint8Array(buffer);

  view.setUint16(0, 0, true);
  view.setUint16(2, 1, true);
  view.setUint16(4, camadas.length, true);

  let deslocamento = cabecalho;
  camadas.forEach((camada, indice) => {
    const entrada = 6 + indice * 16;
    // 256 é gravado como 0, por definição do formato.
    bytes[entrada] = camada.imagem.width >= 256 ? 0 : camada.imagem.width;
    bytes[entrada + 1] = camada.imagem.height >= 256 ? 0 : camada.imagem.height;
    bytes[entrada + 2] = 0;
    bytes[entrada + 3] = 0;
    view.setUint16(entrada + 4, 1, true);
    view.setUint16(entrada + 6, 32, true);
    view.setUint32(entrada + 8, camada.png.byteLength, true);
    view.setUint32(entrada + 12, deslocamento, true);
    bytes.set(new Uint8Array(camada.png), deslocamento);
    deslocamento += camada.png.byteLength;
  });

  return buffer;
}
