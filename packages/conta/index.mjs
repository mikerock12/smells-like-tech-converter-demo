/**
 * A conta de quem comprou: entrar com um código enviado ao e-mail, sem senha.
 *
 * A conta não libera nada sozinha. Quem libera o Pro continua sendo a chave assinada
 * (packages/licenca), que funciona sem internet; a conta é o lugar onde as chaves de um
 * e-mail ficam guardadas e de onde elas chegam ao navegador. Se a conta sair do ar, quem
 * já tem a chave no navegador ou no aplicativo não perde nada.
 *
 * Por que código no e-mail e não senha: não há senha para vazar, esquecer ou recuperar,
 * e o código prova que a pessoa lê aquele e-mail — que é exatamente o que liga uma
 * compra a alguém. As compras de um e-mail aparecem na conta desse e-mail, inclusive as
 * feitas antes de a conta existir.
 *
 * Módulo puro: quem fala com o D1 e com o provedor de e-mail está em `lib/conta/`.
 */

import { alcanceDoPlano } from "../converter-core/precos.mjs";
import { diaEmBrasilia } from "../funil/index.mjs";
import { paraBase64Url } from "../licenca/index.mjs";

/** O código vale por 10 minutos e aceita 5 tentativas; depois disso, só pedindo outro. */
export const MINUTOS_DO_CODIGO = 10;
export const TENTATIVAS_DO_CODIGO = 5;
/** Entre um código e outro para o mesmo e-mail, e quantos cabem numa hora. */
export const SEGUNDOS_ENTRE_CODIGOS = 60;
export const CODIGOS_POR_HORA = 5;
/** Quanto tempo o navegador fica com a conta aberta. */
export const DIAS_DA_SESSAO = 90;

/**
 * O cookie da sessão. O prefixo `__Host-` faz o navegador recusá-lo sem `Secure`, com
 * `Domain` ou fora de `Path=/`: ninguém num subdomínio consegue plantar uma sessão aqui.
 */
export const COOKIE_DA_SESSAO = "__Host-slt_sessao";

/** Onde o navegador lembra o e-mail da conta, só para preencher o checkout. Não é segredo. */
export const EMAIL_LEMBRADO = "slt.conta.email";
/** "1" quando a pessoa desligou o registro de uso; o navegador então nem anota. */
export const USO_DESLIGADO = "slt.conta.uso-desligado";
/** O uso anotado que ainda não foi mandado. */
export const USO_PENDENTE = "slt.conta.uso";

/** O servidor aceita uso dos últimos 7 dias; o resto é descartado sem erro. */
export const DIAS_DE_USO_ACEITOS = 7;
export const LINHAS_DE_USO_POR_ENVIO = 200;

export function normalizarEmail(texto) {
  return String(texto ?? "").trim().toLowerCase();
}

/**
 * Seis dígitos, com a mesma chance para cada um: descarta os sorteios do fim da faixa de
 * 32 bits que fariam alguns restos saírem mais que outros.
 */
export function novoCodigo(sortear = () => crypto.getRandomValues(new Uint32Array(1))[0]) {
  const teto = Math.floor(2 ** 32 / 1_000_000) * 1_000_000;
  for (;;) {
    const sorteio = sortear();
    if (sorteio < teto) return String(sorteio % 1_000_000).padStart(6, "0");
  }
}

/** O que a pessoa digitou, só com os dígitos: aceita "123 456" e "123-456". */
export function codigoDigitado(texto) {
  const digitos = String(texto ?? "").replace(/\D/g, "");
  return digitos.length === 6 ? digitos : null;
}

async function sha256Hex(texto) {
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(texto)));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * O banco guarda o resumo do código, nunca o código. O e-mail entra no resumo para o
 * mesmo código em dois e-mails não produzir a mesma linha.
 */
export function resumoDoCodigo(email, codigo) {
  return sha256Hex(`codigo:${normalizarEmail(email)}:${codigo}`);
}

/** 32 bytes aleatórios: é o que vai no cookie. */
export function novoToken(bytes = crypto.getRandomValues(new Uint8Array(32))) {
  return paraBase64Url(bytes);
}

/** O banco guarda o resumo do token: quem ler o banco não consegue abrir sessão nenhuma. */
export function resumoDoToken(token) {
  return sha256Hex(`sessao:${token}`);
}

