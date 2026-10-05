import { unzipSync } from "fflate";
import { outputFileName, kindOf } from "@/packages/converter-core/index.mjs";
import { converterLegenda } from "@/packages/converter-core/legendas.mjs";
import { dividirTexto, locutor, velocidade } from "@/packages/kokoro/config.mjs";
import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { arquivosKokoro } from "./kokoro";

export async function lerTexto(contexto: ContextoDoMotor): Promise<string> {
  const entrada = contexto.entradas[0];
  const formato = entrada.formato;
  if (formato === "pdf") {
    const { pdfParaTexto } = await import("./pdfjs");
    const resultado = await pdfParaTexto({ ...contexto, opcoes: { formato: "txt", marcarPaginas: false } });
    return new TextDecoder().decode(resultado.saidas[0]?.bytes);
  }
  if (formato && kindOf(formato) === "image") {
    const { reconhecerTexto } = await import("./ocr");
    let arquivo = entrada.arquivo;
    // TIFF/HEIC usam o mesmo decodificador local das conversões de imagem.
    if (formato === "tiff" || formato === "heic") {
      const { decodificar } = await import("../motores/imagem");
      const imagem = await decodificar(arquivo, formato);
      const canvas = new OffscreenCanvas(imagem.width, imagem.height);
      canvas.getContext("2d")!.putImageData(imagem, 0, 0);
      arquivo = new File([await canvas.convertToBlob({ type: "image/png" })], "imagem.png", { type: "image/png" });
    }
    const resultado = await reconhecerTexto({ ...contexto, entradas: [{ ...entrada, arquivo }], opcoes: { idioma: "por" } });
    return new TextDecoder().decode(resultado.saidas[0]?.bytes);
  }
  if (formato === "docx") {
    const xml = unzipSync(new Uint8Array(await entrada.arquivo.arrayBuffer()), {
      filter: (arquivo) => arquivo.name === "word/document.xml" && arquivo.originalSize <= 8 * 1024 * 1024,
    })["word/document.xml"];
    if (!xml) throw new Error("DOCX sem texto ou grande demais. Use o aplicativo.");
    const doc = new DOMParser().parseFromString(new TextDecoder().decode(xml), "application/xml");
    if (doc.querySelector("parsererror")) throw new Error("O DOCX contém XML inválido.");
    return [...doc.getElementsByTagNameNS("*", "p")].map((p) => [...p.getElementsByTagNameNS("*", "t")].map((t) => t.textContent ?? "").join("")).join("\n");
  }
  const texto = await entrada.arquivo.text();
  if (formato === "srt" || formato === "vtt") return converterLegenda(texto, "txt", 0);
  if (formato === "html") {
    // Template é inerte: imagens/iframes de HTML de terceiros não fazem requisições.
    const modelo = document.createElement("template");
    modelo.innerHTML = texto;
    modelo.content.querySelectorAll("script,style,noscript,template,title,meta,link").forEach((item) => item.remove());
    modelo.content.querySelectorAll("br").forEach((item) => item.replaceWith(document.createTextNode("\n")));
    modelo.content.querySelectorAll("p,div,li,h1,h2,h3,h4,h5,h6,tr").forEach((item) => item.append(document.createTextNode("\n")));
    return modelo.content.textContent ?? "";
  }
  if (formato === "md") return texto.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/^\s{0,3}#{1,6}\s+/gm, "").replace(/[*_`]/g, "");
  if (formato !== "txt") throw new Error("Para narrar documentos aqui, use DOCX, Markdown ou HTML. Outros documentos: aplicativo Android.");
  return texto;
}

