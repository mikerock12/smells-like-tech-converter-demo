/**
 * Restaurar áudio com IA (experimental): o diagnóstico vira etapas e parâmetros do reparo.
 *
 * As medidas vêm do núcleo nativo (slt_audio_analyzer_*), igual nos três clientes; estas
 * regras decidem o que fazer com elas. São conservadoras por desenho: sem defeito
 * suficiente, nada muda; cada etapa tem um limite; o que a gravação perdeu (banda cortada,
 * detalhe destruído pela compressão) não volta com filtro. Os modelos de IA candidatos
 * de plateia/ruído/reverberação (MelBand RoFormer) e o AudioSR ainda estão em revisão. O
 * reparo de compressão por IA usa o Apollo (JusperLee/Apollo, CC BY-SA 4.0, código e pesos),
 * convertido para ONNX e baixado sob demanda, só quando a pessoa liga a opção "ia"
 * (docs/architecture/ADR-AUDIO-RESTORATION-MODELS.md).
 *
 * Espelhos: Core/Music/AudioRestoration.cs (Windows/plugin) e musica/Restaurador.kt
 * (Android), conferidos pelos casos em restaurador-casos.json.
 */

export const VERSAO_DO_RESTAURADOR = 1;

export const PERFIS_DO_RESTAURADOR = [
  { id: "auto", rotulo: "Automático", resumo: "Analisa e trata só o que encontrar, com cuidado." },
  { id: "lossy", rotulo: "MP3 / música comprimida", resumo: "Com a IA ligada, o Apollo refaz o que a compressão destruiu; sem ela, só a limpeza leve." },
  { id: "live", rotulo: "Show ao vivo / gravação de fã", resumo: "Ruído, zumbido e recorte, preservando a plateia e a ambiência." },
  { id: "archive", rotulo: "Fita, vinil ou gravação antiga", resumo: "Zumbido, estalos, chiado e um pouco de brilho." },
  { id: "manual", rotulo: "Avançado (eu escolho)", resumo: "Você liga e dosa cada etapa." },
];

export const ETAPAS_DO_RESTAURADOR = [
  { id: "compressao", rotulo: "Reparo de compressão por IA (Apollo)" },
  { id: "dc", rotulo: "Deslocamento DC" },
  { id: "zumbido", rotulo: "Zumbido da rede (50/60 Hz)" },
  { id: "estalos", rotulo: "Estalos e cliques" },
  { id: "recorte", rotulo: "Recorte (distorção por volume alto)" },
  { id: "ruido", rotulo: "Chiado e ruído de fundo" },
  { id: "grave", rotulo: "Ronco e vento (corte de grave)" },
  { id: "brilho", rotulo: "Brilho (agudos)" },
];

export const FORMATOS_DO_RESTAURADOR = ["wav-24", "flac", "wav-32f", "mp3"];

const limitar = (v, a, b) => Math.min(b, Math.max(a, v));
const arredondar = (v) => Math.round(v * 1e4) / 1e4;
const numero = (v, padrao) => (Number.isFinite(Number(v)) ? Number(v) : padrao);

/** As opções como viajam na conversão (texto JSON ou objeto), normalizadas. */
export function lerOpcoesDoRestaurador(entrada) {
  let o = entrada;
  if (typeof entrada === "string") {
    try { o = entrada ? JSON.parse(entrada) : {}; } catch { throw new Error("As opções da restauração estão corrompidas. Escolha de novo."); }
  }
  o = o ?? {};
  const perfil = PERFIS_DO_RESTAURADOR.some((p) => p.id === o.perfil) ? o.perfil : "auto";
  const etapas = {};
  for (const e of ETAPAS_DO_RESTAURADOR) etapas[e.id] = limitar(Math.round(numero(o.etapas?.[e.id], 0)), e.id === "brilho" ? -100 : 0, 100);
  return {
    versao: VERSAO_DO_RESTAURADOR,
    perfil,
    intensidade: limitar(Math.round(numero(o.intensidade, 35)), 0, 100),
    preservarAmbiencia: limitar(Math.round(numero(o.preservarAmbiencia, 80)), 0, 100),
    formato: FORMATOS_DO_RESTAURADOR.includes(o.formato) ? o.formato : "wav-24",
    etapas,
    relatorio: o.relatorio === true,
    ia: o.ia === true,
  };
}

/**
 * O que o diagnóstico sugere, com gravidade (0 a 1) e confiança (0 a 1) por problema.
 * `d`: os valores do núcleo (CAMPOS_DO_DIAGNOSTICO de nativo.mjs).
 */
