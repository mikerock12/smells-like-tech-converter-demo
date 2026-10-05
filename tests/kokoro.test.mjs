import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { VOZES, dividirTexto, locutor, velocidade, normalizarTexto } from "../packages/kokoro/config.mjs";
import vm from "node:vm";
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
  assert.match(runner, /lang: 'pt-br'/); assert.doesNotMatch(runner, /https?:/);
});

test("todos os motores configuram o dialeto brasileiro e os apps não usam int8", () => {
  const android = readFileSync(new URL("../android/app/src/main/java/br/com/smellsliketech/converter/conversao/Kokoro.kt", import.meta.url), "utf8");
  const windows = readFileSync(new URL("../desktop/src/SmellsLikeTech.Converter.Engine.Speech/KokoroTtsEngine.cs", import.meta.url), "utf8");
  assert.match(android, /lang = "pt-br"/);
  assert.match(windows, /Kokoro.Lang = "pt-br"/);
  for (const motor of [android, windows]) { assert.match(motor, /model\.onnx/); assert.doesNotMatch(motor, /model\.int8\.onnx/); }
});
test("OCR remove só linhas sem conteúdo pronunciável e preserva data, fim e NFC", () => {
  assert.equal(normalizarTexto("  ATENC\u0327A\u0303O\n)\nDIA\t02/10\n1\nNÃO TERÃO AULA.\n!!!"), "ATENÇÃO\nDIA 02/10\n1\nNÃO TERÃO AULA.");
  assert.equal(normalizarTexto(")...\n!"), "");
});
test("worker repete NaN/silêncio em blocos menores sem descartar nenhum trecho", async () => {
  const code = readFileSync(new URL("../packages/kokoro/runner.js", import.meta.url), "utf8");
  const calls = []; const replies = [];
  const context = vm.createContext({ Float32Array, self: { postMessage: msg => replies.push(msg) }, importScripts() {} });
  vm.runInContext(code, context);
  context.fake = { generate: ({ text }) => {
    calls.push(text); return { sampleRate: 24000, samples: new Float32Array(text.length > 40 ? [NaN, 0] : [.25, -.25]) };
  } };
  vm.runInContext("engine = fake", context);
  const texto = "Primeira parte do aviso em português. Segunda parte termina aqui.";
  await context.self.onmessage({ data: { type: "generate", text: texto, sid: 42, speed: 1 } });
  assert.equal(replies[0].type, "audio");
  assert.ok(calls.length >= 3);
  assert.equal(calls.filter(t => t.length <= 40).join(" ").replace(/\s+/gu, ""), texto.replace(/\s+/gu, ""));
  assert.ok([...new Float32Array(replies[0].samples)].every(Number.isFinite));
  context.fake = { generate: () => ({ sampleRate: 24000, samples: new Float32Array([0, 0]) }) };
  vm.runInContext("engine = fake", context); replies.length = 0;
  await context.self.onmessage({ data: { type: "generate", text: texto, sid: 42, speed: 1 } });
  assert.equal(replies[0].type, "error");
  assert.match(replies[0].message, /mesmo após dividi-lo/);
});