// Uma inferência por vez para não carregar vários modelos grandes simultaneamente.
let fila: Promise<void> = Promise.resolve();
export async function narrar(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const anterior = fila;
  let liberar!: () => void;
  fila = new Promise<void>((resolve) => { liberar = resolve; });
  contexto.progresso("aguardando o motor Kokoro", 0);
  await anterior;
  let worker: Worker | null = null;
  try {
    if (contexto.cancelado()) throw new Error("Narração cancelada.");
    const texto = (await lerTexto({ ...contexto, progresso: (etapa, fracao) => contexto.progresso(etapa, fracao * 0.15) })).trim();
    if (!texto) throw new Error("Não há texto legível para narrar.");
    // Evita esgotar a memória do navegador; o aplicativo grava em fluxo sem este limite.
    if (texto.length > 50_000) throw new Error("Este texto excede 50 mil caracteres. Narre pelo aplicativo Windows ou Android.");
    contexto.progresso("preparando Kokoro-82M", 0.16);
    const arquivos = await arquivosKokoro();
    if (contexto.cancelado()) throw new Error("Narração cancelada.");
    worker = new Worker("/motores/kokoro/runner.js", { name: "kokoro-82m" });
    const motor = worker;
    const pedir = (dados: object, transferencias: Transferable[] = []) => new Promise<{ samples?: ArrayBuffer; sampleRate?: number }>((resolve, reject) => {
      const limpar = () => { clearInterval(timer); motor.removeEventListener("message", mensagem); motor.removeEventListener("error", erro); };
      const erro = () => { limpar(); reject(new Error("Kokoro parou de responder. Tente uma narração menor ou use o aplicativo.")); };
      const mensagem = (evento: MessageEvent) => {
        limpar();
        if (evento.data.type === "error") reject(new Error(evento.data.message)); else resolve(evento.data);
      };
      const timer = setInterval(() => {
        if (contexto.cancelado()) { limpar(); motor.terminate(); reject(new ConversionError("cancelado", "Narração cancelada.")); }
      }, 100);
      motor.addEventListener("message", mensagem); motor.addEventListener("error", erro);
      motor.postMessage(dados, transferencias);
    });
    await pedir({ type: "initialize", files: arquivos }, arquivos.map(([, bytes]) => bytes));
    const partes = dividirTexto(texto);
    const canais: Float32Array[] = [];
    const sid = locutor(String(contexto.opcoes.voz ?? "pf_dora"));
    const speed = velocidade(Number(contexto.opcoes.velocidade ?? 0));
    const volume = Number(contexto.opcoes.volume ?? 100);
    if (!Number.isFinite(volume)) throw new Error("Volume inválido.");
    for (const [indice, parte] of partes.entries()) {
      const audio = await pedir({ type: "generate", text: parte, sid, speed });
      if (audio.sampleRate !== 24000 || !audio.samples) throw new Error("Áudio Kokoro inválido.");
      const amostras = new Float32Array(audio.samples);
      for (let i = 0; i < amostras.length; i++) amostras[i] *= Math.max(0, Math.min(100, volume)) / 100;
      canais.push(amostras);
      contexto.progresso("narrando com Kokoro-82M", 0.2 + (indice + 1) / partes.length * 0.7);
    }
    worker.terminate(); worker = null;
    const pcm = new Float32Array(canais.reduce((n, parte) => n + parte.length, 0));
    let posicao = 0;
    for (const parte of canais) { pcm.set(parte, posicao); posicao += parte.length; }
    canais.length = 0;
    if (contexto.cancelado()) throw new Error("Narração cancelada.");
    const { default: Fabrica } = await import("@/workers/media.worker?worker");
    worker = new Fabrica({ name: "kokoro-codificador" });
    const formato = String(contexto.opcoes.formato ?? "mp3") === "wav" ? "wav" : "mp3";
    const nome = outputFileName(contexto.entradas[0].nome, formato).replace(/(\.[^.]+)$/, "-narracao$1");
    const saidas = await new Promise<Saida[]>((resolve, reject) => {
      const codificador = worker!;
      const timer = setInterval(() => {
        if (contexto.cancelado()) { clearInterval(timer); codificador.terminate(); reject(new Error("Narração cancelada.")); }
      }, 100);
      codificador.onerror = () => { clearInterval(timer); reject(new Error("Não foi possível codificar a narração.")); };
      codificador.onmessage = ({ data }) => {
        if (data.type === "progress") { contexto.progresso("gravando narração", 0.9 + data.fraction * 0.1); return; }
        clearInterval(timer);
        if (data.type === "failed") reject(new Error(data.message)); else resolve(data.saidas);
      };
      codificador.postMessage({ type: "codificarAudio", jobId: contexto.jobId, nome, canais: [pcm], sampleRate: 24000, formato, bitrate: 128 }, [pcm.buffer]);
    });
    return { saidas, nota: "Kokoro-82M · português · processamento local" };
  } finally { worker?.terminate(); liberar(); }
}
