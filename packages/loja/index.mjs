/**
 * A parte pura da loja: o que dá para testar sem banco e sem Mercado Pago.
 *
 * - referência do pedido;
 * - corpo da preferência de checkout;
 * - conferência da assinatura do webhook;
 * - a carga da licença que um produto pago gera.
 *
 * Quem fala com a rede e com o D1 está em `lib/loja/`. Aqui só há regra.
 */

import { produto as buscarProduto, emReais, MAQUINAS_POR_LICENCA } from "../converter-core/precos.mjs";

/** Alfabeto sem 0/O, 1/I: a referência é lida em voz alta no suporte. */
const ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** "SLT-K7Q2-9XMZ". */
export function novaReferencia(aleatorio = crypto.getRandomValues(new Uint8Array(8))) {
  let texto = "";
  for (let indice = 0; indice < 8; indice += 1) {
    texto += ALFABETO[aleatorio[indice] % ALFABETO.length];
    if (indice === 3) texto += "-";
  }
  return `SLT-${texto}`;
}

export function referenciaValida(texto) {
  return /^SLT-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(String(texto ?? ""));
}

/** E-mail bom o bastante para receber uma chave. Não é validação de RFC. */
export function emailValido(texto) {
  const email = String(texto ?? "").trim();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email);
}

/**
 * De onde veio uma compra ou uma visita: "limite:lote", "precos", "qr", "google".
 * Minúsculas, números e ":", "_" ou "-", até 40 caracteres. Qualquer outra coisa vira
 * nulo — o campo nunca carrega texto livre, e-mail ou identificador de pessoa.
 */
export function origemValida(texto) {
  const origem = String(texto ?? "").trim().toLowerCase();
  return /^[a-z0-9][a-z0-9:_-]{0,39}$/.test(origem) ? origem : null;
}

/**
 * O que mandamos ao Mercado Pago para abrir o checkout (Checkout Pro).
 *
 * Um pagamento por vez, sem assinatura recorrente: quem quer o Pro mensal paga um mês e
 * decide de novo no mês seguinte. É o "cancelamento em um clique" mais honesto que há —
 * não existe o que cancelar.
 */
export function corpoDaPreferencia({ ref, produtoId, email, site }) {
  const produto = buscarProduto(produtoId);
  if (!produto) throw new Error(`Produto desconhecido: ${produtoId}`);

  const base = String(site).replace(/\/$/, "");

  return {
    items: [
      {
        id: produto.id,
        title: `Smells Like Tech Converter — ${produto.titulo}`,
        description: produto.nota,
        category_id: "software",
        quantity: 1,
        currency_id: "BRL",
        unit_price: emReais(produto.centavos),
      },
    ],
    payer: { email },
    external_reference: ref,
    back_urls: {
      success: `${base}/obrigado?pedido=${ref}`,
      pending: `${base}/obrigado?pedido=${ref}`,
      failure: `${base}/precos?pedido=${ref}&situacao=recusado`,
    },
    auto_return: "approved",
    notification_url: `${base}/api/webhooks/mercadopago`,
    statement_descriptor: "SMELLSLIKETECH",
    binary_mode: false,
    metadata: { pedido: ref, produto: produto.id, plano: produto.plano },
  };
}

/**
 * Lê o cabeçalho `x-signature` do Mercado Pago: "ts=...,v1=...".
 */
export function lerAssinatura(cabecalho) {
  const partes = Object.fromEntries(
    String(cabecalho ?? "")
      .split(",")
      .map((parte) => parte.trim().split("=", 2))
      .filter((par) => par.length === 2)
      .map(([chave, valor]) => [chave.trim(), valor.trim()]),
  );
  return partes.ts && partes.v1 ? { ts: partes.ts, v1: partes.v1.toLowerCase() } : null;
}

/**
 * O texto que o Mercado Pago assina: "id:<data.id>;request-id:<x-request-id>;ts:<ts>;".
 * O id vem em minúsculas quando é alfanumérico, como manda a documentação deles.
 */
export function manifestoDoWebhook({ dataId, requestId, ts }) {
  const id = /^[a-z0-9]+$/i.test(String(dataId)) ? String(dataId).toLowerCase() : String(dataId);
  return `id:${id};request-id:${requestId};ts:${ts};`;
}

