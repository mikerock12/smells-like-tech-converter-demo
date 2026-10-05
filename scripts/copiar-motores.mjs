#!/usr/bin/env node
/**
 * Copia para public/motores/ os arquivos que precisam ser servidos da nossa origem e
 * que o empacotador não resolve sozinho: worker, núcleos e idiomas do Tesseract,
 * mapas de caracteres, fontes e decodificadores de PDF.
 *
 * Roda antes do build e do dev. A pasta é ignorada pelo git: os binários já vivem
 * em node_modules, com versões e integridade registradas no lockfile.
 */
import { copyFileSync, cpSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const raiz = join(dirname(fileURLToPath(import.meta.url)), "..");
const destino = join(raiz, "public", "motores", "tesseract");
mkdirSync(destino, { recursive: true });

copyFileSync(join(raiz, "node_modules", "tesseract.js", "dist", "worker.min.js"), join(destino, "worker.min.js"));

const nucleo = join(raiz, "node_modules", "tesseract.js-core");
let copiados = 1;
for (const arquivo of readdirSync(nucleo)) {
  if (/^tesseract-core.*\.(js|wasm)$/.test(arquivo)) {
    copyFileSync(join(nucleo, arquivo), join(destino, arquivo));
    copiados += 1;
  }
}

console.log(`motores: ${copiados} arquivos do Tesseract em public/motores/tesseract`);

// Pacotes fixados no lockfile: nenhuma consulta a CDN durante a conversão.
const idiomas = join(destino, "idiomas");
mkdirSync(idiomas, { recursive: true });
for (const idioma of ["por", "eng", "spa"]) {
  copyFileSync(join(raiz, "node_modules", "@tesseract.js-data", idioma, "4.0.0_best_int", `${idioma}.traineddata.gz`), join(idiomas, `${idioma}.traineddata.gz`));
}
for (const pasta of ["cmaps", "standard_fonts", "wasm"]) {
  cpSync(join(raiz, "node_modules", "pdfjs-dist", pasta), join(raiz, "public", "motores", "pdfjs", pasta), { recursive: true });
}
console.log("motores: idiomas OCR e recursos de PDF servidos pelo próprio site");
