"use client";

import { useState } from "react";

import { PLANOS } from "@/packages/converter-core/precos.mjs";
import { chaveLegivel } from "@/packages/licenca/index.mjs";

import { usePlano } from "@/lib/plano/usePlano";

export default function AtivarChave() {
  const { estado, ativar, remover, pronto } = usePlano();
  const [texto, setTexto] = useState("");
  const [aviso, setAviso] = useState<string | null>(null);
  const [pedido, setPedido] = useState("");
  const [email, setEmail] = useState("");
  const [recuperada, setRecuperada] = useState<string | null>(null);
  const [buscando, setBuscando] = useState(false);

  async function confirmar() {
    setAviso(null);
    const resultado = await ativar(texto);
    if (resultado.situacao === "valida") {
      setTexto("");
      return;
    }
    if (resultado.situacao === "invalida" && resultado.motivo === "sem-chave-publica") {
      setAviso("Este site ainda não foi configurado para reconhecer chaves (falta a chave pública no build).");
    } else if (resultado.situacao === "invalida" && resultado.motivo === "assinatura") {
      setAviso("Essa chave não foi emitida por nós, ou foi alterada. Confira se copiou inteira.");
    } else {
      setAviso("Isso não parece uma chave. Ela começa com SLT1. e tem três partes separadas por ponto.");
    }
  }

  async function recuperar(evento: React.FormEvent) {
    evento.preventDefault();
    setBuscando(true);
    setRecuperada(null);
    setAviso(null);
    try {
      const resposta = await fetch("/api/chave/recuperar", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ pedido, email }),
      });
      const corpo = (await resposta.json()) as { chave?: string; erro?: string };
      if (!resposta.ok || !corpo.chave) {
        setAviso(corpo.erro ?? "Não encontrado.");
        return;
      }
      setRecuperada(corpo.chave);
      setTexto(corpo.chave);
    } catch {
      setAviso("Sem conexão com a loja agora.");
    } finally {
      setBuscando(false);
    }
  }

  return (
    <>
      {pronto && estado.situacao === "valida" && (
        <aside className={`instalacao ${estado.plano === "pro" ? "instalacao--pronta" : "instalacao--aviso"}`} aria-live="polite">
          <span className="instalacao__selo">{estado.plano === "pro" ? "Pro ativo" : "chave válida"}</span>
          <h2>
            {PLANOS[estado.carga.plano as keyof typeof PLANOS]?.nome ?? estado.carga.plano} · {estado.carga.email}
          </h2>
          <p className="instalacao__detalhe">
            Pedido {estado.carga.id} ·{" "}
            {estado.carga.expira
              ? estado.vencida
                ? `venceu em ${new Date(estado.carga.expira).toLocaleDateString("pt-BR")}`
                : `vale até ${new Date(estado.carga.expira).toLocaleDateString("pt-BR")}`
              : "não vence"}
            {estado.plano !== "pro" && !estado.vencida && " · esta chave é do aplicativo e não libera o Pro no site."}
          </p>
          <div className="plugin__acoes">
            {estado.vencida && (
              <a className="button button--primary" href="/precos">
                Renovar
              </a>
            )}
            <button type="button" className="button button--ghost" onClick={remover}>
              Remover deste navegador
            </button>
          </div>
        </aside>
      )}

      <section className="plugin-page__secao">
        <h2 className="panel__title">Ativar uma chave</h2>
        <textarea
          className="texto-para-narrar chave-campo"
          rows={4}
          value={texto}
          onChange={(evento) => setTexto(evento.target.value)}
          placeholder="SLT1.…"
          aria-label="Chave de acesso"
          spellCheck={false}
        />
        <div className="panel__actions">
          <button type="button" className="button button--primary" onClick={() => void confirmar()} disabled={!texto.trim()}>
            Ativar neste navegador
          </button>
        </div>
        {aviso && <p className="notice notice--warning">{aviso}</p>}
      </section>

      <section className="plugin-page__secao">
        <h2 className="panel__title">Perdi a chave</h2>
        <p className="panel__descricao">
          O jeito mais fácil é{" "}
          <a className="converter__link" href="/conta">
            entrar na conta
          </a>{" "}
          com o e-mail da compra: todas as suas chaves estão lá. Ou informe aqui o e-mail e o número do pedido
          (SLT-XXXX-XXXX, no e-mail e no comprovante do Mercado Pago).
        </p>
        <form className="grid" onSubmit={recuperar}>
          <label className="field">
            <span className="field__label">E-mail da compra</span>
            <input type="email" value={email} onChange={(evento) => setEmail(evento.target.value)} required autoComplete="email" />
          </label>
          <label className="field">
            <span className="field__label">Número do pedido</span>
            <input type="text" value={pedido} onChange={(evento) => setPedido(evento.target.value.toUpperCase())} placeholder="SLT-XXXX-XXXX" required />
          </label>
          <div className="field field--wide">
            <button type="submit" className="button" disabled={buscando}>
              {buscando ? "Procurando…" : "Recuperar a chave"}
            </button>
          </div>
        </form>
        {recuperada && <pre className="chave">{chaveLegivel(recuperada)}</pre>}
      </section>
    </>
  );
}
