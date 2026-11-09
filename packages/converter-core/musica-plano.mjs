/**
 * O plano grátis na criação musical do site: um projeto novo por semana, à parte dos
 * contadores do estúdio de vídeo. A semana vai de segunda a domingo no horário de Brasília
 * (America/Sao_Paulo) e é identificada como "2026-W46" (semana ISO).
 *
 * Conta a criação, não o uso: reabrir, continuar gravando, editar e exportar o mesmo projeto
 * não gastam nada, para ninguém perder uma gravação por causa de um erro. O plugin não conta:
 * ele só executa o que o site pede, sempre com o id de um projeto já contado aqui.
 *
 * Como os outros limites do site, mora no navegador (sem cadastro): limpar os dados zera o
 * contador. É um limite de conveniência, não uma trava contra fraude.
 * Windows e Android não usam esta regra: lá vale o teste de 7 dias e depois a licença.
 */

import { limite } from "./precos.mjs";

/** Data local de São Paulo (ano, mês, dia) de um instante. */
export function dataEmSaoPaulo(instante = new Date()) {
  const partes = new Intl.DateTimeFormat("en-CA", { timeZone: "America/Sao_Paulo", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(instante);
  const valor = (tipo) => Number(partes.find((parte) => parte.type === tipo)?.value);
  return { ano: valor("year"), mes: valor("month"), dia: valor("day") };
}

/** Semana ISO (segunda a domingo) de uma data civil: "AAAA-Wss". */
export function semanaIso(ano, mes, dia) {
  const data = new Date(Date.UTC(ano, mes - 1, dia));
  const diaDaSemana = data.getUTCDay() || 7; // segunda = 1 … domingo = 7
  data.setUTCDate(data.getUTCDate() + 4 - diaDaSemana); // a quinta-feira decide o ano
  const anoIso = data.getUTCFullYear();
  const inicio = new Date(Date.UTC(anoIso, 0, 1));
  const semana = Math.ceil(((data - inicio) / 86400000 + 1) / 7);
  return `${anoIso}-W${String(semana).padStart(2, "0")}`;
}

export function semanaDeSaoPaulo(instante = new Date()) {
  const { ano, mes, dia } = dataEmSaoPaulo(instante);
  return semanaIso(ano, mes, dia);
}

/**
 * Pode criar `projetoId` agora? `criadosNaSemana` são os ids já contados nesta semana.
 * Devolve null (pode) ou o bloqueio com a mensagem. Reabrir um id já contado sempre pode.
 */
export function conferirCriacaoMusical(plano, { projetoId, criadosNaSemana = [] }) {
  const teto = limite(plano, "musica");
  if (teto === null || criadosNaSemana.includes(projetoId)) return null;
  if (criadosNaSemana.length >= teto) {
    return {
      contador: "musica",
      mensagem:
        teto === 1
          ? "No grátis cabe um projeto musical novo por semana, e o desta semana já foi criado. Ele continua aberto para gravar, editar e exportar; na segunda-feira libera outro, ou o Pro tira o limite."
          : `No grátis cabem ${teto} projetos musicais novos por semana, e os desta semana já foram criados.`,
    };
  }
  return null;
}
