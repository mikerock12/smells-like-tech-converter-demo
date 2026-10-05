/**
 * Planos, limites e preços — a única fonte da verdade.
 *
 * A página de preços, o checkout, a emissão de licenças e os limites aplicados na
 * oficina leem daqui. Mudar um preço é mudar uma linha; o resto acompanha.
 *
 * O raciocínio de cada número está em docs/precos.md. Em resumo: o processamento
 * acontece na máquina de quem usa, então o custo marginal é quase zero. Isso permite um
 * plano grátis generoso de verdade — sem marca d'água e sem cadastro — e cobrar pelo
 * que economiza tempo de quem trabalha com mídia: lote sem limite, as ferramentas de
 * voz, presets e o aplicativo completo.
 */

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}

export const MOEDA = "BRL";

/** Preço em centavos, para nunca somar ponto flutuante. */
export const PLANOS = deepFreeze({
  gratis: {
    id: "gratis",
    nome: "Grátis",
    chamada: "Para quem precisa converter um arquivo agora",
    descricao:
      "Todas as conversões que rodam no navegador, sem cadastro e sem marca d'água. Com limites de volume, não de qualidade.",
    centavos: 0,
    site: true,
    app: false,
    android: false,
    cor: "neutra",
  },
  pro: {
    id: "pro",
    nome: "Pro",
    chamada: "Para quem trabalha com mídia todo dia",
    descricao:
      "Sem limites de lote, de duração ou de páginas, e com as ferramentas de voz: transcrição, legenda, narração e OCR sem contador. No site e no plugin.",
    centavos: 1990,
    site: true,
    app: false,
    android: false,
    cor: "laranja",
  },
  app: {
    id: "app",
    nome: "Aplicativo para Windows",
    chamada: "Licença vitalícia: pague uma vez, use para sempre",
    descricao:
      "O programa completo, com janela, fila com histórico, lote a partir de pastas e ajuste fino de cada motor. Funciona sem internet e sem o site.",
    centavos: 24900,
    site: false,
    app: true,
    android: false,
    cor: "ambar",
  },
  android: {
    id: "android",
    nome: "Aplicativo para Android",
    chamada: "O conversor completo no celular, pago uma vez",
    descricao:
      "Imagem, vídeo, áudio, PDF, OCR e narração no próprio celular, sem site e sem internet. O plugin é só para Windows: no celular, é este app que faz tudo.",
    centavos: 24900,
    site: false,
    app: false,
    android: true,
    cor: "ambar",
  },
  completo: {
    id: "completo",
    nome: "Tudo, para sempre",
    chamada: "Aplicativos para Windows e Android + Pro vitalício no site e no plugin",
    descricao:
      "Tudo o que existe, sem mensalidade, sem vencimento. Para quem já sabe que vai usar por anos.",
    centavos: 34900,
    site: true,
    app: true,
    android: true,
    cor: "amarela",
  },
});

/**
 * O que se compra. Cada produto vira uma licença com o plano indicado e o prazo em
 * dias (nulo = vitalício). Os ids são os `external_reference` do Mercado Pago.
 */
export const PRODUTOS = deepFreeze([
  {
    id: "pro-mensal",
    plano: "pro",
    titulo: "Pro · mensal",
    centavos: 1990,
    dias: 31,
    periodo: "por mês",
    nota: "Cancele quando quiser: é um pagamento por vez, sem renovação automática.",
  },
  {
    id: "pro-anual",
    plano: "pro",
    titulo: "Pro · anual",
    centavos: 14900,
    dias: 366,
    periodo: "por ano",
    nota: "Equivale a R$ 12,42 por mês. Dois meses e meio de graça em relação ao mensal.",
  },
  {
    id: "app-vitalicio",
    plano: "app",
    titulo: "Aplicativo para Windows · licença vitalícia",
    centavos: 24900,
    dias: null,
    periodo: "pagamento único",
    nota: "Vale em até 3 computadores seus. Todas as atualizações incluídas.",
  },
  {
    id: "android-vitalicio",
    plano: "android",
    titulo: "Aplicativo para Android · licença vitalícia",
    centavos: 24900,
    dias: null,
    periodo: "pagamento único",
    nota: "Vale em até 3 celulares seus. Todas as atualizações incluídas.",
  },
  {
    id: "completo-vitalicio",
    plano: "completo",
    titulo: "Tudo, para sempre",
    centavos: 34900,
    dias: null,
    periodo: "pagamento único",
    nota: "Aplicativos para Windows e Android e Pro vitalício no site e no plugin. Em até 3 aparelhos de cada.",
  },
]);

export const PRODUTOS_POR_ID = deepFreeze(Object.fromEntries(PRODUTOS.map((produto) => [produto.id, produto])));

export function produto(id) {
  return PRODUTOS_POR_ID[id] ?? null;
}

/** Quantos computadores uma licença vitalícia do aplicativo pode ativar. */
export const MAQUINAS_POR_LICENCA = 3;

/** Dias de teste do aplicativo antes de pedir a licença. */
export const DIAS_DE_TESTE_DO_APP = 7;

