"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { estimarRestante, textoDoAndamento, type EstadoDoAndamento } from "@/packages/converter-core/andamento.mjs";

/**
 * Acompanhamento das tarefas longas do site (conversões, separação de faixas, exportações):
 * porcentagem e tempo estimado na tela, no título da aba e, quando a pessoa troca de aba ou
 * de app, numa notificação do sistema que se atualiza sozinha.
 *
 * O que o navegador permite e o que não permite: enquanto há trabalho, a tela é mantida
 * acesa (Screen Wake Lock), porque com a tela bloqueada o celular congela a página. Em
 * outra aba ou outro app o processamento continua, mas o navegador pode desacelerar ou
 * pausar a página depois de alguns minutos. Para processar com a tela desligada, o app
 * Android e o aplicativo/plugin do Windows fazem isso de verdade.
 */

export type Andamento = { etapa: string; fracao: number; segundos: number | null; texto: string };

type Ativo = { id: number; titulo: string; andamento: Andamento; estado: EstadoDoAndamento };

const ativos = new Map<number, Ativo>();
let proximo = 1;
let tituloOriginal: string | null = null;
let trava: { release(): Promise<void> } | null = null;
let ultimaNotificacao = 0;
let ouvindo = false;

const CHAVE_RECUSA = "segundo-plano:notificacoes-recusadas";
const MARCA = "processamento-em-andamento";

function principal(): Ativo | null {
  let escolhido: Ativo | null = null;
  for (const a of ativos.values()) if (!escolhido || a.andamento.fracao < escolhido.andamento.fracao) escolhido = a;
  return escolhido;
}

function atualizarTitulo() {
  if (typeof document === "undefined") return;
  const a = principal();
  if (!a) {
    if (tituloOriginal !== null) document.title = tituloOriginal;
    tituloOriginal = null;
    return;
  }
  tituloOriginal ??= document.title;
  const porcentagem = Math.floor(a.andamento.fracao * 100);
  document.title = `${porcentagem}% · ${a.titulo}${ativos.size > 1 ? ` (+${ativos.size - 1})` : ""} — ${tituloOriginal}`;
}

async function registro(): Promise<ServiceWorkerRegistration | null> {
  try {
    if (!("serviceWorker" in navigator)) return null;
    return (await navigator.serviceWorker.getRegistration()) ?? null;
  } catch {
    return null;
  }
}

async function notificar(forcar = false) {
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
  const agora = Date.now();
  if (!forcar && agora - ultimaNotificacao < 3000) return;
  ultimaNotificacao = agora;
  const a = principal();
  const sw = await registro();
  if (!a || !sw) return;
  if (!document.hidden) {
    (await sw.getNotifications({ tag: MARCA })).forEach((n) => n.close());
    return;
  }
  const quantas = ativos.size > 1 ? `${ativos.size} tarefas · ` : "";
  await sw.showNotification(`${quantas}${a.titulo}`, {
    tag: MARCA,
    body: `${a.andamento.etapa ? `${a.andamento.etapa} · ` : ""}${a.andamento.texto}\nO Smells Like Tech Converter está trabalhando em segundo plano nesta aba.`,
    icon: "/marca/icone-192.png",
    silent: true,
    requireInteraction: false,
    data: { url: location.pathname },
  } as NotificationOptions).catch(() => undefined);
}

async function avisarFim(titulo: string, mensagem: string) {
  if (typeof Notification === "undefined" || Notification.permission !== "granted" || !document.hidden) return;
  const sw = await registro();
  if (!sw) return;
  (await sw.getNotifications({ tag: MARCA })).forEach((n) => n.close());
  if (ativos.size > 0) return notificar(true);
  await sw.showNotification(titulo, { tag: `${MARCA}-fim`, body: mensagem, icon: "/marca/icone-192.png", data: { url: location.pathname } }).catch(() => undefined);
}

