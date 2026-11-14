"use client";

import { useEffect, useRef, useState } from "react";

import { PASSOS_DA_MUSICA, nomeDaFaixaSeparada, novaFaixa, type ProjetoMusical } from "@/packages/converter-core/musica.mjs";
import type { FonteDeAudio } from "@/packages/converter-core/musica-mixer.mjs";
import { FORMATOS_DE_EXPORTACAO, PRESETS_DE_MASTER } from "@/packages/converter-core/musica-master.mjs";
import { AVISO_DA_SEPARACAO, MODELO_DE_SEPARACAO, MODOS_DE_SEPARACAO, reduzirRuido } from "@/packages/converter-core/musica-separacao.mjs";
import { MODELOS_DE_SEPARACAO, MODO_ROFORMER_EXPERIMENTAL, ROFORMER_COMPACTO } from "@/packages/converter-core/musica-modelos.mjs";
import { perfilDeRestauracaoDaFaixa } from "@/packages/converter-core/nativo.mjs";
import { salvarMidia } from "@/lib/musica/armazenamento";
import { codificarWav, lerWav } from "@/packages/converter-core/musica-master.mjs";
import { usePlugin } from "@/lib/plugin/usePlugin";
import type { PedidoDeSeparacaoMusical, Trabalho } from "@/lib/plugin/protocolo";
import { exportarMusica, formatosDisponiveis, type Exportado } from "@/lib/musica/exportar";
import { MotorDaMusica, type ResultadoDaEntrada } from "@/lib/musica/motor";
import { SeparadorNoNavegador, apagarModelo, garantirModelo, modeloBaixado, restaurarFaixa, type PerfilDeMascara } from "@/lib/musica/separacao";
import { novoId, type Mudar } from "./CriacaoMusical";
import { AjusteMusical } from "./AjusteMusical";

const ms = (quadros: number, taxa: number) => `${((quadros / taxa) * 1000).toFixed(1)} ms`;

