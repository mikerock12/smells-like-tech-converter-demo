#!/usr/bin/env node
/**
 * O app para Android: a chave de assinatura e os pacotes assinados.
 *
 *   npm run android:chave         cria a chave oficial (uma vez na vida; nunca sobrescreve)
 *   npm run android:certificado   mostra a impressão digital SHA-256 do certificado (o Google pede no registro do app)
 *   npm run android:aab           testes + pacote da Google Play (.aab) assinado em android/artifacts
 *   npm run android:apk           testes + APK da variante "site" assinado, para instalar num celular de teste
 *   npm run android:copia         cópia criptografada da chave (gpg, AES-256) na pasta das chaves no Drive
 *   npm run android:nativos       baixa o FFmpeg do app (release do GitHub) para android/app/src/main/jniLibs
 *
 * A chave mora em ~/.smellsliketech/android/, fora do repositório e das worktrees (que são
 * apagadas). Perder a chave é não conseguir mais atualizar o app nos celulares de quem já
 * instalou: guarde uma cópia criptografada no Drive, como as chaves das licenças.
 *
 * O app sai só pela Google Play (decidido em 30/09/2026): depois do `aab`, envie o .aab no
 * Play Console. O site não oferece APK para baixar.
 *
 * O FFmpeg (vídeo, áudio e transcrição) é compilado no GitHub Actions por
 * android/nativos/compilar.sh e publicado numa release; android/nativos/versao.json diz qual
 * release e o SHA-256 de cada executável. `apk` e `aab` baixam e conferem antes de compilar.
 */
import { spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const raiz = join(dirname(fileURLToPath(import.meta.url)), "..");
const projeto = join(raiz, "android");
const pastaDaChave = join(homedir(), ".smellsliketech", "android");
const propriedades = join(pastaDaChave, "assinatura.properties");
const chave = join(pastaDaChave, "converter-android.jks");
const APELIDO = "converter";
/** A pasta do Drive onde já estão as chaves das licenças (mesma senha, mesmo formato). */
const PASTA_DAS_CHAVES = "G:\\Meu Drive\\Smells Like Tech Converter — Chaves das licenças";

const sdk = process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA ?? "", "Android", "Sdk");
const apksigner = () => {
  const versoes = ["36.0.0", "35.0.0", "34.0.0"].map((versao) => join(sdk, "build-tools", versao, process.platform === "win32" ? "apksigner.bat" : "apksigner"));
  const achado = versoes.find(existsSync);
  if (!achado) falhar(`Não achei o apksigner em ${join(sdk, "build-tools")}.`);
  return achado;
};

function falhar(mensagem) {
  console.error(`✗ ${mensagem}`);
  process.exit(1);
}

function rodar(comando, argumentos, opcoes = {}) {
  const noWindows = process.platform === "win32";
  // No Windows as aspas são consumidas pelo shell. No Linux (CI) o processo recebe os
  // argumentos diretamente: aspas literais virariam parte do caminho do download.
  const semAspasExternas = (valor) => valor.startsWith('"') && valor.endsWith('"') ? valor.slice(1, -1) : valor;
  const resultado = spawnSync(noWindows ? comando : semAspasExternas(comando), noWindows ? argumentos : argumentos.map(semAspasExternas), { encoding: "utf8", shell: noWindows, ...opcoes });
  if (resultado.status !== 0) falhar(`${comando} ${argumentos.join(" ")} falhou:\n${resultado.stderr || resultado.stdout}`);
  return resultado.stdout;
}

function lerPropriedades() {
  if (!existsSync(propriedades)) falhar(`Não existe a chave de publicação em ${pastaDaChave}. Crie com npm run android:chave (ou restaure a cópia do Drive).`);
  return Object.fromEntries(
    readFileSync(propriedades, "utf8")
      .split(/\r?\n/)
      .filter((linha) => linha.includes("=") && !linha.startsWith("#"))
      .map((linha) => [linha.slice(0, linha.indexOf("=")).trim(), linha.slice(linha.indexOf("=") + 1).trim().replace(/\\:/g, ":").replace(/\\\\/g, "\\")]),
  );
}

function impressaoDigital() {
  const { senha } = lerPropriedades();
  const saida = rodar("keytool", ["-list", "-v", "-keystore", `"${chave}"`, "-alias", APELIDO, "-storepass:env", "SLT_SENHA"], {
    env: { ...process.env, SLT_SENHA: senha },
  });
  return /SHA256:\s*([0-9A-F:]+)/i.exec(saida)?.[1] ?? falhar("O keytool não mostrou o SHA-256 do certificado.");
}

const comando = process.argv[2];

