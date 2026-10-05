"use client";

import { useState } from "react";

import { EMAIL_LEMBRADO } from "@/packages/conta/index.mjs";
import { emailValido } from "@/packages/loja/index.mjs";

/** Onde a página de obrigado encontra o caminho de volta para a conversão interrompida. */
export const CHAVE_DA_VOLTA = "slt:voltar-para";

/**
 * O botão de comprar. Pede só o e-mail (é para onde vai a chave), cria o pedido e
 * leva a pessoa ao checkout do Mercado Pago. O pagamento acontece lá, nunca aqui.
 *
 * `origem` diz de onde veio a compra ("limite:lote", "precos"). Quando a compra nasce
 * no meio de uma conversão, o caminho da página fica guardado nesta aba, e a página de
 * obrigado oferece a volta depois de ativar a chave.
 */
export default function BotaoDeCompra({
  produto,
  rotulo,
  destaque = false,
  origem = "precos",
  voltarDepois = false,
}: {
  produto: string;
  rotulo: string;
  destaque?: boolean;
  origem?: string;
  voltarDepois?: boolean;
}) {
  const [aberto, setAberto] = useState(false);
  const [email, setEmail] = useState("");
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<string | null>(null);

  async function comprar(evento: React.FormEvent) {
    evento.preventDefault();
    setErro(null);
    if (!emailValido(email)) {
      setErro("Informe um e-mail válido: é para lá que vai a chave.");
      return;
    }

    setEnviando(true);
    try {
      const resposta = await fetch("/api/checkout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ produto, email: email.trim(), origem }),
      });
      const corpo = (await resposta.json()) as { url?: string; erro?: string };
      if (!resposta.ok || !corpo.url) {
        setErro(corpo.erro ?? "Não foi possível abrir o pagamento agora.");
        return;
      }
      if (voltarDepois) {
        try {
          window.sessionStorage.setItem(CHAVE_DA_VOLTA, window.location.pathname);
        } catch {
          // Sem armazenamento da aba (janela privada restrita): a volta só não aparece.
        }
      }
      window.location.assign(corpo.url);
    } catch {
      setErro("Sem conexão com a loja. Tente de novo em instantes.");
    } finally {
      setEnviando(false);
    }
  }

  function abrir() {
    // Quem já entrou na conta compra com o mesmo e-mail, e a compra aparece lá sozinha.
    if (!email) {
      try {
        setEmail(localStorage.getItem(EMAIL_LEMBRADO) ?? "");
      } catch {
        // Sem armazenamento: o campo só começa vazio.
      }
    }
    setAberto(true);
  }

  if (!aberto) {
    return (
      <button type="button" className={`button ${destaque ? "button--primary" : ""}`} onClick={abrir}>
        {rotulo}
      </button>
    );
  }

  return (
    <form className="compra" onSubmit={comprar}>
      <label className="field">
        <span className="field__label">Seu e-mail (para receber a chave)</span>
        <input
          type="email"
          value={email}
          onChange={(evento) => setEmail(evento.target.value)}
          placeholder="voce@exemplo.com.br"
          autoComplete="email"
          required
          disabled={enviando}
        />
      </label>
      <div className="compra__acoes">
        <button type="submit" className="button button--primary" disabled={enviando}>
          {enviando ? "Abrindo o Mercado Pago…" : "Ir para o pagamento"}
        </button>
        <button type="button" className="button button--ghost" onClick={() => setAberto(false)} disabled={enviando}>
          Voltar
        </button>
      </div>
      {erro && <p className="notice notice--warning">{erro}</p>}
      <p className="compra__processado">
        <span>Pagamento processado pelo</span>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/marca/mercado-pago-branco.svg" alt="Mercado Pago" width={99} height={40} />
      </p>
      <small className="compra__nota">Pix, cartão ou boleto, no site do Mercado Pago. Sem assinatura automática.</small>
    </form>
  );
}
