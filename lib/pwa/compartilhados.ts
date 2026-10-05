/**
 * Os arquivos que alguém compartilhou com o app (no Android: Compartilhar → Converter).
 *
 * O service worker recebe o envio e guarda os arquivos no Cache Storage do aparelho
 * (packages/pwa/sw.js); a oficina abre em /converter?compartilhado=N e os busca daqui.
 * Nada disso passa pela rede.
 */

const CACHE_DOS_COMPARTILHADOS = "slt-compartilhados";

export type Recebidos = { arquivos: File[]; falhou: boolean } | null;

/** Os arquivos compartilhados, uma vez só: depois de lidos, saem do cache e da URL. */
export async function receberCompartilhados(): Promise<Recebidos> {
  const endereco = new URL(window.location.href);
  const marca = endereco.searchParams.get("compartilhado");
  if (marca === null) return null;

  endereco.searchParams.delete("compartilhado");
  window.history.replaceState(window.history.state, "", endereco);
  if (marca === "erro" || !("caches" in window)) return { arquivos: [], falhou: true };

  const cache = await caches.open(CACHE_DOS_COMPARTILHADOS);
  const arquivos: File[] = [];
  for (const pedido of await cache.keys()) {
    const resposta = await cache.match(pedido);
    if (!resposta) continue;
    const nome = decodeURIComponent(resposta.headers.get("X-Nome") ?? "arquivo");
    const conteudo = await resposta.blob();
    arquivos.push(new File([conteudo], nome, { type: conteudo.type || resposta.headers.get("Content-Type") || "" }));
  }
  await caches.delete(CACHE_DOS_COMPARTILHADOS);
  return { arquivos, falhou: arquivos.length === 0 };
}
