/**
 * Separar faixas no conversor (experimental): um áudio vira um .zip com as faixas separadas,
 * juntas do jeito que a pessoa escolher, cada faixa com os efeitos do estúdio musical; um
 * vídeo fica só com a voz, ou com tudo menos a voz.
 *
 * Aqui ficam só as regras, sem áudio: os modelos e as faixas de cada um, as combinações
 * prontas, a validação dos arquivos pedidos e o projeto musical que mistura cada arquivo.
 * A mistura é a do estúdio (musica-mixer.mjs), então os efeitos soam igual nos dois lugares.
 *
 * Espelhos: MusicStemPackage.cs e PacoteDeFaixas.kt, conferidos por musica-pacote-casos.json.
 */

import { novaFaixa, novoProjetoMusical } from "./musica.mjs";

export const VERSAO_DO_PACOTE = 1;

/** Quantos arquivos um pacote pode ter (um por faixa, mais algumas combinações). */
export const MAXIMO_DE_ARQUIVOS = 12;

const FAIXA = {
  vozes: "Voz",
  bateria: "Bateria",
  baixo: "Baixo",
  guitarra: "Guitarra",
  piano: "Piano",
  outros: "Outros",
};

export const MODELOS_DO_PACOTE = [
  {
    id: "spleeter",
    rotulo: "Spleeter · 4 faixas",
    resumo: "Voz, bateria, baixo e outros. O mais rápido; guitarra e piano ficam em “outros”.",
    modo: "4",
    faixas: ["vozes", "bateria", "baixo", "outros"],
  },
  {
    id: "roformer",
    rotulo: "RoFormer · 6 faixas (experimental)",
    resumo: "Voz, bateria, baixo, guitarra, piano e resíduo. Bem mais lento e pede mais memória.",
    modo: "6",
    faixas: ["vozes", "bateria", "baixo", "guitarra", "piano", "outros"],
  },
];

export function modeloDoPacote(id) {
  return MODELOS_DO_PACOTE.find((m) => m.id === id) ?? null;
}

export function rotuloDaFaixaDoPacote(id) {
  return FAIXA[id] ?? id;
}

/** O que sobra quando se tira a voz (ou qualquer lista de faixas). */
function menos(modelo, ...tirar) {
  return modelo.faixas.filter((f) => !tirar.includes(f));
}

/**
 * Combinações prontas. Cada uma é uma lista de arquivos; cada arquivo junta as faixas dele.
 * A pessoa pode partir de uma e mudar à vontade.
 */
export function combinacoesProntas(modeloId) {
  const modelo = modeloDoPacote(modeloId);
  if (!modelo) return [];
  const seis = modelo.faixas.includes("guitarra");
  const lista = [
    { id: "separadas", rotulo: "Cada faixa num arquivo", grupos: modelo.faixas.map((f) => [f]) },
    { id: "karaoke", rotulo: "Sem a voz (playback)", grupos: [menos(modelo, "vozes")] },
    { id: "voz", rotulo: "Só a voz", grupos: [["vozes"]] },
    { id: "voz-e-base", rotulo: "Voz num arquivo, o resto no outro", grupos: [["vozes"], menos(modelo, "vozes")] },
    { id: "sem-bateria", rotulo: "Sem a bateria (para tocar junto)", grupos: [menos(modelo, "bateria")] },
    { id: "sem-baixo", rotulo: "Sem o baixo (para tocar junto)", grupos: [menos(modelo, "baixo")] },
    { id: "voz-baixo-e-bateria", rotulo: "Voz e baixo juntos, bateria à parte", grupos: [["vozes", "baixo"], ["bateria"]] },
  ];
  if (seis) {
    lista.push(
      { id: "sem-guitarra", rotulo: "Sem a guitarra (para tocar junto)", grupos: [menos(modelo, "guitarra")] },
      { id: "sem-piano", rotulo: "Sem o piano (para tocar junto)", grupos: [menos(modelo, "piano")] },
      { id: "cordas-e-ritmo", rotulo: "Guitarra e baixo juntos, voz e bateria no outro", grupos: [["guitarra", "baixo"], ["vozes", "bateria"]] },
    );
  }
  return lista.map((c) => ({ ...c, grupos: c.grupos.map((faixas) => ({ nome: nomeDoGrupo(faixas, modelo), faixas })) }));
}