/**
 * Se já dá para mandar outro código a este e-mail. `anterior` é a linha do último envio
 * (ou nulo); a janela de uma hora conta quantos saíram, para ninguém usar o formulário
 * para encher a caixa de entrada de outra pessoa.
 */
export function podeEnviarCodigo(anterior, agora = Date.now()) {
  if (!anterior) return { ok: true, janelaInicio: new Date(agora).toISOString(), enviados: 1 };

  const desdeUltimo = (agora - Date.parse(anterior.enviadoEm)) / 1000;
  if (desdeUltimo < SEGUNDOS_ENTRE_CODIGOS) {
    return { ok: false, esperarSegundos: Math.ceil(SEGUNDOS_ENTRE_CODIGOS - desdeUltimo) };
  }

  const inicio = Date.parse(anterior.janelaInicio);
  if (agora - inicio >= 60 * 60 * 1000) return { ok: true, janelaInicio: new Date(agora).toISOString(), enviados: 1 };
  if (anterior.enviados >= CODIGOS_POR_HORA) {
    return { ok: false, esperarSegundos: Math.ceil((inicio + 60 * 60 * 1000 - agora) / 1000) };
  }
  return { ok: true, janelaInicio: anterior.janelaInicio, enviados: anterior.enviados + 1 };
}

/**
 * Confere o código digitado contra a linha guardada.
 * "ok" · "errado" (conta uma tentativa) · "expirado" · "esgotado" · "sem-codigo".
 */
export function conferirCodigo(registro, resumoDigitado, agora = Date.now()) {
  if (!registro || !registro.resumo) return "sem-codigo";
  if (registro.tentativas >= TENTATIVAS_DO_CODIGO) return "esgotado";
  if (Date.parse(registro.expira) < agora) return "expirado";
  return iguais(registro.resumo, resumoDigitado) ? "ok" : "errado";
}

/** Comparação em tempo constante, para não vazar por quanto tempo bateu. */
function iguais(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diferenca = 0;
  for (let indice = 0; indice < a.length; indice += 1) diferenca |= a.charCodeAt(indice) ^ b.charCodeAt(indice);
  return diferenca === 0;
}

export function cookieDaSessao(token, dias = DIAS_DA_SESSAO) {
  return `${COOKIE_DA_SESSAO}=${token}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${dias * 24 * 60 * 60}`;
}

