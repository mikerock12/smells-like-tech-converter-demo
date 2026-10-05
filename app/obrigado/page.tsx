import type { Metadata } from "next";

import { SiteFooter, SiteHeader } from "../SiteChrome";
import ChaveDoPedido from "./ChaveDoPedido";

export const metadata: Metadata = {
  title: "Obrigado — sua chave — Smells Like Tech Converter",
  description: "Confirmação do pagamento e entrega da chave de acesso.",
  robots: { index: false },
};

export default async function PaginaDeObrigado({ searchParams }: { searchParams: Promise<{ pedido?: string }> }) {
  const { pedido } = await searchParams;

  return (
    <>
      <SiteHeader page="outra" />
      <main className="plugin-page">
        <header className="plugin-page__topo">
          <p className="converter__eyebrow">Pedido {pedido ?? ""}</p>
          <h1>
            Obrigado.
            <br />
            <em>Sua chave chega aqui.</em>
          </h1>
          <p className="plugin-page__lead">
            Assim que o Mercado Pago confirmar o pagamento, a chave aparece nesta página e vai para o seu e-mail. Pix
            confirma em segundos; boleto pode levar até dois dias úteis. Pode fechar e voltar depois: o número do pedido
            acima recupera tudo.
          </p>
        </header>
        <ChaveDoPedido pedido={pedido ?? ""} />
      </main>
      <SiteFooter />
    </>
  );
}