async function hmacHex(segredo, texto) {
  const chave = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(segredo),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const assinatura = new Uint8Array(await crypto.subtle.sign("HMAC", chave, new TextEncoder().encode(texto)));
  return [...assinatura].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * Confere que o aviso veio mesmo do Mercado Pago.
 *
 * Também rejeita avisos velhos demais (mais de 10 minutos): um aviso capturado não pode
 * ser reenviado semanas depois para forçar um pedido a "pago".
 */
export async function webhookAutentico({ segredo, assinatura, requestId, dataId, agora = Date.now(), toleranciaMs = 10 * 60 * 1000 }) {
  const lida = lerAssinatura(assinatura);
  if (!lida || !requestId || !dataId || !segredo) return false;

  const lido = Number(lida.ts);
  if (!Number.isFinite(lido)) return false;
  // O Mercado Pago manda o ts em segundos (10 dígitos). Aceitamos também milissegundos:
  // abaixo de 1e12 só pode ser segundos, porque 1e12 ms já é setembro de 2001.
  const tsMs = lido < 1e12 ? lido * 1000 : lido;
  if (Math.abs(agora - tsMs) > toleranciaMs) return false;

  // A assinatura cobre o ts exatamente como veio no cabeçalho, sem a conversão acima.
  const esperado = await hmacHex(segredo, manifestoDoWebhook({ dataId, requestId, ts: lida.ts }));
  return iguais(esperado, lida.v1);
}

/** Comparação em tempo constante, para não vazar por quanto tempo bateu. */
function iguais(a, b) {
  if (a.length !== b.length) return false;
  let diferenca = 0;
  for (let indice = 0; indice < a.length; indice += 1) {
    diferenca |= a.charCodeAt(indice) ^ b.charCodeAt(indice);
  }
  return diferenca === 0;
}

/**
 * A carga da licença que um produto pago gera. A data de emissão é a do pagamento; o
 * vencimento soma os dias do produto, ou fica nulo quando é vitalício.
 */
export function cargaDaLicenca({ ref, produtoId, email, pagoEm = new Date() }) {
  const produto = buscarProduto(produtoId);
  if (!produto) throw new Error(`Produto desconhecido: ${produtoId}`);

  const emitida = new Date(pagoEm);
  const expira = produto.dias === null ? null : new Date(emitida.getTime() + produto.dias * 24 * 60 * 60 * 1000);

  return {
    v: 1,
    id: ref,
    plano: produto.plano,
    email: String(email).trim().toLowerCase(),
    emitida: emitida.toISOString(),
    expira: expira ? expira.toISOString() : null,
    maquinas: MAQUINAS_POR_LICENCA,
  };
}

/**
 * Situação de um pagamento do Mercado Pago, traduzida para o pedido.
 *
 * "estornado" é dinheiro que voltou depois de aprovado: devolução total (pelo painel ou
 * pela API) ou contestação no cartão. A devolução parcial deixa o pagamento "approved",
 * e o pedido continua pago.
 */
export function estadoDoPagamento(status) {
  switch (String(status)) {
    case "approved":
      return "pago";
    case "rejected":
    case "cancelled":
      return "recusado";
    case "refunded":
    case "charged_back":
      return "estornado";
    default:
      return "criado";
  }
}

/** Dias de garantia anunciados em /precos: dentro deles, a devolução é sem perguntas. */
export const DIAS_DE_GARANTIA = 7;

/** "19,90", "19.90", "R$ 19,90" ou "19" → centavos. Qualquer outra coisa → null. */
export function centavosDoValor(texto) {
  const limpo = String(texto ?? "").replace(/^\s*R\$\s*/i, "").trim();
  const partes = /^(\d+)(?:[.,](\d{1,2}))?$/.exec(limpo);
  if (!partes) return null;
  const centavos = Number(partes[1]) * 100 + Number((partes[2] ?? "0").padEnd(2, "0"));
  return centavos > 0 ? centavos : null;
}

/**
 * Quanto devolver e o corpo do pedido à API de estornos do Mercado Pago.
 *
 * Sem valor, devolve tudo o que ainda sobra do pagamento. `zera` diz se, depois deste
 * estorno, não sobra nada: é aí que o pagamento vira "refunded" e o pedido, "estornado".
 * O corpo vazio é o estorno total da API; com `amount`, é parcial.
 */
export function pedidoDeEstorno({ pagoCentavos, jaEstornadoCentavos = 0, pedidoCentavos = null }) {
  const resta = pagoCentavos - jaEstornadoCentavos;
  if (resta <= 0) throw new Error("Este pagamento já foi todo devolvido.");
  const centavos = pedidoCentavos ?? resta;
  if (centavos > resta) {
    throw new Error(`O valor pedido passa do que ainda pode ser devolvido (${formatarCentavos(resta)}).`);
  }
  const zera = centavos === resta;
  const corpo = zera && jaEstornadoCentavos === 0 ? {} : { amount: centavos / 100 };
  return { centavos, zera, corpo };
}

/**
 * A chave de idempotência do estorno. Repetir o mesmo comando (rede caiu, terminal
 * fechou) não devolve duas vezes: o Mercado Pago reconhece a chave e responde o mesmo.
 */
export function chaveDoEstorno(ref, centavos, jaEstornadoCentavos = 0) {
  return `estorno-${ref}-${jaEstornadoCentavos}-${centavos}`;
}

export function dentroDaGarantia(pagoEm, agora = Date.now(), dias = DIAS_DE_GARANTIA) {
  const pago = new Date(pagoEm).getTime();
  if (!Number.isFinite(pago)) return false;
  return agora - pago <= dias * 24 * 60 * 60 * 1000;
}

export function formatarCentavos(centavos) {
  return `R$ ${(centavos / 100).toFixed(2).replace(".", ",")}`;
}
