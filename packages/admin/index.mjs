/**
 * O painel administrativo: regras puras, sem banco.
 *
 * Quem é admin, qual é a janela de tempo que o painel mostra, como a receita se divide
 * em dias, semanas ou meses, e quem tem plano ativo. Tudo no painel usa a mesma janela:
 * a soma das colunas do gráfico é igual ao número "receita no período", e o funil e o
 * uso contam os mesmos dias.
 *
 * O acesso é pelo e-mail da conta, listado em ADMIN_EMAILS (segredo do Worker). O dono
 * entra em /conta com o código, como qualquer cliente, e o painel se abre para ele.
 */

import { normalizarEmail } from "../conta/index.mjs";
import { alcanceDoPlano, produto as buscarProduto } from "../converter-core/precos.mjs";
import { diaEmBrasilia } from "../funil/index.mjs";
import { emailValido, referenciaValida } from "../loja/index.mjs";

const DIA = 86_400_000;

/** Os períodos que o painel oferece, em dias. */
export const PERIODOS = [7, 30, 90, 365];

export function periodoDoPainel(texto) {
  const dias = Number(texto);
  return PERIODOS.includes(dias) ? dias : 30;
}

/** ADMIN_EMAILS aceita um ou vários e-mails, separados por vírgula, ponto e vírgula ou espaço. */
export function ehAdmin(email, lista) {
  if (!email) return false;
  const alvo = normalizarEmail(email);
  return String(lista ?? "")
    .split(/[,;\s]+/)
    .map(normalizarEmail)
    .filter(Boolean)
    .includes(alvo);
}

/**
 * Meia-noite de Brasília daquele dia, em UTC, para comparar com `pago_em` (ISO em UTC).
 * Brasília é UTC−3 o ano inteiro desde o fim do horário de verão, em 2019.
 */
export function inicioEmUtc(dia) {
  return `${dia}T03:00:00.000Z`;
}

function somarDias(dia, quantos) {
  const data = new Date(`${dia}T12:00:00Z`);
  data.setUTCDate(data.getUTCDate() + quantos);
  return data.toISOString().slice(0, 10);
}

function diasEntre(de, ate) {
  return Math.round((Date.parse(`${ate}T12:00:00Z`) - Date.parse(`${de}T12:00:00Z`)) / DIA);
}

const MESES = ["jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"];
const MESES_LONGOS = ["janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"];

const curto = (dia) => `${dia.slice(8, 10)}/${dia.slice(5, 7)}`;

/**
 * A janela do painel: até hoje, em baldes de um dia (7 e 30 dias), de uma semana (90) ou
 * de um mês (365, os 12 meses até o atual). `desde` é o primeiro dia do primeiro balde,
 * e é dele que todas as consultas do painel partem.
 */
export function janelaDoPainel(dias, agora = new Date()) {
  const hoje = diaEmBrasilia(agora);
  const tipo = dias <= 31 ? "dia" : dias <= 90 ? "semana" : "mes";

  if (tipo === "mes") {
    const [ano, mes] = hoje.split("-").map(Number);
    const baldes = Array.from({ length: 12 }, (_, indice) => {
      const inicio = new Date(Date.UTC(ano, mes - 1 - (11 - indice), 1));
      const de = inicio.toISOString().slice(0, 10);
      const fim = new Date(Date.UTC(inicio.getUTCFullYear(), inicio.getUTCMonth() + 1, 0)).toISOString().slice(0, 10);
      return {
        de,
        ate: fim < hoje ? fim : hoje,
        rotulo: MESES[inicio.getUTCMonth()],
        rotuloLongo: `${MESES_LONGOS[inicio.getUTCMonth()]} de ${inicio.getUTCFullYear()}`,
      };
    });
    return { dias, tipo, hoje, desde: baldes[0].de, baldes };
  }

  const passo = tipo === "semana" ? 7 : 1;
  const quantos = Math.ceil(dias / passo);
  const desde = somarDias(hoje, -(quantos * passo - 1));
  const baldes = Array.from({ length: quantos }, (_, indice) => {
    const de = somarDias(desde, indice * passo);
    const ate = somarDias(de, passo - 1);
    return { de, ate, rotulo: curto(de), rotuloLongo: passo === 1 ? curto(de) : `${curto(de)} a ${curto(ate)}` };
  });
  return { dias, tipo, hoje, desde, baldes };
}

