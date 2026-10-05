/**
 * O app instalável (PWA), do lado da página: registrar o service worker, guardar o convite
 * de instalação do navegador e conversar com o service worker sobre o modo offline.
 *
 * O service worker só existe no build (scripts/pwa.mjs); no `npm run dev` nada é
 * registrado, para o cache não esconder as mudanças de quem está desenvolvendo.
 */

/** O evento que o Chrome e o Edge disparam quando o site pode virar app. */
type ConviteDeInstalacao = Event & {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
};

export type EstadoOffline = {
  tipo: "estado";
  versao: string;
  total: number;
  guardados: number;
  bytes: number;
  preparado: boolean;
  preparando?: boolean;
  erro?: string | null;
};

let convite: ConviteDeInstalacao | null = null;
let instalado = false;
const ouvintes = new Set<() => void>();
const avisar = () => ouvintes.forEach((ouvinte) => ouvinte());

export function ouvirInstalacao(ouvinte: () => void): () => void {
  ouvintes.add(ouvinte);
  return () => ouvintes.delete(ouvinte);
}

export const podeInstalar = () => convite !== null;
export const acabouDeInstalar = () => instalado;

/** Aberto como app (na tela inicial), e não numa aba do navegador. */
export function emModoApp(): boolean {
  if (typeof window === "undefined") return false;
  return window.matchMedia("(display-mode: standalone)").matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

/** iPhone e iPad não têm botão de instalar: é pelo menu Compartilhar do Safari. */
export function ehAparelhoDaApple(): boolean {
  if (typeof navigator === "undefined") return false;
  return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

export async function instalar(): Promise<boolean> {
  if (!convite) return false;
  const pedido = convite;
  convite = null;
  avisar();
  await pedido.prompt();
  return (await pedido.userChoice).outcome === "accepted";
}

let iniciado = false;
let checandoAtualizacao = true;
const ouvintesDaAtualizacao = new Set<() => void>();
export const verificandoAtualizacaoOffline = () => checandoAtualizacao;
export function ouvirAtualizacaoOffline(ouvinte: () => void): () => void {
  ouvintesDaAtualizacao.add(ouvinte);
  return () => { ouvintesDaAtualizacao.delete(ouvinte); };
}
function marcarChecagem(valor: boolean): void {
  checandoAtualizacao = valor;
  for (const ouvinte of ouvintesDaAtualizacao) ouvinte();
}

/** Chamado uma vez, no layout. */
export function iniciarApp(): void {
  if (typeof window === "undefined" || iniciado) return;
  iniciado = true;

  window.addEventListener("beforeinstallprompt", (evento) => {
    // Sem isso o Chrome mostra a própria barra; o convite fica para o botão do site.
    evento.preventDefault();
    convite = evento as ConviteDeInstalacao;
    avisar();
  });
  window.addEventListener("appinstalled", () => {
    convite = null;
    instalado = true;
    avisar();
  });

  if (process.env.NODE_ENV !== "production" || !("serviceWorker" in navigator)) return;
  const registrar = () => {
    marcarChecagem(true);
    navigator.serviceWorker
      .register("/sw.js", { updateViaCache: "none" })
      .then((registro) => {
        const observar = () => {
          marcarChecagem(true);
          const worker = registro.installing ?? registro.waiting;
          worker?.addEventListener("statechange", () => {
            if (worker.state === "activated") {
              marcarChecagem(false);
              // O aviso final pode chegar antes deste evento; peça-o novamente após ativar.
              worker.postMessage({ tipo: "estado" });
            }
            if (worker.state === "redundant") {
              // A atualização não terminou: não reutilize um "pronto" da versão antiga.
              marcarChecagem(true);
              window.dispatchEvent(new Event("slt-offline-erro"));
            }
          });
        };
        if (registro.installing || registro.waiting) observar();
        else marcarChecagem(false);
        registro.addEventListener("updatefound", observar);
        // A aba comum também prepara todos os motores, sem precisar instalar o PWA.
        void navigator.serviceWorker.ready.then((pronto) => pronto.active?.postMessage({ tipo: "preparar" }));
      })
      .catch(() => {
        marcarChecagem(true);
        window.dispatchEvent(new Event("slt-offline-erro"));
      });
  };
  if (document.readyState === "complete") registrar();
  else window.addEventListener("load", registrar, { once: true });
  window.addEventListener("online", registrar);
}

/** Tenta novamente após perda de conexão ou falta de espaço. */
export function tentarOffline(): void {
  window.dispatchEvent(new Event("online"));
  void navigator.serviceWorker.ready.then((registro) => registro.active?.postMessage({ tipo: "preparar" }));
}

/** Pede ao service worker e ouve o estado do modo offline. Devolve a função que para de ouvir. */
export function acompanharOffline(aoMudar: (estado: EstadoOffline) => void, preparar = false): () => void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return () => {};
  let ouvindo = true;
  const ouvinte = (evento: MessageEvent) => {
    if (evento.data?.tipo === "estado" && !(checandoAtualizacao && evento.data.preparado)) aoMudar(evento.data as EstadoOffline);
  };
  navigator.serviceWorker.addEventListener("message", ouvinte);
  // `ready` espera o service worker ficar ativo (na primeira visita, ele ainda está
  // instalando). No `npm run dev` não há service worker, e a pergunta nunca sai.
  const perguntar = () => void navigator.serviceWorker.ready.then((registro) => {
    if (ouvindo) registro.active?.postMessage({ tipo: preparar ? "preparar" : "estado" });
  });
  perguntar();
  navigator.serviceWorker.addEventListener("controllerchange", perguntar);
  window.addEventListener("offline", perguntar);
  return () => {
    ouvindo = false;
    navigator.serviceWorker.removeEventListener("message", ouvinte);
    navigator.serviceWorker.removeEventListener("controllerchange", perguntar);
    window.removeEventListener("offline", perguntar);
  };
}