/** "Voz", "Voz e baixo", "Sem voz", "Tudo": o nome que o arquivo leva por padrão. */
export function nomeDoGrupo(faixas, modelo) {
  const todas = modelo?.faixas ?? [];
  const ordenadas = todas.length ? todas.filter((f) => faixas.includes(f)) : [...faixas];
  if (ordenadas.length === 0) return "Vazio";
  if (todas.length && ordenadas.length === todas.length) return "Tudo";
  const faltam = todas.filter((f) => !faixas.includes(f));
  if (todas.length > 2 && faltam.length === 1) return `Sem ${rotuloDaFaixaDoPacote(faltam[0]).toLowerCase()}`;
  const nomes = ordenadas.map((f, i) => (i === 0 ? rotuloDaFaixaDoPacote(f) : rotuloDaFaixaDoPacote(f).toLowerCase()));
  return nomes.length === 1 ? nomes[0] : `${nomes.slice(0, -1).join(", ")} e ${nomes[nomes.length - 1]}`;
}

/**
 * O projeto que guarda os ajustes do pacote: uma faixa por faixa separada (com volume, pan e
 * efeitos), sem clipes. O master dele vale para todos os arquivos. É um projeto musical comum,
 * então o editor de efeitos do estúdio funciona nele sem adaptação.
 */
export function configuracaoDoPacote(modeloId, taxa = 44100, titulo = "Faixas separadas") {
  const modelo = modeloDoPacote(modeloId);
  if (!modelo) throw new Error(`Modelo desconhecido: ${modeloId}`);
  const projeto = novoProjetoMusical("pacote", titulo, taxa);
  projeto.metronomo = { ...projeto.metronomo, ligado: false, naExportacao: false };
  projeto.faixas = modelo.faixas.map((id, i) => novaFaixa(id, rotuloDaFaixaDoPacote(id), i));
  return projeto;
}

/** Problemas dos arquivos pedidos, em texto para a tela. Lista vazia: pode separar. */
export function problemasDoPacote(modeloId, grupos) {
  const modelo = modeloDoPacote(modeloId);
  if (!modelo) return ["Escolha o modelo de separação."];
  if (!Array.isArray(grupos) || grupos.length === 0) return ["Escolha pelo menos um arquivo para gerar."];
  if (grupos.length > MAXIMO_DE_ARQUIVOS) return [`No máximo ${MAXIMO_DE_ARQUIVOS} arquivos por pacote.`];
  const problemas = [];
  const nomes = new Set();
  grupos.forEach((g, i) => {
    const rotulo = `Arquivo ${i + 1}`;
    if (!g || !Array.isArray(g.faixas) || g.faixas.length === 0) problemas.push(`${rotulo}: escolha pelo menos uma faixa.`);
    else {
      const estranhas = g.faixas.filter((f) => !modelo.faixas.includes(f));
      if (estranhas.length) problemas.push(`${rotulo}: ${estranhas.join(", ")} não existe no ${modelo.rotulo}.`);
      if (new Set(g.faixas).size !== g.faixas.length) problemas.push(`${rotulo}: a mesma faixa aparece duas vezes.`);
    }
    const nome = String(g?.nome ?? "").trim().toLowerCase();
    if (!nome) problemas.push(`${rotulo}: dê um nome ao arquivo.`);
    else if (nomes.has(nome)) problemas.push(`${rotulo}: já existe outro arquivo chamado “${g.nome}”.`);
    nomes.add(nome);
  });
  return problemas;
}

/**
 * O projeto que mistura um arquivo do pacote: as faixas do grupo, com os ajustes da
 * configuração, cada uma tocando a faixa separada inteira (mídia com o id da faixa).
 */
export function projetoDoArquivo(configuracao, grupo, quadros) {
  const faixas = configuracao.faixas
    .filter((f) => grupo.faixas.includes(f.id))
    .map((f) => ({
      ...f,
      mudo: false,
      solo: false,
      gravar: false,
      monitorar: false,
      saida: "master",
      envios: [],
      clipes: [{ id: `clipe-${f.id}`, midia: f.id, inicio: 0, inicioNaMidia: 0, duracao: quadros, ganhoDb: 0, entradaSuave: 0, saidaSuave: 0, mudo: false }],
    }));
  return {
    ...configuracao,
    faixas,
    barramentos: [],
    midias: faixas.map((f) => ({ id: f.id, nome: `${f.id}.wav`, formato: "wav", taxa: configuracao.taxa, canais: 2, quadros, bits: 32 })),
    metronomo: { ...configuracao.metronomo, ligado: false, naExportacao: false },
    loop: { ligado: false, inicio: 0, fim: 0 },
    punch: { ligado: false, entrada: 0, saida: 0 },
    marcadores: [],
    separacoes: [],
  };
}

const semAcento = (s) => String(s).normalize("NFD").replace(/[^\w\s-]/g, "").trim().replace(/\s+/g, "-").toLowerCase();

