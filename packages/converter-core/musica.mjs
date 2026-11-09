/**
 * Criação musical do estúdio: o projeto multipista (.sltmusic), o tempo em quadros de áudio,
 * solo/mudo, pan e ajuste L/R, automação, tomadas, gravação com contagem e compensação de
 * latência.
 *
 * É a referência dos três núcleos: Music*.cs (Windows/plugin) e musica/*.kt (Android)
 * repetem estas contas, conferidas pelos casos de `musica-casos/` (node scripts/musica-casos.mjs).
 * Tudo que é posição ou duração está em quadros inteiros (amostras por canal), nunca em
 * segundos, para não acumular deriva entre as faixas.
 */

export const VERSAO_DA_MUSICA = 1;
export const TAXA_PADRAO = 48000;
export const TAXAS = [44100, 48000, 96000];
/** Bloco de processamento do mixer, em quadros. */
export const BLOCO = 128;
/** Abaixo disto o fader é silêncio. */
export const GANHO_MINIMO_DB = -60;
export const GANHO_MAXIMO_DB = 12;
export const AJUSTE_MINIMO_DB = -24;
export const AJUSTE_MAXIMO_DB = 12;
export const BPM_MINIMO = 30;
export const BPM_MAXIMO = 300;
export const CORES_DAS_FAIXAS = ["#FF7A18", "#1E88E5", "#43A047", "#E53935", "#8E24AA", "#FFD54A", "#00ACC1", "#F06292"];
export const PROFUNDIDADES = [16, 24, 32];

const ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const COR = /^#[0-9A-Fa-f]{6}$/;
const inteiro = (valor) => Number.isInteger(valor);
const finito = (valor) => typeof valor === "number" && Number.isFinite(valor);
const limitar = (valor, minimo, maximo) => Math.min(maximo, Math.max(minimo, valor));

/** dB → ganho linear. O mínimo do fader (−60 dB ou menos) é silêncio. */
export function dbParaLinear(db) {
  if (!finito(db) || db <= GANHO_MINIMO_DB) return 0;
  return Math.pow(10, db / 20);
}

export function linearParaDb(ganho) {
  return ganho > 0 ? 20 * Math.log10(ganho) : -Infinity;
}

/** Quadros por batida (semínima, ou a figura do denominador do compasso). */
export function quadrosPorBatida(projeto) {
  const [, denominador] = projeto.compasso;
  return (projeto.taxa * 60) / projeto.bpm * (4 / denominador);
}

/** Onde cai a batida `k` (a partir do início), sem somar arredondamentos. */
export function quadroDaBatida(projeto, k) {
  return Math.round(k * quadrosPorBatida(projeto));
}

export function quadrosPorCompasso(projeto) {
  return quadrosPorBatida(projeto) * projeto.compasso[0];
}

/** "compasso.batida.centésimos", começando em 1.1.00. */
export function posicaoMusical(projeto, quadro) {
  const porBatida = quadrosPorBatida(projeto);
  const batidas = Math.max(0, quadro) / porBatida;
  const inteiras = Math.floor(batidas + 1e-9);
  const compasso = Math.floor(inteiras / projeto.compasso[0]) + 1;
  const batida = (inteiras % projeto.compasso[0]) + 1;
  const centesimos = Math.min(99, Math.floor((batidas - inteiras) * 100 + 1e-9));
  return { compasso, batida, centesimos, texto: `${compasso}.${batida}.${String(Math.max(0, centesimos)).padStart(2, "0")}` };
}

/** "m:ss.mmm" a partir de quadros. */
export function tempoDoQuadro(projeto, quadro) {
  const ms = Math.round((Math.max(0, quadro) / projeto.taxa) * 1000);
  const minutos = Math.floor(ms / 60000);
  const segundos = Math.floor((ms % 60000) / 1000);
  return `${minutos}:${String(segundos).padStart(2, "0")}.${String(ms % 1000).padStart(3, "0")}`;
}

