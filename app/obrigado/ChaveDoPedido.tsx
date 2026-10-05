"use client";

import { useEffect, useState, useSyncExternalStore } from "react";

import { chaveLegivel } from "@/packages/licenca/index.mjs";
import { PLANOS, alcanceDoPlano } from "@/packages/converter-core/precos.mjs";

import { usePlano } from "@/lib/plano/usePlano";

import { CHAVE_DA_VOLTA } from "../precos/BotaoDeCompra";

type Pedido = { ref: string; produto: string; plano: string; estado: string; chave: string | null; expira: string | null };

/**
 * A compra que nasceu no meio de uma conversão deixou o caminho de volta nesta aba. É
 * lido uma vez; no servidor não existe aba, daí o nulo.
 */
function lerVolta(): string | null {
  try {
    const caminho = window.sessionStorage.getItem(CHAVE_DA_VOLTA);
    return caminho && /^\/converter(\/[a-z0-9-]+)?$/.test(caminho) ? caminho : null;
  } catch {
    return null; // Sem armazenamento da aba: a página funciona igual, só sem o atalho.
  }
}
const semAssinatura = () => () => {};

/**
 * Acompanha o pedido até a chave aparecer, e a ativa neste navegador com um clique.
 * Consulta a cada poucos segundos enquanto está pendente — o webhook do Mercado Pago
 * chega por trás, e a página só precisa perguntar.
 */
export default function ChaveDoPedido({ pedido }: { pedido: string }) {
  const [dados, setDados] = useState<Pedido | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [copiado, setCopiado] = useState(false);
  const voltarPara = useSyncExternalStore(semAssinatura, lerVolta, () => null);
  const { ativar, estado } = usePlano();

  useEffect(() => {
    if (!pedido) return;
    let vivo = true;
    let temporizador: number | undefined;

    async function consultar() {
      try {
        const resposta = await fetch(`/api/pedidos/${encodeURIComponent(pedido)}`, { cache: "no-store" });
        const corpo = (await resposta.json()) as Pedido & { erro?: string };
        if (!vivo) return;
        if (!resposta.ok) {
          setErro(corpo.erro ?? "Não foi possível consultar o pedido.");
          return;
        }
        setDados(corpo);
        setErro(null);
        if (corpo.estado !== "pago" && corpo.estado !== "recusado" && corpo.estado !== "estornado") {
          temporizador = window.setTimeout(consultar, 4000);
        }
      } catch {
        if (vivo) temporizador = window.setTimeout(consultar, 8000);
      }
    }

    void consultar();
    return () => {
      vivo = false;
      if (temporizador) window.clearTimeout(temporizador);
    };
  }, [pedido]);

  if (!pedido) {
    return (
      <p className="notice notice--warning">
        Esta página precisa do número do pedido. Se você acabou de pagar, use o link do Mercado Pago; se perdeu a chave,{" "}
        <a className="converter__link" href="/chave">
          recupere aqui
        </a>
        .
      </p>
    );
  }

  if (erro) return <p className="notice notice--warning">{erro}</p>;

  if (!dados) {
    return (
      <aside className="instalacao" aria-live="polite">
        <h2>Consultando o pedido…</h2>
      </aside>
    );
  }

  if (dados.estado === "recusado") {
    return (
      <aside className="instalacao instalacao--aviso" aria-live="polite">
        <h2>O pagamento não foi aprovado</h2>
        <p className="instalacao__detalhe">
          O Mercado Pago recusou este pagamento. Nada foi cobrado.{" "}
          <a className="converter__link" href="/precos">
            Tente de novo
          </a>{" "}
          com outro meio, ou fale conosco citando o pedido {dados.ref}.
        </p>
      </aside>
    );
  }

  if (dados.estado === "estornado") {
    return (
      <aside className="instalacao instalacao--aviso" aria-live="polite">
        <span className="instalacao__selo">estornado</span>
        <h2>Este pedido foi estornado</h2>
        <p className="instalacao__detalhe">
          O valor do pedido {dados.ref} voltou para quem pagou, pelo Mercado Pago. A chave deste pedido não aparece
          mais aqui nem em /chave. Se foi engano,{" "}
          <a className="converter__link" href="/precos">
            compre de novo
          </a>
          .
        </p>
      </aside>
    );
  }

  if (!dados.chave) {
    return (
      <aside className="instalacao" aria-live="polite">
        <span className="instalacao__selo">aguardando o pagamento</span>
        <h2>Esperando a confirmação do Mercado Pago</h2>
        <p className="instalacao__detalhe">
          Esta página confere sozinha a cada poucos segundos. Pedido {dados.ref} · {PLANOS[dados.plano as keyof typeof PLANOS]?.nome ?? dados.plano}.
        </p>
      </aside>
    );
  }

  const alcance = alcanceDoPlano(dados.plano);
  const ativa = estado.situacao === "valida" && estado.carga.id === dados.ref;

  return (
    <aside className="instalacao instalacao--pronta" aria-live="polite">
      <span className="instalacao__selo">pago · chave emitida</span>
      <h2>{PLANOS[dados.plano as keyof typeof PLANOS]?.nome ?? dados.plano}: esta é a sua chave</h2>
      <pre className="chave">{chaveLegivel(dados.chave)}</pre>
      <div className="plugin__acoes">
        <button
          type="button"
          className="button"
          onClick={() => {
            void navigator.clipboard.writeText(dados.chave ?? "").then(() => setCopiado(true));
          }}
        >
          {copiado ? "Copiada" : "Copiar a chave"}
        </button>
        {alcance.site && (
          <button type="button" className="button button--primary" onClick={() => void ativar(dados.chave ?? "")} disabled={ativa}>
            {ativa ? "Ativada neste navegador" : "Ativar neste navegador"}
          </button>
        )}
      </div>
      <p className="instalacao__detalhe">
        {alcance.site && "No site: a chave fica guardada neste navegador; em outro computador, cole em /chave. "}
        {alcance.app && "No aplicativo: Configurações → Licença → colar a chave. "}
        {alcance.android && "No app para Android: Licença → colar a chave. "}
        {dados.expira ? `Vale até ${new Date(dados.expira).toLocaleDateString("pt-BR")}.` : "Não vence."} Guarde o número do pedido: {dados.ref}.
      </p>
      <p className="instalacao__detalhe">
        A chave também fica na sua conta:{" "}
        <a className="converter__link" href="/conta">
          entre com o e-mail da compra
        </a>{" "}
        em qualquer navegador para levá-la junto.
      </p>
      {voltarPara && ativa && (
        <a className="primary-action" href={voltarPara}>
          Voltar para a sua conversão <span aria-hidden="true">→</span>
        </a>
      )}
    </aside>
  );
}
