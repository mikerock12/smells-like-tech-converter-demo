/** Chromium descartável, modelo oficial local e todas as conversões depois de cortar a rede. */
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { PDFDocument, StandardFonts } from "pdf-lib";
import { zipSync, strToU8 } from "fflate";
import { resolve } from "node:path";
import { createServer } from "node:http";
import { createReadStream, statSync, mkdtempSync, readFileSync } from "node:fs";

const site = (process.env.KOKORO_TEST_SITE ?? "http://127.0.0.1:3002").replace(/\/$/, "");
let servidorDoModelo;
let modeloLocal;
if (new URL(site).hostname === "127.0.0.1") {
  const caminho = resolve("outputs/kokoro/kokoro-82m-v1-fp32.zip");
  servidorDoModelo = createServer((pedido, resposta) => {
    if (pedido.method !== "GET" || pedido.url !== "/kokoro.zip") { resposta.writeHead(404); resposta.end(); return; }
    console.log('GET do pacote local recebido.');
    resposta.writeHead(200, { "Content-Type": "application/zip", "Content-Length": statSync(caminho).size,
      "Access-Control-Allow-Origin": new URL(site).origin, "Cross-Origin-Resource-Policy": "cross-origin" });
    const stream = createReadStream(caminho);
    stream.on('end', () => console.log('Stream local concluído.'));
    stream.pipe(resposta);
  });
  await new Promise((resolve) => servidorDoModelo.listen(0, "127.0.0.1", resolve));
  modeloLocal = `http://127.0.0.1:${servidorDoModelo.address().port}/kokoro.zip`;
}
// Perfil descartável em disco: o modo anônimo pode limitar o Cache Storage em RAM.
const context = await chromium.launchPersistentContext(mkdtempSync(resolve('outputs/kokoro/browser-test-')), {
  channel: 'chromium', headless: true, serviceWorkers: 'allow', viewport: { width: 1280, height: 900 },
});
const browser = context;
let pagina;
try {
  if (new URL(site).hostname === "127.0.0.1") {
    // Pesos exatamente iguais aos publicados; não distribui artefatos de revisão de licença.
    // Redireciona para um stream local: não copia 321 MB em base64 pelo protocolo de depuração.
    await context.route(`${site}/api/motores/kokoro`, (route) => {
      console.log('GET do modelo interceptado.');
      return route.fulfill({ status: 307, headers: { Location: modeloLocal } });
    });
  }
  const page = await context.newPage();
  pagina = page;
  page.on('console', (message) => { if (message.type() === 'error') console.log('Browser:', message.text()); });
  const erros = [];
  const envios = [];
  const metricas = [];
  let semRede = false;
  const campos = new Set(['startTime','pageloadId','eventType','nt','location','versions','bi','memory','firstPaint','firstContentfulPaint','timingsV2','siteToken','st']);
  const externos = [];
  const sondagensLocais = [];
  page.on("pageerror", (erro) => erros.push(erro.message));
  context.on("request", (request) => {
    if (request.method() === 'GET' || !/^https?:/.test(request.url())) return;
    const url = new URL(request.url());
    let metrica = false;
    // Apenas métricas reconhecidas de carregamento, antes de fornecer qualquer arquivo.
    if (!semRede && url.origin === new URL(site).origin && url.pathname === '/cdn-cgi/rum' && request.method() === 'POST') {
      try {
        const dados = JSON.parse(request.postData());
        metrica = Object.keys(dados).every(c => campos.has(c)) && 'timingsV2' in dados && 'pageloadId' in dados;
      } catch { /* Qualquer conteúdo desconhecido reprova o teste. */ }
    }
    if (metrica) metricas.push('desempenho do carregamento antes de qualquer entrada');
    else envios.push(`${request.method()} ${url.pathname}`);
  });
  context.on('request', (request) => {
    if (!semRede && request.method() === 'GET' && /^https?:/.test(request.url()) &&
        new URL(request.url()).origin === 'https://static.cloudflareinsights.com' &&
        /^\/beacon\.min\.js(?:\/v[a-z0-9]+)?$/.test(new URL(request.url()).pathname)) {
      metricas.push('GET do beacon de desempenho antes de qualquer entrada'); return;
    }
    // A descoberta do plugin é um GET sem conteúdo no loopback, não um serviço externo.
    if (request.method() === 'GET' && request.url() === 'http://127.0.0.1:5199/v1/ola') {
      sondagensLocais.push(request.url()); return;
    }
    if (/^https?:/.test(request.url()) && new URL(request.url()).origin !== new URL(site).origin &&
        (!modeloLocal || new URL(request.url()).origin !== new URL(modeloLocal).origin)) externos.push(request.url());
  });
  await page.goto(`${site}/converter`, { waitUntil: "load" });
  console.log('Site carregado; preparando cache PWA.');
  await page.locator('[data-offline-pronto="true"]').waitFor({ timeout: 180000 });
  await page.getByRole("button", { name: "Preparar narração offline", exact: true }).click();
  console.log('Baixando e verificando modelo Kokoro.');
  await page.locator('[data-kokoro-pronto="true"]').waitFor({ timeout: 180000 });
  console.log('Modelo preparado. Cortando a rede para as conversões.');
  const pdf = await PDFDocument.create();
  const fonte = await pdf.embedFont(StandardFonts.Helvetica);
  pdf.addPage().drawText("Esta narracao de PDF funciona sem internet.", { font: fonte, size: 16 });
  const docx = zipSync({ "word/document.xml": strToU8('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Este documento é narrado no aparelho.</w:t></w:r></w:p></w:body></w:document>') });
  const png = await page.evaluate(async () => {
    const canvas = document.createElement("canvas"); canvas.width = 1000; canvas.height = 160;
    const ctx = canvas.getContext("2d"); ctx.fillStyle = "white"; ctx.fillRect(0, 0, 1000, 160);
    ctx.fillStyle = "black"; ctx.font = "48px Arial"; ctx.fillText("NARRACAO SEM INTERNET", 20, 100);
    return canvas.toDataURL().split(",")[1];
  });
  const arquivo = (name, buffer, mimeType = "text/plain") => ({ name, buffer: Buffer.from(buffer), mimeType });
  const casos = [
    ["Dora TXT", arquivo("texto.txt", "Olá. Esta narração Kokoro funciona sem internet, em português do Brasil."), "pf_dora", "wav"],
    ["Alex TXT", arquivo("texto.txt", "Olá. Esta é a voz Alex narrando no seu computador."), "pm_alex", "wav"],
    ["Santa TXT", arquivo("texto.txt", "Olá. Esta é a voz Santa narrando localmente."), "pm_santa", "wav"],
    ["PDF", arquivo("texto.pdf", await pdf.save(), "application/pdf"), "pf_dora", "mp3"],
    ["DOCX", arquivo("texto.docx", docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), "pf_dora", "wav"],
    ["MD", arquivo("texto.md", "# Documento\nEste **texto** funciona sem internet."), "pm_alex", "wav"],
    ["HTML", arquivo("texto.html", '<p>Texto em HTML, narrado localmente.</p><img src="https://invalid.example/conteudo-privado"><iframe src="https://invalid.example/pagina"></iframe><script>não narrar</script>', "text/html"), "pm_santa", "mp3"],
    ["SRT", arquivo("legenda.srt", "1\n00:00:00,000 --> 00:00:02,000\nLegenda narrada sem internet.\n"), "pf_dora", "wav"],
    ["Imagem OCR", arquivo("texto.png", Buffer.from(png, "base64"), "image/png"), "pf_dora", "mp3"],
    ["Texto em blocos", arquivo("longo.txt", "Esta frase em português precisa ser lida até o fim. ".repeat(5) + "Fim do documento."), "pf_dora", "wav"],
  ];
  // Fixture privada opcional: o arquivo nunca entra no repositório ou em pedidos HTTP.
  if (process.env.KOKORO_TEST_IMAGE) casos.push([
    'Imagem de regressão informada localmente',
    arquivo('regressao.jpg', readFileSync(resolve(process.env.KOKORO_TEST_IMAGE)), 'image/jpeg'),
    'pf_dora', 'wav',
  ]);
  semRede = true;
  await context.setOffline(true);
  for (const [nome, entrada, voz, formato] of casos) {
    console.log(`Testando ${nome} com rede desligada...`);
    await page.goto(`${site}/converter`, { waitUntil: "load" });
    await context.setOffline(true);
    assert.equal(await page.evaluate(async () => {
      try { await fetch("/api/conta?teste-kokoro-offline=1", { cache: "no-store", signal: AbortSignal.timeout(3000) }); return false; }
      catch { return true; }
    }), true);
    await page.locator('[data-kokoro-pronto="true"]').waitFor();
    await page.locator('input[type="file"]').setInputFiles(entrada);
    const grupo = page.locator(".grupo").first();
    await grupo.locator(".grupo__ferramenta select").selectOption("voz.narrar");
    for (const [rotulo, valor] of [["Voz Kokoro-82M", voz], ["Formato de saída", formato]])
      await grupo.locator("label").filter({ has: page.getByText(rotulo, { exact: true }) }).locator("select").selectOption(valor);
    await grupo.locator(".grupo__acoes button").click();
    await page.waitForFunction(() => document.querySelector(".job--done, .job--failed") !== null, null, { timeout: 180000 });
    if (await page.locator(".job--failed").count()) throw new Error(`${nome}: ${await page.locator(".job__error").innerText()}`);
    const audio = await page.locator('.job--done a[download]').first().evaluate(async (link) => {
      const bytes = await (await fetch(link.href)).arrayBuffer();
      const ctx = new OfflineAudioContext(1, 1, 24000);
      const pcm = await ctx.decodeAudioData(bytes.slice(0));
      let picoPcm = null;
      if (link.download.endsWith(".wav")) {
        picoPcm = 0; const view = new DataView(bytes);
        for (let i = 44; i + 1 < bytes.byteLength; i += 2) picoPcm = Math.max(picoPcm, Math.abs(view.getInt16(i, true)) / 32768);
      }
      return { bytes: bytes.byteLength, duracao: pcm.duration, pico: pcm.getChannelData(0).reduce((max, sample) => Math.max(max, Math.abs(sample)), 0), picoPcm, nome: link.download };
    });
    console.log(JSON.stringify(audio));
    assert.ok(audio.bytes > 5000); assert.ok(audio.duracao > .5); assert.ok(audio.pico > .01);
    assert.ok(audio.nome.endsWith(`.${formato}`));
    console.log(`✓ ${nome}: ${audio.duracao.toFixed(1)} s, ${audio.bytes} bytes, sem internet.`);
  }
  assert.deepEqual(erros, []); assert.deepEqual(envios, []); assert.deepEqual(externos, []);
  console.log(`OK: ${casos.length} narrações Kokoro reais, três vozes, MP3/WAV, recarga offline e nenhum envio de conversão. ${sondagensLocais.length} sondagens GET sem conteúdo no loopback do plugin. ${metricas.length} métricas reconhecidas antes de qualquer entrada.`);
} catch (error) {
  console.error(error);
  if (pagina) {
    console.error(await pagina.locator('[aria-label="Preparação da narração Kokoro-82M"]').innerText().catch(() => ''));
    console.error('Armazenamento:', await pagina.evaluate(() => navigator.storage.estimate()).catch(() => null));
  }
  throw error;
} finally {
  await browser.close();
  if (servidorDoModelo) {
    servidorDoModelo.closeAllConnections();
    await new Promise((resolve) => servidorDoModelo.close(resolve));
  }
}