/** Cliques do metrônomo em [inicio, fim): a primeira batida do compasso é forte. */
export function cliquesDoMetronomo(projeto, inicio, fim) {
  const porBatida = quadrosPorBatida(projeto);
  const cliques = [];
  let k = Math.max(0, Math.ceil(inicio / porBatida - 1e-9));
  for (;;) {
    const quadro = quadroDaBatida(projeto, k);
    if (quadro >= fim) break;
    if (quadro >= inicio) cliques.push({ quadro, forte: k % projeto.compasso[0] === 0 });
    k += 1;
  }
  return cliques;
}

/* ------------------------------------------------------------------ projeto */

export function novaFaixa(id, nome, indice = 0) {
  return {
    id,
    nome,
    cor: CORES_DAS_FAIXAS[indice % CORES_DAS_FAIXAS.length],
    ganhoDb: 0,
    pan: 0,
    ajusteEsquerdoDb: 0,
    ajusteDireitoDb: 0,
    gravar: false,
    monitorar: false,
    mudo: false,
    solo: false,
    entrada: null,
    canaisDeEntrada: 1,
    saida: "master",
    efeitos: [],
    envios: [],
    automacao: { volume: [], pan: [] },
    clipes: [],
  };
}

export function novoBarramento(id, nome) {
  return { id, nome, ganhoDb: 0, pan: 0, mudo: false, efeitos: [] };
}

export function novoProjetoMusical(id, titulo = "Nova música", taxa = TAXA_PADRAO) {
  return {
    versao: VERSAO_DA_MUSICA,
    id,
    titulo,
    taxa,
    profundidade: 24,
    bpm: 120,
    compasso: [4, 4],
    faixas: [],
    barramentos: [],
    master: { ganhoDb: 0, efeitos: [] },
    marcadores: [],
    midias: [],
    metronomo: { ligado: true, volumeDb: -6, naExportacao: false },
    contagem: 1,
    latencia: { entrada: 0, saida: 0, medida: false },
    loop: { ligado: false, inicio: 0, fim: 0 },
    punch: { ligado: false, entrada: 0, saida: 0 },
    separacoes: [],
  };
}

/** Modelos prontos: só faixas e barramentos com nomes, sem mídia. */
export const MODELOS_DA_MUSICA = [
  { id: "vazio", rotulo: "Em branco", faixas: ["Faixa 1"], barramentos: [] },
  { id: "violao-e-voz", rotulo: "Violão e voz", faixas: ["Violão", "Voz"], barramentos: ["Reverb"] },
  { id: "duo-vocal", rotulo: "Duo vocal", faixas: ["Base", "Voz 1", "Voz 2"], barramentos: ["Vozes", "Reverb"] },
  { id: "banda", rotulo: "Banda", faixas: ["Bateria", "Baixo", "Guitarra", "Teclado", "Voz"], barramentos: ["Guitarras", "Reverb", "Delay"] },
  { id: "podcast", rotulo: "Podcast", faixas: ["Apresentação", "Convidado", "Trilha"], barramentos: [] },
];

const idDoNome = (prefixo, nome, usados) => {
  const base = `${prefixo}-${nome.normalize("NFD").replace(/[^\w\s-]/g, "").trim().toLowerCase().replace(/[\s_]+/g, "-")}`.slice(0, 40) || prefixo;
  let id = base;
  for (let n = 2; usados.has(id); n += 1) id = `${base}-${n}`;
  usados.add(id);
  return id;
};