/**
 * Limites do plano grátis. `null` no Pro significa sem limite.
 *
 * São limites de volume e de conveniência, nunca de qualidade: o grátis não tem marca
 * d'água, não reduz resolução e não guarda arquivo nenhum. Os contadores "por dia" são
 * do próprio navegador de quem usa (zeram à meia-noite local).
 */
export const LIMITES = deepFreeze({
  gratis: {
    lote: 5,
    video: 10,
    pdf: 50,
    pesado: 5,
    transcricao: 3,
    narracao: 1500,
    ocr: 5,
  },
  pro: {
    lote: null,
    video: null,
    pdf: null,
    pesado: null,
    transcricao: null,
    narracao: null,
    ocr: null,
  },
});

/** Como cada limite aparece para a pessoa. */
export const ROTULOS_DOS_LIMITES = deepFreeze({
  lote: { titulo: "Arquivos por vez", unidade: "arquivos", periodo: null },
  video: { titulo: "Vídeo no navegador", unidade: "minutos por arquivo", periodo: null },
  pdf: { titulo: "Páginas de PDF", unidade: "páginas por arquivo", periodo: null },
  pesado: { titulo: "Conversões pelo plugin", unidade: "por dia", periodo: "dia" },
  transcricao: { titulo: "Transcrição", unidade: "minutos por dia", periodo: "dia" },
  narracao: { titulo: "Narração", unidade: "caracteres por dia", periodo: "dia" },
  ocr: { titulo: "Reconhecimento de texto", unidade: "páginas por dia", periodo: "dia" },
});

/** Quadro comparativo da página de preços, na ordem em que aparece. */
export const COMPARATIVO = deepFreeze([
  { item: "Conversões no navegador (imagem, áudio, vídeo, PDF)", gratis: "Ilimitadas", pro: "Ilimitadas" },
  { item: "Marca d'água", gratis: "Nunca", pro: "Nunca" },
  { item: "Cadastro para usar", gratis: "Não", pro: "Não (só a chave)" },
  { item: "Arquivos por vez", gratis: "5", pro: "Sem limite" },
  { item: "Vídeo no navegador", gratis: "Até 10 min por arquivo", pro: "Sem limite" },
  { item: "PDF", gratis: "Até 50 páginas por arquivo", pro: "Sem limite" },
  { item: "Conversões pesadas pelo plugin", gratis: "5 por dia", pro: "Sem limite" },
  { item: "Transcrição e legenda (Whisper local)", gratis: "3 minutos por dia, para testar", pro: "Sem limite" },
  { item: "Narração (texto → voz)", gratis: "1.500 caracteres por dia", pro: "Sem limite" },
  { item: "Foto e PDF escaneado → texto (OCR)", gratis: "5 páginas por dia", pro: "Sem limite" },
  { item: "PDF → Word editável", gratis: "—", pro: "Incluído" },
  { item: "Presets salvos e fila prioritária", gratis: "—", pro: "Incluído" },
  { item: "Suporte", gratis: "Comunidade", pro: "E-mail em até 1 dia útil" },
]);

/** "R$ 19,90". */
export function formatarReais(centavos) {
  const valor = Number(centavos) || 0;
  const inteiro = Math.floor(valor / 100);
  const resto = String(valor % 100).padStart(2, "0");
  return `R$ ${inteiro.toLocaleString("pt-BR")},${resto}`;
}

/** "19,90" sem o símbolo, para o Mercado Pago (que quer número, não texto). */
export function emReais(centavos) {
  return Math.round(Number(centavos) || 0) / 100;
}

/** Quanto o anual economiza sobre doze mensais, em porcentagem inteira. */
export function economiaDoAnual() {
  const mensal = produto("pro-mensal").centavos * 12;
  const anual = produto("pro-anual").centavos;
  return Math.round(((mensal - anual) / mensal) * 100);
}

/** Um plano de licença libera o site (e o plugin) e/ou o aplicativo. */
export function alcanceDoPlano(plano) {
  const definicao = PLANOS[plano];
  return definicao ? { site: definicao.site, app: definicao.app, android: definicao.android } : { site: false, app: false, android: false };
}

/**
 * O plano efetivo de quem está usando o site: "pro" quando existe licença válida com
 * alcance no site; "gratis" caso contrário. É a única regra que a oficina consulta.
 */
export function planoDoSite(licenca) {
  if (!licenca) return "gratis";
  if (!alcanceDoPlano(licenca.plano).site) return "gratis";
  if (licenca.expira && Date.parse(licenca.expira) < Date.now()) return "gratis";
  return "pro";
}

/** Limite de um contador para o plano: número ou null (sem limite). */
export function limite(plano, contador) {
  const tabela = LIMITES[plano === "pro" ? "pro" : "gratis"];
  return tabela ? (tabela[contador] ?? null) : null;
}

/** Uma ferramenta Pro só abre com plano Pro; as grátis abrem para todos. */
export function ferramentaLiberada(ferramenta, plano) {
  return ferramenta.plano === "gratis" || plano === "pro";
}