export function PainelDeGravacao({ projeto, mudar, entrada, saidaEstimada, quadro, abrirEntrada, fecharEntrada, calibrar }: {
  projeto: ProjetoMusical;
  mudar: Mudar;
  entrada: ResultadoDaEntrada | null;
  saidaEstimada: number;
  quadro: number;
  abrirEntrada: (dispositivo: string | null, canais: number) => Promise<void>;
  fecharEntrada: () => void;
  calibrar: () => void;
}) {
  const [dispositivos, setDispositivos] = useState<MediaDeviceInfo[]>([]);
  const [dispositivo, setDispositivo] = useState<string>("");
  const [canais, setCanais] = useState(1);
  useEffect(() => {
    void MotorDaMusica.entradas().then(setDispositivos);
  }, [entrada]);
  const taxa = projeto.taxa;
  const estimada = entrada ? entrada.latenciaDeEntrada + saidaEstimada : null;
  return (
    <div className="musica__colunas">
      <section>
        <h3>Entrada</h3>
        <label className="musica__campo">Dispositivo
          <select value={dispositivo} onChange={(e) => setDispositivo(e.target.value)}>
            <option value="">Padrão do sistema</option>
            {dispositivos.map((d, i) => <option key={d.deviceId || i} value={d.deviceId}>{d.label || `Entrada ${i + 1}`}</option>)}
          </select>
        </label>
        <label className="musica__campo">Canais
          <select value={canais} onChange={(e) => setCanais(Number(e.target.value))}><option value={1}>Mono</option><option value={2}>Estéreo (se a entrada tiver)</option></select>
        </label>
        <div className="musica__linha">
          <button type="button" className="button" onClick={() => void abrirEntrada(dispositivo || null, canais)}>{entrada ? "Trocar a entrada" : "Usar esta entrada"}</button>
          {entrada && <button type="button" className="button button--ghost" onClick={fecharEntrada}>Fechar o microfone</button>}
        </div>
        {entrada && (
          <p className="estudio__dica">
            {entrada.dispositivo} · {entrada.canais === 1 ? "mono" : `${entrada.canais} canais`} · {entrada.semProcessamento ? "sem processamento de voz (sinal limpo)" : "com algum processamento do navegador"}
            {entrada.avisos.map((a) => <span key={a}><br />⚠ {a}</span>)}
          </p>
        )}
        <p className="estudio__dica">
          Use fones com fio ou uma interface de áudio USB. Com fone Bluetooth o atraso varia e a gravação sai fora do tempo. Sem fones, deixe o MON
          desligado para não dar microfonia. A gravação é sempre seca: os efeitos da faixa só tocam, não entram no arquivo gravado.
        </p>
      </section>
      <section>
        <h3>Latência</h3>
        <p className="estudio__dica">
          {projeto.latencia.medida ? `Medida: ${ms(projeto.latencia.entrada + projeto.latencia.saida, taxa)} de ida e volta.` : estimada !== null ? `Estimada pelo navegador: ${ms(estimada, taxa)} (calibre para ficar exata).` : "Abra a entrada para estimar."}
          {" "}Cada tomada entra na linha do tempo já compensada.
        </p>
        <div className="musica__linha">
          <button type="button" className="button button--ghost" onClick={calibrar} disabled={!entrada}>Calibrar com cliques</button>
          {projeto.latencia.medida && <button type="button" className="button button--ghost" onClick={() => mudar((p) => ({ ...p, latencia: { entrada: 0, saida: 0, medida: false, ajuste: p.latencia.ajuste ?? 0 } }))}>Voltar à estimativa</button>}
        </div>
        <label className="musica__campo">Correção manual (ms)
          <input type="number" step={0.5} value={(((projeto.latencia.ajuste ?? 0) / taxa) * 1000).toFixed(1)} onChange={(e) => mudar((p) => ({ ...p, latencia: { ...p.latencia, ajuste: Math.round((Number(e.target.value) / 1000) * taxa) } }), "ajuste")} />
        </label>
        <small className="estudio__dica">Positivo adianta a tomada; negativo atrasa. A calibração precisa ouvir os cliques: microfone perto do alto-falante ou cabo da saída para a entrada.</small>
      </section>
      <section>
        <h3>Punch e metrônomo</h3>
        <label className="estudio__chave"><input type="checkbox" checked={projeto.punch.ligado} onChange={(e) => mudar((p) => ({ ...p, punch: { ...p.punch, ligado: e.target.checked && p.punch.saida > p.punch.entrada } }))} disabled={!(projeto.punch.saida > projeto.punch.entrada)} /> Gravar só entre a entrada e a saída do punch</label>
        <button type="button" className="button button--ghost" onClick={() => mudar((p) => ({ ...p, punch: { ligado: p.loop.fim > p.loop.inicio, entrada: p.loop.inicio, saida: p.loop.fim } }))} disabled={!(projeto.loop.fim > projeto.loop.inicio)}>Usar a região do loop como punch</button>
        <div className="musica__campo">Volume do metrônomo (dB)
          <AjusteMusical valor={projeto.metronomo.volumeDb} min={-40} max={6} passo={PASSOS_DA_MUSICA.metronomoDb} rotulo="volume do metrônomo" mudar={(v) => mudar((p) => ({ ...p, metronomo: { ...p.metronomo, volumeDb: v } }), "metro")} />
        </div>
        <label className="estudio__chave"><input type="checkbox" checked={projeto.metronomo.naExportacao} onChange={(e) => mudar((p) => ({ ...p, metronomo: { ...p.metronomo, naExportacao: e.target.checked } }))} /> Metrônomo também na exportação</label>
        <button type="button" className="button button--ghost" onClick={() => mudar((p) => ({ ...p, marcadores: [...p.marcadores, { id: novoId("marca"), quadro: Math.round(quadro), nome: `Marca ${p.marcadores.length + 1}` }] }))}>+ Marcador no cursor</button>
        <label className="musica__campo">Loop: início e fim (s)
          <span className="musica__linha">
            <input type="number" min={0} step={0.1} value={(projeto.loop.inicio / taxa).toFixed(2)} onChange={(e) => mudar((p) => ({ ...p, loop: { ...p.loop, inicio: Math.max(0, Math.round(Number(e.target.value) * taxa)) } }), "loopi")} aria-label="Início do loop" />
            <input type="number" min={0} step={0.1} value={(projeto.loop.fim / taxa).toFixed(2)} onChange={(e) => mudar((p) => ({ ...p, loop: { ...p.loop, fim: Math.max(0, Math.round(Number(e.target.value) * taxa)) } }), "loopf")} aria-label="Fim do loop" />
          </span>
        </label>
      </section>
    </div>
  );
}