export function avaliarDiagnostico(d) {
  const nyquist = d.taxa / 2;
  const recorte = d.recorte > 1e-4 ? limitar(Math.log10(d.recorte / 1e-4) / 2, 0, 1) : 0;
  const zumbido = limitar((d.zumbidoDb - 6) / 14, 0, 1);
  const plano = limitar((d.planuraDoRuido - 0.15) / 0.45, 0, 1);
  const ruido = limitar((d.pisoDeRuidoDb + 70) / 30, 0, 1) * plano;
  const estalos = limitar((d.estalosPorMinuto - 3) / 40, 0, 1);
  const dc = limitar((Math.abs(d.dc) - 0.003) / 0.027, 0, 1);
  const banda = d.bandaHz > 0 && d.bandaHz < 0.85 * nyquist && d.bandaHz < 17000 ? limitar((17000 - d.bandaHz) / 7000, 0, 1) : 0;
  const problemas = {
    recorte: { gravidade: arredondar(recorte), confianca: d.picoDb > -1 ? 0.8 : 0.6 },
    zumbido: { gravidade: arredondar(zumbido), confianca: 0.7, hz: d.zumbidoHz },
    ruido: { gravidade: arredondar(ruido), confianca: arredondar(0.4 + 0.4 * plano) },
    estalos: { gravidade: arredondar(estalos), confianca: 0.6 },
    dc: { gravidade: arredondar(dc), confianca: 0.9 },
    banda: { gravidade: arredondar(banda), confianca: 0.5, hz: Math.round(d.bandaHz) },
  };
  const tratados = ["recorte", "zumbido", "ruido", "estalos", "dc"];
  const recomendacoes = tratados.filter((id) => problemas[id].gravidade >= 0.15);
  const avisos = ["o_diagnostico_nao_garante_a_origem"];
  if (banda >= 0.15) avisos.push("banda_limitada");
  if (d.silencio > 0.9) avisos.push("quase_silencio");
  return { problemas, recomendacoes, avisos, semDefeitos: recomendacoes.length === 0 };
}

const ZERO = { dc: 0, zumbidoHz: 0, zumbido: 0, estalos: 0, recorte: 0, nivelDoRecorte: 0, ruido: 0, arDb: 0, corteGraveHz: 0, tetoDb: 0, ganhoDb: 0 };

/**
 * As etapas e os parâmetros do reparo (nomes de PARAMETROS_DO_REPARO). `semMudanca`: o
 * áudio sai igual (nada para tratar, intensidade zero ou tudo desligado no manual).
 */
