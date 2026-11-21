import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const libjxl = "8405932022a556380c2d8c272eff154a923feb197233f348ce5f7334fb0a5ede";
const avisos = {
  "public/licencas/jpeg-xl/jsquash.txt": "8c3690b09c168f196446cf5904332023bbc15eb92b6a7cee470ac829e6a65d20",
  "public/licencas/jpeg-xl/libjxl.txt": libjxl,
  "android/app/src/main/assets/licencas/jpeg-xl/libjxl.txt": libjxl,
  "android/app/src/main/assets/licencas/jpeg-xl/LICENSE-BSD.md": "2aa92cada6431e75615e3fe6cb1a9082c98f777d48ae1c087c0da0e37f7b8bff",
  "android/app/src/main/assets/licencas/jpeg-xl/LICENSE.md": "0ce66956c28eeb705d0e79100813e45ea70b1b668804a3cd29ce9d6ac2cb3f4a",
};
for (const [arquivo, esperado] of Object.entries(avisos)) {
  const texto = await readFile(new URL(`../${arquivo}`, import.meta.url), "utf8");
  assert.equal(createHash("sha256").update(texto.replace(/\r\n/g, "\n")).digest("hex"), esperado, arquivo);
}
console.log(`${Object.keys(avisos).length} avisos de JPEG XL conferidos.`);
