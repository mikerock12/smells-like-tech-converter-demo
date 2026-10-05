/**
 * O service worker do app instalável (PWA).
 *
 * O build (scripts/pwa.mjs) grava este arquivo em dist/client/sw.js com uma linha na
 * frente: `self.PWA_BUILD = { versao, arquivos, bytes }`, a lista do que o build gerou em
 * /_next/static e /motores. É ela que deixa o app converter sem internet.
 *
 * O que ele guarda, e quando:
 * - em qualquer visita: o build inteiro, os três idiomas OCR e os recursos de PDF;
 *   só diz pronto depois que tudo foi guardado, mesmo na aba comum;
 * - no uso: cada código ou motor que a página carrega fica guardado (os do build têm hash
 *   no nome e nunca mudam; /motores também pertence à versão).
 *
 * O que ele NUNCA faz: guardar ou mandar arquivo de quem converte. /api, /baixar e
 * qualquer outro domínio passam direto, sem cache. O único arquivo de usuário que passa
 * por aqui é o compartilhado com o app (share target), e ele fica no próprio aparelho:
 * vai do Android para o Cache Storage e dali para a oficina, sem tocar a rede.
 */

const BUILD = self.PWA_BUILD ?? { versao: "dev", arquivos: [], bytes: 0 };

// Cada build leva a versão no nome; preservamos também a cópia imediatamente anterior.
const CACHE_DO_BUILD = `slt-build-${BUILD.versao}`;
const CACHE_DE_PAGINAS = `slt-paginas-${BUILD.versao}`;
// O que não muda com o build fica entre versões.
const CACHE_PUBLICO = "slt-publico";
const CACHE_DE_ESTADO = "slt-estado";
const CACHE_DOS_COMPARTILHADOS = "slt-compartilhados";

/** As páginas que abrem sem internet desde a primeira visita. */
const CASCA = ["/", "/converter", "/ferramentas", "/instalar", "/chave"];
const PAGINAS_GUARDADAS = 40;
/** Quanto a página espera a rede antes de mostrar a versão guardada (internet ruim). */
const ESPERA_DA_REDE_MS = 4000;
const MARCA_DE_PREPARADO = "/__pwa/preparado";

/**
 * Para onde vai cada pedido. Função pura: os testes a leem daqui.
 * "rede" é o comportamento normal do navegador, sem cache nenhum.
 */
function rota(url, metodo, modo, origemDoSite) {
  const endereco = new URL(url);
  if (endereco.origin !== origemDoSite) return "rede";
  if (metodo === "POST" && endereco.pathname === "/compartilhar") return "compartilhar";
  if (metodo !== "GET") return "rede";
  if (/^\/(api|baixar|cdn-cgi)(\/|$)/.test(endereco.pathname) || endereco.pathname === "/sw.js") return "rede";
  if (modo === "navigate") return /^\/(admin|conta)(\/|$)/.test(endereco.pathname) ? "rede" : "pagina";
  if (endereco.pathname.startsWith("/_next/static/")) return "build";
  if (endereco.pathname.startsWith("/motores/")) return "motores";
  if (/\.(png|webp|svg|ico|jpg|webmanifest)$/.test(endereco.pathname)) return "publico";
  return "rede";
}

/** Guardar uma resposta só quando ela é do próprio site e inteira. */
const guardavel = (resposta) => resposta.ok && resposta.status === 200 && resposta.type === "basic";

self.addEventListener("install", (evento) => {
  evento.waitUntil(instalar());
});

self.addEventListener("activate", (evento) => {
  evento.waitUntil(
    (async () => {
      if (self.registration.navigationPreload) await self.registration.navigationPreload.enable();
      for (const [prefixo, atual] of [["slt-build-", CACHE_DO_BUILD], ["slt-paginas-", CACHE_DE_PAGINAS]]) {
        const antigos = (await caches.keys()).filter((nome) => nome.startsWith(prefixo) && nome !== atual);
        // A aba já aberta ainda pode precisar de um chunk da versão anterior.
        for (const nome of antigos.slice(0, -1)) await caches.delete(nome);
      }
      await self.clients.claim();
      await avisarEstado(await contarEstado());
    })(),
  );
});

self.addEventListener("fetch", (evento) => {
  const pedido = evento.request;
  // Pedaço de arquivo (Range) é sempre da rede: o cache guarda arquivos inteiros.
  if (pedido.headers.has("range")) return;
  const qual = rota(pedido.url, pedido.method, pedido.mode, self.location.origin);
  if (qual === "pagina") evento.respondWith(pagina(evento));
  else if (qual === "build") evento.respondWith(primeiroDoCache(CACHE_DO_BUILD, pedido));
  else if (qual === "motores") evento.respondWith(primeiroDoCache(CACHE_DO_BUILD, pedido));
  else if (qual === "publico") evento.respondWith(guardadoERevalidado(evento));
  else if (qual === "compartilhar") evento.respondWith(receberCompartilhados(pedido));
});

