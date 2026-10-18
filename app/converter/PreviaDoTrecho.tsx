"use client";

import { useEffect, useRef, useState } from "react";

import type { Opcoes } from "@/lib/converter/ferramentas";
import { escreverTempo, escreverTempoCompleto, lerTempoEmSegundos, milesimo } from "@/lib/converter/tempo";

/** Velocidades da prévia: devagar para achar o quadro exato, rápido para passar o resto. */
const VELOCIDADES = [0.25, 0.5, 0.75, 1, 1.5, 2];

/** Sem informação melhor, o passo de um quadro considera 30 quadros por segundo. */
const QUADROS_PADRAO = 30;

type VideoComQuadros = HTMLVideoElement & {
  requestVideoFrameCallback?: (callback: (agora: number, dados: { mediaTime: number }) => void) => number;
  cancelVideoFrameCallback?: (id: number) => void;
};

/**
 * O player do corte, igual ao do app para Android e Windows: linha do tempo em
 * hh:mm:ss,mmm, pausa, um quadro para trás e para a frente, velocidade de 0,25x a 2x, marcação
 * de início e fim no ponto exato e "Tocar o trecho". Abre em tela cheia com os mesmos controles
 * embaixo. O arquivo é aberto direto do seu computador (um endereço blob: local); nada sai do
 * navegador. Com vários arquivos, a prévia mostra o primeiro e o corte vale para todos.
 */
