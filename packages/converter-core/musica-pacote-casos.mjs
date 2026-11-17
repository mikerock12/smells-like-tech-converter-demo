/**
 * O que o caso compartilhado do pacote de faixas confere (musica-pacote-casos.json). Os
 * testes do C# e do Kotlin recalculam os mesmos campos a partir de `entrada`.
 */

import { fonteDaMemoria, renderizar } from "./musica-mixer.mjs";
import { gerarMidia, resumo } from "./musica-casos.mjs";
import {
  combinacoesProntas, configuracaoDoPacote, grupoDoVideo, lerTratamentoDoPacote, modeloDoPacote, nomeDoGrupo, nomesDosArquivos,
  problemasDoPacote, projetoDoArquivo, resumoDoArquivo,
} from "./musica-pacote.mjs";

export function esperadoDoPacote(entrada) {
  const combinacoes = Object.fromEntries(entrada.modelos.map((m) => [m, combinacoesProntas(m).map((c) => ({ id: c.id, grupos: c.grupos }))]));
  const nomesDeGrupo = entrada.nomesDeGrupo.map((c) => nomeDoGrupo(c.faixas, modeloDoPacote(c.modelo)));
  const nomes = nomesDosArquivos(entrada.nomes.titulo, entrada.nomes.grupos, entrada.nomes.extensao);
  const problemas = entrada.problemas.map((c) => problemasDoPacote(c.modelo, c.grupos));
  const video = Object.fromEntries(entrada.modelos.map((m) => [m, { voz: grupoDoVideo(m, "voz"), resto: grupoDoVideo(m, "resto") }]));

  const a = entrada.arquivo;
  const configuracao = configuracaoDoPacote(a.modelo, a.taxa);
  configuracao.faixas = configuracao.faixas.map((f) => ({ ...f, ...(a.ajustes[f.id] ?? {}) }));
  configuracao.master.efeitos = a.master;
  const projeto = projetoDoArquivo(configuracao, a.grupo, a.quadros);
  const fontes = new Map(Object.entries(a.geradores).map(([id, g]) => [id, fonteDaMemoria(gerarMidia(g, a.quadros, a.taxa))]));
  const { esq, dir } = renderizar(projeto, fontes, { quadros: a.quadros });

  const tratamentos = (entrada.tratamentos ?? []).map(lerTratamentoDoPacote);
  return { combinacoes, nomesDeGrupo, nomes, problemas, video, arquivo: resumoDoArquivo(projeto), render: resumo(esq, dir, a.amostras), tratamentos };
}