/** O balde de um dia ("2026-09-27"), ou -1 fora da janela. */
export function baldeDoDia(janela, dia) {
  if (dia < janela.desde || dia > janela.hoje) return -1;
  if (janela.tipo === "mes") return janela.baldes.findIndex((balde) => balde.de.slice(0, 7) === dia.slice(0, 7));
  return Math.floor(diasEntre(janela.desde, dia) / (janela.tipo === "semana" ? 7 : 1));
}

/** Soma os pagamentos (`pagoEm` ISO, `centavos`) nos baldes da janela, com os vazios em zero. */
export function receitaPorBalde(janela, pagamentos) {
  const baldes = janela.baldes.map((balde) => ({ ...balde, centavos: 0, pedidos: 0 }));
  for (const pagamento of pagamentos) {
    if (!pagamento.pagoEm) continue;
    const indice = baldeDoDia(janela, diaEmBrasilia(new Date(pagamento.pagoEm)));
    if (indice < 0) continue;
    baldes[indice].centavos += Number(pagamento.centavos) || 0;
    baldes[indice].pedidos += 1;
  }
  return baldes;
}

function ativa(licenca, agora) {
  return licenca.estado === "pago" && (licenca.expira === null || Date.parse(licenca.expira) >= agora);
}

/**
 * Os planos que cada e-mail tem valendo agora: `{ email: [{ plano, expira }] }`. Duas
 * licenças do mesmo plano viram uma, com o vencimento mais distante.
 */
export function planosAtivosPorEmail(licencas, agora = Date.now()) {
  const porEmail = {};
  for (const licenca of licencas) {
    if (!ativa(licenca, agora)) continue;
    const planos = (porEmail[licenca.email] ??= []);
    const mesmo = planos.find((item) => item.plano === licenca.plano);
    if (!mesmo) planos.push({ plano: licenca.plano, expira: licenca.expira });
    else if (mesmo.expira !== null && (licenca.expira === null || licenca.expira > mesmo.expira)) mesmo.expira = licenca.expira;
  }
  return porEmail;
}

/**
 * O retrato do Pro agora: quantas pessoas têm o Pro valendo no site, quanto isso dá por
 * mês (o anual conta 1/12 do preço; o vitalício não entra, porque não se repete) e quantas
 * licenças vitalícias estão ativas.
 */
export function retratoDoPro(licencas, agora = Date.now()) {
  const pessoas = new Set();
  let porMes = 0;
  let vitalicias = 0;
  for (const licenca of licencas) {
    if (!ativa(licenca, agora)) continue;
    if (licenca.expira === null) vitalicias += 1;
    if (!alcanceDoPlano(licenca.plano).site) continue;
    pessoas.add(licenca.email);
    const produto = buscarProduto(licenca.produto);
    if (produto?.dias) porMes += licenca.centavos / Math.max(1, Math.round(produto.dias / 30.4375));
  }
  return { pessoasComPro: pessoas.size, porMesCentavos: Math.round(porMes), vitalicias };
}

/** Marca dos pedidos de cortesia: não entram na receita nem contam como compra. */
export const ORIGEM_DA_CORTESIA = "cortesia";

/**
 * Confere o pedido de cortesia do painel: e-mail, produto do catálogo e, opcionalmente, o
 * número de um pedido já emitido fora da loja (para registrar uma chave antiga com o
 * mesmo número). Devolve os dados limpos ou a mensagem do que está errado.
 */
export function validarCortesia(entrada) {
  const email = normalizarEmail(entrada?.email);
  if (!emailValido(email)) return { ok: false, erro: "Informe um e-mail válido." };
  const produto = buscarProduto(String(entrada?.produto ?? ""));
  if (!produto) return { ok: false, erro: "Escolha um produto do catálogo." };
  const ref = String(entrada?.ref ?? "").trim().toUpperCase();
  if (ref && !referenciaValida(ref)) return { ok: false, erro: "O número do pedido tem o formato SLT-XXXX-XXXX." };
  return { ok: true, email, produtoId: produto.id, ref: ref || null };
}
