export const MAX_BYTES_OPENVERSE = 80 * 1024 * 1024;
export const MAX_MS_OPENVERSE = 15 * 60 * 1000;
export const ID_OPENVERSE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const texto = (valor, max = 250) => typeof valor === "string" ? valor.trim().slice(0, max) : "";
const positivo = (valor) => Number.isSafeInteger(valor) && valor > 0 ? valor : null;

function urlHttps(valor) {
  if (typeof valor !== "string" || valor.length > 2048) return null;
  try {
    const url = new URL(valor);
    if (url.protocol === "https:" && !url.username && !url.password && !url.port && !url.hash) return url;
  } catch { /* metadado inválido */ }
  return null;
}

export function normalizarAudioOpenverse(item, categoria) {
  if (!item || typeof item.id !== "string" || !ID_OPENVERSE.test(item.id) || item.license !== "cc0" || item.license_version !== "1.0" || item.mature === true) return null;
  if (item.category && item.category !== categoria) return null;
  const licenca = urlHttps(item.license_url);
  const origem = urlHttps(item.foreign_landing_url);
  if (!origem || licenca?.hostname !== "creativecommons.org" ||
      !/^\/publicdomain\/zero\/1\.0\/(?:deed(?:\.[\w-]+)?\/?)?$/.test(licenca.pathname)) return null;
  return {
    id: item.id.toLowerCase(), titulo: texto(item.title) || "Áudio sem título",
    autor: texto(item.creator) || null, fonte: texto(item.source, 80), categoria,
    generos: Array.isArray(item.genres) ? item.genres.slice(0, 10).map((g) => texto(g, 60)).filter(Boolean) : [],
    duracaoMs: positivo(item.duration), tamanho: positivo(item.filesize),
    licenca: "https://creativecommons.org/publicdomain/zero/1.0/",
    origem: origem.href, midia: urlHttps(item.url)?.href ?? null, credito: texto(item.attribution, 500),
  };
}

export function decidirAudioOpenverse(item, revisao) {
  const { midia, ...publico } = item;
  const estado = revisao?.estado ?? "PENDING";
  const aprovada = estado === "APPROVED" && item.categoria === revisao.categoria &&
    item.origem === revisao.origem && midia === revisao.midia &&
    (!item.duracaoMs || item.duracaoMs <= MAX_MS_OPENVERSE) &&
    (!item.tamanho || item.tamanho <= MAX_BYTES_OPENVERSE) &&
    /^[0-9a-f]{64}$/.test(revisao.sha256) &&
    /^https:\/\/upload\.wikimedia\.org\/wikipedia\/commons\//.test(midia ?? "") &&
    /\.(mp3|wav|ogg|oga)$/i.test(new URL(midia).pathname);
  if (!aprovada) return { ...publico, estado: estado === "APPROVED" ? "RECHECK" : estado };
  return { ...publico, estado, midia, sha256: revisao.sha256, revisadoEm: revisao.revisado_em };
}
