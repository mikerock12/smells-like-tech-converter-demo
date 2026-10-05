/**
 * Chaves de acesso: formato, assinatura e verificação.
 *
 * Uma chave é `SLT1.<carga>.<assinatura>`: a carga é um JSON em base64url com o plano,
 * o e-mail e as datas; a assinatura é ECDSA P-256 sobre SHA-256, feita pelo servidor
 * com a chave privada e conferida em qualquer lugar com a chave pública — no site, no
 * plugin e no aplicativo, sem consultar servidor nenhum.
 *
 * Por que assim: o produto promete funcionar sem internet e sem cadastro. Uma licença
 * que precisasse ser validada online quebraria as duas promessas. Com assinatura, a
 * chave se prova sozinha; o que a chave diz não pode ser alterado sem invalidá-la.
 *
 * Usa só WebCrypto (`globalThis.crypto.subtle`): funciona no navegador, no Worker da
 * Cloudflare e no Node 22. O aplicativo em C# verifica o mesmo formato com
 * `ECDsa.VerifyData` — P-256, SHA-256, assinatura bruta r||s de 64 bytes, que é o que
 * o WebCrypto produz.
 */

export const PREFIXO = "SLT1";

const ALGORITMO = { name: "ECDSA", namedCurve: "P-256" };
const ASSINATURA = { name: "ECDSA", hash: "SHA-256" };

export function paraBase64Url(bytes) {
  let binario = "";
  for (const byte of bytes) binario += String.fromCharCode(byte);
  return btoa(binario).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function deBase64Url(texto) {
  const normalizado = String(texto).replace(/-/g, "+").replace(/_/g, "/");
  const completo = normalizado + "=".repeat((4 - (normalizado.length % 4)) % 4);
  const binario = atob(completo);
  const bytes = new Uint8Array(binario.length);
  for (let indice = 0; indice < binario.length; indice += 1) bytes[indice] = binario.charCodeAt(indice);
  return bytes;
}

/**
 * Serialização canônica da carga: chaves em ordem fixa, sem espaços. A assinatura cobre
 * exatamente estes bytes, então os dois lados precisam produzir a mesma sequência.
 */
export function serializarCarga(carga) {
  const ordenada = {
    v: carga.v,
    id: carga.id,
    plano: carga.plano,
    email: carga.email,
    emitida: carga.emitida,
    expira: carga.expira,
    maquinas: carga.maquinas,
  };
  return new TextEncoder().encode(JSON.stringify(ordenada));
}

export function montarChave(carga, assinatura) {
  return `${PREFIXO}.${paraBase64Url(serializarCarga(carga))}.${paraBase64Url(assinatura)}`;
}

function cargaValida(carga) {
  return (
    carga !== null &&
    typeof carga === "object" &&
    carga.v === 1 &&
    typeof carga.id === "string" &&
    carga.id.length > 0 &&
    typeof carga.plano === "string" &&
    typeof carga.email === "string" &&
    typeof carga.emitida === "string" &&
    (carga.expira === null || typeof carga.expira === "string") &&
    Number.isInteger(carga.maquinas)
  );
}

/**
 * Separa a chave em carga e assinatura, sem verificar nada ainda. Devolve nulo para
 * qualquer coisa que não tenha a forma esperada: texto colado com espaços, chave
 * truncada, JSON estranho.
 */
export function lerChave(texto) {
  const limpo = String(texto ?? "").trim().replace(/\s+/g, "");
  const partes = limpo.split(".");
  if (partes.length !== 3 || partes[0] !== PREFIXO) return null;

  try {
    const assinado = deBase64Url(partes[1]);
    const assinatura = deBase64Url(partes[2]);
    const carga = JSON.parse(new TextDecoder().decode(assinado));
    if (!cargaValida(carga)) return null;

    // A carga só vale se for exatamente a serialização canônica: uma chave remontada
    // com espaços ou noutra ordem teria sido assinada sobre outros bytes.
    const canonica = serializarCarga(carga);
    if (canonica.length !== assinado.length || !canonica.every((byte, indice) => byte === assinado[indice])) {
      return null;
    }

    if (assinatura.length !== 64) return null;
    return { carga, assinatura, assinado };
  } catch {
    return null;
  }
}

/** Mostra a chave em linhas curtas, para caber na tela. */
export function chaveLegivel(chave) {
  return String(chave).replace(/(.{40})/g, "$1\n").trim();
}

function comoArrayBuffer(bytes) {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

/** Gera um par novo. Só o operador do site roda isto, uma vez, ao publicar. */
export async function gerarParDeChaves() {
  const par = await crypto.subtle.generateKey(ALGORITMO, true, ["sign", "verify"]);
  const publica = new Uint8Array(await crypto.subtle.exportKey("spki", par.publicKey));
  const privada = new Uint8Array(await crypto.subtle.exportKey("pkcs8", par.privateKey));
  return { publica: paraBase64Url(publica), privada: paraBase64Url(privada) };
}

export function importarPublica(spkiBase64Url) {
  return crypto.subtle.importKey("spki", comoArrayBuffer(deBase64Url(spkiBase64Url)), ALGORITMO, false, ["verify"]);
}

export function importarPrivada(pkcs8Base64Url) {
  return crypto.subtle.importKey("pkcs8", comoArrayBuffer(deBase64Url(pkcs8Base64Url)), ALGORITMO, false, ["sign"]);
}

export async function assinarLicenca(carga, privada) {
  const bytes = serializarCarga(carga);
  const assinatura = new Uint8Array(await crypto.subtle.sign(ASSINATURA, privada, bytes));
  return montarChave(carga, assinatura);
}

/**
 * Verifica a assinatura. Não olha a data: vencer é decisão de quem usa a licença — o
 * site trata Pro vencido como grátis, e o aplicativo vitalício nunca vence.
 */
export async function verificarLicenca(chave, publica) {
  const lida = lerChave(chave);
  if (!lida) return { ok: false, motivo: "formato" };

  const valida = await crypto.subtle.verify(ASSINATURA, publica, lida.assinatura, lida.assinado);
  return valida ? { ok: true, carga: lida.carga } : { ok: false, motivo: "assinatura" };
}

/** Uma licença está vencida quando tem data e a data já passou. */
export function vencida(carga, agora = Date.now()) {
  return carga.expira !== null && Date.parse(carga.expira) < agora;
}