/** Redução de ruído de fundo, fora do tempo real: devolve os dois canais limpos. */
export function canaisSemRuido(canais: Float32Array[]): Float32Array[] {
  const n = canais[0].length;
  const saida = [new Float32Array(n), new Float32Array(n)];
  reduzirRuido({ comprimento: n, canais: canais.length, ler: (c, i, k, d) => d.set(canais[Math.min(c, canais.length - 1)].subarray(i, i + k)), emitir: (c, i, a, k) => saida[c].set(a.subarray(0, k), i) });
  return saida;
}

/**
 * Reduz o ruído de um clipe gravado (chiado, ventilador): grava a mídia limpa e troca o clipe
 * para ela. A original fica guardada; Desfazer volta.
 */
export async function reduzirRuidoDoClipe(projeto: ProjetoMusical, faixaId: string, clipeId: string, canaisDe: (midia: string) => Float32Array[] | undefined, carregarMidia: (projetoId: string, midiaId: string, canais?: Float32Array[]) => Promise<void>, mudar: Mudar): Promise<string> {
  const clipe = projeto.faixas.find((f) => f.id === faixaId)?.clipes.find((c) => c.id === clipeId);
  const canais = clipe ? canaisDe(clipe.midia) : undefined;
  if (!clipe || !canais) return "O áudio deste clipe ainda não carregou.";
  await new Promise((r) => setTimeout(r, 30));
  const saida = canaisSemRuido(canais);
  const midia = novoId("limpa");
  await salvarMidia(projeto.id, midia, saida, projeto.taxa);
  await carregarMidia(projeto.id, midia, saida);
  mudar((p) => ({
    ...p,
    midias: [...p.midias, { id: midia, nome: "Sem ruído.wav", formato: "wav", taxa: p.taxa, canais: 2, quadros: saida[0].length, bits: 32, origem: clipe.midia }],
    faixas: p.faixas.map((f) => (f.id === faixaId ? { ...f, clipes: f.clipes.map((c) => (c.id === clipeId ? { ...c, midia, nome: `${c.nome ?? "Clipe"} (sem ruído)` } : c)) } : f)),
  }));
  return "Ruído reduzido neste clipe. A gravação original continua guardada: Desfazer volta para ela.";
}