if (comando === "chave") {
  if (existsSync(chave) || existsSync(propriedades)) {
    falhar(`Já existe uma chave em ${pastaDaChave}. Ela não é substituída: trocar a chave impede atualizar o app de quem já instalou.`);
  }
  mkdirSync(pastaDaChave, { recursive: true });
  const senha = randomBytes(24).toString("base64url");
  rodar(
    "keytool",
    [
      "-genkeypair", "-keystore", `"${chave}"`, "-alias", APELIDO,
      "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
      "-dname", `"CN=Smells Like Tech Converter, O=Smells Like Tech Informatica, L=Porto Alegre, ST=RS, C=BR"`,
      "-storetype", "PKCS12", "-storepass:env", "SLT_SENHA", "-keypass:env", "SLT_SENHA",
    ],
    { env: { ...process.env, SLT_SENHA: senha } },
  );
  const caminho = chave.replace(/\\/g, "\\\\").replace(/:/g, "\\:");
  writeFileSync(
    propriedades,
    `# Chave de publicação do app para Android. NÃO versionar, NÃO compartilhar.\n# Cópia criptografada no Drive, junto das chaves das licenças.\narquivo=${caminho}\napelido=${APELIDO}\nsenha=${senha}\n`,
  );
  console.log(`✓ Chave criada em ${pastaDaChave}`);
  console.log(`  Certificado (SHA-256): ${impressaoDigital()}`);
  console.log("  Agora: guarde uma cópia criptografada desta pasta no Drive. Sem ela, o app não recebe mais atualizações.");
} else if (comando === "certificado") {
  console.log(impressaoDigital());
} else if (comando === "copia") {
  // O gpg pede a senha numa janela própria (pinentry): ela não passa por este script
  // nem pelo terminal. Use a mesma senha do arquivo das chaves das licenças.
  lerPropriedades();
  const destino = process.argv[3] ?? PASTA_DAS_CHAVES;
  if (!existsSync(destino)) falhar(`Não achei a pasta ${destino}. Passe outra: npm run android:copia -- "<pasta>"`);
  const gpg = ["C:\\Program Files\\Git\\usr\\bin\\gpg.exe", "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe", "C:\\Program Files\\GnuPG\\bin\\gpg.exe"].find(existsSync) ?? "gpg";
  const saida = join(destino, `chave-android-smells-like-tech-converter-${new Date().toISOString().slice(0, 10)}.tar.asc`);
  if (existsSync(saida)) falhar(`Já existe ${saida}.`);
  const pacote = join(tmpdir(), `slt-android-${randomBytes(6).toString("hex")}.tar`);
  try {
    rodar("tar", ["-cf", `"${pacote}"`, "-C", `"${pastaDaChave}"`, "converter-android.jks", "assinatura.properties"]);
    const resultado = spawnSync(gpg, ["--symmetric", "--cipher-algo", "AES256", "--armor", "--output", saida, pacote], { stdio: "inherit" });
    if (resultado.status !== 0) falhar("O gpg não gravou a cópia (senha cancelada?).");
  } finally {
    rmSync(pacote, { force: true });
  }
  console.log(`✓ Cópia criptografada em ${saida}`);
  // --output, e não "> chave.tar": no PowerShell o redirecionamento vira texto e estraga o .tar.
  console.log(`  Para restaurar: gpg --output chave.tar --decrypt "${saida}" e tar -xf chave.tar -C "${pastaDaChave}"`);
} else if (comando === "nativos") {
  garantirNativos();
} else if (comando === "apk") {
  lerPropriedades();
  const versao = versaoDoApp();
  compilar(":app:assembleSiteRelease");

  const gerado = join(projeto, "app", "build", "outputs", "apk", "site", "release", "app-site-release.apk");
  if (!existsSync(gerado)) falhar(`O Gradle não gerou ${gerado} (o APK sem assinatura sai com outro nome: confira a chave).`);
  const certificado = rodar(`"${apksigner()}"`, ["verify", "--print-certs", `"${gerado}"`]);
  const assinadoCom = /SHA-256 digest:\s*([0-9a-f]+)/i.exec(certificado)?.[1]?.toUpperCase();
  const esperado = impressaoDigital().replace(/:/g, "");
  if (assinadoCom !== esperado) falhar(`O APK não está assinado com a chave de publicação (achei ${assinadoCom ?? "nenhuma"}).`);

  const arquivo = `SmellsLikeTechConverter-android-${versao}.apk`;
  mkdirSync(join(projeto, "artifacts"), { recursive: true });
  const destino = join(projeto, "artifacts", arquivo);
  copyFileSync(gerado, destino);
  const sha256 = createHash("sha256").update(readFileSync(destino)).digest("hex");
  console.log(`✓ ${arquivo} · ${(statSync(destino).size / 1048576).toFixed(1)} MB · sha256 ${sha256}`);
  console.log("  Para instalar num celular de teste. O app sai só pela Google Play: o site não oferece APK.");
} else if (comando === "aab") {
  lerPropriedades();
  // Sem a chave de licenciamento, a versão da Play não reconheceria compra nenhuma.
  const configuracao = readFileSync(join(projeto, "app", "build.gradle.kts"), "utf8");
  if (!/val chaveDaPlay = "[A-Za-z0-9+/=]{200,}"/.test(configuracao)) {
    falhar("Falta a chave de licenciamento da Play em android/app/build.gradle.kts (val chaveDaPlay). Ela fica no Play Console → Monetização → Licenciamento.");
  }
  const versao = versaoDoApp();
  compilar(":app:bundlePlayRelease");

  const gerado = join(projeto, "app", "build", "outputs", "bundle", "playRelease", "app-play-release.aab");
  if (!existsSync(gerado)) falhar(`O Gradle não gerou ${gerado}.`);
  const certificado = rodar("keytool", ["-printcert", "-jarfile", `"${gerado}"`]);
  const assinadoCom = /SHA256:\s*([0-9A-F:]+)/i.exec(certificado)?.[1];
  if (assinadoCom !== impressaoDigital()) falhar(`O pacote não está assinado com a chave de publicação (achei ${assinadoCom ?? "nenhuma"}).`);

  const arquivo = `SmellsLikeTechConverter-play-${versao}.aab`;
  mkdirSync(join(projeto, "artifacts"), { recursive: true });
  const destino = join(projeto, "artifacts", arquivo);
  copyFileSync(gerado, destino);
  console.log(`✓ ${arquivo} · ${(statSync(destino).size / 1048576).toFixed(1)} MB · versionCode ${/versionCode\s*=\s*(\d+)/.exec(configuracao)?.[1]}`);
  console.log(`  Agora: envie ${destino} no Play Console (Testar e lançar → a faixa de teste ou produção).`);
} else {
  console.error("Uso: node scripts/android.mjs chave | certificado | copia [pasta] | nativos | apk | aab");
  process.exit(1);
}