export function cookieQueApaga() {
  return `${COOKIE_DA_SESSAO}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;
}

/** O valor de um cookie no cabeçalho `Cookie`, ou nulo. */
export function lerCookie(cabecalho, nome = COOKIE_DA_SESSAO) {
  for (const parte of String(cabecalho ?? "").split(";")) {
    const separador = parte.indexOf("=");
    if (separador < 0) continue;
    if (parte.slice(0, separador).trim() === nome) {
      const valor = parte.slice(separador + 1).trim();
      return valor || null;
    }
  }
  return null;
}

/**
 * Uma requisição que muda a conta (pedir código, entrar, sair) só vale vinda do próprio
 * site. O `SameSite=Lax` já segura o cookie em POST de outro domínio; conferir a origem
 * fecha o resto, inclusive o formulário de outro site pedindo códigos em nosso nome.
 */
export function origemDoProprioSite(origem, urlDaRequisicao) {
  if (!origem) return false;
  try {
    return new URL(origem).origin === new URL(urlDaRequisicao).origin;
  } catch {
    return false;
  }
}

/**
 * Qual chave da conta vai para o navegador: a que libera o Pro no site e ainda vale,
 * preferindo a que não vence e, depois, a que vence mais tarde. Nulo quando nenhuma
 * serve ao site (só a do aplicativo, ou todas vencidas).
 */
export function chaveParaONavegador(licencas, agora = Date.now()) {
  const servem = licencas.filter(
    (licenca) =>
      licenca.chave &&
      licenca.estado === "pago" &&
      alcanceDoPlano(licenca.plano).site &&
      (licenca.expira === null || Date.parse(licenca.expira) >= agora),
  );
  servem.sort((a, b) => fimDaLicenca(b) - fimDaLicenca(a));
  return servem[0] ?? null;
}

function fimDaLicenca(licenca) {
  return licenca.expira === null ? Number.POSITIVE_INFINITY : Date.parse(licenca.expira);
}

/**
 * Soma uma conversão ao uso ainda não enviado. A forma é a mesma que vai ao servidor:
 * `{ "2026-09-27": { "imagem.converter": { arquivos: 3, bytes: 812345 } } }` — a
 * ferramenta, quantos arquivos e o tamanho somado. Nome de arquivo não entra.
 */
export function somarUso(pendente, { dia, ferramenta, arquivos, bytes }) {
  const doDia = { ...(pendente?.[dia] ?? {}) };
  const atual = doDia[ferramenta] ?? { arquivos: 0, bytes: 0 };
  doDia[ferramenta] = { arquivos: atual.arquivos + arquivos, bytes: atual.bytes + bytes };
  return { ...(pendente ?? {}), [dia]: doDia };
}

/**
 * Confere o uso que o navegador mandou e devolve as linhas para somar no banco, ou nulo
 * quando o corpo está malformado. Dia futuro ou velho demais e ferramenta que não existe
 * no catálogo são descartados sem erro: um navegador que ficou dias fechado não precisa
 * de mensagem, só não conta.
 */
export function linhasDoUso(corpo, ferramentaExiste, agora = new Date()) {
  const dias = corpo?.dias;
  if (!dias || typeof dias !== "object" || Array.isArray(dias)) return null;

  const hoje = diaEmBrasilia(agora);
  const primeiro = diaEmBrasilia(new Date(agora.getTime() - (DIAS_DE_USO_ACEITOS - 1) * 86_400_000));
  const linhas = [];
  for (const [dia, ferramentas] of Object.entries(dias)) {
    if (!ferramentas || typeof ferramentas !== "object" || Array.isArray(ferramentas)) return null;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dia) || dia > hoje || dia < primeiro) continue;
    for (const [ferramenta, valores] of Object.entries(ferramentas)) {
      const arquivos = valores?.arquivos;
      const bytes = valores?.bytes;
      if (!Number.isInteger(arquivos) || arquivos < 1 || arquivos > 100_000) return null;
      if (!Number.isInteger(bytes) || bytes < 0 || bytes > 10 ** 13) return null;
      if (!ferramentaExiste(ferramenta)) continue;
      linhas.push({ dia, ferramenta, arquivos, bytes });
      if (linhas.length > LINHAS_DE_USO_POR_ENVIO) return null;
    }
  }
  return linhas;
}

/** O e-mail com o código. Texto puro, curto, e com o código logo no assunto. */
export function emailDoCodigo({ codigo, site }) {
  const base = String(site).replace(/\/$/, "");
  return {
    assunto: `${codigo} é o seu código de acesso`,
    texto: [
      `Seu código para entrar na conta do Smells Like Tech Converter: ${codigo}`,
      "",
      `Ele vale por ${MINUTOS_DO_CODIGO} minutos. Digite na página ${base}/conta.`,
      "",
      "Se não foi você que pediu, ignore este e-mail: sem o código, ninguém entra.",
      "",
      "Smells Like Tech Informática · Porto Alegre/RS",
    ].join("\n"),
  };
}

/** O arquivo que "Baixar a chave" salva: a chave e o que fazer com ela sem internet. */
export function arquivoDaChave({ chave, ref, nomeDoPlano, expira, site }) {
  const base = String(site).replace(/\/$/, "");
  return [
    `Smells Like Tech Converter · ${nomeDoPlano} · pedido ${ref}`,
    expira ? `Vale até ${new Date(expira).toLocaleDateString("pt-BR", { timeZone: "America/Sao_Paulo" })}.` : "Não vence.",
    "",
    "A CHAVE (copie as linhas abaixo inteiras):",
    "",
    chave,
    "",
    "Como usar sem internet:",
    `- No site: entre em ${base}/conta uma vez, ou cole a chave em ${base}/chave. Ela fica guardada no navegador.`,
    "- No aplicativo para Windows: Configurações → Licença → colar a chave. Basta uma vez.",
    "",
    "Guarde este arquivo em um lugar seguro (Documentos, seu Drive ou seu e-mail). Quem tiver a",
    "chave consegue usar a licença, então não publique nem mande para outras pessoas.",
    "",
  ].join("\n");
}
