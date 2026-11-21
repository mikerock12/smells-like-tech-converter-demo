import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { unzipSync } from "fflate";
import { chromium } from "playwright";

// Executar com o Worker compilado em execução. O perfil descartável usa só a imagem da fixture.
const url = new URL("/editor/imagem", process.argv[2] ?? "http://127.0.0.1:8787");
const fixture = new URL("../android/app/src/androidTest/assets/editor-navegador.sltimage", import.meta.url);
const zip = unzipSync(await readFile(fixture));
const foto = Object.entries(zip).find(([nome]) => nome.startsWith("assets/"));
assert.ok(foto, "A fixture do Android precisa conter a foto de referência.");
const browser = await chromium.launch({ channel: "chromium" });
try {
  const page = await browser.newPage({ acceptDownloads: true });
  async function importar(nome, mimeType, bytes) {
    await page.goto(url.href, { waitUntil: "domcontentloaded" });
    await page.locator("fieldset:enabled input[type=file]").setInputFiles({ name: nome, mimeType, buffer: Buffer.from(bytes) });
    await page.waitForFunction(() => {
      const c = document.querySelector("canvas");
      return c?.width === 32 && c.height === 24 && c.getContext("2d").getImageData(0, 0, 1, 1).data[3] === 255;
    });
  }
  async function baixar(rotulo) {
    const espera = page.waitForEvent("download");
    await page.getByRole("button", { name: rotulo, exact: true }).click();
    const download = await espera;
    const partes = [];
    for await (const parte of await download.createReadStream()) partes.push(parte);
    return Buffer.concat(partes);
  }
  const pixels = () => page.locator("canvas").evaluate(c => Array.from(c.getContext("2d").getImageData(0, 0, c.width, c.height).data));

  await importar("prova.png", "image/png", foto[1]);
  const original = await pixels();
  assert.deepEqual(original.slice(0, 4), [36, 156, 214, 255]);
  assert.deepEqual(original.slice(31 * 4, 32 * 4), [0, 0, 0, 0]);
  await page.getByRole("button", { name: "Adicionar texto" }).click();
  await page.getByLabel("Texto", { exact: true }).fill("A");
  const projeto = await baixar("Salvar projeto");
  const editado = await pixels();
  assert.notDeepEqual(editado, original);
  await importar("prova.sltimage", "application/octet-stream", projeto);
  assert.deepEqual(await pixels(), editado);
  const jxl = await baixar("Exportar imagem");
  await importar("prova.jxl", "image/jxl", jxl);
  assert.deepEqual(await pixels(), editado);
  await importar("prova.sltimage", "application/octet-stream", projeto);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByText("Recortar composição", { exact: true }).click();
  for (const [margem, valor] of Object.entries({ Esquerda: 8, Topo: 4, Direita: 12, Base: 10 }))
    await page.getByLabel(margem + " (px)").fill(String(valor));
  await page.getByRole("button", { name: "Aplicar recorte" }).click();
  await page.waitForFunction(() => document.querySelector("canvas")?.width === 12);
  const recorte = [];
  for (let y = 4; y < 14; y++) recorte.push(...editado.slice((y * 32 + 8) * 4, (y * 32 + 20) * 4));
  assert.deepEqual(await pixels(), recorte);
  await page.getByRole("button", { name: "Desfazer" }).click();
  await page.waitForFunction(() => document.querySelector("canvas")?.width === 32);
  assert.deepEqual(await pixels(), editado);
  await page.getByRole("button", { name: "Refazer" }).click();
  await page.waitForFunction(() => document.querySelector("canvas")?.width === 12);
  assert.deepEqual(await pixels(), recorte);
  const salvo = unzipSync(await baixar("Salvar projeto"));
  assert.deepEqual(salvo[foto[0]], foto[1]);
  const manifesto = JSON.parse(new TextDecoder().decode(salvo["manifest.json"]));
  assert.equal(manifesto.layers.length, 2);
  assert.deepEqual([manifesto.canvas.width, manifesto.canvas.height], [12, 10]);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  console.log(`Projeto de duas camadas e JXL de ${jxl.length} bytes reabertos. 768 pixels RGBA conferidos em ${url.origin}.`);
} finally {
  await browser.close();
}
