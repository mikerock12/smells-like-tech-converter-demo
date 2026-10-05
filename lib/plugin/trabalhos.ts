import type { Ferramenta } from "@/packages/converter-core/catalogo.mjs";

import type { Opcoes } from "@/lib/converter/ferramentas";
import { lerTempoEmSegundos } from "@/lib/converter/ferramentas";

/**
 * Traduz as opções de uma ferramenta do site para o JobOptions do aplicativo.
 *
 * O plugin fala o dialeto do aplicativo (`operation` como discriminador, campos em
 * PascalCase). Só o que o site sabe preencher vai; o resto fica no padrão do produto,
 * validado por allowlist do outro lado.
 */

function tempoEmTimeSpan(texto: string | number | boolean | undefined): string | undefined {
  const segundos = lerTempoEmSegundos(texto);
  if (segundos === null) return undefined;
  const h = Math.floor(segundos / 3600);
  const m = Math.floor((segundos % 3600) / 60);
  const s = (segundos % 60).toFixed(3);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${s.padStart(6, "0")}`;
}

const ALTURAS = [2160, 1440, 1080, 720, 480, 360];

export function opcoesDoPlugin(ferramenta: Ferramenta, opcoes: Opcoes): Record<string, unknown> {
  const operation = ferramenta.plugin;
  if (!operation) throw new Error(`“${ferramenta.titulo}” não existe no plugin.`);

  const formato = String(opcoes.formato ?? ferramenta.saidas[0]);
  const bitrate = Number(opcoes.bitrate ?? 192) || 192;
  const inicio = tempoEmTimeSpan(opcoes.inicio);
  const fim = lerTempoEmSegundos(opcoes.fim);
  const inicioSegundos = lerTempoEmSegundos(opcoes.inicio) ?? 0;
  const duracaoDoCorte = fim !== null ? tempoEmTimeSpan(Math.max(0.1, fim - inicioSegundos)) : undefined;

  switch (operation) {
    case "image.convert":
      return {
        operation,
        OutputFormat: formato === "ico" ? "ico" : formato,
        Quality: Number(opcoes.qualidade ?? 82) || 82,
        KeepTransparency: opcoes.manterTransparencia !== false,
      };
    case "audio.convert":
      return {
        operation,
        OutputFormat: formato,
        AudioBitrateKbps: formato === "wav" || formato === "flac" ? undefined : bitrate,
        Normalize: opcoes.normalizar === true,
        Channels: opcoes.mono === true ? 1 : undefined,
        TrimStart: inicio,
        TrimDuration: duracaoDoCorte,
      };
    case "video.extractAudio":
      return {
        operation,
        OutputFormat: formato,
        AudioBitrateKbps: formato === "wav" || formato === "flac" ? undefined : bitrate,
        TrimStart: inicio,
        TrimDuration: duracaoDoCorte,
      };
    case "video.convert": {
      const resolucao = Number(opcoes.resolucao);
      const qualidade = { "muito-alta": 90, alta: 78, media: 65, baixa: 50, "muito-baixa": 35 }[String(opcoes.qualidadeDoVideo ?? "media")] ?? 65;
      const alvo = String(opcoes.alvo ?? "");
      return {
        operation,
        OutputFormat: ["mp4", "mkv", "mov", "webm", "avi"].includes(formato) ? formato : "mp4",
        TargetHeight: ALTURAS.includes(resolucao) ? resolucao : alvo === "whatsapp" ? 720 : undefined,
        Quality: alvo === "whatsapp" ? 45 : alvo === "email" ? 55 : alvo === "quarto" ? 40 : alvo === "metade" ? 55 : qualidade,
        FrameRate: [24, 25, 30, 50, 60].includes(Number(opcoes.fps)) ? Number(opcoes.fps) : undefined,
        Rotation: Number(opcoes.rotacao ?? 0) || 0,
        Audio: ferramenta.id === "video.silenciar" || opcoes.semAudio === true ? 1 : 0,
        TrimStart: inicio,
        TrimDuration: duracaoDoCorte,
        VideoCodec: ferramenta.id === "video.cortar" || ferramenta.id === "video.silenciar" ? 5 : 0,
      };
    }
    case "video.toGif":
      return {
        operation,
        Width: [240, 320, 480, 640, 800].includes(Number(opcoes.largura)) ? Number(opcoes.largura) : 480,
        FrameRate: [8, 10, 12, 15, 20, 24].includes(Number(opcoes.fps)) ? Number(opcoes.fps) : 12,
        TrimStart: inicio,
        TrimDuration: tempoEmTimeSpan(Number(opcoes.duracao ?? 5) || 5),
      };
    case "video.toFrames":
      return {
        operation,
        ImageFormat: ["png", "jpg", "webp"].includes(formato) ? formato : "png",
        FramesPerSecond: opcoes.modo === "intervalo" ? 1 / Math.max(0.5, Number(opcoes.intervalo ?? 5)) : 1,
        MaxFrames: opcoes.modo === "distribuidos" ? Number(opcoes.quantidade ?? 10) || 10 : 300,
      };
    case "pdf.toImage":
      return {
        operation,
        OutputFormat: ["png", "jpg", "webp"].includes(formato) ? formato : "png",
        Dpi: [72, 96, 150, 200, 300, 600].includes(Number(opcoes.dpi)) ? Number(opcoes.dpi) : 150,
      };
    case "pdf.toDocument":
      return {
        operation,
        OutputFormat: ["docx", "txt", "md", "html"].includes(formato) ? formato : "docx",
        UseOcrWhenNeeded: opcoes.ocr !== false,
        IncludePageMarks: opcoes.marcarPaginas !== false,
      };
    case "ocr.imageToText":
      return {
        operation,
        OutputFormat: "txt",
        Language: String(opcoes.idioma ?? "por").startsWith("eng") ? "en-US" : String(opcoes.idioma ?? "por").startsWith("spa") ? "es" : "pt-BR",
      };
    case "speech.transcribe":
      return {
        operation,
        OutputFormat: ["txt", "srt", "vtt"].includes(formato) ? formato : "srt",
        Language: String(opcoes.idioma ?? "pt"),
        Model: String(opcoes.modelo ?? "small"),
      };
    case "speech.synthesize":
      return {
        operation,
        OutputFormat: formato === "wav" ? "wav" : "mp3",
        Rate: Math.max(-10, Math.min(10, Number(opcoes.velocidade ?? 0) || 0)),
        Volume: Math.max(0, Math.min(100, Number(opcoes.volume ?? 100))),
        VoiceId: String(opcoes.voz ?? "pf_dora"),
      };
    case "pdf.merge":
    case "pdf.split":
    case "pdf.fromImage":
      return { operation, OutputFormat: "pdf", Pages: String(opcoes.paginas ?? "") };
    default:
      return { operation, OutputFormat: formato };
  }
}
