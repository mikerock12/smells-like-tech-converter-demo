import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { conferirModelos } from "../scripts/check-image-model-licenses.mjs";

test("o pacote do editor exige pesos aprovados, origem revisada e cópia idêntica", async t => {
  const raiz = await mkdtemp(join(tmpdir(), "slt-modelos-"));
  t.after(() => rm(raiz, { recursive: true, force: true }));
  const pasta = "packages/image-editor-models/weights";
  const licencas = "docs/image-editor/licensing";
  for (const dir of [pasta, `${licencas}/approvals`, "public/modelos/imagem"]) await mkdir(join(raiz, dir), { recursive: true });
  const bytes = Buffer.from("modelo sintético de teste");
  const arquivo = "referencia.bin";
  const registro = { id: "referencia", status: "approved", owner: "teste", origin: "trained-in-house", trainingAllowed: true,
    commercialAllowed: true, redistributionAllowed: true, codeSpdx: "MIT", weightsLicense: "MIT", distributionFile: arquivo,
    review: `${licencas}/REVISAO.md`, datasetRegister: `${licencas}/DADOS.md`, weightsSource: "receita-1", codeSource: "gerador-1",
    sha256: createHash("sha256").update(bytes).digest("hex") };
  const verificar = (mudanca = {}) => conferirModelos(raiz, { version: 1, models: [{ ...registro, ...mudanca }] });
  for (const doc of [registro.review, registro.datasetRegister]) await writeFile(join(raiz, doc), "Evidência sintética de teste.");
  await writeFile(join(raiz, `${licencas}/approvals/referencia.json`), JSON.stringify({ reviewedBy: "teste", reviewedAt: "2026-11-21", approved: registro }));
  await writeFile(join(raiz, pasta, arquivo), bytes);
  assert.equal(await verificar(), 1);
  await assert.rejects(verificar({ weightsLicense: "CC-BY-NC-4.0" }), /Permissão/);
  await assert.rejects(verificar({ weightsLicense: "unknown" }), /Permissão/);
  await assert.rejects(verificar({ weightsSource: "receita-2" }), /parecer/);
  await assert.rejects(verificar({ status: "pending" }), /sem aprovação/);
  const copia = join(raiz, "public/modelos/imagem", arquivo);
  await writeFile(copia, "bytes diferentes");
  await assert.rejects(verificar(), /SHA-256 da cópia/);
  await writeFile(copia, bytes);
  await writeFile(join(raiz, pasta, arquivo), "pesos alterados");
  await assert.rejects(verificar(), /SHA-256 divergente/);
  await writeFile(join(raiz, pasta, arquivo), bytes);
  await writeFile(join(raiz, pasta, "desconhecido.onnx"), bytes);
  await assert.rejects(verificar(), /fora do inventário/);
});
