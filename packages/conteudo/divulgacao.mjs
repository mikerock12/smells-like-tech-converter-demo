/**
 * Textos prontos para indicar o conversor no WhatsApp da assistência — na entrega do
 * serviço ou no orçamento, sem discurso de vendedor. A página /indicar mostra com
 * botão de copiar; marketing/whatsapp/README.md tem os mesmos textos para quem prefere
 * copiar do repositório.
 */
export const MENSAGENS_DO_WHATSAPP = Object.freeze([
  Object.freeze({
    id: "curta",
    titulo: "Versão curta",
    uso: "Para qualquer cliente, depois do serviço.",
    texto: [
      "Se precisar converter PDF, vídeo ou áudio de cliente sem mandar o arquivo pra site nenhum, usa isso:",
      "",
      "https://converter.smellsliketech.com.br",
      "",
      "Roda no computador. Sem cadastro. Se o lote for grande, tem o aplicativo vitalício.",
    ].join("\n"),
  }),
  Object.freeze({
    id: "pj",
    titulo: "Versão para cliente PJ / escritório",
    uso: "Para escritório, clínica, contabilidade.",
    texto: [
      "Ferramenta que a gente usa e indica: conversor de PDF, imagem e vídeo que não envia o arquivo pra internet.",
      "",
      "https://converter.smellsliketech.com.br",
      "",
      "Grátis no navegador. Se for uso diário, o aplicativo é pagamento único (até 3 PCs).",
    ].join("\n"),
  }),
]);
