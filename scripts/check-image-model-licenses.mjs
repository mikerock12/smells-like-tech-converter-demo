import { createHash } from "node:crypto";
import { isDeepStrictEqual } from "node:util";
import { readFile, readdir } from "node:fs/promises";
import { basename, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const raiz = fileURLToPath(new URL("../", import.meta.url));
const pastaPesos = "packages/image-editor-models/weights";
const { $defs: { model } } = JSON.parse(await readFile(new URL("../packages/image-editor-models/schema.json", import.meta.url), "utf8"));
const termosRestritos = /(?:noncommercial|non-commercial|research.only|academic.only|(?:^|-)NC(?:-|$)|unknown|unspecified)/i;

export async function conferirModelos(base, manifesto) {
  if (manifesto.version !== 1 || !Array.isArray(manifesto.models)) throw new Error("Manifesto de modelos incompatível.");
  const ids = new Set();
  const aprovados = new Set();
  for (const m of manifesto.models) {
    if (!m || model.required.some(campo => !Object.hasOwn(m, campo))) throw new Error("Entrada incompleta no inventário de pesos.");
    if (!/^[0-9a-f]{64}$/.test(m.sha256) || !model.properties.origin.enum.includes(m.origin)) throw new Error(`Proveniência inválida em ${m.id}.`);
    if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(m.id) || ids.has(m.id)) throw new Error("Identificador inválido ou duplicado.");
    ids.add(m.id);
    if (!["approved", "pending", "blocked", "licensed-install"].includes(m.status)) throw new Error(`Situação inválida em ${m.id}.`);
    if (m.origin === "trained-in-house" && (m.status === "approved" || m.redistributionAllowed !== false || m.distributionFile !== null)) {
      throw new Error(`Os pesos próprios do editor são privados: ${m.id}.`);
    }
    if (m.status === "licensed-install") {
      const parecer = JSON.parse(await readFile(join(base, "docs/image-editor/licensing/approvals", m.id + ".json"), "utf8"));
      if (m.origin !== "trained-in-house" || !m.commercialAllowed || !m.trainingAllowed || !m.weightsLicense ||
          !parecer.reviewedBy || !parecer.reviewedAt || !isDeepStrictEqual(parecer.approved, m)) {
        throw new Error(`Instalação licenciada sem parecer correspondente: ${m.id}.`);
      }
    }
    if (m.status !== "approved") {
      if (m.distributionFile !== null) throw new Error(`Distribuição sem aprovação em ${m.id}.`);
      continue;
    }
    if (m.commercialAllowed !== true || m.redistributionAllowed !== true || !m.codeSpdx || !m.weightsLicense || termosRestritos.test(m.weightsLicense) || termosRestritos.test(m.codeSpdx)) {
      throw new Error(`Permissão comercial ou redistribuição pendente em ${m.id}.`);
    }
    if (!m.owner || !m.weightsSource || !m.codeSource || !m.review || !m.datasetRegister || !m.origin || (m.origin === "trained-in-house" && m.trainingAllowed !== true)) {
      throw new Error(`Origem ou dados sem revisão em ${m.id}.`);
    }
    const parecer = JSON.parse(await readFile(join(base, "docs/image-editor/licensing/approvals", `${m.id}.json`), "utf8"));
    if (!parecer.reviewedBy || !/^\d{4}-\d{2}-\d{2}$/.test(parecer.reviewedAt) || !isDeepStrictEqual(parecer.approved, m)) {
      throw new Error(`O parecer não corresponde a esta versão de ${m.id}.`);
    }
    for (const evidencia of [m.review, m.datasetRegister]) {
      if (!/^docs\/image-editor\/licensing\/[A-Za-z0-9_-]+\.md$/.test(evidencia) || !(await readFile(join(base, evidencia), "utf8")).trim()) {
        throw new Error(`Evidência ausente em ${m.id}.`);
      }
    }
    const nome = m.distributionFile;
    if (typeof nome !== "string" || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(nome) || basename(nome) !== nome || aprovados.has(nome)) {
      throw new Error(`Nome de artefato inválido em ${m.id}.`);
    }
    const hash = createHash("sha256").update(await readFile(join(base, pastaPesos, nome))).digest("hex");
    if (hash !== m.sha256) throw new Error(`SHA-256 divergente em ${m.id}.`);
    aprovados.add(nome);
  }
  for (const pasta of [pastaPesos, "public/modelos/imagem", "android/app/src/main/assets/image-editor/weights", "desktop/artifacts/app/image-editor/weights", "desktop/artifacts/plugin/image-editor/weights"]) {
    let arquivos;
    try { arquivos = await readdir(join(base, pasta), { withFileTypes: true }); }
    catch (erro) { if (erro.code !== "ENOENT") throw erro; continue; }
    for (const f of arquivos) {
      if (!f.isFile() || !aprovados.has(f.name)) throw new Error(`Peso fora do inventário aprovado: ${pasta}/${f.name}`);
      if (pasta !== pastaPesos) {
        const modelo = manifesto.models.find(m => m.distributionFile === f.name);
        const hash = createHash("sha256").update(await readFile(join(base, pasta, f.name))).digest("hex");
        if (hash !== modelo.sha256) throw new Error(`SHA-256 da cópia divergente: ${pasta}/${f.name}`);
      }
    }
  }
  return aprovados.size;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const manifesto = JSON.parse(await readFile(join(raiz, "packages/image-editor-models/models.manifest.json"), "utf8"));
  const total = await conferirModelos(raiz, manifesto);
  console.log(`Pesos do editor habilitados para distribuição: ${total}.`);
}