async function manterTelaAcesa() {
  if (trava || ativos.size === 0 || document.hidden) return;
  try {
    const wake = (navigator as Navigator & { wakeLock?: { request(tipo: "screen"): Promise<{ release(): Promise<void>; addEventListener(e: string, f: () => void): void }> } }).wakeLock;
    if (!wake) return;
    const nova = await wake.request("screen");
    nova.addEventListener("release", () => { if (trava === nova) trava = null; });
    trava = nova;
  } catch {
    // Sem permissão (bateria fraca, aba sem foco): o processamento segue, só a tela pode apagar.
  }
}

function ouvirVisibilidade() {
  if (ouvindo || typeof document === "undefined") return;
  ouvindo = true;
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) void manterTelaAcesa();
    void notificar(true);
  });
}

/** Pede para avisar nas notificações, uma vez; quem recusou não é perguntado de novo. */
export async function pedirNotificacoes(): Promise<void> {
  try {
    if (typeof Notification === "undefined" || Notification.permission !== "default") return;
    if (localStorage.getItem(CHAVE_RECUSA) === "1") return;
    const resposta = await Notification.requestPermission();
    if (resposta === "denied") localStorage.setItem(CHAVE_RECUSA, "1");
  } catch {
    // Navegador sem notificações ou armazenamento bloqueado: segue sem elas.
  }
}

export class Acompanhamento {
  private readonly ativo: Ativo;
  private encerrado = false;

  constructor(titulo: string) {
    ouvirVisibilidade();
    this.ativo = { id: proximo++, titulo, andamento: { etapa: "", fracao: 0, segundos: null, texto: textoDoAndamento(0, null) }, estado: {} };
    ativos.set(this.ativo.id, this.ativo);
    void pedirNotificacoes();
    void manterTelaAcesa();
    atualizarTitulo();
  }

  /**
   * Registra o avanço. `segundos` vem do motor quando ele sabe (transcrição, plugin); senão,
   * a estimativa sai do ritmo da própria fração. Devolve o que mostrar na tela.
   */
  atualizar(etapa: string, fracao: number, segundos?: number | null): Andamento {
    if (this.encerrado) return this.ativo.andamento;
    const f = Math.max(0, Math.min(1, fracao));
    // Uma fração que volta é outra etapa (download → separação): o ritmo é outro.
    if (f + 0.001 < this.ativo.andamento.fracao) this.ativo.estado = {};
    const estimado = estimarRestante(this.ativo.estado, f);
    const restante = segundos ?? estimado;
    this.ativo.andamento = { etapa, fracao: f, segundos: restante, texto: textoDoAndamento(f, restante) };
    atualizarTitulo();
    void notificar();
    return this.ativo.andamento;
  }

  /** Recomeça a estimativa numa etapa nova com outro ritmo (download → separação). */
  reiniciarEstimativa() {
    this.ativo.estado = {};
  }

  get andamento(): Andamento {
    return this.ativo.andamento;
  }

  terminar(mensagem?: string) {
    if (this.encerrado) return;
    this.encerrado = true;
    ativos.delete(this.ativo.id);
    atualizarTitulo();
    if (ativos.size === 0 && trava) {
      void trava.release().catch(() => undefined);
      trava = null;
    }
    void avisarFim(this.ativo.titulo, mensagem ?? "Pronto. Toque para voltar ao conversor.");
  }
}

/**
 * O andamento de um painel (separação, exportação) como estado do React: `mudar({ etapa,
 * fracao })` atualiza tela, título da aba e notificação; `mudar(null)` encerra.
 */
export function useAndamento(titulo: string): [Andamento | null, (novo: { etapa: string; fracao: number } | null, fim?: string) => void] {
  const [andamento, setAndamento] = useState<Andamento | null>(null);
  const atual = useRef<Acompanhamento | null>(null);
  useEffect(() => () => { atual.current?.terminar(); atual.current = null; }, []);
  const mudar = useCallback((novo: { etapa: string; fracao: number } | null, fim?: string) => {
    if (!novo) {
      atual.current?.terminar(fim);
      atual.current = null;
      setAndamento(null);
      return;
    }
    atual.current ??= new Acompanhamento(titulo);
    setAndamento(atual.current.atualizar(novo.etapa, novo.fracao));
  }, [titulo]);
  return [andamento, mudar];
}