export default function PreviaDoTrecho({
  arquivo,
  video,
  opcoes,
  onChange,
  disabled = false,
}: {
  arquivo: File;
  video: boolean;
  opcoes: Opcoes;
  onChange: (opcoes: Opcoes) => void;
  disabled?: boolean;
}) {
  const [endereco, setEndereco] = useState<string | null>(null);
  const [duracao, setDuracao] = useState(0);
  const [posicao, setPosicao] = useState(0);
  const [tocando, setTocando] = useState(false);
  const [velocidade, setVelocidade] = useState(1);
  const [telaCheia, setTelaCheia] = useState(false);
  // Quando o navegador não deixa (iPhone, páginas embutidas), a prévia ocupa a janela por CSS.
  const [janelaInteira, setJanelaInteira] = useState(false);
  const [falhou, setFalhou] = useState(false);
  const caixa = useRef<HTMLDivElement>(null);
  const midia = useRef<VideoComQuadros & HTMLAudioElement>(null);
  const pararEm = useRef<number | null>(null);
  const quadrosPorSegundo = useRef(QUADROS_PADRAO);

  // Um arquivo novo remonta o componente (key no cartão), então o estado já nasce zerado.
  useEffect(() => {
    const url = URL.createObjectURL(arquivo);
    queueMicrotask(() => setEndereco(url));
    return () => URL.revokeObjectURL(url);
  }, [arquivo]);

  useEffect(() => {
    const mudou = () => setTelaCheia(document.fullscreenElement === caixa.current);
    document.addEventListener("fullscreenchange", mudou);
    return () => document.removeEventListener("fullscreenchange", mudou);
  }, []);

  // Posição contínua e parada no fim do trecho; o timeupdate do navegador é lento demais.
  useEffect(() => {
    let quadro = 0;
    const acompanhar = () => {
      const elemento = midia.current;
      if (elemento) {
        if (pararEm.current !== null && elemento.currentTime >= pararEm.current) {
          elemento.pause();
          pararEm.current = null;
        }
        if (!elemento.paused) setPosicao(elemento.currentTime);
      }
      quadro = requestAnimationFrame(acompanhar);
    };
    quadro = requestAnimationFrame(acompanhar);
    return () => cancelAnimationFrame(quadro);
  }, []);

  // A taxa de quadros real, medida pelos quadros que o navegador entrega enquanto toca.
  useEffect(() => {
    const elemento = midia.current;
    if (!video || !endereco || !elemento?.requestVideoFrameCallback) return;
    let anterior: number | null = null;
    const intervalos: number[] = [];
    let id = 0;
    const medir = (_agora: number, dados: { mediaTime: number }) => {
      if (anterior !== null) {
        const intervalo = dados.mediaTime - anterior;
        if (intervalo > 0.002 && intervalo < 0.2) {
          intervalos.push(intervalo);
          if (intervalos.length > 30) intervalos.shift();
          const ordenados = [...intervalos].sort((a, b) => a - b);
          const mediana = ordenados[Math.floor(ordenados.length / 2)];
          if (intervalos.length >= 5) quadrosPorSegundo.current = Math.min(240, Math.max(1, Math.round(1 / mediana)));
        }
      }
      anterior = dados.mediaTime;
      id = elemento.requestVideoFrameCallback!(medir);
    };
    id = elemento.requestVideoFrameCallback(medir);
    return () => elemento.cancelVideoFrameCallback?.(id);
  }, [video, endereco]);

  const inicio = Math.min(lerTempoEmSegundos(opcoes.inicio) ?? 0, duracao || Infinity);
  const fimDigitado = lerTempoEmSegundos(opcoes.fim);
  const fim = fimDigitado ?? duracao;

  const irPara = (segundos: number) => {
    const elemento = midia.current;
    if (!elemento) return;
    elemento.currentTime = Math.max(0, Math.min(segundos, duracao));
    setPosicao(elemento.currentTime);
  };

  const tocar = () => {
    const elemento = midia.current;
    if (!elemento) return;
    elemento.playbackRate = velocidade;
    void elemento.play().catch(() => undefined);
  };

  const pausar = () => {
    midia.current?.pause();
    pararEm.current = null;
  };

  const quadro = (sentido: -1 | 1) => {
    const elemento = midia.current;
    if (!elemento) return;
    pausar();
    irPara(elemento.currentTime + sentido / quadrosPorSegundo.current);
  };

  const marcarInicio = (segundos: number) => {
    const agora = milesimo(segundos);
    // Início depois do fim marcado: o fim volta a ser o fim do arquivo.
    onChange({ ...opcoes, inicio: escreverTempo(agora), fim: fimDigitado !== null && fimDigitado <= agora ? "" : opcoes.fim ?? "" });
  };
  const marcarFim = (segundos: number) => {
    const agora = milesimo(segundos);
    if (agora > inicio) onChange({ ...opcoes, fim: escreverTempo(agora) });
  };

  const tocarTrecho = () => {
    irPara(inicio);
    pararEm.current = fim > inicio ? fim : null;
    tocar();
  };

  const mudarVelocidade = (nova: number) => {
    setVelocidade(nova);
    if (midia.current) midia.current.playbackRate = nova;
  };

  const alternarTelaCheia = () => {
    if (janelaInteira) setJanelaInteira(false);
    else if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
    else if (caixa.current?.requestFullscreen) {
      void caixa.current.requestFullscreen().catch(() => setJanelaInteira(true));
      // Alguns navegadores embutidos nem aceitam nem recusam: sem resposta, ocupa a janela.
      setTimeout(() => { if (!document.fullscreenElement) setJanelaInteira(true); }, 600);
    } else setJanelaInteira(true);
  };
  const ampliada = telaCheia || janelaInteira;

  // Em tela cheia: setas andam um quadro, espaço toca ou pausa (Esc sai, pelo navegador). A
  // última versão do atalho fica numa ref para a escuta do documento não precisar ser refeita.
  const atalho = useRef<(evento: globalThis.KeyboardEvent) => void>(() => undefined);
  useEffect(() => {
    atalho.current = (evento) => {
      const alvo = (evento.target as HTMLElement | null)?.tagName;
      if (alvo === "INPUT" || alvo === "SELECT") return;
      if (evento.key === "Escape" && janelaInteira) setJanelaInteira(false);
      else if (evento.key === "ArrowLeft" && video) quadro(-1);
      else if (evento.key === "ArrowRight" && video) quadro(1);
      else if (evento.key === " ") (tocando ? pausar : tocar)();
      else return;
      evento.preventDefault();
    };
  });
  useEffect(() => {
    if (!telaCheia && !janelaInteira) return;
    const ouvir = (evento: globalThis.KeyboardEvent) => atalho.current(evento);
    document.addEventListener("keydown", ouvir);
    return () => document.removeEventListener("keydown", ouvir);
  }, [telaCheia, janelaInteira]);

  if (!endereco) return null;
  if (falhou) {
    return (
      <p className="notice field--wide">
        O navegador não consegue mostrar a prévia deste arquivo, mas o corte funciona pelos campos.
      </p>
    );
  }

  const comum = {
    ref: midia,
    src: endereco,
    preload: "metadata" as const,
    onLoadedMetadata: () => setDuracao(Number.isFinite(midia.current?.duration) ? (midia.current?.duration ?? 0) : 0),
    onPlay: () => setTocando(true),
    onPause: () => setTocando(false),
    onSeeked: () => setPosicao(midia.current?.currentTime ?? 0),
    onTimeUpdate: () => {
      const elemento = midia.current;
      if (!elemento) return;
      setPosicao(elemento.currentTime);
      // Também aqui: com a aba em segundo plano o navegador quase não roda animationFrame.
      if (pararEm.current !== null && elemento.currentTime >= pararEm.current) {
        elemento.pause();
        pararEm.current = null;
      }
    },
    onError: () => setFalhou(true),
    className: "previa__midia",
  };

  return (
    <div className={`previa field--wide${ampliada ? " previa--tela-cheia" : ""}${janelaInteira ? " previa--janela-inteira" : ""}`} ref={caixa}>
      {/* Prévia do arquivo da própria pessoa: não existe legenda para mostrar aqui. */}
      {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
      {video ? <video {...comum} playsInline onClick={() => (tocando ? pausar() : tocar())} /> : <audio {...comum} />}
      {duracao > 0 && (
        <div className="previa__controles">
          <div className="previa__linha">
            <span className="previa__tempo">
              {escreverTempoCompleto(posicao)} / {escreverTempoCompleto(duracao)}
            </span>
            <input
              type="range" min={0} max={duracao} step={0.001} value={posicao} aria-label="Posição"
              onChange={(evento) => { pausar(); irPara(Number(evento.target.value)); }}
            />
          </div>
          <div className="previa__botoes">
            {video && (
              <button type="button" className="button" onClick={() => quadro(-1)} title="Volta um quadro (seta para a esquerda na tela cheia)">
                ◀ Quadro
              </button>
            )}
            <button type="button" className="button" onClick={() => (tocando ? pausar() : tocar())}>
              {tocando ? "Pausar" : "Tocar"}
            </button>
            {video && (
              <button type="button" className="button" onClick={() => quadro(1)} title="Avança um quadro (seta para a direita na tela cheia)">
                Quadro ▶
              </button>
            )}
            <label className="previa__velocidade">
              <span>Velocidade</span>
              <select value={velocidade} onChange={(evento) => mudarVelocidade(Number(evento.target.value))}>
                {VELOCIDADES.map((valor) => (
                  <option key={valor} value={valor}>
                    {String(valor).replace(".", ",")}x
                  </option>
                ))}
              </select>
            </label>
          </div>
          <p className="previa__resumo">
            Trecho: {escreverTempoCompleto(inicio)} até {escreverTempoCompleto(fim)} · {escreverTempoCompleto(Math.max(0, fim - inicio))}
          </p>
          <div className="previa__faixas">
            <label className="field">
              <span className="field__label">Início do trecho</span>
              <input
                type="range" min={0} max={duracao} step={0.001} value={inicio} disabled={disabled}
                onChange={(evento) => { const valor = Number(evento.target.value); pausar(); irPara(valor); marcarInicio(valor); }}
              />
            </label>
            <label className="field">
              <span className="field__label">Fim do trecho</span>
              <input
                type="range" min={0} max={duracao} step={0.001} value={fim} disabled={disabled}
                onChange={(evento) => { const valor = Number(evento.target.value); pausar(); irPara(valor); marcarFim(valor); }}
              />
            </label>
          </div>
          <div className="previa__botoes">
            <button type="button" className="button" onClick={() => marcarInicio(midia.current?.currentTime ?? 0)} disabled={disabled}>
              Início aqui
            </button>
            <button type="button" className="button" onClick={() => marcarFim(midia.current?.currentTime ?? 0)} disabled={disabled}>
              Fim aqui
            </button>
            <button type="button" className="button" onClick={tocarTrecho} disabled={disabled}>
              Tocar o trecho
            </button>
            {video && (
              <button type="button" className="button" onClick={alternarTelaCheia}>
                {ampliada ? "Sair da tela cheia" : "Tela cheia"}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