export function PainelDeSeparacao({ projeto, canaisDe, mudar, carregarMidia }: {
  projeto: ProjetoMusical;
  canaisDe: (midia: string) => Float32Array[] | undefined;
  mudar: Mudar;
  carregarMidia: (projetoId: string, midiaId: string, canais?: Float32Array[]) => Promise<void>;
}) {
  const clipes = projeto.faixas.flatMap((f) => f.clipes.filter((c) => !c.mudo).map((c) => ({ faixa: f, clipe: c })));
  const [escolhido, setEscolhido] = useState(clipes[0]?.clipe.id ?? "");
  const [modoEscolhido, setModo] = useState("4");
  const [roformer, setRoFormer] = useState(false);
  const [mascara, setMascara] = useState<PerfilDeMascara>("classica");
  const [intensidade, setIntensidade] = useState(0);
  const [limpar, setLimpar] = useState(false);
  const [locais, setLocais] = useState([false, false]);
  const [noPlugin, setNoPlugin] = useState({ legacy: false, maximum: false });
  const [andamento, setAndamento] = useState<{ etapa: string; fracao: number } | null>(null);
  const [mensagem, setMensagem] = useState<string | null>(null);
  const separador = useRef<SeparadorNoNavegador | null>(null);
  const plugin = usePlugin();
  const pluginDisponivel = plugin.apresentacao?.operacoes.some((o) => o.id === "music.separate" && o.disponivel) ?? false;
  const pluginRoFormer = pluginDisponivel && plugin.apresentacao?.perfisDaSeparacaoMusical?.includes("maximum") === true;
  const modos = roformer ? [MODO_ROFORMER_EXPERIMENTAL] : MODOS_DE_SEPARACAO;
  const modo = roformer ? "6" : modoEscolhido;
  const definicao = modos.find((m) => m.id === modo)!;
  const pluginSepara = pluginDisponivel && (roformer || mascara === "classica" ||
    plugin.apresentacao?.mascarasDaSeparacaoMusical?.includes(mascara) === true);
  const [usarPlugin, setUsarPlugin] = useState(false);
  const delegar = usarPlugin && pluginSepara && (!roformer || pluginRoFormer);
  const gerenciaPlugin = delegar && plugin.apresentacao?.gerenciaModelosMusicais === true;
  const perfil = roformer ? "maximum" : "legacy";
  const baixado = gerenciaPlugin ? noPlugin[perfil] : !delegar && locais[Number(roformer)];
  const modelo = roformer ? (delegar ? MODELOS_DE_SEPARACAO.maximum.onnxExperimental.id : ROFORMER_COMPACTO.id) : MODELO_DE_SEPARACAO.id;
  const cancelarNoPlugin = useRef<(() => void) | null>(null);
  const descarga = useRef<AbortController | null>(null);
  useEffect(() => {
    void Promise.all([modeloBaixado(), modeloBaixado(true)]).then(setLocais);
    return () => descarga.current?.abort();
  }, []);
  useEffect(() => {
    if (gerenciaPlugin) void plugin.cliente.modelosMusicais().then(setNoPlugin).catch(() => undefined);
  }, [gerenciaPlugin, plugin.cliente]);

  const atualizarModelo = async (excluir: boolean) => {
    if (excluir && !confirm("Excluir o modelo baixado? As faixas já criadas ficam no projeto.")) return;
    const controle = new AbortController();
    descarga.current = controle;
    setMensagem(null);
    setAndamento({ etapa: excluir ? "Excluindo modelo" : "Baixando modelo", fracao: 0 });
    try {
      if (gerenciaPlugin) setNoPlugin(await plugin.cliente.modelosMusicais(perfil, excluir ? "excluir" : "baixar", controle.signal));
      else {
        if (excluir) await apagarModelo(roformer);
        else await garantirModelo((fracao) => setAndamento({ etapa: "Baixando modelo", fracao }), controle.signal, roformer);
        setLocais(await Promise.all([modeloBaixado(), modeloBaixado(true)]));
      }
      setMensagem(excluir ? "Modelo excluído." : "Modelo baixado e verificado.");
    } catch (erro) { setMensagem(controle.signal.aborted ? "Operação cancelada." : String(erro instanceof Error ? erro.message : erro)); }
    finally { descarga.current = null; setAndamento(null); }
  };

  /** O plugin do Windows separa com o ONNX nativo: manda o áudio (WAV), recebe uma faixa por arquivo. */
  const separarNoPlugin = async (canais: Float32Array[]) => {
    setAndamento({ etapa: "Enviando ao plugin", fracao: 0 });
    const wav = codificarWav(canais, projeto.taxa, 32);
    const arquivo = new File([wav.buffer as ArrayBuffer], "audio-para-separar.wav", { type: "audio/wav" });
    const pedido: PedidoDeSeparacaoMusical = { operation: "music.separate", Mode: modo, SampleRate: projeto.taxa,
      QualityProfile: roformer ? "maximum" : "legacy", MaskProfile: roformer ? undefined : mascara };
    const trabalho = await plugin.cliente.converterArquivo(arquivo, pedido, "faixas");
    const final = await new Promise<Trabalho>((resolver, rejeitar) => {
      const parar = plugin.cliente.acompanhar(trabalho.id, (t) => {
        setAndamento({ etapa: t.mensagem ?? "Separando no plugin", fracao: t.progresso ?? 0 });
        if (!t.encerrado) return;
        if (t.erro) rejeitar(new Error(t.erro));
        else resolver(t);
      });
      cancelarNoPlugin.current = () => { parar(); void plugin.cliente.cancelar(trabalho.id); rejeitar(new Error("Separação cancelada.")); };
    });
    if (final.arquivos.length !== definicao.faixas.length) throw new Error("O plugin devolveu uma quantidade inesperada de faixas.");
    const faixas = [];
    for (const [i, f] of definicao.faixas.entries()) {
      const resposta = await fetch(plugin.cliente.enderecoDoArquivo(final.id, final.arquivos[i].indice), { headers: { "X-Smells-Like-Tech": "1" } });
      if (!resposta.ok) throw new Error("Não deu para receber as faixas do plugin.");
      faixas.push({ id: f.id, rotulo: f.rotulo, canais: lerWav(new Uint8Array(await resposta.arrayBuffer())).canais });
    }
    return faixas;
  };
  const alvo = clipes.find((x) => x.clipe.id === escolhido) ?? clipes[0];

  const separar = async () => {
    if (!alvo) return;
    const canais = canaisDe(alvo.clipe.midia);
    if (!canais) return;
    setMensagem(null);
    try {
      let faixas;
      if (delegar) faixas = await separarNoPlugin(canais);
      else {
        const controle = new AbortController();
        descarga.current = controle;
        setAndamento({ etapa: "Conferindo o modelo", fracao: 0 });
        await garantirModelo((f) => setAndamento({ etapa: "Baixando o modelo", fracao: f }), controle.signal, roformer);
        setLocais(await Promise.all([modeloBaixado(), modeloBaixado(true)]));
        descarga.current = null;
        separador.current = new SeparadorNoNavegador();
        faixas = await separador.current.separar(canais.map((c) => c.slice()), projeto.taxa, modo, (etapa, fracao) => setAndamento({ etapa, fracao }), mascara);
      }
      const novas: { midia: string; bruta: string | null; rotulo: string; id: string; quadros: number }[] = [];
      for (const [k, faixa] of faixas.entries()) {
        const midia = novoId("stem");
        let bruta: string | null = null;
        if (limpar) {
          setAndamento({ etapa: `Reduzindo o ruído: ${faixa.rotulo}`, fracao: (k + 0.5) / faixas.length });
          await new Promise((r) => setTimeout(r, 30));
          faixa.canais = canaisSemRuido(faixa.canais);
        }
        if (intensidade > 0) {
          bruta = novoId("stem-bruta");
          await salvarMidia(projeto.id, bruta, faixa.canais, projeto.taxa);
          await carregarMidia(projeto.id, bruta, faixa.canais);
          setAndamento({ etapa: `Restaurando ${faixa.rotulo}`, fracao: (k + 0.5) / faixas.length });
          const restaurada = await restaurarFaixa(faixa.canais, projeto.taxa, perfilDeRestauracaoDaFaixa(faixa.id), intensidade);
          faixa.canais = restaurada.canais;
        }
        setAndamento({ etapa: `Guardando ${faixa.rotulo}`, fracao: 1 });
        await salvarMidia(projeto.id, midia, faixa.canais, projeto.taxa);
        await carregarMidia(projeto.id, midia, faixa.canais);
        novas.push({ midia, bruta, rotulo: faixa.rotulo, id: faixa.id, quadros: faixa.canais[0].length });
      }
      mudar((p) => {
        const origem = p.midias.find((m) => m.id === alvo.clipe.midia);
        const i = p.faixas.findIndex((f) => f.id === alvo.faixa.id);
        const criadas = novas.map((n, k) => ({ ...novaFaixa(novoId("faixa"), nomeDaFaixaSeparada(n.rotulo, alvo.faixa.nome), i + k + 1), clipes: [{ ...alvo.clipe, id: novoId("clipe"), midia: n.midia, nome: n.rotulo }] }));
        return {
          ...p,
          midias: [...p.midias, ...novas.flatMap((n) => {
            const metadados = { nome: `${n.rotulo}.wav`, formato: "wav", taxa: p.taxa, canais: 2, quadros: n.quadros, bits: 32, origem: origem?.id ?? null, instrumento: n.id, modelo, versaoDoModelo: "1" };
            return n.bruta ? [
              { ...metadados, id: n.bruta },
              { ...metadados, id: n.midia, origem: n.bruta, nome: `${n.rotulo} restaurada.wav`, restauracao: { intensidade, versao: 1 } },
            ] : [{ ...metadados, id: n.midia }];
          })],
          // A original fica, muda, como referência.
          faixas: [...p.faixas.slice(0, i).concat({ ...p.faixas[i], mudo: true }), ...criadas, ...p.faixas.slice(i + 1)],
          separacoes: [...p.separacoes, { id: novoId("sep"), midia: alvo.clipe.midia, modo, modelo, faixas: novas.map((n) => n.midia),
            perfilDeQualidade: roformer ? "maximum" : "legacy", perfilDeMascara: roformer ? undefined : mascara }],
        };
      });
      setMensagem(`Pronto: ${novas.length} faixas novas abaixo de “${alvo.faixa.nome}”, que ficou muda como referência.${limpar ? " O ruído de fundo foi reduzido em cada uma." : ""}`);
    } catch (e) {
      setMensagem(e instanceof Error ? e.message : String(e));
    } finally {
      descarga.current = null;
      if (gerenciaPlugin) void plugin.cliente.modelosMusicais().then(setNoPlugin).catch(() => undefined);
      setAndamento(null);
      separador.current = null;
      cancelarNoPlugin.current = null;
    }
  };

  return (
    <div className="musica__colunas">
      <section>
        <h3>Separar faixas por IA</h3>
        <label className="musica__campo">Modelo de separação
          <select value={perfil} disabled={!!andamento} onChange={(e) => setRoFormer(e.target.value === "maximum")}>
            <option value="legacy">Spleeter · padrão</option>
            <option value="maximum">RoFormer Mega 53 · experimental</option>
          </select>
        </label>
        {pluginSepara && (!roformer || pluginRoFormer) && <label className="estudio__chave"><input type="checkbox" checked={usarPlugin} disabled={!!andamento} onChange={(e) => setUsarPlugin(e.target.checked)} /> Usar o plugin local do Windows</label>}
        <p className="estudio__dica">{roformer
          ? `${delegar ? "357" : "97"} MB no disco. Separa guitarra e piano além de voz, bateria, baixo e resíduo. Pode melhorar voz e baixo. A qualidade da guitarra varia. Referência em CPU de PC: ${delegar ? "3,9 GiB de RAM e cerca de 5 min" : "2,9 GiB de RAM e cerca de 2 min"} para 8 s de áudio. O tempo e a RAM variam neste aparelho. Comece por um trecho curto.`
          : "78 MB no disco. Mais leve. Separa voz e instrumental ou quatro faixas, com guitarra e piano em Outros. Na bancada do PC, 8 s levaram cerca de 5 s e usaram cerca de 0,5 GiB de RAM."}</p>
        {(!delegar || gerenciaPlugin) && <div className="musica__linha">
          <span>{baixado ? "Modelo baixado" : "Disponível para baixar"}</span>
          <button type="button" className="button button--ghost" disabled={!!andamento} onClick={() => void atualizarModelo(baixado)}>{baixado ? "Excluir modelo baixado" : "Baixar modelo"}</button>
        </div>}
        {clipes.length === 0 ? <p className="estudio__dica">Importe ou grave um áudio primeiro.</p> : (
          <>
            <fieldset className="musica__escolha">
              <legend>1. Escolha o áudio</legend>
              {clipes.map(({ faixa, clipe }) => (
                <label key={clipe.id} className={`musica__opcao${alvo?.clipe.id === clipe.id ? " musica__opcao--marcada" : ""}`} title={`${faixa.nome} — ${clipe.nome ?? "Clipe"}`} aria-label={`${faixa.nome}, ${clipe.nome ?? "Clipe"}`}>
                  <input type="radio" name="audio-para-separar" checked={alvo?.clipe.id === clipe.id} onChange={() => setEscolhido(clipe.id)} />
                  <span className="musica__opcao-texto">
                    <strong>{faixa.nome}</strong>
                    <small>{clipe.nome ?? "Clipe"} · {(clipe.duracao / projeto.taxa).toFixed(1)} s</small>
                  </span>
                </label>
              ))}
            </fieldset>
            <label className="estudio__chave"><input type="checkbox" checked={!roformer && mascara === "refinada"} disabled={!!andamento || roformer}
              onChange={(e) => setMascara(e.target.checked ? "refinada" : "classica")} /> Refinar agudos (experimental)</label>
            <label className="musica__campo">Restauração por instrumento ({intensidade}%)
              <input type="range" min={0} max={100} value={intensidade} disabled={!!andamento} onChange={(e) => setIntensidade(Number(e.target.value))} />
            </label>
            <fieldset className="musica__escolha">
              <legend>2. Em quantas faixas</legend>
              {modos.map((m) => (
                <label key={m.id} className="estudio__chave"><input type="radio" name="modo-de-separacao" checked={modo === m.id} disabled={!!andamento} onChange={() => setModo(m.id)} /> {m.faixas.length} faixas: {m.rotulo}</label>
              ))}
              <small className="estudio__dica">As faixas novas entram com o nome do que são: {definicao.faixas.map((f) => nomeDaFaixaSeparada(f.rotulo, "…")).join(", ")}.</small>
            </fieldset>
            <label className="estudio__chave"><input type="checkbox" role="switch" checked={limpar} onChange={(e) => setLimpar(e.target.checked)} /> Reduzir ruído nas faixas separadas</label>
            <small className="estudio__dica">Tira o chiado e o ruído constante que a separação costuma deixar em cada faixa (mais útil na voz). Deixe desligado se a música original já é limpa.</small>
            <div className="musica__linha">
              <button type="button" className="button" onClick={() => void separar()} disabled={!!andamento}>Separar</button>
            </div>
          </>
        )}
        {andamento && (
          <div className="estudio__andamento" aria-live="polite">
            <span>{andamento.etapa}</span>
            <progress max={1} value={andamento.fracao} />
            <button type="button" className="button button--ghost" onClick={() => { descarga.current?.abort(); separador.current?.cancelar(); cancelarNoPlugin.current?.(); }}>Cancelar</button>
          </div>
        )}
        {mensagem && <p className="musica__aviso" role="status">{mensagem}</p>}
        <p className="estudio__dica">{roformer ? "Pode haver vazamento entre as faixas separadas. A faixa original fica no projeto para comparação." : AVISO_DA_SEPARACAO}</p>
        <p className="estudio__dica">Os pesos têm licença MIT. O áudio é processado neste aparelho.</p>
        <p className="estudio__dica">Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar.</p>
      </section>
    </div>
  );
}

