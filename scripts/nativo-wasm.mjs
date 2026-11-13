/**
 * Compila o núcleo nativo (native/slt-media-engine) para WebAssembly, sem libc e sem
 * Emscripten: clang --target=wasm32 em modo freestanding. A matemática (sin, cos, pow...)
 * é importada do JavaScript e a memória é a de wasm/memoria.cpp.
 *
 *   node scripts/nativo-wasm.mjs            compila e grava packages/nativo/slt_native.wasm
 *   node scripts/nativo-wasm.mjs --conferir  compila numa pasta temporária e compara com o versionado
 *
 * O clang vem de SLT_CLANG, do NDK do Android (o mesmo do app) ou do PATH. O binário é
 * versionado, como o modelo do recorte: o build do site não precisa de compilador C++.
 */

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const raiz = join(dirname(fileURLToPath(import.meta.url)), "..");
const fonte = join(raiz, "native", "slt-media-engine");
const destino = join(raiz, "packages", "nativo");
const exe = process.platform === "win32" ? ".exe" : "";

function clangDoNdk() {
  const sdks = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT, process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, "Android", "Sdk"), process.env.HOME && join(process.env.HOME, "Android", "Sdk")].filter(Boolean);
  for (const sdk of sdks) {
    const ndks = join(sdk, "ndk");
    if (!existsSync(ndks)) continue;
    for (const versao of readdirSync(ndks).sort().reverse()) {
      const pre = join(ndks, versao, "toolchains", "llvm", "prebuilt");
      if (!existsSync(pre)) continue;
      for (const host of readdirSync(pre)) {
        const clang = join(pre, host, "bin", `clang++${exe}`);
        if (existsSync(clang)) return clang;
      }
    }
  }
  return null;
}

const clang = process.env.SLT_CLANG || clangDoNdk() || "clang++";
const comuns = ["--target=wasm32", "-std=c++20", "-O2", "-ffreestanding", "-nostdlib", "-nostdlibinc", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden", "-ffp-contract=off", "-Wall", "-Wextra", "-Werror", `-I${join(fonte, "include")}`];
const fontes = ["api.cpp", "consistencia.cpp", "efeitos.cpp", "espectral.cpp", "json.cpp", "mascaras.cpp", "misturador.cpp", "motor.cpp", "restauracao.cpp"].map((f) => join(fonte, "src", f));

function compilar(pasta) {
  const objetos = [];
  for (const arquivo of [...fontes, join(fonte, "wasm", "memoria.cpp")]) {
    const objeto = join(pasta, `${arquivo.split(/[\\/]/).pop()}.o`);
    const extras = arquivo.endsWith("memoria.cpp") ? ["-fno-builtin"] : [];
    execFileSync(clang, [...comuns, ...extras, "-c", arquivo, "-o", objeto], { stdio: "inherit" });
    objetos.push(objeto);
  }
  const saida = join(pasta, "slt_native.wasm");
  execFileSync(clang, ["--target=wasm32", "-nostdlib", "-Wl,--no-entry", "-Wl,--export-dynamic", "-Wl,--strip-all", "-Wl,-z,stack-size=1048576", "-Wl,--initial-memory=2097152", ...objetos, "-o", saida], { stdio: "inherit" });
  return readFileSync(saida);
}

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const pasta = mkdtempSync(join(tmpdir(), "slt-wasm-"));
try {
  const bytes = compilar(pasta);
  const modulo = new WebAssembly.Module(bytes);
  const exportadas = WebAssembly.Module.exports(modulo).map((e) => e.name).filter((n) => n.startsWith("slt_")).sort();
  const importadas = WebAssembly.Module.imports(modulo).map((i) => `${i.module}.${i.name}`).sort();
  if (process.argv.includes("--conferir")) {
    const atual = readFileSync(join(destino, "slt_native.wasm"));
    if (sha256(atual) !== sha256(bytes)) {
      console.error(`O slt_native.wasm versionado (${sha256(atual).slice(0, 12)}) não é o que este código gera (${sha256(bytes).slice(0, 12)}). Rode node scripts/nativo-wasm.mjs com o mesmo clang do NDK ${"27.0.12077973"}.`);
      process.exit(1);
    }
    console.log(`slt_native.wasm confere: ${bytes.length} bytes, ${sha256(bytes)}`);
  } else {
    mkdirSync(destino, { recursive: true });
    writeFileSync(join(destino, "slt_native.wasm"), bytes);
    const cabecalho = readFileSync(join(fonte, "include", "slt_media_api.h"), "utf8");
    const maior = cabecalho.match(/^#define SLT_API_VERSION_MAJOR (\d+)u/m)[1];
    const menor = cabecalho.match(/^#define SLT_API_VERSION_MINOR (\d+)u/m)[1];
    writeFileSync(join(destino, "slt_native.json"), `${JSON.stringify({ arquivo: "slt_native.wasm", bytes: bytes.length, sha256: sha256(bytes), abi: `${maior}.${menor}`, compilador: execFileSync(clang, ["--version"]).toString().split("\n")[0].trim(), exportadas, importadas }, null, 2)}\n`);
    console.log(`slt_native.wasm: ${bytes.length} bytes, ${exportadas.length} funções, importa ${importadas.join(", ")}`);
  }
} finally {
  rmSync(pasta, { recursive: true, force: true });
}
