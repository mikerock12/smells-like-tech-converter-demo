/**
 * O plano grátis no estúdio do site.
 *
 * Como nas conversões, o limite é de volume, nunca de qualidade: sem marca d'água, mesma
 * resolução, mesmos codificadores. O grátis cabe quem posta um Stories ou um Status por dia;
 * quem produz conteúdo todo dia esbarra no contador e o Pro passa a ser a escolha óbvia.
 *
 * - Exportações por dia (`estudio`): vídeo, GIF, imagem e áudio, no navegador ou no plugin.
 *   A transcrição (saída texto) usa o contador de transcrição, não este.
 * - Duração do projeto (`estudioDuracao`, em segundos).
 * - Remoção de fundo em vídeo (quadro a quadro) é do Pro; em fotos, stickers e GIFs, não.
 *
 * Os números ficam em precos.mjs (LIMITES), a única fonte da verdade dos planos.
 */

import { duracaoLegivel, duracaoTotal } from "./estudio.mjs";
import { limite } from "./precos.mjs";

/** Algum clipe ou camada de vídeo com a remoção de fundo ligada. */
export function removeFundoDeVideo(projeto) {
  const ligada = (item) => item.tipo === "video" && item.transformacao?.removerFundo === true;
  return projeto.clipes.some(ligada) || (projeto.camadas ?? []).some(ligada);
}

/**
 * O que impede a exportação no plano, ou null. `exportacoesHoje` e `transcricaoRestante`
 * vêm dos contadores do navegador (lib/plano/contadores).
 */
export function conferirEstudio(projeto, plano, { exportacoesHoje = 0, transcricaoRestante = null } = {}) {
  if (plano === "pro") return null;
  const total = duracaoTotal(projeto);

  if (projeto.saida === "texto") {
    const minutos = Math.ceil(total / 60);
    if (transcricaoRestante !== null && minutos > transcricaoRestante) {
      return {
        contador: "transcricao",
        mensagem: `Isso dá ${minutos} ${minutos === 1 ? "minuto" : "minutos"} de transcrição, e no grátis cabem ${transcricaoRestante} por dia.`,
      };
    }
    return null;
  }

  if (removeFundoDeVideo(projeto)) {
    return {
      contador: "estudio-recorte",
      recurso: "A remoção de fundo em vídeo",
      mensagem: "No grátis, a remoção de fundo vale para fotos, stickers e GIFs. Em vídeo, quadro a quadro, ela é do Pro.",
    };
  }

  const teto = limite(plano, "estudioDuracao");
  if (teto !== null && Math.round(total) > teto) {
    return {
      contador: "estudio-duracao",
      mensagem: `No grátis, o projeto do estúdio vai até ${duracaoLegivel(teto)}; este tem ${duracaoLegivel(total)}. Dá para encurtar os clipes, ou exportar inteiro no Pro.`,
    };
  }

  const porDia = limite(plano, "estudio");
  if (porDia !== null && exportacoesHoje >= porDia) {
    return {
      contador: "estudio",
      mensagem: `As ${porDia} exportações do estúdio de hoje já foram usadas. Amanhã elas voltam; o projeto fica salvo aqui.`,
    };
  }
  return null;
}
