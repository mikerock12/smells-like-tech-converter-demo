/**
 * Os artigos do site.
 *
 * Não são a ferramenta: são a página que responde a dúvida de quem ainda está decidindo
 * — "é seguro mandar o PDF do cliente para um conversor?" — e aponta para a ferramenta
 * certa. Cada um existe para uma busca e um clique. O texto de cada artigo está na
 * própria página (app/<slug>/page.tsx); aqui fica o que o resto do site precisa saber:
 * título, resumo e com que páginas de conversão ele conversa.
 */

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  for (const child of Object.values(value)) deepFreeze(child);
  return Object.freeze(value);
}

export const ARTIGOS = deepFreeze([
  {
    slug: "sem-upload",
    prioridade: "A",
    titulo: "Por que não enviar arquivo para conversor online",
    resumo: "O que acontece com o arquivo que você sobe, por que “apagamos em 1 hora” não resolve, e como converter sem enviar nada.",
    geral: true,
    conversoes: ["webp-para-png", "heic-para-jpg", "juntar-pdf", "mp4-para-mp3"],
  },
  {
    slug: "vs-convertio",
    prioridade: "A",
    titulo: "Convertio sobe o seu arquivo. Este não.",
    resumo: "A diferença entre um conversor na nuvem e um que roda no seu computador, na prática: privacidade, limite, fila e preço.",
    geral: false,
    conversoes: ["webp-para-png", "mp4-para-mp3", "mov-para-mp4", "png-para-jpg", "pdf-para-jpg"],
  },
  {
    slug: "lgpd-conversor",
    prioridade: "A",
    titulo: "Conversor de PDF e LGPD: o que muda quando o arquivo não sai do PC",
    resumo: "Subir o PDF de um cliente para um site é compartilhar dado pessoal com um terceiro. Converter no próprio computador não é.",
    geral: false,
    conversoes: ["juntar-pdf", "dividir-pdf", "comprimir-pdf", "pdf-para-word", "pdf-para-texto", "imagens-para-pdf", "pdf-para-jpg", "girar-pdf"],
  },
  {
    slug: "transcrever-sem-nuvem",
    prioridade: "B",
    titulo: "Como transcrever reunião sem mandar o áudio para a nuvem",
    resumo: "Transcrição com o Whisper rodando no seu Windows: texto e legenda com tempo, sem o áudio sair da máquina.",
    geral: false,
    conversoes: ["audio-para-texto", "video-para-texto", "audio-para-srt"],
  },
  {
    slug: "heic-iphone",
    prioridade: "B",
    titulo: "Foto HEIC do iPhone no Windows, sem app da Apple e sem upload",
    resumo: "Por que o Windows não abre a foto do iPhone e como converter HEIC para JPG no navegador, sem instalar nada.",
    geral: false,
    conversoes: ["heic-para-jpg", "heic-para-png"],
  },
  {
    slug: "comprimir-video-whatsapp",
    prioridade: "B",
    titulo: "Comprimir vídeo para WhatsApp sem perder a cara do arquivo",
    resumo: "Por que o WhatsApp estraga o vídeo, e como deixar ele leve antes de mandar, escolhendo você o que perde.",
    geral: false,
    conversoes: ["comprimir-video", "cortar-video"],
  },
  {
    slug: "pdf-cliente",
    prioridade: "B",
    titulo: "Advogado, contador, clínica: converter arquivo de cliente sem vazar",
    resumo: "Contrato, holerite, exame: juntar, dividir, comprimir e passar para Word sem entregar o documento a um site.",
    geral: false,
    conversoes: ["juntar-pdf", "comprimir-pdf", "pdf-para-word", "dividir-pdf", "foto-para-texto", "imagens-para-pdf"],
  },
  {
    slug: "como-funciona",
    prioridade: "C",
    titulo: "Como a conversão local funciona (WebAssembly e WebCodecs, em português)",
    resumo: "O que roda dentro do seu navegador quando você converte aqui, sem jargão e com o teste para conferir.",
    geral: true,
    conversoes: ["webp-para-png", "mp4-para-mp3", "comprimir-pdf"],
  },
  {
    slug: "vs-cloudconvert",
    prioridade: "C",
    titulo: "CloudConvert vs conversor local",
    resumo: "Quando um conversor na nuvem faz sentido, quando não faz, e o que muda para o seu arquivo em cada caso.",
    geral: false,
    conversoes: ["mkv-para-mp4", "webm-para-mp4", "wav-para-mp3", "avif-para-jpg"],
  },
  {
    slug: "vs-pdf24",
    prioridade: "C",
    titulo: "PDF24 vs conversor no navegador, sem instalar suíte",
    resumo: "O PDF24 online, o PDF24 instalado e um conversor que roda no navegador: o que cada um faz com o seu PDF.",
    geral: false,
    conversoes: ["juntar-pdf", "comprimir-pdf", "dividir-pdf", "pdf-para-jpg"],
  },
]);

export function artigo(slug) {
  return ARTIGOS.find((item) => item.slug === slug) ?? null;
}

/**
 * Até `quantos` artigos para mostrar numa página de conversão: primeiro os que falam
 * daquela conversão, depois os gerais.
 */
export function artigosDaConversao(slug, quantos = 2) {
  // O artigo que é sobre aquela conversão vem antes do geral que só a cita.
  const especificos = ARTIGOS.filter((item) => item.conversoes.includes(slug)).sort((a, b) => Number(a.geral) - Number(b.geral));
  const gerais = ARTIGOS.filter((item) => item.geral && !especificos.includes(item));
  return [...especificos, ...gerais].slice(0, quantos);
}