export function projetoDoModelo(modeloId, id, titulo, taxa = TAXA_PADRAO) {
  const modelo = MODELOS_DA_MUSICA.find((m) => m.id === modeloId) ?? MODELOS_DA_MUSICA[0];
  const projeto = novoProjetoMusical(id, titulo, taxa);
  const usados = new Set();
  projeto.barramentos = modelo.barramentos.map((nome) => {
    const bus = novoBarramento(idDoNome("bus", nome, usados), nome);
    if (nome === "Reverb") bus.efeitos = [{ id: "fx-reverb", tipo: "reverberacao", ligado: true, tamanho: 0.7, amortecimento: 0.4, largura: 1, mistura: 1 }];
    if (nome === "Delay") bus.efeitos = [{ id: "fx-delay", tipo: "atraso", ligado: true, tempoMs: 375, retorno: 0.35, mistura: 1 }];
    return bus;
  });
  projeto.faixas = modelo.faixas.map((nome, i) => {
    const faixa = novaFaixa(idDoNome("faixa", nome, usados), nome, i);
    const reverb = projeto.barramentos.find((b) => b.nome === "Reverb");
    if (reverb && /voz|viol/i.test(nome)) faixa.envios = [{ barramento: reverb.id, nivelDb: -12, preFader: false }];
    const vozes = projeto.barramentos.find((b) => b.nome === "Vozes");
    if (vozes && /voz/i.test(nome)) faixa.saida = vozes.id;
    const guitarras = projeto.barramentos.find((b) => b.nome === "Guitarras");
    if (guitarras && /guitarra/i.test(nome)) faixa.saida = guitarras.id;
    return faixa;
  });
  return projeto;
}

/* ------------------------------------------------------------------ mixer */

/**
 * Quem toca: com algum solo, só as faixas em solo; o mudo sempre vence. A mídia de uma faixa
 * muda continua no projeto, só não entra na mistura nem na exportação.
 */
export function faixasAudiveis(projeto) {
  const algumSolo = projeto.faixas.some((faixa) => faixa.solo);
  return projeto.faixas.filter((faixa) => !faixa.mudo && (!algumSolo || faixa.solo)).map((faixa) => faixa.id);
}

/**
 * Ganhos esquerdo/direito de uma faixa: fader em dB, pan de −100 (esquerda) a +100 (direita)
 * com lei de potência constante sem reforço (no centro os dois lados ficam em 0 dB, e o
 * lado oposto cai pela curva cos/sen), e o ajuste L/R, que muda cada canal sozinho.
 * Pan e ajuste L/R são coisas diferentes: o pan move a faixa no estéreo, o ajuste corrige
 * o nível de um canal.
 */
export function ganhosEstereo(ganhoDb, pan, ajusteEsquerdoDb = 0, ajusteDireitoDb = 0) {
  const ganho = dbParaLinear(ganhoDb);
  const angulo = ((limitar(pan, -100, 100) + 100) / 200) * (Math.PI / 2);
  const esquerdo = Math.min(1, Math.SQRT2 * Math.cos(angulo));
  const direito = Math.min(1, Math.SQRT2 * Math.sin(angulo));
  return [
    ganho * esquerdo * (ajusteEsquerdoDb === 0 ? 1 : Math.pow(10, ajusteEsquerdoDb / 20)),
    ganho * direito * (ajusteDireitoDb === 0 ? 1 : Math.pow(10, ajusteDireitoDb / 20)),
  ];
}

/** Valor de uma automação no quadro: linear entre os pontos, constante fora deles. */
export function valorDaAutomacao(pontos, quadro, padrao) {
  if (!pontos || pontos.length === 0) return padrao;
  if (quadro <= pontos[0].quadro) return pontos[0].valor;
  const ultimo = pontos[pontos.length - 1];
  if (quadro >= ultimo.quadro) return ultimo.valor;
  for (let i = 1; i < pontos.length; i += 1) {
    const b = pontos[i];
    if (quadro <= b.quadro) {
      const a = pontos[i - 1];
      if (b.quadro === a.quadro) return b.valor;
      return a.valor + ((b.valor - a.valor) * (quadro - a.quadro)) / (b.quadro - a.quadro);
    }
  }
  return ultimo.valor;
}

/** Fim do último clipe, em quadros. */
export function duracaoEmQuadros(projeto) {
  let fim = 0;
  for (const faixa of projeto.faixas) for (const clipe of faixa.clipes) fim = Math.max(fim, clipe.inicio + clipe.duracao);
  return fim;
}

