import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { VOZES, dividirTexto, locutor, velocidade } from "../packages/kokoro/config.mjs";
import { ferramenta, ferramentasPara } from "../packages/converter-core/catalogo.mjs";

test("as três vozes pt-BR correspondem aos locutores oficiais", () => {
  assert.deepEqual(VOZES.map((voz) => voz.sid), [42, 43, 44]);
  for (const voz of VOZES) assert.equal(locutor(voz.id), voz.sid);
  assert.throws(() => locutor("Windows"));
});
test("velocidade é compartilhada com Windows e limitada a 0.5–2x", () => {
  assert.equal(velocidade(-10), .5); assert.equal(velocidade(), 1); assert.equal(velocidade(10), 2);
  assert.throws(() => velocidade(NaN));
});
test("texto longo mantém o fim e não separa pares UTF-16", () => {
  const texto = "Uma frase em português para narrar. ".repeat(200) + "FIM DO DOCUMENTO.";
  const partes = dividirTexto(texto);
  assert.ok(partes.length > 30); assert.ok(partes.every((parte) => parte.length <= 200));
  assert.equal(partes.join("").replaceAll(" ", ""), texto.replaceAll(" ", ""));
  assert.deepEqual(dividirTexto(""), []); assert.throws(() => dividirTexto("Teste", 0));
  assert.equal(dividirTexto("😀".repeat(300), 199).join(""), "😀".repeat(300));
});
test("narração é local e não oferece planilhas ilegíveis", () => {
  assert.equal(ferramenta("voz.narrar").navegador, true);
  assert.ok(ferramentasPara("legenda", ["srt"]).some((f) => f.id === "voz.narrar"));
  assert.ok(ferramentasPara("documento", ["docx"]).some((f) => f.id === "voz.narrar"));
  assert.ok(!ferramentasPara("documento", ["xlsx"]).some((f) => f.id === "voz.narrar"));
});
test("download é GET constante de pesos públicos, não aceita arquivos", () => {
  const rota = readFileSync(new URL("../app/api/motores/kokoro/route.ts", import.meta.url), "utf8");
  assert.match(rota, /export async function GET/); assert.doesNotMatch(rota, /POST|formData|request\.body/);
  assert.match(rota, /smells-like-tech-converter-demo\/releases\/download/);
  const runner = readFileSync(new URL("../packages/kokoro/runner.js", import.meta.url), "utf8");
  assert.match(runner, /lang: 'pt'/); assert.doesNotMatch(runner, /https?:/);
});