export function planejarRestauracao(d, opcoesBrutas) {
  const opcoes = lerOpcoesDoRestaurador(opcoesBrutas);
  const avaliacao = avaliarDiagnostico(d);
  const p = { ...ZERO };
  const avisos = [...avaliacao.avisos];
  const s = opcoes.intensidade / 100;
  const g = (id) => avaliacao.problemas[id].gravidade;
  const nivel = Math.min(1, 10 ** (d.picoDb / 20)) * 0.995;

  if (opcoes.perfil === "manual") {
    const e = opcoes.etapas;
    if (e.dc > 0) p.dc = 1;
    if (e.zumbido > 0) { p.zumbidoHz = d.zumbidoHz === 60 ? 60 : 50; p.zumbido = e.zumbido / 100; }
    p.estalos = e.estalos / 100;
    if (e.recorte > 0) { p.recorte = e.recorte / 100; p.nivelDoRecorte = nivel; }
    p.ruido = e.ruido / 100;
    if (e.grave > 0) p.corteGraveHz = 20 + 0.4 * e.grave;
    p.arDb = (6 * e.brilho) / 100;
  } else if (s > 0) {
    const perfil = opcoes.perfil;
    const dose = (gravidade) => limitar(gravidade * (0.4 + 1.2 * s), 0, 1);
    if (g("dc") > 0 || perfil === "archive") p.dc = 1;
    if (g("zumbido") >= 0.15) { p.zumbidoHz = d.zumbidoHz === 60 ? 60 : 50; p.zumbido = limitar(0.5 + 0.5 * g("zumbido"), 0, 1) * Math.min(1, 0.5 + s); }
    if (g("estalos") >= 0.15) p.estalos = dose(g("estalos")) * (perfil === "archive" ? 1 : 0.8);
    if (g("recorte") >= 0.15) { p.recorte = limitar(dose(0.5 + 0.5 * g("recorte")), 0, 1); p.nivelDoRecorte = nivel; }
    if (g("ruido") >= 0.15) {
      let r = limitar(g("ruido") * (0.3 + s), 0, 1);
      if (perfil === "live") r *= 1 - (0.7 * opcoes.preservarAmbiencia) / 100;
      if (perfil === "archive") r = Math.min(1, r * 1.2);
      p.ruido = r;
    }
    if (perfil === "live") p.corteGraveHz = 35;
    if (perfil === "archive") { p.corteGraveHz = 30; p.arDb = 1.5 * s; }
    if (perfil === "lossy" && !opcoes.ia) avisos.push("reparo_de_compressao_por_ia_desligado");
  }
  // Reparo de compressão por IA (Apollo): só com a opção ligada. No MP3 e no Avançado, sempre;
  // nos outros perfis, só quando os agudos param cedo (sinal de compressão ou banda cortada).
  // No Avançado, a etapa "compressao" dosa a mistura.
  // O Apollo recria o som inteiro (não só os agudos): abaixo do corte fica o original, que o
  // MP3 preserva bem, e acima entra o Apollo. O corte fica 3 kHz abaixo de onde os agudos param.
  let ia = null;
  const corte = limitar(Math.round(((d.bandaHz > 0 ? d.bandaHz : 16000) - 3000) / 100) * 100, 4000, 16000);
  if (opcoes.perfil === "manual") {
    if (opcoes.etapas.compressao > 0) ia = { modelo: "apollo", mistura: arredondar(opcoes.etapas.compressao / 100), corteHz: corte };
  } else if (opcoes.ia && s > 0 && (opcoes.perfil === "lossy" || g("banda") >= 0.15)) {
    ia = { modelo: "apollo", mistura: arredondar(Math.min(1, 0.4 + s)), corteHz: corte };
  }
  for (const k of Object.keys(p)) p[k] = arredondar(p[k]);
  const ativos = [];
  if (p.dc > 0) ativos.push({ id: "dc", quanto: 1 });
  if (p.zumbido > 0) ativos.push({ id: "zumbido", quanto: p.zumbido, hz: p.zumbidoHz });
  if (p.estalos > 0) ativos.push({ id: "estalos", quanto: p.estalos });
  if (p.recorte > 0) ativos.push({ id: "recorte", quanto: p.recorte });
  if (p.ruido > 0) ativos.push({ id: "ruido", quanto: p.ruido });
  if (p.corteGraveHz > 0) ativos.push({ id: "grave", quanto: p.corteGraveHz });
  if (p.arDb !== 0) ativos.push({ id: "brilho", quanto: p.arDb });
  if (ia) ativos.unshift({ id: "compressao", quanto: ia.mistura });
  const semMudanca = ativos.length === 0;
  // Qualquer etapa pode subir um pico (o recorte refeito sobe de propósito): o teto protege.
  if (!semMudanca) p.tetoDb = -1;
  if (semMudanca) avisos.push(opcoes.perfil === "manual" || s === 0 ? "nada_ligado" : "sem_defeitos_suficientes");
  return { versao: VERSAO_DO_RESTAURADOR, perfil: opcoes.perfil, etapas: ativos, parametros: p, ia, avisos, semMudanca, avaliacao };
}

const TEXTOS_DOS_AVISOS = {
  o_diagnostico_nao_garante_a_origem: "O diagnóstico mede o arquivo; não garante como ele foi gravado ou comprimido.",
  banda_limitada: "Os agudos param cedo: o que a compressão ou a gravação cortou não volta com filtros.",
  quase_silencio: "O áudio é quase todo silêncio.",
  reparo_de_compressao_por_ia_desligado: "O reparo de compressão por IA (Apollo) está desligado: este perfil faz só a limpeza leve. Ligue a IA para refazer o que a compressão destruiu.",
  sem_defeitos_suficientes: "O diagnóstico não encontrou defeitos suficientes para recomendar uma restauração: o áudio sai como entrou.",
  nada_ligado: "Nenhuma etapa ligada: o áudio sai como entrou.",
};

export function textoDoAviso(id) {
  return TEXTOS_DOS_AVISOS[id] ?? id;
}

/** "Zumbido de 60 Hz (forte) · Chiado (leve)…" para a tela, com o que foi medido. */
export function resumoDoDiagnostico(d) {
  const a = avaliarDiagnostico(d);
  const intensidade = (g) => (g >= 0.66 ? "forte" : g >= 0.33 ? "médio" : "leve");
  const nomes = { recorte: "Recorte", zumbido: `Zumbido de ${d.zumbidoHz} Hz`, ruido: "Chiado/ruído de fundo", estalos: "Estalos", dc: "Deslocamento DC" };
  const achados = a.recomendacoes.map((id) => `${nomes[id]} (${intensidade(a.problemas[id].gravidade)})`);
  const banda = a.problemas.banda.gravidade >= 0.15 ? ` · Agudos até ~${Math.round(d.bandaHz / 100) / 10} kHz` : "";
  return `${achados.length ? achados.join(" · ") : "Sem defeitos evidentes"}${banda} · pico ${d.picoDb.toFixed(1)} dBFS · piso de ruído ${d.pisoDeRuidoDb.toFixed(0)} dB`;
}

/** Nome do arquivo restaurado: "show-restaurado.flac". */
export function nomeDoRestaurado(nome, extensao) {
  return `${String(nome).replace(/\.[^.]+$/, "") || "audio"}-restaurado.${extensao}`;
}
