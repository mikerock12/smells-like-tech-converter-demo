/**
 * O que cada caso compartilhado do estúdio confere. Os mesmos campos são recalculados pelo
 * C# e pelo Kotlin nos testes deles, a partir de `projeto` e `entradas`.
 */
import { avisos, duracaoTotal, problemas, receitaFfmpeg, segmentos, tamanhoDoGif } from "./estudio.mjs";

export function esperadoDoCaso({ projeto, entradas }) {
  const erros = problemas(projeto);
  return {
    duracao: duracaoTotal(projeto),
    segmentos: segmentos(projeto),
    problemas: erros,
    avisos: avisos(projeto),
    gif: tamanhoDoGif(projeto.largura, projeto.altura),
    receita: erros.length === 0 ? receitaFfmpeg(projeto, entradas) : null,
  };
}
