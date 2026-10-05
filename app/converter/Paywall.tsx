"use client";

import type { Ferramenta } from "@/packages/converter-core/catalogo.mjs";
import { formatarReais, produto } from "@/packages/converter-core/precos.mjs";

import type { Bloqueio } from "@/lib/converter/limites";

import BotaoDeCompra from "../precos/BotaoDeCompra";

/**
 * O que aparece quando o grátis bate no limite.
 *
 * Diz o que travou, lembra que a qualidade é a mesma e oferece as duas saídas — o Pro
 * por Pix, que libera a chave na hora, e o aplicativo vitalício. Sem "desbloqueie o
 * potencial", sem "seja Pro": a pessoa quer o arquivo convertido.
 *
 * A compra leva a origem ("limite:lote") e o caminho de volta, para a página de obrigado
 * trazer a pessoa de novo à conversão que parou.
 */
export default function Paywall({ bloqueio, ferramenta }: { bloqueio: Bloqueio; ferramenta: Ferramenta }) {
  const mensal = produto("pro-mensal")!;
  const app = produto("app-vitalicio")!;
  const origem = `limite:${bloqueio.contador}`;
  const soNoPro = ferramenta.plano === "pro" && bloqueio.contador === "pesado";

  return (
    <aside className="paywall" aria-live="polite">
      <h3 className="paywall__titulo">{soNoPro ? `${ferramenta.titulo} faz parte do Pro.` : "O grátis chegou no volume. A qualidade não muda."}</h3>
      <p className="paywall__motivo">{bloqueio.mensagem}</p>
      <p>
        Você já converte no seu computador, sem upload. O Pro {soNoPro ? `libera “${ferramenta.titulo}” e ` : ""}tira o teto: arquivo por
        vez, duração de vídeo, páginas de PDF, transcrição e OCR.
      </p>
      <p className="paywall__preco">
        <strong>
          {formatarReais(mensal.centavos)} por {mensal.dias} dias.
        </strong>{" "}
        Sem renovação automática. Pix libera a chave na hora.
      </p>
      <div className="paywall__acoes">
        <BotaoDeCompra produto={mensal.id} rotulo="Pagar com Pix" destaque origem={origem} voltarDepois />
        <a className="button button--ghost" href={`/aplicativo?origem=${encodeURIComponent(origem)}`}>
          Ver o aplicativo vitalício ({formatarReais(app.centavos)})
        </a>
      </div>
      <p className="paywall__nota">
        <a href={`/precos?origem=${encodeURIComponent(origem)}`}>Comparar os planos</a> · Pix, cartão ou boleto, pelo Mercado Pago.
      </p>
    </aside>
  );
}
