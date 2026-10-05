#!/usr/bin/env node
/**
 * Monta o service worker do app instalável depois do build (roda como `postbuild`).
 *
 * Lista o build em dist/client/_next/static e os motores/idiomas em /motores. Grava sw.js: uma
 * linha com `self.PWA_BUILD = { versao, arquivos, bytes }` seguida de packages/pwa/sw.js.
 * A versão resume URLs, conteúdo e o próprio service worker: recurso novo, versão nova.
 */
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const raiz = join(dirname(fileURLToPath(import.meta.url)), "..");
const cliente = join(raiz, "dist", "client");
const estatico = join(cliente, "_next", "static");

function listar(pasta) {
  return readdirSync(pasta, { withFileTypes: true }).flatMap((entrada) => {
    const caminho = join(pasta, entrada.name);
    return entrada.isDirectory() ? listar(caminho) : [caminho];
  });
}

// Inclui imports tardios, workers, fontes de PDF e os três núcleos OCR LSTM.
const motores = listar(join(cliente, "motores")).filter((caminho) => {
  const url = relative(cliente, caminho).split(sep).join("/");
  return url.startsWith("motores/kokoro/") || url.startsWith("motores/pdfjs/") || /^motores\/tesseract\/(worker\.min\.js|tesseract-core(?:-simd|-relaxedsimd)?-lstm\.wasm\.js|idiomas\/(por|eng|spa)\.traineddata\.gz)$/.test(url);
});
const arquivos = [...listar(estatico), ...motores]
  .map((caminho) => ({ url: `/${relative(cliente, caminho).split(sep).join("/")}`, bytes: statSync(caminho).size }))
  .sort((a, b) => a.url.localeCompare(b.url));

const modelo = readFileSync(join(raiz, "packages", "pwa", "sw.js"), "utf8");
const versao = createHash("sha256")
  .update(arquivos.map((arquivo) => `${arquivo.url}:${createHash("sha256").update(readFileSync(join(cliente, arquivo.url))).digest("hex")}`).join("\n"))
  .update(modelo)
  .digest("hex")
  .slice(0, 12);
const build = { versao, arquivos: arquivos.map((arquivo) => arquivo.url), bytes: arquivos.reduce((soma, arquivo) => soma + arquivo.bytes, 0) };

writeFileSync(join(cliente, "sw.js"), `self.PWA_BUILD = ${JSON.stringify(build)};\n${modelo}`);
console.log(`pwa: sw.js versão ${versao}, ${arquivos.length} arquivos (${(build.bytes / 1048576).toFixed(1)} MB) para usar sem internet`);