/** Fator do clipe num ponto: ganho do clipe vezes as rampas de entrada e saída (lineares). */
export function envelopeDoClipe(clipe, deslocamento) {
  let fator = clipe.ganhoDb === 0 ? 1 : dbParaLinear(clipe.ganhoDb);
  if (clipe.entradaSuave > 0 && deslocamento < clipe.entradaSuave) fator *= deslocamento / clipe.entradaSuave;
  const restante = clipe.duracao - deslocamento;
  if (clipe.saidaSuave > 0 && restante < clipe.saidaSuave) fator *= restante / clipe.saidaSuave;
  return fator;
}

/* ------------------------------------------------------------------ gravação */

/**
 * O que acontece ao apertar REC no `cursor`: a contagem (compassos inteiros de clique antes do
 * ponto), de onde o transporte começa a tocar e de onde até onde a tomada vale. Com punch
 * ligado, só o trecho entre entrada e saída fica na faixa.
 */
export function planoDaGravacao(projeto, cursor) {
  const contagem = Math.max(0, Math.min(4, Math.trunc(projeto.contagem ?? 0)));
  const preRolagem = Math.round(contagem * quadrosPorCompasso(projeto));
  const punch = projeto.punch?.ligado && projeto.punch.saida > projeto.punch.entrada ? projeto.punch : null;
  const inicioDaTomada = punch ? punch.entrada : cursor;
  const inicioDoTransporte = Math.max(0, (punch ? Math.min(cursor, punch.entrada) : cursor) - preRolagem);
  const cliquesDaContagem = [];
  if (contagem > 0) {
    const porBatida = quadrosPorBatida(projeto);
    const batidas = contagem * projeto.compasso[0];
    for (let k = 0; k < batidas; k += 1) {
      const quadro = inicioDaTomada - Math.round((batidas - k) * porBatida);
      if (quadro >= inicioDoTransporte) cliquesDaContagem.push({ quadro, forte: k % projeto.compasso[0] === 0 });
    }
  }
  return { inicioDoTransporte, inicioDaTomada, fimDaTomada: punch ? punch.saida : null, cliquesDaContagem, preRolagem };
}

/**
 * Onde a tomada entra na linha do tempo. `quadroAlinhado` é a posição do transporte que saía
 * para o dispositivo quando chegou o primeiro quadro capturado. Quem toca ouve o que saiu com
 * o atraso de saída e o microfone entrega com o atraso de entrada: os dois somam.
 * `ajuste` é a correção manual (ou da calibração) em quadros.
 */
export function inicioCompensado(quadroAlinhado, latencia) {
  return quadroAlinhado - Math.round(latencia.entrada ?? 0) - Math.round(latencia.saida ?? 0) + Math.round(latencia.ajuste ?? 0);
}

/**
 * Transforma a captura em clipe: compensa a latência e corta o que ficou fora do punch ou
 * antes do começo do projeto. `quadrosCapturados` é o tamanho do arquivo gravado.
 */
export function clipeDaTomada(projeto, { id, midia, quadroAlinhado, quadrosCapturados }, plano) {
  const posicao = inicioCompensado(quadroAlinhado, projeto.latencia ?? {});
  let inicioNaMidia = 0;
  let inicio = posicao;
  const corteInicial = Math.max(plano.inicioDaTomada, 0);
  if (inicio < corteInicial) {
    inicioNaMidia = corteInicial - inicio;
    inicio = corteInicial;
  }
  let fim = posicao + quadrosCapturados;
  if (plano.fimDaTomada !== null) fim = Math.min(fim, plano.fimDaTomada);
  const duracao = fim - inicio;
  if (duracao <= 0) return null;
  return { id, midia, inicio, inicioNaMidia, duracao, ganhoDb: 0, entradaSuave: 0, saidaSuave: 0, mudo: false };
}