self.addEventListener("message", (evento) => {
  const mensagem = evento.data ?? {};
  if (mensagem.tipo === "preparar") evento.waitUntil(prepararParaOffline());
  else if (mensagem.tipo === "estado") evento.waitUntil(contarEstado().then((estado) => evento.source?.postMessage(estado)));
});

// ==================== instalação ====================

async function instalar() {
  // Uma atualização incompleta não substitui a cópia offline que já funciona.
  if (!(await prepararParaOffline(true))) throw new Error("Preparação offline incompleta");
  await self.skipWaiting();
}

async function guardarCasca() {
  const paginas = await caches.open(CACHE_DE_PAGINAS);
  const build = await caches.open(CACHE_DO_BUILD);
  for (const caminho of CASCA) {
    const resposta = await fetch(caminho, { cache: "no-cache", credentials: "omit" });
    if (!guardavel(resposta)) throw new Error(`Página indisponível: ${caminho}`);
    const html = await resposta.clone().text();
    await paginas.put(caminho, resposta);
    const recursos = [...html.matchAll(/(?:src|href)="(\/_next\/static\/[^"?#]+)"/g)].map((achado) => achado[1]);
    await guardarQueFaltam(build, recursos);
  }
}

async function guardarQueFaltam(cache, caminhos, aoGuardar = () => {}) {
  const faltam = [];
  for (const caminho of new Set(caminhos)) if (!(await cache.match(caminho))) faltam.push(caminho);
  // Seis de cada vez: rápido sem entupir a conexão do celular.
  for (let inicio = 0; inicio < faltam.length; inicio += 6) {
    await Promise.all(
      faltam.slice(inicio, inicio + 6).map(async (caminho) => {
        const resposta = await fetch(caminho);
        if (!guardavel(resposta)) throw new Error(`Recurso indisponível: ${caminho}`);
        await cache.put(caminho, resposta);
      }),
    );
    await aoGuardar(Math.min(inicio + 6, faltam.length));
  }
  return faltam.length;
}

// ==================== offline completo ====================

async function jaPreparado() {
  const marca = await (await caches.open(CACHE_DE_ESTADO)).match(MARCA_DE_PREPARADO);
  return marca ? (await marca.text()) === BUILD.versao : false;
}

async function contarEstado() {
  const build = await caches.open(CACHE_DO_BUILD);
  let guardados = 0;
  for (const caminho of BUILD.arquivos) if (await build.match(caminho)) guardados += 1;
  const paginas = await caches.open(CACHE_DE_PAGINAS);
  const cascaCompleta = (await Promise.all(CASCA.map((caminho) => paginas.match(caminho)))).every(Boolean);
  return { tipo: "estado", versao: BUILD.versao, total: BUILD.arquivos.length, guardados, bytes: BUILD.bytes, preparado: BUILD.arquivos.length > 0 && guardados === BUILD.arquivos.length && cascaCompleta && await jaPreparado() };
}

let preparacaoEmCurso = null;

async function avisarEstado(estado) {
  const clientes = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  for (const cliente of clientes) cliente.postMessage(estado);
}

function prepararParaOffline(instalando = false) {
  if (preparacaoEmCurso) return preparacaoEmCurso;
  preparacaoEmCurso = (async () => {
    let erro = null;
    try {
      const antes = await contarEstado();
      if (antes.preparado) { await avisarEstado(antes); return true; }
      await avisarEstado({ ...antes, preparando: true });
      await guardarQueFaltam(await caches.open(CACHE_DO_BUILD), BUILD.arquivos, (feitos) =>
        avisarEstado({ ...antes, guardados: Math.min(antes.total, antes.guardados + feitos), preparando: true }),
      );
      await guardarCasca();
      await (await caches.open(CACHE_DE_ESTADO)).put(MARCA_DE_PREPARADO, new Response(BUILD.versao));
    } catch {
      erro = "Preparação incompleta. Reconecte a internet e tente novamente; os recursos já guardados permanecem no aparelho.";
    }
    const estado = await contarEstado();
    await avisarEstado({ ...estado, preparado: !instalando && estado.preparado, preparando: instalando && !erro, erro });
    return estado.preparado && erro === null;
  })().finally(() => { preparacaoEmCurso = null; });
  return preparacaoEmCurso;
}

// ==================== estratégias ====================

/** Arquivo com hash no nome nunca muda: se está guardado, é ele. */
async function primeiroDoCache(nome, pedido) {
  const cache = await caches.open(nome);
  const guardada = await cache.match(pedido);
  if (guardada) return guardada;
  if (new URL(pedido.url).pathname.startsWith("/_next/static/")) {
    for (const anterior of (await caches.keys()).filter((chave) => chave.startsWith("slt-build-") && chave !== nome)) {
      const antiga = await (await caches.open(anterior)).match(pedido);
      if (antiga) return antiga;
    }
  }
  const resposta = await fetch(pedido);
  if (guardavel(resposta)) await cache.put(pedido, resposta.clone());
  return resposta;
}

/** Ícones e imagens da marca: responde o guardado e atualiza por trás. */
async function guardadoERevalidado(evento) {
  const cache = await caches.open(CACHE_PUBLICO);
  const guardada = await cache.match(evento.request);
  const daRede = fetch(evento.request).then(async (resposta) => {
    if (guardavel(resposta)) await cache.put(evento.request, resposta.clone());
    return resposta;
  });
  if (guardada) {
    evento.waitUntil(daRede.catch(() => undefined));
    return guardada;
  }
  return daRede;
}

/**
 * Páginas: a rede primeiro, para ninguém ver o site velho, e a versão guardada quando a
 * rede falha ou demora. A página guardada é a mesma para qualquer ?origem=.
 */
async function pagina(evento) {
  const endereco = new URL(evento.request.url);
  const chave = endereco.pathname;
  const cache = await caches.open(CACHE_DE_PAGINAS);

  const daRede = (async () => {
    const resposta = (await evento.preloadResponse) ?? (await fetch(evento.request));
    if (guardavel(resposta)) {
      await cache.put(chave, resposta.clone());
      await apararPaginas(cache);
    }
    return resposta;
  })();
  evento.waitUntil(daRede.catch(() => undefined));

  try {
    return await Promise.race([daRede, new Promise((_, recusar) => setTimeout(() => recusar(new Error("devagar")), ESPERA_DA_REDE_MS))]);
  } catch {
    const guardada =
      (await cache.match(chave)) ?? (chave.startsWith("/converter") ? await cache.match("/converter") : undefined);
    if (guardada) return guardada;
    try {
      return await daRede;
    } catch {
      return (await cache.match("/converter")) ?? semInternet();
    }
  }
}

async function apararPaginas(cache) {
  const chaves = await cache.keys();
  for (const chave of chaves.slice(0, Math.max(0, chaves.length - PAGINAS_GUARDADAS))) {
    if (!CASCA.includes(new URL(chave.url).pathname)) await cache.delete(chave);
  }
}

/** A página que aparece quando não há rede nem versão guardada. Isolada como o site. */
function semInternet() {
  const html = `<!doctype html><html lang="pt-BR" style="color-scheme:dark"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sem internet — Smells Like Tech Converter</title><body style="margin:0;min-height:100vh;display:grid;place-items:center;background:#0e0e12;color:#f3f3f5;font:16px/1.6 system-ui,sans-serif;padding:24px"><main style="max-width:420px"><h1 style="font-size:22px">Sem internet agora</h1><p style="color:#a6a6b0">Esta página ainda não foi guardada neste aparelho. Reconecte, abra o conversor e espere o aviso «Pronto para converter sem internet» antes de desligar a conexão.</p><p><a href="/converter" style="color:#ffa23a">Abrir o conversor</a></p></main></body></html>`;
  return new Response(html, {
    status: 503,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cross-Origin-Opener-Policy": "same-origin",
      "Cross-Origin-Embedder-Policy": "require-corp",
      "Cross-Origin-Resource-Policy": "same-origin",
    },
  });
}

// ==================== compartilhar com o app ====================

/**
 * O Android manda o arquivo compartilhado num POST para /compartilhar. O pedido para
 * aqui, nunca chega à rede: os arquivos vão para o Cache Storage do próprio aparelho e a
 * oficina os busca ao abrir (lib/pwa/compartilhados.ts).
 */
async function receberCompartilhados(pedido) {
  try {
    const dados = await pedido.formData();
    const arquivos = dados.getAll("arquivos").filter((item) => typeof item !== "string");
    await caches.delete(CACHE_DOS_COMPARTILHADOS);
    const cache = await caches.open(CACHE_DOS_COMPARTILHADOS);
    await Promise.all(
      arquivos.map((arquivo, indice) =>
        cache.put(
          `/__compartilhado/${indice}`,
          new Response(arquivo, {
            headers: {
              "Content-Type": arquivo.type || "application/octet-stream",
              "X-Nome": encodeURIComponent(arquivo.name || `arquivo-${indice + 1}`),
            },
          }),
        ),
      ),
    );
    return Response.redirect(`/converter?compartilhado=${arquivos.length}`, 303);
  } catch {
    return Response.redirect("/converter?compartilhado=erro", 303);
  }
}
