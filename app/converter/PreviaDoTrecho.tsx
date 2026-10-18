"use client";

import { useEffect, useRef, useState } from "react";

import type { Opcoes } from "@/lib/converter/ferramentas";
import { escreverTempo, lerTempoEmSegundos } from "@/lib/converter/tempo";

/**
 * O mini player do corte, igual ao do app para Android e Windows: assiste (ou ouve) o
 * arquivo, marca início e fim no ponto em que está e toca só o trecho escolhido. O arquivo
 * é aberto direto do seu computador (um endereço blob: local); nada sai do navegador. Com
 * vários arquivos, a prévia mostra o primeiro e o corte vale para todos.
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
  const [falhou, setFalhou] = useState(false);
  const midia = useRef<HTMLVideoElement & HTMLAudioElement>(null);
  const pararEm = useRef<number | null>(null);

  // Um arquivo novo remonta o componente (key no cartão), então o estado já nasce zerado.
  useEffect(() => {
    const url = URL.createObjectURL(arquivo);
    queueMicrotask(() => setEndereco(url));
    return () => URL.revokeObjectURL(url);
  }, [arquivo]);

  // "Tocar o trecho" para sozinho no fim marcado; o timeupdate do navegador é lento demais.
  useEffect(() => {
    let quadro = 0;
    const acompanhar = () => {
      const elemento = midia.current;
      if (elemento && pararEm.current !== null && elemento.currentTime >= pararEm.current) {
        elemento.pause();
        pararEm.current = null;
      }
      quadro = requestAnimationFrame(acompanhar);
    };
    quadro = requestAnimationFrame(acompanhar);
    return () => cancelAnimationFrame(quadro);
  }, []);

  const inicio = Math.min(lerTempoEmSegundos(opcoes.inicio) ?? 0, duracao || Infinity);
  const fimDigitado = lerTempoEmSegundos(opcoes.fim);
  const fim = fimDigitado ?? duracao;
  const decimo = (segundos: number) => Math.round(segundos * 10) / 10;

  const irPara = (segundos: number) => {
    const elemento = midia.current;
    if (!elemento) return;
    elemento.currentTime = Math.max(0, Math.min(segundos, duracao));
    setPosicao(elemento.currentTime);
  };

  const marcarInicio = (segundos: number) => {
    const agora = decimo(segundos);
    // Início depois do fim marcado: o fim volta a ser o fim do arquivo.
    onChange({ ...opcoes, inicio: escreverTempo(agora), fim: fimDigitado !== null && fimDigitado <= agora ? "" : opcoes.fim ?? "" });
  };
  const marcarFim = (segundos: number) => {
    const agora = decimo(segundos);
    if (agora > inicio) onChange({ ...opcoes, fim: escreverTempo(agora) });
  };

  const tocarTrecho = () => {
    const elemento = midia.current;
    if (!elemento) return;
    irPara(inicio);
    pararEm.current = fim > inicio ? fim : null;
    void elemento.play().catch(() => undefined);
  };

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
    controls: true,
    preload: "metadata" as const,
    onLoadedMetadata: () => setDuracao(Number.isFinite(midia.current?.duration) ? (midia.current?.duration ?? 0) : 0),
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
    onSeeked: () => setPosicao(midia.current?.currentTime ?? 0),
    onError: () => setFalhou(true),
    className: "previa__midia",
  };

  return (
    <div className="previa field--wide">
      {/* Prévia do arquivo da própria pessoa: não existe legenda para mostrar aqui. */}
      {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
      {video ? <video {...comum} playsInline /> : <audio {...comum} />}
      {duracao > 0 && (
        <>
          <p className="previa__resumo">
            {escreverTempo(posicao)} / {escreverTempo(duracao)} · Trecho: {escreverTempo(inicio)} até {escreverTempo(fim)} ({escreverTempo(Math.max(0, fim - inicio))})
          </p>
          <div className="previa__faixas">
            <label className="field">
              <span className="field__label">Início do trecho</span>
              <input
                type="range" min={0} max={duracao} step={0.1} value={inicio} disabled={disabled}
                onChange={(evento) => { const valor = Number(evento.target.value); irPara(valor); marcarInicio(valor); }}
              />
            </label>
            <label className="field">
              <span className="field__label">Fim do trecho</span>
              <input
                type="range" min={0} max={duracao} step={0.1} value={fim} disabled={disabled}
                onChange={(evento) => { const valor = Number(evento.target.value); irPara(valor); marcarFim(valor); }}
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
          </div>
        </>
      )}
    </div>
  );
}