/**
 * Nova tomada na faixa: o clipe novo entra e os que ele cobre ficam mudos (nunca apagados),
 * para escolher depois qual vale.
 */
export function adicionarTomada(faixa, clipe) {
  const fim = clipe.inicio + clipe.duracao;
  const clipes = faixa.clipes.map((c) => (!c.mudo && c.inicio < fim && c.inicio + c.duracao > clipe.inicio ? { ...c, mudo: true } : c));
  return { ...faixa, clipes: [...clipes, clipe] };
}

/**
 * Calibração por pulso: o app toca cliques nos quadros `pulsos` e grava a volta (cabo de
 * loopback ou microfone perto do alto-falante). Devolve a latência de ida e volta mediana em
 * quadros, ou null se não achou os pulsos.
 */
export function medirLatencia(gravado, pulsos, taxa) {
  let pico = 0;
  for (let i = 0; i < gravado.length; i += 1) pico = Math.max(pico, Math.abs(gravado[i]));
  if (pico < 1e-3) return null;
  const limiar = pico * 0.5;
  const janela = Math.round(taxa * 0.25);
  const atrasos = [];
  for (const pulso of pulsos) {
    for (let i = Math.max(0, pulso); i < Math.min(gravado.length, pulso + janela); i += 1) {
      if (Math.abs(gravado[i]) >= limiar) {
        atrasos.push(i - pulso);
        break;
      }
    }
  }
  if (atrasos.length < Math.ceil(pulsos.length / 2)) return null;
  atrasos.sort((a, b) => a - b);
  return atrasos[Math.floor(atrasos.length / 2)];
}

/* ------------------------------------------------------------------ ajuste fino */

/** Passos dos botões − e + dos controles da mistura. */
export const PASSOS_DA_MUSICA = { ganhoDb: 0.5, pan: 1, ajusteDb: 0.5, envioDb: 0.5, metronomoDb: 1, suaveSegundos: 0.01 };

/**
 * Passo do − e + de um parâmetro de efeito: 0,5 dB; 2% da frequência (relativo, para ser fino
 * nos graves e nos agudos); 0,5, 1 ou 10 ms conforme a faixa; nos demais, um centésimo da faixa
 * arredondado para 1, 2 ou 5 vezes uma potência de 10.
 */
