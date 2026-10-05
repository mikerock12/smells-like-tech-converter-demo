#!/usr/bin/env node
/** Build de produção + wrangler local. Nenhum motor é usado antes de desligar a rede. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { PDFDocument, StandardFonts } from "pdf-lib";
import UTIF from "utif2";

const site = (process.env.OFFLINE_TEST_SITE ?? "http://127.0.0.1:3002").replace(/\/$/, "");
const browser = await chromium.launch({ channel: "chromium", headless: true, args: ["--autoplay-policy=no-user-gesture-required"] });
try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, serviceWorkers: "allow" });
  const page = await context.newPage();
  const erros = [];
  const externos = [];
  const metricasOpcionais = [];
  const envios = [];
  let semRede = false;
  async function garantirRedeDesligada() {
    // COOP pode trocar o alvo de navegação do Chromium. Reaplique a emulação e
    // prove a falha de uma API nunca cacheada, não apenas navigator.onLine.
    await context.setOffline(true);
    assert.equal(await page.evaluate(async () => {
      try {
        await fetch("/api/conta?verificacao-offline=1", { cache: "no-store", credentials: "omit", signal: AbortSignal.timeout(5000) });
        return false;
      } catch { return true; }
    }), true, "a API não cacheada precisa estar realmente inacessível");
  }
  const camposDeMetricas = new Set(["startTime", "pageloadId", "eventType", "nt", "location", "versions", "bi", "memory", "firstPaint", "firstContentfulPaint", "timingsV2", "siteToken", "st"]);
  page.on("pageerror", (erro) => erros.push(erro.message));
  context.on("request", (r) => {
    if (!/^https?:/.test(r.url())) return;
    const url = new URL(r.url());
    // A Cloudflare pode inserir este beacon no HTML de produção. Não é um motor:
    // as conversões abaixo têm de passar mesmo quando a rede que o atende é desligada.
    const beacon = url.origin === "https://static.cloudflareinsights.com" && /^\/beacon\.min\.js(?:\/v[a-z0-9]+)?$/.test(url.pathname) && r.method() === "GET";
    if (beacon) metricasOpcionais.push(r.url());
    else if (![new URL(site).origin, "http://127.0.0.1:5199"].includes(url.origin)) externos.push(r.url());
    if (r.method() !== "GET") {
      let metricaDeCarregamento = false;
      if (!semRede && url.origin === new URL(site).origin && url.pathname === "/cdn-cgi/rum" && r.method() === "POST") {
        try {
          const dados = JSON.parse(r.postData());
          metricaDeCarregamento = Object.keys(dados).every((campo) => camposDeMetricas.has(campo)) && "timingsV2" in dados && "pageloadId" in dados;
        } catch { /* Corpo desconhecido continua sendo tratado como envio proibido. */ }
      }
      if (metricaDeCarregamento) metricasOpcionais.push("/cdn-cgi/rum (desempenho do carregamento, antes de qualquer conversão)");
      else envios.push(`${r.method()} ${url.pathname}`);
    }
  });
  await page.goto(`${site}/converter`, { waitUntil: "load" });
  try {
    await page.locator('[data-offline-pronto="true"]').waitFor({ timeout: 180000 });
  } catch (erro) {
    console.error("Preparo offline não concluído:", await page.locator(".offline-status").innerText());
    console.error("Erros de JavaScript:", erros);
    throw erro;
  }
  assert.equal(await page.evaluate(() => navigator.serviceWorker.controller !== null && crossOriginIsolated), true);
  assert.match(await page.locator("#nota-offline").innerText(), /códigos por e-mail/);
  // Fixtures pelo canvas/MediaRecorder do navegador, não pelos motores do aplicativo.
  const dados = await page.evaluate(async () => {
    const canvas = document.createElement("canvas"); canvas.width = 800; canvas.height = 140;
    const ctx = canvas.getContext("2d"); ctx.fillStyle = "white"; ctx.fillRect(0, 0, 800, 140);
    ctx.fillStyle = "black"; ctx.font = "48px Arial"; ctx.fillText("OFFLINE LOCAL TESTE", 20, 85);
    const png = canvas.toDataURL("image/png").split(",")[1];
    const pcm = new ArrayBuffer(44 + 44100 * 2);
    const view = new DataView(pcm);
    const texto = (offset, str) => [...str].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
    texto(0, "RIFF"); view.setUint32(4, pcm.byteLength - 8, true); texto(8, "WAVE"); texto(12, "fmt ");
    view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
    view.setUint32(24, 44100, true); view.setUint32(28, 88200, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    texto(36, "data"); view.setUint32(40, pcm.byteLength - 44, true);
    for (let i = 0; i < 44100; i++) view.setInt16(44 + i * 2, Math.round(6000 * Math.sin(i * 2 * Math.PI * 440 / 44100)), true);
    const base64 = async (blob) => await new Promise((resolve) => {
      const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(",")[1]); reader.readAsDataURL(blob);
    });
    const ac = new AudioContext(); await ac.resume();
    const destino = ac.createMediaStreamDestination(); const oscilador = ac.createOscillator();
    oscilador.connect(destino); oscilador.start();
    const stream = new MediaStream([...canvas.captureStream(12).getVideoTracks(), ...destino.stream.getAudioTracks()]);
    const recorder = new MediaRecorder(stream, { mimeType: "video/webm;codecs=vp8,opus" });
    const partes = [];
    recorder.ondataavailable = (e) => { if (e.data.size) partes.push(e.data); };
    const terminou = new Promise((resolve) => { recorder.onstop = resolve; });
    const pintar = setInterval(() => { ctx.fillStyle = "black"; ctx.fillRect(Math.random() * 700, 110, 10, 10); }, 60);
    recorder.start(); await new Promise((resolve) => setTimeout(resolve, 1400)); recorder.stop(); await terminou;
    clearInterval(pintar); oscilador.stop(); stream.getTracks().forEach((t) => t.stop()); await ac.close();
    return { png, wav: await base64(new Blob([pcm])), webm: await base64(new Blob(partes)) };
  });
  const arquivo = (name, mimeType, buffer) => ({ name, mimeType, buffer });
  const png = arquivo("texto.png", "image/png", Buffer.from(dados.png, "base64"));
  const wav = arquivo("som.wav", "audio/wav", Buffer.from(dados.wav, "base64"));
  const webm = arquivo("video.webm", "video/webm", Buffer.from(dados.webm, "base64"));
  const tiff = arquivo("foto.tiff", "image/tiff", Buffer.from(UTIF.encodeImage(new Uint8Array(16 * 16 * 4).fill(255), 16, 16)));
  const pdf = await PDFDocument.create(); const font = await pdf.embedFont(StandardFonts.Helvetica);
  for (let n = 0; n < 2; n++) pdf.addPage([200, 100]).drawText("OFFLINE LOCAL PDF", { x: 10, y: 50, size: 15, font });
  const pdfFile = arquivo("texto.pdf", "application/pdf", Buffer.from(await pdf.save()));
  const escaneado = await PDFDocument.create();
  const foto = await escaneado.embedPng(png.buffer);
  escaneado.addPage([800, 140]).drawImage(foto, { x: 0, y: 0, width: 800, height: 140 });
  const pdfScan = arquivo("escaneado.pdf", "application/pdf", Buffer.from(await escaneado.save()));
  const legenda = arquivo("fala.srt", "text/plain", Buffer.from("1\n00:00:00,000 --> 00:00:01,000\nOFFLINE LOCAL\n"));
  assert.equal(await page.evaluate(async () => (await indexedDB.databases()).some((b) => /tesseract/i.test(b.name ?? ""))), false, "OCR ainda não foi inicializado online");
  semRede = true;
  await garantirRedeDesligada();
  console.log("Rede desligada antes da primeira conversão; perfil novo e nenhuma ferramenta aquecida online.");

  const casos = [
    ["PNG → WEBP", "imagem.converter", [png], { "Formato de saída": "webp" }, "webp"],
    ["PNG → JPG", "imagem.converter", [png], { "Formato de saída": "jpg" }, "jpg"],
    ["PNG → BMP", "imagem.converter", [png], { "Formato de saída": "bmp" }, "bmp"],
    ["PNG → ICO", "imagem.converter", [png], { "Formato de saída": "ico" }, "ico"],
    ["TIFF → WEBP", "imagem.converter", [tiff], { "Formato de saída": "webp" }, "webp"],
    ["Girar imagem", "imagem.girar", [png], {}, "png"],
    ["Imagem → PDF", "imagem.para-pdf", [png], {}, "pdf"],
    ["WAV → MP3", "audio.converter", [wav], {}, "mp3"],
    ["Editar WAV", "audio.editar", [wav], { "Formato de saída": "wav" }, "wav"],
    ["SRT → VTT", "legenda.converter", [legenda], { "Formato de saída": "vtt" }, "vtt"],
    ["Juntar PDF", "pdf.juntar", [pdfFile, { ...pdfFile, name: "segundo.pdf" }], {}, "pdf"],
    ["Dividir PDF", "pdf.dividir", [pdfFile], {}, "pdf"],
    ["Girar PDF", "pdf.girar", [pdfFile], {}, "pdf"],
    ["Comprimir PDF", "pdf.comprimir", [pdfFile], {}, "pdf"],
    ["PDF → PNG", "pdf.para-imagens", [pdfFile], {}, "png"],
    ["PDF → texto", "pdf.para-texto", [pdfFile], { "Formato de saída": "txt" }, "txt"],
    ["PDF escaneado → texto", "pdf.para-texto", [pdfScan], { "Formato de saída": "txt" }, "txt"],
    ["WEBM → MP3", "video.para-audio", [webm], {}, "mp3"],
    ["WEBM → WEBM", "video.converter", [webm], { "Formato de saída": "webm" }, "webm"],
    ["WEBM → GIF", "video.gif", [webm], {}, "gif"],
    ["WEBM → quadros", "video.quadros", [webm], {}, "png"],
    ["OCR português", "imagem.ocr", [png], { "Idioma do texto": "por" }, "txt"],
    ["OCR inglês", "imagem.ocr", [png], { "Idioma do texto": "eng" }, "txt"],
    ["OCR espanhol", "imagem.ocr", [png], { "Idioma do texto": "spa" }, "txt"],
  ];
  for (const [nome, ferramenta, arquivos, opcoes, extensao] of casos) {
    console.log(`Testando ${nome}…`);
    // Reabre offline em cada caso: também comprova a casca, não só uma aba aquecida.
    await page.goto(`${site}/converter`, { waitUntil: "load" });
    await garantirRedeDesligada();
    await page.locator('[data-offline-pronto="true"]').waitFor({ timeout: 30000 });
    await page.locator('input[type="file"]').setInputFiles(arquivos);
    const grupo = page.locator(".grupo").first();
    await grupo.locator(".grupo__ferramenta select").selectOption(ferramenta);
    for (const [label, valor] of Object.entries(opcoes)) await grupo.locator("label").filter({ has: page.getByText(label, { exact: true }) }).locator("select").selectOption(valor);
    await grupo.locator(".grupo__acoes button").click();
    await page.waitForFunction(() => document.querySelector(".job--done, .job--failed") !== null, null, { timeout: 90000 });
    if (await page.locator(".job--failed").count()) throw new Error(`${nome}: ${await page.locator(".job__error").innerText()}`);
    const saida = await page.locator('.job--done a[download]').first().evaluate(async (link) => {
      const bytes = new Uint8Array(await (await fetch(link.href)).arrayBuffer());
      return { nome: link.download, bytes: bytes.length, texto: new TextDecoder().decode(bytes.slice(0, 4096)), inicio: [...bytes.slice(0, 16)] };
    });
    assert.ok(saida.bytes > 10, nome);
    assert.ok(saida.nome.endsWith(`.${extensao}`), `${nome}: ${saida.nome}`);
    if (extensao === "txt") assert.match(saida.texto, /OFFLINE|LOCAL/);
    if (extensao === "pdf") assert.match(saida.texto, /^%PDF/);
    if (extensao === "vtt") assert.match(saida.texto, /^WEBVTT/);
    if (extensao === "png") assert.equal(saida.inicio[0], 137);
    console.log(`✓ ${nome}: ${saida.nome} (${saida.bytes} bytes), offline`);
  }
  await page.goto(`${site}/converter/tiff-para-webp`, { waitUntil: "load" });
  await garantirRedeDesligada();
  await page.locator('input[type="file"]').waitFor({ state: "attached" });
  await page.locator('[data-offline-pronto="true"]').waitFor();
  assert.equal(await page.evaluate(() => crossOriginIsolated), true);
  await page.goto(`${site}/chave`, { waitUntil: "load" });
  await garantirRedeDesligada();
  await page.getByLabel("Chave de acesso").fill("nao-e-uma-chave");
  await page.getByRole("button", { name: "Ativar neste navegador" }).click();
  await page.getByText("Isso não parece uma chave.", { exact: false }).waitFor();
  assert.deepEqual(externos, [], "nenhuma CDN de conversão é necessária, nem para OCR");
  assert.deepEqual(envios, [], "nenhum arquivo foi enviado ao servidor; sem POSTs de conversão nem tráfego de métricas offline");
  assert.deepEqual(erros, [], "nenhum erro JavaScript no navegador");
  if (metricasOpcionais.length) console.log("Beacon opcional da Cloudflare observado no carregamento; não necessário para nenhuma conversão offline.");
  console.log(`OK: ${casos.length} conversões, recarga e página nunca visitada sem internet.`);
} finally {
  await browser.close();
}
