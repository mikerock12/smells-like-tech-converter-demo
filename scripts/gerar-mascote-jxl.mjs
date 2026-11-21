import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import decodeWebp, { init as iniciarWebp } from "@jsquash/webp/decode.js";
import encodeJxl, { init as iniciarJxl } from "@jsquash/jxl/encode.js";

const pasta = new URL("../public/marca/", import.meta.url);
for (const [iniciar, caminho] of [
  [iniciarWebp, "@jsquash/webp/codec/dec/webp_dec.wasm"],
  [iniciarJxl, "@jsquash/jxl/codec/enc/jxl_enc.wasm"],
]) {
  await iniciar(await WebAssembly.compile(await readFile(new URL(import.meta.resolve(caminho)))));
}
const webp = await readFile(new URL("mascote.webp", pasta));
const pixels = await decodeWebp(webp);
const jxl = await encodeJxl(pixels, { quality: 85, effort: 7 });
assert.ok(jxl.byteLength < webp.length, "Compare os dois formatos antes de adotar uma nova variante.");
await writeFile(new URL("mascote.jxl", pasta), new Uint8Array(jxl));
console.log(`Mascote ${pixels.width} × ${pixels.height}. WebP ${webp.length} bytes, JPEG XL ${jxl.byteLength} bytes.`);