export function passoDoParametro(parametro) {
  const faixa = parametro.max - parametro.min;
  if (parametro.unidade === "dB") return { passo: 0.5, relativo: false };
  if (parametro.unidade === "Hz") return { passo: 0.02, relativo: true };
  if (parametro.unidade === "ms") return { passo: faixa <= 100 ? 0.5 : faixa <= 1000 ? 1 : 10, relativo: false };
  const bruto = faixa / 100;
  const potencia = Math.pow(10, Math.floor(Math.log10(bruto)));
  const m = bruto / potencia;
  return { passo: (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * potencia, relativo: false };
}

/**
 * Um toque no − (direção −1) ou no + (1): anda um passo e encaixa na grade do passo (ou, se
 * relativo, multiplica por 1 + passo e arredonda para inteiro, andando pelo menos 1), sempre
 * dentro de [de, ate].
 */
export function ajusteFino(valor, direcao, passo, de, ate, relativo = false) {
  let novo;
  if (relativo) {
    novo = Math.round(valor * Math.pow(1 + passo, direcao));
    if (novo === Math.round(valor)) novo = Math.round(valor) + direcao;
  } else {
    novo = Math.round((valor + direcao * passo) / passo) * passo;
  }
  novo = Number(novo.toFixed(6)) + 0;
  return Math.min(ate, Math.max(de, novo));
}

/** Nome da faixa criada por uma separação: o que ela é primeiro (voz_, bateria_...). */
export function nomeDaFaixaSeparada(rotulo, nomeDaFaixa) {
  return `${rotulo.toLowerCase()}_${nomeDaFaixa}`;
}

/* ------------------------------------------------------------------ validação */

function problemasDosEfeitos(efeitos, onde, erros, validar) {
  if (!Array.isArray(efeitos)) return erros.push(`${onde}: lista de efeitos inválida.`);
  const ids = new Set();
  for (const efeito of efeitos) {
    if (!efeito || !ID.test(efeito.id ?? "") || ids.has(efeito.id)) erros.push(`${onde}: efeito sem id válido.`);
    else ids.add(efeito.id);
    const problema = validar(efeito);
    if (problema) erros.push(`${onde}: ${problema}`);
  }
}

/**
 * Os problemas que impedem abrir ou exportar o projeto, em português, na ordem em que
 * aparecem. `validarEfeito` vem de musica-dsp.mjs (um efeito por vez).
 */
export function problemasDaMusica(projeto, validarEfeito = () => null) {
  const erros = [];
  if (!projeto || typeof projeto !== "object") return ["Projeto inválido."];
  if (projeto.versao !== VERSAO_DA_MUSICA) erros.push(`Versão ${projeto.versao} do projeto musical não suportada.`);
  if (!ID.test(projeto.id ?? "")) erros.push("O projeto precisa de um id.");
  if (!TAXAS.includes(projeto.taxa)) erros.push("Taxa de amostragem não suportada (44,1, 48 ou 96 kHz).");
  if (!PROFUNDIDADES.includes(projeto.profundidade)) erros.push("Profundidade de saída inválida (16, 24 ou 32 bits).");
  if (!finito(projeto.bpm) || projeto.bpm < BPM_MINIMO || projeto.bpm > BPM_MAXIMO) erros.push(`O andamento vai de ${BPM_MINIMO} a ${BPM_MAXIMO} BPM.`);
  if (!Array.isArray(projeto.compasso) || projeto.compasso.length !== 2 || !inteiro(projeto.compasso[0]) || projeto.compasso[0] < 1 || projeto.compasso[0] > 16 || ![2, 4, 8].includes(projeto.compasso[1])) erros.push("Fórmula de compasso inválida.");
  const midias = new Map();
  for (const midia of projeto.midias ?? []) {
    if (!ID.test(midia.id ?? "") || midias.has(midia.id)) erros.push("Mídia sem id válido.");
    else midias.set(midia.id, midia);
    if (!inteiro(midia.quadros) || midia.quadros < 0) erros.push(`Mídia ${midia.id}: duração inválida.`);
    if (midia.canais !== 1 && midia.canais !== 2) erros.push(`Mídia ${midia.id}: só mono ou estéreo.`);
    if (midia.taxa !== projeto.taxa) erros.push(`Mídia ${midia.id}: precisa estar na taxa do projeto.`);
  }
  const barramentos = new Set();
  for (const bus of projeto.barramentos ?? []) {
    if (!ID.test(bus.id ?? "") || barramentos.has(bus.id) || bus.id === "master") erros.push("Barramento sem id válido.");
    else barramentos.add(bus.id);
    if (!finito(bus.ganhoDb) || bus.ganhoDb > GANHO_MAXIMO_DB) erros.push(`Barramento ${bus.nome}: ganho inválido.`);
    if (!finito(bus.pan) || Math.abs(bus.pan) > 100) erros.push(`Barramento ${bus.nome}: pan vai de −100 a +100.`);
    problemasDosEfeitos(bus.efeitos, `Barramento ${bus.nome}`, erros, validarEfeito);
  }
  const faixas = new Set();
  for (const faixa of projeto.faixas ?? []) {
    const nome = faixa.nome ?? faixa.id;
    if (!ID.test(faixa.id ?? "") || faixas.has(faixa.id)) erros.push("Faixa sem id válido.");
    else faixas.add(faixa.id);
    if (faixa.cor !== undefined && !COR.test(faixa.cor)) erros.push(`Faixa ${nome}: cor inválida.`);
    if (!finito(faixa.ganhoDb) || faixa.ganhoDb > GANHO_MAXIMO_DB) erros.push(`Faixa ${nome}: o fader vai até +${GANHO_MAXIMO_DB} dB.`);
    if (!finito(faixa.pan) || Math.abs(faixa.pan) > 100) erros.push(`Faixa ${nome}: pan vai de −100 a +100.`);
    for (const lado of ["ajusteEsquerdoDb", "ajusteDireitoDb"])
      if (!finito(faixa[lado]) || faixa[lado] < AJUSTE_MINIMO_DB || faixa[lado] > AJUSTE_MAXIMO_DB) erros.push(`Faixa ${nome}: ajuste L/R vai de ${AJUSTE_MINIMO_DB} a +${AJUSTE_MAXIMO_DB} dB.`);
    if (faixa.saida !== "master" && !barramentos.has(faixa.saida)) erros.push(`Faixa ${nome}: saída para um barramento que não existe.`);
    for (const envio of faixa.envios ?? []) {
      if (!barramentos.has(envio.barramento)) erros.push(`Faixa ${nome}: envio para um barramento que não existe.`);
      if (!finito(envio.nivelDb) || envio.nivelDb > GANHO_MAXIMO_DB) erros.push(`Faixa ${nome}: nível do envio inválido.`);
    }
    problemasDosEfeitos(faixa.efeitos, `Faixa ${nome}`, erros, validarEfeito);
    for (const tipo of ["volume", "pan"]) {
      const pontos = faixa.automacao?.[tipo] ?? [];
      let anterior = -1;
      for (const ponto of pontos) {
        if (!inteiro(ponto.quadro) || ponto.quadro < anterior || !finito(ponto.valor)) {
          erros.push(`Faixa ${nome}: automação de ${tipo} fora de ordem.`);
          break;
        }
        anterior = ponto.quadro;
      }
    }
    const clipes = new Set();
    for (const clipe of faixa.clipes ?? []) {
      if (!ID.test(clipe.id ?? "") || clipes.has(clipe.id)) erros.push(`Faixa ${nome}: clipe sem id válido.`);
      else clipes.add(clipe.id);
      const midia = midias.get(clipe.midia);
      if (!midia) {
        erros.push(`Faixa ${nome}: o clipe ${clipe.id} usa uma mídia que não está no projeto.`);
        continue;
      }
      if (![clipe.inicio, clipe.inicioNaMidia, clipe.duracao, clipe.entradaSuave, clipe.saidaSuave].every((v) => inteiro(v) && v >= 0) || clipe.duracao === 0)
        erros.push(`Faixa ${nome}: o clipe ${clipe.id} tem posição ou duração inválida.`);
      else if (clipe.inicioNaMidia + clipe.duracao > midia.quadros) erros.push(`Faixa ${nome}: o clipe ${clipe.id} passa do fim da mídia.`);
      else if (clipe.entradaSuave + clipe.saidaSuave > clipe.duracao) erros.push(`Faixa ${nome}: as rampas do clipe ${clipe.id} são maiores que ele.`);
      if (!finito(clipe.ganhoDb) || clipe.ganhoDb > 24) erros.push(`Faixa ${nome}: ganho do clipe ${clipe.id} inválido.`);
    }
  }
  if (!finito(projeto.master?.ganhoDb) || projeto.master.ganhoDb > GANHO_MAXIMO_DB) erros.push("Ganho do master inválido.");
  problemasDosEfeitos(projeto.master?.efeitos ?? [], "Master", erros, validarEfeito);
  if (projeto.loop?.ligado && !(inteiro(projeto.loop.inicio) && inteiro(projeto.loop.fim) && projeto.loop.fim > projeto.loop.inicio && projeto.loop.inicio >= 0)) erros.push("O loop precisa terminar depois de começar.");
  if (projeto.punch?.ligado && !(inteiro(projeto.punch.entrada) && inteiro(projeto.punch.saida) && projeto.punch.saida > projeto.punch.entrada && projeto.punch.entrada >= 0)) erros.push("O punch precisa sair depois de entrar.");
  return erros;
}
