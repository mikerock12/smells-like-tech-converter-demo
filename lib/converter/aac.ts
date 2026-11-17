import { canEncodeAudio } from "mediabunny";

/**
 * AAC em qualquer navegador. O Chrome/Chromium do Linux (e alguns Firefox) não têm
 * codificador AAC no WebCodecs; sem ele o mediabunny descartava a trilha de áudio em
 * silêncio e o vídeo saía só com a imagem. Nesses navegadores entra o codificador AAC-LC do
 * FFmpeg em WebAssembly (@mediabunny/aac-encoder, MPL-2.0 sobre o libavcodec, LGPL),
 * carregado só quando precisa. Funciona na página e nos workers.
 *
 * Para testar o caminho do Linux noutro sistema: `localStorage["converter:aac-wasm"] = "1"`.
 */

let registro: Promise<boolean> | null = null;

function forcado(): boolean {
  try { return typeof localStorage !== "undefined" && localStorage.getItem("converter:aac-wasm") === "1"; } catch { return false; }
}

/** Garante um codificador AAC para a configuração; devolve true se for o de WebAssembly. */
export function garantirAac(config: { numberOfChannels: number; sampleRate: number; bitrate?: number } = { numberOfChannels: 2, sampleRate: 48_000, bitrate: 192_000 }): Promise<boolean> {
  registro ??= (async () => {
    const nativo = !forcado() && await canEncodeAudio("aac", config).catch(() => false);
    if (nativo) return false;
    const { registerAacEncoder } = await import("@mediabunny/aac-encoder");
    registerAacEncoder();
    console.info("AAC: o navegador não codifica AAC; usando o codificador em WebAssembly.");
    return true;
  })();
  return registro;
}
