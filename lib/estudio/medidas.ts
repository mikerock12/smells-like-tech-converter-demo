"use client";

import { useEffect, useState } from "react";

export type Tamanho = { largura: number; altura: number };

const lidas = new WeakMap<File, Promise<Tamanho | null>>();

/** Largura e altura de uma foto, GIF ou vídeo, lidas uma vez por arquivo. */
export function medirMidia(arquivo: File): Promise<Tamanho | null> {
  let promessa = lidas.get(arquivo);
  if (!promessa) {
    promessa = arquivo.type.startsWith("video/") ? medirVideo(arquivo) : createImageBitmap(arquivo).then((imagem) => {
      const tamanho = { largura: imagem.width, altura: imagem.height };
      imagem.close();
      return tamanho;
    }).catch(() => null);
    lidas.set(arquivo, promessa);
  }
  return promessa;
}

function medirVideo(arquivo: File): Promise<Tamanho | null> {
  return new Promise((resolva) => {
    const video = document.createElement("video");
    const url = URL.createObjectURL(arquivo);
    const fim = (tamanho: Tamanho | null) => { URL.revokeObjectURL(url); video.removeAttribute("src"); resolva(tamanho); };
    video.preload = "metadata";
    video.muted = true;
    video.onloadedmetadata = () => fim(video.videoWidth > 0 ? { largura: video.videoWidth, altura: video.videoHeight } : null);
    video.onerror = () => fim(null);
    video.src = url;
  });
}

/** As medidas de todas as mídias abertas, por referência; as que ainda não foram lidas faltam. */
export function useMedidas(arquivos: ReadonlyMap<string, File>): Readonly<Record<string, Tamanho>> {
  const [medidas, setMedidas] = useState<Record<string, Tamanho>>({});
  useEffect(() => {
    let vivo = true;
    for (const [ref, arquivo] of arquivos) {
      void medirMidia(arquivo).then((tamanho) => {
        if (vivo && tamanho) setMedidas((atuais) => (atuais[ref] ? atuais : { ...atuais, [ref]: tamanho }));
      });
    }
    return () => { vivo = false; };
  }, [arquivos]);
  return medidas;
}