export function PainelDeExportacao({ projeto, fontes, faixaSelecionada }: { projeto: ProjetoMusical; fontes: () => Promise<Map<string, FonteDeAudio>>; faixaSelecionada: string | null }) {
  const [formatos, setFormatos] = useState<string[]>(["wav-24"]);
  const [formato, setFormato] = useState("wav-24");
  const [master, setMaster] = useState("sem-alvo");
  const [alcance, setAlcance] = useState<"projeto" | "loop">("projeto");
  const [alvo, setAlvo] = useState<"mix" | "faixas" | "uma">("mix");
  const [cauda, setCauda] = useState(true);
  const [andamento, setAndamento] = useState<{ etapa: string; fracao: number } | null>(null);
  const [resultados, setResultados] = useState<(Exportado & { url: string })[]>([]);
  const [erro, setErro] = useState<string | null>(null);
  const cancelado = useRef(false);
  useEffect(() => {
    void formatosDisponiveis(projeto.taxa).then(setFormatos);
  }, [projeto.taxa]);
  useEffect(() => () => resultados.forEach((r) => URL.revokeObjectURL(r.url)), [resultados]);
  const faixa = projeto.faixas.find((f) => f.id === faixaSelecionada);

  const exportar = async () => {
    setErro(null);
    cancelado.current = false;
    try {
      const prontos = await exportarMusica(projeto, await fontes(), { formato, master, alcance, cadaFaixa: alvo === "faixas", cauda, faixa: alvo === "uma" ? faixaSelecionada : null }, (etapa, fracao) => setAndamento({ etapa, fracao }), () => cancelado.current);
      setResultados(prontos.map((r) => ({ ...r, url: URL.createObjectURL(r.blob) })));
      if (prontos.length === 1) {
        const a = document.createElement("a");
        a.href = URL.createObjectURL(prontos[0].blob);
        a.download = prontos[0].nome;
        a.click();
      }
    } catch (e) {
      setErro(e instanceof DOMException && e.name === "AbortError" ? "Exportação cancelada." : e instanceof Error ? e.message : String(e));
    } finally {
      setAndamento(null);
    }
  };

  return (
    <div className="musica__colunas">
      <section>
        <h3>Exportar</h3>
        <label className="musica__campo">O quê
          <select value={alvo} onChange={(e) => setAlvo(e.target.value as typeof alvo)}>
            <option value="mix">A mistura, com o master</option>
            <option value="faixas">Cada faixa num arquivo (stems, sem o master)</option>
            {faixa && <option value="uma">Só a faixa “{faixa.nome}”</option>}
          </select>
        </label>
        <label className="musica__campo">Formato
          <select value={formato} onChange={(e) => setFormato(e.target.value)}>{FORMATOS_DE_EXPORTACAO.map((f) => <option key={f.id} value={f.id} disabled={!formatos.includes(f.id)}>{f.rotulo}{formatos.includes(f.id) ? "" : " (este navegador não codifica; use o aplicativo ou o plugin)"}</option>)}</select>
        </label>
        <label className="musica__campo">Master
          <select value={master} onChange={(e) => setMaster(e.target.value)} disabled={alvo !== "mix"}>{PRESETS_DE_MASTER.map((p) => <option key={p.id} value={p.id}>{p.rotulo}</option>)}</select>
        </label>
        <label className="musica__campo">Trecho
          <select value={alcance} onChange={(e) => setAlcance(e.target.value as typeof alcance)}><option value="projeto">O projeto inteiro</option><option value="loop" disabled={!(projeto.loop.fim > projeto.loop.inicio)}>Só a região do loop</option></select>
        </label>
        <label className="estudio__chave"><input type="checkbox" checked={cauda} onChange={(e) => setCauda(e.target.checked)} /> Incluir a cauda do reverb e do delay</label>
        <div className="musica__linha">
          <button type="button" className="button" onClick={() => void exportar()} disabled={!!andamento}>Exportar</button>
          {andamento && <button type="button" className="button button--ghost" onClick={() => { cancelado.current = true; }}>Cancelar</button>}
        </div>
        {andamento && <div className="estudio__andamento" aria-live="polite"><span>{andamento.etapa}</span><progress max={1} value={andamento.fracao} /></div>}
        {erro && <p className="estudio__erro" role="alert">{erro}</p>}
        <p className="estudio__dica">WAV de 16 e 24 bits leva dither; o float32 não. Nenhum alvo de volume é imposto: escolha o master pela finalidade e confira a medida.</p>
      </section>
      {resultados.length > 0 && (
        <section>
          <h3>Arquivos</h3>
          <ul className="musica__resultados">
            {resultados.map((r) => (
              <li key={r.url}>
                <a href={r.url} download={r.nome}>{r.nome}</a> · {(r.blob.size / 1024 / 1024).toFixed(1)} MB
                {r.medidas && <small> · {Number.isFinite(r.medidas.lufs) ? `${r.medidas.lufs.toFixed(1)} LUFS` : "silêncio"} · pico real {Number.isFinite(r.medidas.realDb) ? `${r.medidas.realDb.toFixed(1)} dBTP` : "—"}{r.ganhoDb ? ` · ganho ${r.ganhoDb > 0 ? "+" : ""}${r.ganhoDb.toFixed(1)} dB` : ""}</small>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
