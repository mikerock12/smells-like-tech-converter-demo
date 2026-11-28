/// <reference lib="webworker" />
import { ampliarRgba } from "./filtro-2x.mjs";

self.onmessage = ({ data: { imagem, kernels } }) => {
  const dados = ampliarRgba(imagem.data, imagem.width, imagem.height, kernels);
  self.postMessage(new ImageData(dados, imagem.width * 2, imagem.height * 2), { transfer: [dados.buffer] });
};
