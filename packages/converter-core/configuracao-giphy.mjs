/** Barreira à extração estática casual. O material de abertura acompanha o cliente. */
export async function abrirConfiguracaoGiphy(valor = "") {
  if (!valor.startsWith("v1.")) return /^[A-Za-z0-9_-]{16,256}$/.test(valor) ? valor : "";
  try {
    const partes = valor.split(".").slice(1).map(p => Uint8Array.from(atob(p), c => c.charCodeAt(0)));
    if (partes.length !== 5) return "";
    const [nonce, cifra, tag, chave, mascara] = partes;
    if (nonce.length !== 12 || tag.length !== 16 || chave.length !== 32 || mascara.length !== 32) return "";
    for (let i = 0; i < chave.length; i++) chave[i] ^= mascara[i];
    const aes = await crypto.subtle.importKey("raw", chave, "AES-GCM", false, ["decrypt"]);
    chave.fill(0); mascara.fill(0);
    const dados = new Uint8Array(cifra.length + tag.length);
    dados.set(cifra); dados.set(tag, cifra.length);
    const claro = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, aes, dados));
    const texto = new TextDecoder().decode(claro);
    claro.fill(0);
    return /^[A-Za-z0-9_-]{16,256}$/.test(texto) ? texto : "";
  } catch { return ""; }
}