/** Nomes dos arquivos dentro do .zip: "musica-voz-e-baixo.wav", sem repetir. */
export function nomesDosArquivos(titulo, grupos, extensao) {
  const base = semAcento(titulo).slice(0, 40) || "musica";
  const usados = new Set();
  return grupos.map((g, i) => {
    const parte = semAcento(g.nome).slice(0, 40) || `arquivo-${i + 1}`;
    let nome = `${base}-${parte}.${extensao}`;
    for (let n = 2; usados.has(nome); n += 1) nome = `${base}-${parte}-${n}.${extensao}`;
    usados.add(nome);
    return nome;
  });
}

/** Vídeo: o que fica no áudio. "voz" isola a voz; "resto" tira a voz e mantém o resto. */
export const MODOS_DA_VOZ_NO_VIDEO = [
  { id: "resto", rotulo: "Tirar a voz e manter o resto (trilha, efeitos)" },
  { id: "voz", rotulo: "Manter só a voz (narração, falas)" },
];

export function grupoDoVideo(modeloId, manter) {
  const modelo = modeloDoPacote(modeloId);
  if (!modelo) throw new Error(`Modelo desconhecido: ${modeloId}`);
  if (manter === "voz") return { nome: "Voz", faixas: ["vozes"] };
  if (manter === "resto") return { nome: "Sem voz", faixas: menos(modelo, "vozes") };
  throw new Error(`Escolha o que manter no áudio: ${manter}`);
}

/**
 * O pedido inteiro, como viaja nas opções da conversão (texto JSON): modelo, arquivos,
 * ajustes por faixa e formato. Devolve o pedido normalizado ou lança com o problema.
 */
export function lerPedidoDoPacote(texto, padrao = {}) {
  let pedido;
  try {
    pedido = typeof texto === "string" ? JSON.parse(texto) : texto;
  } catch {
    throw new Error("As opções da separação estão corrompidas. Escolha os arquivos de novo.");
  }
  pedido = { ...padrao, ...(pedido ?? {}) };
  const modelo = modeloDoPacote(pedido.modelo);
  if (!modelo) throw new Error("Escolha o modelo de separação.");
  const configuracao = pedido.configuracao && Array.isArray(pedido.configuracao.faixas) ? pedido.configuracao : configuracaoDoPacote(modelo.id);
  const faltando = modelo.faixas.filter((f) => !configuracao.faixas.some((x) => x.id === f));
  if (faltando.length) throw new Error("Os ajustes das faixas não correspondem ao modelo escolhido.");
  const grupos = pedido.grupos ?? (pedido.manter ? [grupoDoVideo(modelo.id, pedido.manter)] : combinacoesProntas(modelo.id)[0].grupos);
  const problemas = problemasDoPacote(modelo.id, grupos);
  if (problemas.length) throw new Error(problemas[0]);
  return {
    versao: VERSAO_DO_PACOTE,
    modelo: modelo.id,
    grupos: grupos.map((g) => ({ nome: String(g.nome).trim(), faixas: modelo.faixas.filter((f) => g.faixas.includes(f)) })),
    configuracao,
    formato: pedido.formato ?? "wav-24",
    master: pedido.master ?? "sem-alvo",
    ...(pedido.manter ? { manter: pedido.manter } : {}),
  };
}

/** Quanto da barra cada etapa ocupa: a separação é quase tudo. */
export const ETAPAS_DO_PACOTE = { lendo: [0, 0.03], modelo: [0.03, 0.08], separando: [0.08, 0.8], mixando: [0.8, 0.96], juntando: [0.96, 1] };

export function fracaoDoPacote(etapa, fracao) {
  const f = Math.max(0, Math.min(1, fracao));
  const [de, ate] = ETAPAS_DO_PACOTE[etapa] ?? [0, 1];
  return Math.round((de + (ate - de) * f) * 1e6) / 1e6;
}

/** Resumo de um projeto de arquivo, para comparar os três núcleos sem depender do JSON exato. */
export function resumoDoArquivo(projeto) {
  return {
    taxa: projeto.taxa,
    faixas: projeto.faixas.map((f) => ({
      id: f.id,
      nome: f.nome,
      ganhoDb: f.ganhoDb,
      pan: f.pan,
      efeitos: f.efeitos.map((e) => e.tipo),
      clipes: f.clipes.map((c) => [c.midia, c.inicio, c.duracao]),
    })),
    master: projeto.master.efeitos.map((e) => e.tipo),
    midias: projeto.midias.map((m) => [m.id, m.quadros]),
  };
}
