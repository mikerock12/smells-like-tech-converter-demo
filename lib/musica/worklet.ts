/// <reference lib="webworker" />
/**
 * O processador de áudio da criação musical, dentro do AudioWorklet (a thread de áudio do
 * navegador). Toca a mistura com o mesmo mixer dos apps (musica-mixer.mjs), conta o tempo em
 * quadros (o relógio é a própria saída de áudio, nunca a interface) e devolve o que entra no
 * microfone, seco, em pedaços, junto com o quadro do transporte que saía naquele instante.
 *
 * Nada de rede, disco ou inferência aqui. As mídias chegam em SharedArrayBuffer (sem cópia).
 */

import { Misturador, fonteDaMemoria } from "@/packages/converter-core/musica-mixer.mjs";
import type { Clique, ProjetoMusical } from "@/packages/converter-core/musica.mjs";

declare const sampleRate: number;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}
declare function registerProcessor(nome: string, classe: new () => AudioWorkletProcessor): void;

type Mensagem =
  | { tipo: "projeto"; projeto: ProjetoMusical }
  | { tipo: "midia"; id: string; canais: Float32Array[] }
  | { tipo: "remover-midia"; id: string }
  | { tipo: "tocar"; de: number; metronomo: boolean }
  | { tipo: "gravar"; de: number; metronomo: boolean; contagem: Clique[]; fimDaContagem: number }
  | { tipo: "parar" }
  | { tipo: "posicionar"; quadro: number }
  | { tipo: "monitorar"; ligado: boolean };

const PEDACO = 4096;

class ProcessadorDaMusica extends AudioWorkletProcessor {
  private misturador = new Misturador(sampleRate);
  private projeto: ProjetoMusical | null = null;
  private tocando = false;
  private gravando = false;
  private metronomo = false;
  private monitorarParado = false;
  private cursor = 0;
  private primeiroQuadro = -1;
  private capturaE = new Float32Array(PEDACO);
  private capturaD = new Float32Array(PEDACO);
  private preenchido = 0;
  private entradaE = new Float32Array(128);
  private entradaD = new Float32Array(128);
  private blocos = 0;

  constructor() {
    super();
    this.port.onmessage = (evento: MessageEvent<Mensagem>) => this.receber(evento.data);
  }

  private receber(m: Mensagem) {
    switch (m.tipo) {
      case "projeto":
        this.projeto = m.projeto;
        this.misturador.definirProjeto(m.projeto);
        break;
      case "midia":
        this.misturador.definirFonte(m.id, fonteDaMemoria(m.canais));
        break;
      case "remover-midia":
        this.misturador.definirFonte(m.id, null);
        break;
      case "tocar":
        this.cursor = m.de;
        this.metronomo = m.metronomo;
        this.misturador.definirContagem([], -1);
        this.tocando = true;
        break;
      case "gravar":
        this.cursor = m.de;
        this.metronomo = m.metronomo;
        this.misturador.definirContagem(m.contagem, m.fimDaContagem);
        this.preenchido = 0;
        this.primeiroQuadro = -1;
        this.gravando = true;
        this.tocando = true;
        break;
      case "parar":
        this.enviarCaptura(true);
        this.tocando = false;
        this.gravando = false;
        this.misturador.definirContagem([], -1);
        this.port.postMessage({ tipo: "posicao", quadro: this.cursor, tocando: false });
        break;
      case "posicionar":
        this.cursor = Math.max(0, Math.round(m.quadro));
        this.port.postMessage({ tipo: "posicao", quadro: this.cursor, tocando: this.tocando });
        break;
      case "monitorar":
        this.monitorarParado = m.ligado;
        break;
    }
  }

  private enviarCaptura(fim: boolean) {
    if (this.preenchido > 0) {
      const e = this.capturaE.slice(0, this.preenchido);
      const d = this.capturaD.slice(0, this.preenchido);
      this.port.postMessage({ tipo: "captura", quadroAlinhado: this.primeiroQuadro, esq: e, dir: d }, [e.buffer, d.buffer]);
      this.preenchido = 0;
    }
    if (fim && this.gravando) this.port.postMessage({ tipo: "fim-da-captura", quadroAlinhado: this.primeiroQuadro });
  }

  process(entradas: Float32Array[][], saidas: Float32Array[][]): boolean {
    const saida = saidas[0];
    const esq = saida[0];
    const dir = saida[1] ?? saida[0];
    const n = esq.length;
    const entrada = entradas[0] ?? [];
    const temEntrada = entrada.length > 0 && entrada[0].length === n;
    if (temEntrada) {
      this.entradaE.set(entrada[0]);
      this.entradaD.set(entrada[1] ?? entrada[0]);
    } else {
      this.entradaE.fill(0);
      this.entradaD.fill(0);
    }
    if (!this.projeto || !this.tocando) {
      if (this.monitorarParado && temEntrada) {
        esq.set(this.entradaE);
        dir.set(this.entradaD);
      } else {
        esq.fill(0);
        dir.fill(0);
      }
      return true;
    }
    this.misturador.processar(this.cursor, n, esq, dir, { entrada: { esq: this.entradaE, dir: this.entradaD }, metronomo: this.metronomo });
    if (this.gravando) {
      // O primeiro quadro capturado entra junto com este bloco da saída: é a âncora da tomada.
      if (this.primeiroQuadro < 0) this.primeiroQuadro = this.cursor;
      this.capturaE.set(this.entradaE, this.preenchido);
      this.capturaD.set(this.entradaD, this.preenchido);
      this.preenchido += n;
      if (this.preenchido + n > PEDACO) this.enviarCaptura(false);
    }
    this.cursor += n;
    const loop = this.projeto.loop;
    if (!this.gravando && loop.ligado && loop.fim > loop.inicio && this.cursor >= loop.fim) this.cursor = loop.inicio;
    this.blocos += 1;
    if (this.blocos % 8 === 0) {
      const medidores: Record<string, number[]> = {};
      for (const [id, m] of this.misturador.medidores) medidores[id] = [m[0], m[1], m[2], m[3]];
      this.port.postMessage({ tipo: "posicao", quadro: this.cursor, tocando: true, medidores });
    }
    return true;
  }
}

registerProcessor("criacao-musical", ProcessadorDaMusica);
