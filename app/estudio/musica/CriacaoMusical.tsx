"use client";

import { useCallback, useEffect, useRef, useState, type ChangeEvent } from "react";

import {
  BPM_MAXIMO,
  BPM_MINIMO,
  MODELOS_DA_MUSICA,
  adicionarTomada,
  clipeDaTomada,
  duracaoEmQuadros,
  medirLatencia,
  novaFaixa,
  planoDaGravacao,
  posicaoMusical,
  projetoDoModelo,
  quadrosPorCompasso,
  tempoDoQuadro,
  type Clipe,
  type Faixa,
  type ProjetoMusical,
} from "@/packages/converter-core/musica.mjs";
import { fonteDaMemoria, type FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { chaveDaFonteDoClipe, temTransformacao, transformarCanais } from "@/packages/converter-core/musica-transformacao.mjs";
import { formatarReais, produto } from "@/packages/converter-core/precos.mjs";
import {
  EscritorDeMidia,
  apagarProjeto,
  exportarArquivo,
  importarArquivo,
  lerMidia,
  lerProjeto,
  recuperarGravacoes,
  listarProjetos,
  midiasSoltas,
  pedirArmazenamentoPersistente,
  salvarMidia,
  salvarProjeto,
  temArmazenamento,
  type ResumoDoProjeto,
} from "@/lib/musica/armazenamento";
import { podeCriar, registrarCriacao } from "@/lib/musica/cota";
import { MotorDaMusica, canaisCompartilhados, type EstadoDoMotor, type ResultadoDaEntrada } from "@/lib/musica/motor";
import { calcularPicos } from "@/lib/musica/picos";
import { guardarSalvamentoAutomatico, salvarAutomaticamente } from "@/lib/musica/preferencias";
import { usePlano } from "@/lib/plano/usePlano";
import BotaoDeCompra from "../../precos/BotaoDeCompra";
import LinhaDoTempoMusical from "./LinhaDoTempoMusical";
import { MixerMusical, EfeitosMusicais } from "./MixerMusical";
import { PainelDeExportacao, PainelDeGravacao, PainelDeSeparacao, reduzirRuidoDoClipe } from "./PaineisMusicais";
import TempoETom from "./TempoETom";

type Painel = "mixer" | "tempo" | "efeitos" | "gravacao" | "separar" | "exportar";
export type Mudar = (mudanca: (p: ProjetoMusical) => ProjetoMusical, juntar?: string) => void;
export type Selecao = { faixa: string | null; clipe: string | null; efeitos: string };

const TAXA = 48000;
let contador = 0;
export const novoId = (prefixo: string) => `${prefixo}-${Date.now().toString(36)}${(contador++).toString(36)}${Math.random().toString(36).slice(2, 5)}`;

export default function CriacaoMusical() {
  const { plano, pronto } = usePlano();
  const [lista, setLista] = useState<ResumoDoProjeto[] | null>(null);
  const [projeto, setProjeto] = useState<ProjetoMusical | null>(null);
  const [picos, setPicos] = useState<Map<string, Float32Array>>(() => new Map());
  const [estado, setEstado] = useState<EstadoDoMotor>({ quadro: 0, tocando: false, medidores: {} });
  const [gravando, setGravando] = useState<string | null>(null);
  const [entrada, setEntrada] = useState<ResultadoDaEntrada | null>(null);
  const [painel, setPainel] = useState<Painel>("mixer");
  const [selecao, setSelecao] = useState<Selecao>({ faixa: null, clipe: null, efeitos: "master" });
  const [zoom, setZoom] = useState(60);
  const [aviso, setAviso] = useState<string | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [bloqueio, setBloqueio] = useState<string | null>(null);
  const [salvo, setSalvo] = useState<"salvo" | "salvando" | "erro" | "pendente">("salvo");
  const [automatico, setAutomatico] = useState(salvarAutomaticamente);
  const [saidaPendente, setSaidaPendente] = useState(false);
  const [soltas, setSoltas] = useState<{ id: string; quadros: number; canais: number }[]>([]);
  const [carregando, setCarregando] = useState<string | null>(null);
  const [passos, setPassos] = useState({ desfazer: 0, refazer: 0 });
  const [segundosGravados, setSegundosGravados] = useState(0);
  const [saidaEstimada, setSaidaEstimada] = useState(0);
  // Só no navegador: no servidor não há armazenamento, e a página tem de nascer igual nos dois.
  const [armazenamento, setArmazenamento] = useState(true);

  const motor = useRef<MotorDaMusica | null>(null);
  const midias = useRef(new Map<string, Float32Array[]>());
  const transformadas = useRef(new Map<string, Float32Array[]>());
  const atual = useRef<ProjetoMusical | null>(null);
  const historico = useRef<{ antes: ProjetoMusical[]; depois: ProjetoMusical[]; ultima: number; chave: string | null }>({ antes: [], depois: [], ultima: 0, chave: null });
  const temporizador = useRef<ReturnType<typeof setTimeout> | null>(null);
  const temporizadorDaTransformacao = useRef<ReturnType<typeof setTimeout> | null>(null);
  const preparacao = useRef(0);
  const capturados = useRef(0);

  const recarregarLista = useCallback(async () => {
    setLista(temArmazenamento() ? await listarProjetos().catch(() => []) : []);
  }, []);

  useEffect(() => {
    let vivo = true;
    queueMicrotask(() => setArmazenamento(temArmazenamento()));
    (temArmazenamento() ? listarProjetos().catch(() => []) : Promise.resolve([])).then((l) => {
      if (vivo) setLista(l);
    });
    return () => {
      vivo = false;
    };
  }, []);

  // Gravação ou edição ainda não salva: o navegador mostra sua confirmação ao fechar/recarregar.
  useEffect(() => {
    if (!gravando && (automatico || salvo !== "pendente")) return;
    const aviso = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", aviso);
    return () => window.removeEventListener("beforeunload", aviso);
  }, [automatico, gravando, salvo]);

  const salvarDepois = useCallback((p: ProjetoMusical) => {
    if (temporizador.current) clearTimeout(temporizador.current);
    if (!automatico) { setSalvo("pendente"); return; }
    setSalvo("salvando");
    temporizador.current = setTimeout(() => {
      salvarProjeto(p).then(() => setSalvo("salvo"), () => setSalvo("erro"));
    }, 700);
  }, [automatico]);

  const prepararTransformacoes = useCallback((p: ProjetoMusical, espera = 250) => {
    const clipes = p.faixas.flatMap((f) => f.clipes).filter((c) => temTransformacao(c)).filter((c, i, todos) => todos.findIndex((x) => chaveDaFonteDoClipe(x) === chaveDaFonteDoClipe(c)) === i);
    const numero = ++preparacao.current;
    if (temporizadorDaTransformacao.current) clearTimeout(temporizadorDaTransformacao.current);
    if (!clipes.length) return Promise.resolve();
    setCarregando("Preparando tempo e tom");
    return new Promise<void>((resolve) => {
      temporizadorDaTransformacao.current = setTimeout(() => {
        try {
          for (const clipe of clipes) {
            const chave = chaveDaFonteDoClipe(clipe);
            const original = midias.current.get(clipe.midia);
            if (!original) continue;
            const canais = transformadas.current.get(chave) ?? transformarCanais(original, p.taxa, { velocidade: clipe.velocidade, semitons: clipe.semitons, preservarTom: clipe.preservarTom });
            transformadas.current.set(chave, canais);
            motor.current?.definirMidia(chave, canaisCompartilhados(canais));
          }
        } catch (e) {
          setErro(e instanceof Error ? e.message : String(e));
        } finally {
          if (numero === preparacao.current) setCarregando(null);
          resolve();
        }
      }, espera);
    });
  }, []);

  const aplicar = useCallback((p: ProjetoMusical) => {
    atual.current = p;
    setProjeto(p);
    motor.current?.definirProjeto(p);
    salvarDepois(p);
    void prepararTransformacoes(p);
  }, [prepararTransformacoes, salvarDepois]);

  /** Toda edição passa por aqui: guarda o passo, toca e aplica a preferência de salvamento. */
  const mudar: Mudar = useCallback((mudanca, juntar) => {
    const p = atual.current;
    if (!p) return;
    const novo = mudanca(p);
    if (novo === p) return;
    const h = historico.current;
    const agora = Date.now();
    if (!(juntar && h.chave === juntar && agora - h.ultima < 800)) {
      h.antes.push(p);
      if (h.antes.length > 200) h.antes.shift();
    }
    h.depois = [];
    h.ultima = agora;
    h.chave = juntar ?? null;
    setPassos({ desfazer: h.antes.length, refazer: 0 });
    aplicar(novo);
  }, [aplicar]);

  const desfazer = useCallback(() => {
    const h = historico.current;
    const p = atual.current;
    const anterior = h.antes.pop();
    if (!anterior || !p) return;
    h.depois.push(p);
    h.chave = null;
    setPassos({ desfazer: h.antes.length, refazer: h.depois.length });
    aplicar(anterior);
  }, [aplicar]);

  const refazer = useCallback(() => {
    const h = historico.current;
    const p = atual.current;
    const proximo = h.depois.pop();
    if (!proximo || !p) return;
    h.antes.push(p);
    h.chave = null;
    setPassos({ desfazer: h.antes.length, refazer: h.depois.length });
    aplicar(proximo);
  }, [aplicar]);

  const garantirMotor = useCallback(async () => {
    if (!motor.current) {
      const m = new MotorDaMusica(TAXA);
      await m.iniciar();
      m.ouvir(setEstado);
      motor.current = m;
    }
    return motor.current;
  }, []);

  const carregarMidia = useCallback(async (projetoId: string, midiaId: string, canais?: Float32Array[]) => {
    const dados = canaisCompartilhados(canais ?? (await lerMidia(projetoId, midiaId)));
    midias.current.set(midiaId, dados);
    motor.current?.definirMidia(midiaId, dados);
    const p = calcularPicos(dados);
    setPicos((anterior) => new Map(anterior).set(midiaId, p));
  }, []);

  const abrir = useCallback(async (p: ProjetoMusical) => {
    setErro(null);
    setCarregando("Abrindo o projeto");
    try {
      const m = await garantirMotor();
      for (const id of [...midias.current.keys()]) m.removerMidia(id);
      midias.current.clear();
      setPicos(new Map());
      historico.current = { antes: [], depois: [], ultima: 0, chave: null };
      setPassos({ desfazer: 0, refazer: 0 });
      atual.current = p;
      setProjeto(p);
      m.definirProjeto(p);
      m.posicionar(0);
      for (const [i, midia] of p.midias.entries()) {
        setCarregando(`Carregando as mídias (${i + 1} de ${p.midias.length})`);
        await carregarMidia(p.id, midia.id);
      }
      await prepararTransformacoes(p, 0);
      setSoltas(await midiasSoltas(p));
      setSelecao({ faixa: p.faixas[0]?.id ?? null, clipe: null, efeitos: "master" });
      setSalvo("salvo");
      void pedirArmazenamentoPersistente();
    } catch (e) {
      setErro(e instanceof Error ? e.message : String(e));
    } finally {
      setCarregando(null);
    }
  }, [carregarMidia, garantirMotor, prepararTransformacoes]);

  const criar = useCallback(async (modeloId: string) => {
    const id = novoId("musica");
    const bloqueado = podeCriar(plano, id);
    if (bloqueado) {
      setBloqueio(bloqueado.mensagem);
      return;
    }
    const modelo = MODELOS_DA_MUSICA.find((m) => m.id === modeloId)!;
    const p = projetoDoModelo(modeloId, id, modelo.id === "vazio" ? "Nova música" : modelo.rotulo, TAXA);
    if (automatico) await salvarProjeto(p);
    registrarCriacao(id);
    await abrir(p);
    if (!automatico) setSalvo("pendente");
  }, [abrir, automatico, plano]);

  const abrirSalvo = useCallback(async (resumo: ResumoDoProjeto) => {
    try {
      // Pasta só com gravações: vira um projeto (não conta como projeto novo da semana).
      abrir(resumo.semProjeto ? await recuperarGravacoes(resumo.id, projetoDoModelo("vazio", resumo.id, "Gravações recuperadas", TAXA)) : await lerProjeto(resumo.id));
    } catch (e) {
      setErro(e instanceof Error ? e.message : String(e));
    }
  }, [abrir]);

  const importarProjeto = useCallback(async (arquivo: File) => {
    setErro(null);
    try {
      setCarregando("Conferindo o projeto");
      const p = await importarArquivo(arquivo);
      const bloqueado = podeCriar(plano, p.id);
      if (bloqueado) {
        await apagarProjeto(p.id);
        setBloqueio(bloqueado.mensagem);
        return;
      }
      registrarCriacao(p.id);
      await abrir(p);
    } catch (e) {
      setErro(e instanceof Error ? e.message : String(e));
    } finally {
      setCarregando(null);
    }
  }, [abrir, plano]);

  /** Cada arquivo de áudio vira uma faixa nova, no cursor; o original não é tocado. */
  const importarAudios = useCallback(async (arquivos: File[]) => {
    const p = atual.current;
    if (!p) return;
    const m = await garantirMotor();
    setErro(null);
    for (const [i, arquivo] of arquivos.entries()) {
      setCarregando(`Importando ${arquivo.name} (${i + 1} de ${arquivos.length})`);
      try {
        if (arquivo.size > 1024 ** 3) throw new Error("passa de 1 GB");
        const buffer = await m.contexto.decodeAudioData(await arquivo.arrayBuffer());
        if (buffer.duration > 2 * 3600) throw new Error("passa de 2 horas");
        const canais = Array.from({ length: Math.min(2, buffer.numberOfChannels) }, (_, c) => buffer.getChannelData(c).slice());
        const midiaId = novoId("midia");
        await salvarMidia(p.id, midiaId, canais, TAXA);
        await carregarMidia(p.id, midiaId, canais);
        const inicio = Math.max(0, Math.round(m.estado.quadro));
        mudar((q) => {
          const nome = arquivo.name.replace(/\.[^.]+$/, "").slice(0, 60);
          const faixa: Faixa = { ...novaFaixa(novoId("faixa"), nome, q.faixas.length), clipes: [{ id: novoId("clipe"), midia: midiaId, inicio, inicioNaMidia: 0, duracao: canais[0].length, ganhoDb: 0, entradaSuave: 0, saidaSuave: 0, mudo: false, nome }] };
          return { ...q, midias: [...q.midias, { id: midiaId, nome: arquivo.name, formato: "wav", taxa: TAXA, canais: canais.length, quadros: canais[0].length, bits: 32, origem: arquivo.name }], faixas: [...q.faixas, faixa] };
        });
      } catch (e) {
        setErro(`Não deu para importar ${arquivo.name}: ${e instanceof Error ? e.message : String(e)}. Formatos aceitos: WAV, MP3, FLAC, M4A, OGG e Opus (o que o navegador decodifica).`);
      }
    }
    setCarregando(null);
  }, [carregarMidia, garantirMotor, mudar]);

  /** Recupera tomadas que ficaram no disco quando a página fechou no meio da gravação. */
  const recuperarSoltas = useCallback(async () => {
    const p = atual.current;
    if (!p) return;
    const recuperadas = soltas;
    for (const s of recuperadas) await carregarMidia(p.id, s.id);
    mudar((q) => {
      const faixa: Faixa = { ...novaFaixa(novoId("faixa"), "Tomadas recuperadas", q.faixas.length), clipes: recuperadas.map((s) => ({ id: novoId("clipe"), midia: s.id, inicio: 0, inicioNaMidia: 0, duracao: Math.max(1, s.quadros), ganhoDb: 0, entradaSuave: 0, saidaSuave: 0, mudo: false, nome: "Recuperada" })) };
      return { ...q, midias: [...q.midias, ...recuperadas.map((s) => ({ id: s.id, nome: `${s.id}.wav`, formato: "wav", taxa: q.taxa, canais: s.canais, quadros: Math.max(1, s.quadros), bits: 32 }))], faixas: [...q.faixas, faixa] };
    });
    setSoltas([]);
    setAviso("As tomadas recuperadas estão numa faixa nova, no começo do projeto. Arraste para o lugar certo.");
  }, [carregarMidia, mudar, soltas]);

  // ==================== transporte ====================

  const tocarOuParar = useCallback(async () => {
    const m = await garantirMotor();
    const p = atual.current;
    if (!p) return;
    if (m.estado.tocando) m.parar();
    else await m.tocar(m.estado.quadro, p.metronomo.ligado);
  }, [garantirMotor]);

  const parar = useCallback(() => motor.current?.parar(), []);

  const irPara = useCallback((quadro: number) => {
    motor.current?.posicionar(Math.max(0, Math.round(quadro)));
  }, []);

  const gravar = useCallback(async () => {
    const p = atual.current;
    const m = motor.current;
    if (!p || !m) return;
    if (m.estado.tocando) return;
    const armadas = p.faixas.filter((f) => f.gravar);
    if (armadas.length !== 1) {
      setErro(armadas.length === 0 ? "Arme uma faixa (botão REC da faixa) antes de gravar." : "No navegador grava uma faixa por vez: deixe só uma armada.");
      return;
    }
    if (!entrada || !m.entradaAberta) {
      setPainel("gravacao");
      setErro("Escolha a entrada de áudio no painel Gravação antes de gravar.");
      return;
    }
    setErro(null);
    const faixa = armadas[0];
    // Sem calibração, vale a latência que o navegador informa.
    const projetoDaGravacao = p.latencia.medida ? p : { ...p, latencia: { ...p.latencia, entrada: entrada.latenciaDeEntrada, saida: m.latenciaDeSaida() } };
    const plano = planoDaGravacao(projetoDaGravacao, Math.round(m.estado.quadro));
    const midiaId = novoId("tomada");
    const canais = faixa.canaisDeEntrada === 2 && entrada.canais >= 2 ? 2 : 1;
    const escritor = await EscritorDeMidia.abrir(p.id, midiaId, canais, TAXA);
    capturados.current = 0;
    setSegundosGravados(0);
    setGravando(faixa.id);
    await m.gravar(plano.inicioDoTransporte, p.metronomo.ligado, plano.cliquesDaContagem, plano.inicioDaTomada, (pedaco) => {
      escritor.escrever(canais === 2 ? [pedaco.esq, pedaco.dir] : [pedaco.esq]);
      capturados.current += pedaco.esq.length;
      setSegundosGravados(capturados.current / TAXA);
    }, async (quadroAlinhado) => {
      const quadros = await escritor.fechar();
      setGravando(null);
      const clipe = clipeDaTomada(projetoDaGravacao, { id: novoId("clipe"), midia: midiaId, quadroAlinhado, quadrosCapturados: quadros }, plano);
      if (!clipe) {
        setAviso("A gravação parou antes do ponto de entrada: nada foi acrescentado (o arquivo ficou guardado).");
        return;
      }
      await carregarMidia(p.id, midiaId);
      mudar((q) => ({
        ...q,
        latencia: projetoDaGravacao.latencia,
        midias: [...q.midias, { id: midiaId, nome: `${faixa.nome} — tomada.wav`, formato: "wav", taxa: TAXA, canais, quadros, bits: 32 }],
        faixas: q.faixas.map((f) => (f.id === faixa.id ? adicionarTomada(f, { ...clipe, nome: `Tomada ${f.clipes.length + 1}` }) : f)),
      }));
    });
  }, [carregarMidia, entrada, mudar]);

  /** Toca cliques e grava a volta (cabo de loopback ou microfone perto do alto-falante). */
  const calibrar = useCallback(async () => {
    const p = atual.current;
    const m = motor.current;
    if (!p || !m || !m.entradaAberta) {
      setErro("Abra a entrada de áudio antes de calibrar.");
      return;
    }
    setErro(null);
    setAviso("Calibrando: deixe o microfone perto do alto-falante (ou ligue a saída na entrada com um cabo) e fique em silêncio por 3 segundos.");
    const pulsos = [12000, 36000, 60000, 84000];
    const vazio = { ...p, faixas: [], barramentos: [], master: { ganhoDb: 0, efeitos: [] }, metronomo: { ...p.metronomo, volumeDb: 0 } };
    m.definirProjeto(vazio);
    const pedacos: Float32Array[] = [];
    const resultado = await new Promise<number | null>((resolver) => {
      void m.gravar(0, false, pulsos.map((quadro) => ({ quadro, forte: true })), 120000, (pedaco) => pedacos.push(pedaco.esq), (quadroAlinhado) => {
        const total = pedacos.reduce((s, x) => s + x.length, 0);
        const gravado = new Float32Array(total);
        let k = 0;
        for (const x of pedacos) {
          gravado.set(x, k);
          k += x.length;
        }
        resolver(medirLatencia(gravado, pulsos.map((q) => q - quadroAlinhado), TAXA));
      });
      setTimeout(() => m.parar(), 2700);
    });
    m.definirProjeto(atual.current!);
    m.posicionar(0);
    if (resultado === null) {
      setAviso("Não deu para ouvir os cliques de volta. Com fones, a calibração não funciona: use o valor estimado, ou ajuste a correção manual em milissegundos.");
      return;
    }
    mudar((q) => ({ ...q, latencia: { entrada: resultado, saida: 0, medida: true, ajuste: q.latencia.ajuste ?? 0 } }));
    setAviso(`Latência de ida e volta medida: ${((resultado / TAXA) * 1000).toFixed(1)} ms. As próximas tomadas entram compensadas.`);
  }, [mudar]);

  // ==================== atalhos ====================

  useEffect(() => {
    if (!projeto) return;
    const tecla = (e: KeyboardEvent) => {
      const alvo = e.target as HTMLElement;
      if (alvo.closest("input, textarea, select, [contenteditable]")) return;
      if (e.code === "Space") {
        e.preventDefault();
        void tocarOuParar();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
        e.preventDefault();
        if (e.shiftKey) refazer();
        else desfazer();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "y") {
        e.preventDefault();
        refazer();
      } else if (e.key.toLowerCase() === "r" && !e.ctrlKey && !e.metaKey) {
        void gravar();
      } else if (e.key === "Home") {
        irPara(0);
      } else if (e.key.toLowerCase() === "l" && !e.ctrlKey) {
        mudar((p) => ({ ...p, loop: { ...p.loop, ligado: !p.loop.ligado } }));
      }
    };
    window.addEventListener("keydown", tecla);
    return () => window.removeEventListener("keydown", tecla);
  }, [desfazer, gravar, irPara, mudar, projeto, refazer, tocarOuParar]);

  const concluirFechamento = useCallback(async (salvar: boolean) => {
    motor.current?.parar();
    if (temporizador.current) clearTimeout(temporizador.current);
    if (temporizadorDaTransformacao.current) clearTimeout(temporizadorDaTransformacao.current);
    if (salvar && atual.current) await salvarProjeto(atual.current);
    setSaidaPendente(false);
    setProjeto(null);
    atual.current = null;
    setSalvo("salvo");
    await recarregarLista();
  }, [recarregarLista]);

  const fecharProjeto = useCallback(() => {
    if (!automatico && salvo === "pendente") { setSaidaPendente(true); return; }
    void concluirFechamento(automatico);
  }, [automatico, concluirFechamento, salvo]);

  const baixarProjeto = useCallback(async () => {
    const p = atual.current;
    if (!p) return;
    setCarregando("Juntando o projeto");
    try {
      await salvarProjeto(p);
      setSalvo("salvo");
      const blob = await exportarArquivo(p);
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `${p.titulo.replace(/[^\w\s-]/g, "").trim() || "musica"}.sltmusic`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    } catch (e) {
      setErro(e instanceof Error ? e.message : String(e));
    } finally {
      setCarregando(null);
    }
  }, []);

  const fontes = useCallback(async (): Promise<Map<string, FonteDeAudio>> => {
    if (atual.current) await prepararTransformacoes(atual.current, 0);
    const mapa = new Map<string, FonteDeAudio>();
    for (const [id, canais] of midias.current) mapa.set(id, fonteDaMemoria(canais));
    for (const [id, canais] of transformadas.current) mapa.set(id, fonteDaMemoria(canais));
    return mapa;
  }, [prepararTransformacoes]);

  // ==================== tela ====================

  if (!projeto) {
    return (
      <section className="estudio musica">
        <a className="estudio__voltar musica__voltar" href="/estudio">← Estúdio de vídeo</a>
        <span className="estudio__selo">Estúdio · Criação musical</span>
        <h1>Grave, mixe e separe faixas no navegador</h1>
        <p className="estudio__dica">
          Grave uma faixa ouvindo as outras, importe áudios, separe voz, bateria e baixo por IA, aplique efeitos e exporte. Tudo fica neste aparelho: nenhum
          arquivo é enviado. Use fones com fio (ou uma interface de áudio): com fone Bluetooth o atraso varia e a gravação sai fora do tempo.
        </p>
        {!armazenamento && <p className="estudio__erro">Este navegador não tem armazenamento local para projetos (OPFS). Use o Chrome, o Edge, o Firefox ou o Safari atualizados.</p>}
        {pronto && plano === "gratis" && <p className="estudio__dica">No grátis cabe <strong>um projeto musical novo por semana</strong>; reabrir, gravar mais e exportar o mesmo projeto não contam. O Pro não tem limite.</p>}
        {bloqueio && <AvisoDoLimite mensagem={bloqueio} />}
        {erro && <p className="estudio__erro" role="alert">{erro}</p>}
        {carregando && <p className="estudio__dica" aria-live="polite">{carregando}…</p>}
        <div className="estudio__rede">
          <h2>Começar um projeto</h2>
          <div className="estudio__saidas">
            {MODELOS_DA_MUSICA.map((m) => (
              <button key={m.id} type="button" className="estudio__cartao" onClick={() => void criar(m.id)} disabled={!armazenamento || !!carregando}>
                <strong>{m.rotulo}</strong>
                <span>{m.faixas.join(", ")}{m.barramentos.length ? ` · barramentos: ${m.barramentos.join(", ")}` : ""}</span>
              </button>
            ))}
            <label className="estudio__cartao">
              <strong>Abrir um .sltmusic</strong>
              <span>Projeto salvo no site, no Windows ou no Android.</span>
              <input type="file" accept=".sltmusic,application/zip" hidden onChange={(e: ChangeEvent<HTMLInputElement>) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void importarProjeto(f); }} />
            </label>
          </div>
        </div>
        {lista && lista.length > 0 && (
          <div className="estudio__rede">
            <h2>Projetos neste aparelho</h2>
            <ul className="musica__projetos">
              {lista.map((r) => (
                <li key={r.id}>
                  <button type="button" className="estudio__cartao" onClick={() => void abrirSalvo(r)} disabled={!!carregando}>
                    <strong>{r.titulo}</strong>
                    <span>{r.semProjeto ? "O arquivo do projeto se perdeu; as tomadas ficaram. Toque para recuperar." : `${r.faixas} ${r.faixas === 1 ? "faixa" : "faixas"} · ${new Date(r.atualizado).toLocaleString("pt-BR")}`}</span>
                  </button>
                  <button type="button" className="estudio__icone" aria-label={`Apagar ${r.titulo}`} onClick={async () => { if (confirm(`Apagar “${r.titulo}” e as gravações dele deste aparelho? Não dá para desfazer.`)) { await apagarProjeto(r.id); await recarregarLista(); } }}>✕</button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    );
  }

  const posicao = posicaoMusical(projeto, estado.quadro);
  const duracao = duracaoEmQuadros(projeto);
  const armada = projeto.faixas.find((f) => f.gravar);

  return (
    <section className="estudio musica musica--editor">
      <header className="musica__cabecalho">
        <button type="button" className="estudio__voltar" onClick={() => void fecharProjeto()} disabled={!!gravando}>← Projetos</button>
        <input className="musica__titulo" aria-label="Nome do projeto" value={projeto.titulo} maxLength={80} onChange={(e) => mudar((p) => ({ ...p, titulo: e.target.value }), "titulo")} />
        <span className={`musica__salvo musica__salvo--${salvo}`} aria-live="polite">{salvo === "salvo" ? "Salvo neste aparelho" : salvo === "salvando" ? "Salvando…" : salvo === "pendente" ? "Alterações não salvas" : "Não salvou: espaço cheio?"}</span>
        <label className="estudio__chave" title="Desligado: pergunta se você quer salvar ao sair do projeto"><input type="checkbox" checked={automatico} onChange={(e) => {
          const ligado = e.target.checked;
          setAutomatico(ligado);
          guardarSalvamentoAutomatico(ligado);
          if (ligado && atual.current) {
            setSalvo("salvando");
            void salvarProjeto(atual.current).then(() => setSalvo("salvo"), () => setSalvo("erro"));
          }
        }} /> Salvar automaticamente</label>
        <div className="musica__acoes">
          <button type="button" className="button button--ghost" onClick={desfazer} disabled={!passos.desfazer} title="Desfazer (Ctrl+Z)">↶ Desfazer</button>
          <button type="button" className="button button--ghost" onClick={refazer} disabled={!passos.refazer} title="Refazer (Ctrl+Shift+Z)">↷ Refazer</button>
          <label className="button button--ghost">
            Importar áudio
            <input type="file" accept="audio/*,.wav,.mp3,.flac,.m4a,.ogg,.opus,.aac" multiple hidden onChange={(e) => { const f = [...(e.target.files ?? [])]; e.target.value = ""; if (f.length) void importarAudios(f); }} />
          </label>
          <button type="button" className="button button--ghost" onClick={() => void baixarProjeto()}>Salvar .sltmusic</button>
        </div>
      </header>

      <div className="musica__transporte" role="toolbar" aria-label="Transporte">
        <button type="button" className="musica__botao" onClick={() => irPara(0)} aria-label="Voltar ao início" title="Início (Home)">⏮</button>
        <button type="button" className="musica__botao musica__botao--tocar" onClick={() => void tocarOuParar()} aria-label={estado.tocando ? "Parar" : "Tocar"} title="Tocar/parar (Espaço)">{estado.tocando ? "⏹" : "▶"}</button>
        <button type="button" className={`musica__botao musica__botao--rec${gravando ? " musica__botao--gravando" : ""}`} onClick={() => (gravando ? parar() : void gravar())} aria-label={gravando ? "Parar a gravação" : "Gravar"} title={armada ? `Gravar em ${armada.nome} (R)` : "Arme uma faixa para gravar"}>{gravando ? "■ REC" : "● REC"}</button>
        <button type="button" className={`musica__alternar${projeto.loop.ligado ? " musica__alternar--ligado" : ""}`} onClick={() => mudar((p) => ({ ...p, loop: p.loop.fim > p.loop.inicio ? { ...p.loop, ligado: !p.loop.ligado } : { ligado: true, inicio: 0, fim: Math.max(Math.round(quadrosPorCompasso(p) * 4), duracao) } }))} title="Loop (L)">⟲ Loop</button>
        <button type="button" className={`musica__alternar${projeto.metronomo.ligado ? " musica__alternar--ligado" : ""}`} onClick={() => mudar((p) => ({ ...p, metronomo: { ...p.metronomo, ligado: !p.metronomo.ligado } }))}>♩ Metrônomo</button>
        <label className="musica__campo">Contagem
          <select value={projeto.contagem} onChange={(e) => mudar((p) => ({ ...p, contagem: Number(e.target.value) }))}>
            {[0, 1, 2].map((c) => <option key={c} value={c}>{c === 0 ? "sem" : `${c} compasso${c > 1 ? "s" : ""}`}</option>)}
          </select>
        </label>
        <label className="musica__campo">BPM
          <input type="number" min={BPM_MINIMO} max={BPM_MAXIMO} step={1} value={projeto.bpm} onChange={(e) => { const v = Number(e.target.value); if (v >= BPM_MINIMO && v <= BPM_MAXIMO) mudar((p) => ({ ...p, bpm: v }), "bpm"); }} />
        </label>
        <label className="musica__campo">Compasso
          <select value={`${projeto.compasso[0]}/${projeto.compasso[1]}`} onChange={(e) => { const [a, b] = e.target.value.split("/").map(Number); mudar((p) => ({ ...p, compasso: [a, b] })); }}>
            {["2/4", "3/4", "4/4", "5/4", "6/8", "7/8", "12/8"].map((c) => <option key={c}>{c}</option>)}
          </select>
        </label>
        <output className="musica__relogio" aria-label="Posição">
          <strong>{posicao.texto}</strong>
          <small>{tempoDoQuadro(projeto, estado.quadro)}</small>
        </output>
        <label className="musica__campo musica__campo--zoom">Zoom
          <input type="range" min={10} max={400} value={zoom} onChange={(e) => setZoom(Number(e.target.value))} aria-label="Zoom da linha do tempo" />
        </label>
      </div>

      {gravando && <p className="musica__gravando" role="status">Gravando em {projeto.faixas.find((f) => f.id === gravando)?.nome} · {segundosGravados.toFixed(1)} s · a tomada vai direto para o disco</p>}
      {erro && <p className="estudio__erro" role="alert">{erro} <button type="button" className="estudio__icone" onClick={() => setErro(null)} aria-label="Fechar">✕</button></p>}
      {aviso && <p className="musica__aviso" role="status">{aviso} <button type="button" className="estudio__icone" onClick={() => setAviso(null)} aria-label="Fechar">✕</button></p>}
      {soltas.length > 0 && (
        <p className="musica__aviso" role="status">
          {soltas.length === 1 ? "Uma tomada ficou gravada" : `${soltas.length} tomadas ficaram gravadas`} sem entrar no projeto (a página fechou no meio).{" "}
          <button type="button" className="button button--ghost" onClick={() => void recuperarSoltas()}>Recuperar</button>
        </p>
      )}
      {carregando && <p className="estudio__dica" aria-live="polite">{carregando}…</p>}

      <LinhaDoTempoMusical projeto={projeto} picos={picos} estado={estado} zoom={zoom} mudar={mudar} irPara={irPara} selecao={selecao} selecionar={setSelecao} gravando={gravando}
        exportarFaixa={(id) => { setPainel("exportar"); setSelecao((s) => ({ ...s, faixa: id })); }} abrirEfeitos={(id) => { setSelecao((s) => ({ ...s, efeitos: id })); setPainel("efeitos"); }}
        limparClipe={(faixaId, clipeId) => { setAviso("Reduzindo o ruído do clipe…"); void reduzirRuidoDoClipe(projeto, faixaId, clipeId, (id) => midias.current.get(id), carregarMidia, mudar).then(setAviso); }} />

      <nav className="musica__abas" aria-label="Painéis">
        {([["mixer", "Mixer"], ["tempo", "Tempo e tom"], ["efeitos", "Efeitos"], ["gravacao", "Gravação"], ["separar", "Separar por IA"], ["exportar", "Exportar"]] as const).map(([id, rotulo]) => (
          <button key={id} type="button" className={`musica__aba${painel === id ? " musica__aba--ativa" : ""}`} onClick={() => setPainel(id)} aria-pressed={painel === id}>{rotulo}</button>
        ))}
      </nav>
      <div className="musica__painel">
        {painel === "mixer" && <MixerMusical projeto={projeto} estado={estado} mudar={mudar} abrirEfeitos={(id) => { setSelecao((s) => ({ ...s, efeitos: id })); setPainel("efeitos"); }} />}
        {painel === "tempo" && <TempoETom projeto={projeto} mudar={mudar} canaisDe={(id) => midias.current.get(id)} />}
        {painel === "efeitos" && <EfeitosMusicais projeto={projeto} alvo={selecao.efeitos} escolher={(id) => setSelecao((s) => ({ ...s, efeitos: id }))} mudar={mudar} />}
        {painel === "gravacao" && (
          <PainelDeGravacao projeto={projeto} mudar={mudar} entrada={entrada} saidaEstimada={saidaEstimada} quadro={estado.quadro}
            abrirEntrada={async (dispositivo, canais) => { const m = await garantirMotor(); try { const r = await m.abrirEntrada(dispositivo, canais); setEntrada(r); setSaidaEstimada(m.latenciaDeSaida()); setErro(null); } catch (e) { setErro(e instanceof Error ? e.message : String(e)); } }}
            fecharEntrada={() => { motor.current?.fecharEntrada(); setEntrada(null); }} calibrar={() => void calibrar()} />
        )}
        {painel === "separar" && <PainelDeSeparacao projeto={projeto} canaisDe={(id) => midias.current.get(id)} mudar={mudar} carregarMidia={carregarMidia} />}
        {painel === "exportar" && <PainelDeExportacao projeto={projeto} fontes={fontes} faixaSelecionada={selecao.faixa} />}
      </div>
      {saidaPendente && <div className="musica__saida-fundo" role="presentation"><section className="musica__saida" role="dialog" aria-modal="true" aria-labelledby="saida-titulo">
        <h2 id="saida-titulo">Salvar o projeto?</h2>
        <p>Suas últimas alterações ainda não foram salvas neste aparelho.</p>
        <div className="musica__saida-acoes">
          <button type="button" className="button button--primary" onClick={() => void concluirFechamento(true)}>Salvar e sair</button>
          <button type="button" className="button button--ghost" onClick={() => void concluirFechamento(false)}>Sair sem salvar</button>
          <button type="button" className="button button--ghost" onClick={() => setSaidaPendente(false)}>Continuar editando</button>
        </div>
      </section></div>}
    </section>
  );
}

function AvisoDoLimite({ mensagem }: { mensagem: string }) {
  const mensal = produto("pro-mensal")!;
  return (
    <aside className="paywall" aria-live="polite">
      <h3 className="paywall__titulo">O grátis chegou no limite da semana. O projeto que você já tem continua aberto.</h3>
      <p className="paywall__motivo">{mensagem}</p>
      <p className="paywall__preco"><strong>{formatarReais(mensal.centavos)} por {mensal.dias} dias</strong> tiram o limite de projetos musicais (e os do estúdio de vídeo). Pix libera a chave na hora.</p>
      <div className="paywall__acoes">
        <BotaoDeCompra produto={mensal.id} rotulo="Pagar com Pix" destaque origem="limite:musica" voltarDepois />
        <a className="button button--ghost" href="/aplicativo?origem=limite%3Amusica">Aplicativo para Windows e Android</a>
      </div>
    </aside>
  );
}

export function clipePorId(projeto: ProjetoMusical, id: string | null): { faixa: Faixa; clipe: Clipe } | null {
  for (const faixa of projeto.faixas) for (const clipe of faixa.clipes) if (clipe.id === id) return { faixa, clipe };
  return null;
}