function versaoDoApp() {
  const versao = /versionName\s*=\s*"([^"]+)"/.exec(readFileSync(join(projeto, "app", "build.gradle.kts"), "utf8"))?.[1];
  return versao ?? falhar("Não achei o versionName em android/app/build.gradle.kts.");
}

/**
 * O executável do FFmpeg de cada ABI em android/app/src/main/jniLibs, conferido pelo SHA-256
 * de android/nativos/versao.json. Baixa da release (repositório privado: pelo gh, com o
 * login de quem roda) só o que falta ou não confere.
 */
function garantirNativos() {
  const { release, sha256 } = JSON.parse(readFileSync(join(projeto, "nativos", "versao.json"), "utf8"));
  const resumo = (arquivo) => createHash("sha256").update(readFileSync(arquivo)).digest("hex");
  for (const [abi, esperado] of Object.entries(sha256)) {
    const destino = join(projeto, "app", "src", "main", "jniLibs", abi, "libffmpeg.so");
    if (existsSync(destino) && resumo(destino) === esperado) continue;
    const temporaria = join(tmpdir(), `slt-nativos-${randomBytes(6).toString("hex")}`);
    mkdirSync(temporaria, { recursive: true });
    try {
      rodar("gh", ["release", "download", release, "--pattern", `libffmpeg-${abi}.so`, "--dir", `"${temporaria}"`], { cwd: raiz });
      const baixado = join(temporaria, `libffmpeg-${abi}.so`);
      const achado = resumo(baixado);
      if (achado !== esperado) falhar(`O FFmpeg de ${abi} da release ${release} não confere (SHA-256 ${achado}).`);
      mkdirSync(dirname(destino), { recursive: true });
      copyFileSync(baixado, destino);
      console.log(`✓ FFmpeg ${abi} · ${(statSync(destino).size / 1048576).toFixed(1)} MB (${release})`);
    } finally {
      rmSync(temporaria, { recursive: true, force: true });
    }
  }
  if (!existsSync(join(projeto, "app", "src", "main", "assets", "licencas-nativas.txt"))) {
    falhar("Falta android/app/src/main/assets/licencas-nativas.txt: rode bash android/nativos/licencas.sh.");
  }
}

/** Os testes das duas variantes (a licença e o recibo valem nas duas) e a tarefa pedida. */
function compilar(tarefa) {
  garantirNativos();
  const gradlew = join(projeto, process.platform === "win32" ? "gradlew.bat" : "gradlew");
  rodar(`"${gradlew}"`, [":app:testSiteDebugUnitTest", ":app:testPlayDebugUnitTest", tarefa, "--console=plain"], { cwd: projeto, stdio: "inherit" });
}
