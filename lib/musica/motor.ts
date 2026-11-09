"use client";

import type { Clique, ProjetoMusical } from "@/packages/converter-core/musica.mjs";

/**
 * O motor de áudio do navegador para a criação musical: um AudioContext a 48 kHz e o
 * AudioWorklet que mistura (lib/musica/worklet.ts). O relógio é o do áudio: a posição vem
 * do worklet, contada em quadros que saíram, nunca de timers da página.
 *
 * Gravar: o microfone entra pelo mesmo worklet, sem AGC, cancelamento de eco ou supressão de
 * ruído quando o navegador deixa (getSettings diz o que valeu). A tomada vem em pedaços com
 * o quadro do transporte que saía no primeiro deles; a compensação de latência é do núcleo.
 */

export type EstadoDoMotor = { quadro: number; tocando: boolean; medidores: Record<string, number[]> };

export type PedacoCapturado = { esq: Float32Array; dir: Float32Array };

export type ResultadoDaEntrada = {
  dispositivo: string;
  canais: number;
  semProcessamento: boolean;
  avisos: string[];
  latenciaDeEntrada: number;
};

const urlDoWorklet = async () => (await import("./worklet.ts?worker&url")).default;

export class MotorDaMusica {
  readonly contexto: AudioContext;
  private no: AudioWorkletNode | null = null;
  private fonteDoMicrofone: MediaStreamAudioSourceNode | null = null;
  private fluxo: MediaStream | null = null;
  private ouvintes = new Set<(estado: EstadoDoMotor) => void>();
  private aoCapturar: ((pedaco: PedacoCapturado, quadroAlinhado: number) => void) | null = null;
  private aoTerminarCaptura: ((quadroAlinhado: number) => void) | null = null;
  private enviadas = new Set<string>();
  estado: EstadoDoMotor = { quadro: 0, tocando: false, medidores: {} };

  constructor(taxa: number) {
    this.contexto = new AudioContext({ sampleRate: taxa, latencyHint: "interactive" });
  }

  async iniciar(): Promise<void> {
    if (this.no) return;
    await this.contexto.audioWorklet.addModule(await urlDoWorklet());
    this.no = new AudioWorkletNode(this.contexto, "criacao-musical", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
    this.no.connect(this.contexto.destination);
    this.no.port.onmessage = (evento) => this.receber(evento.data);
  }

  private receber(m: { tipo: string; quadro?: number; tocando?: boolean; medidores?: Record<string, number[]>; esq?: Float32Array; dir?: Float32Array; quadroAlinhado?: number }) {
    if (m.tipo === "posicao") {
      this.estado = { quadro: m.quadro ?? 0, tocando: m.tocando ?? false, medidores: m.medidores ?? this.estado.medidores };
      for (const ouvinte of this.ouvintes) ouvinte(this.estado);
    } else if (m.tipo === "captura" && m.esq && m.dir) {
      this.aoCapturar?.({ esq: m.esq, dir: m.dir }, m.quadroAlinhado ?? 0);
    } else if (m.tipo === "fim-da-captura") {
      this.aoTerminarCaptura?.(m.quadroAlinhado ?? 0);
    }
  }

  ouvir(ouvinte: (estado: EstadoDoMotor) => void): () => void {
    this.ouvintes.add(ouvinte);
    return () => this.ouvintes.delete(ouvinte);
  }

  private enviar(mensagem: object, transferir: Transferable[] = []) {
    this.no?.port.postMessage(mensagem, transferir);
  }

  definirProjeto(projeto: ProjetoMusical) {
    this.enviar({ tipo: "projeto", projeto });
  }

  /** Canais em SharedArrayBuffer não são copiados; os comuns são copiados para o worklet. */
  definirMidia(id: string, canais: Float32Array[]) {
    this.enviar({ tipo: "midia", id, canais });
    this.enviadas.add(id);
  }

  removerMidia(id: string) {
    this.enviar({ tipo: "remover-midia", id });
    this.enviadas.delete(id);
  }

  temMidia(id: string) {
    return this.enviadas.has(id);
  }

  async tocar(de: number, metronomo: boolean) {
    await this.contexto.resume();
    this.enviar({ tipo: "tocar", de, metronomo });
  }

  parar() {
    this.enviar({ tipo: "parar" });
  }

  posicionar(quadro: number) {
    this.enviar({ tipo: "posicionar", quadro });
  }

  monitorarParado(ligado: boolean) {
    this.enviar({ tipo: "monitorar", ligado });
  }

  /** Latência de saída que o navegador informa, em quadros. */
  latenciaDeSaida(): number {
    const c = this.contexto as AudioContext & { outputLatency?: number };
    return Math.round(((c.outputLatency ?? 0) + (c.baseLatency ?? 0)) * this.contexto.sampleRate);
  }

  /** Lista as entradas de áudio (os nomes só aparecem depois da permissão do microfone). */
  static async entradas(): Promise<MediaDeviceInfo[]> {
    if (!navigator.mediaDevices?.enumerateDevices) return [];
    return (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "audioinput");
  }

  /** Abre o microfone escolhido, sem processamento de voz, e liga ao worklet. */
  async abrirEntrada(dispositivo: string | null, canais: number): Promise<ResultadoDaEntrada> {
    await this.iniciar();
    this.fecharEntrada();
    if (!navigator.mediaDevices?.getUserMedia) throw new Error("Este navegador não grava áudio. Use o Chrome, o Edge, o Firefox ou o Safari atualizados.");
    const fluxo = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: dispositivo ? { exact: dispositivo } : undefined,
        channelCount: { ideal: canais },
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
        sampleRate: { ideal: this.contexto.sampleRate },
      },
    });
    this.fluxo = fluxo;
    const trilha = fluxo.getAudioTracks()[0];
    const ajustes = trilha.getSettings() as MediaTrackSettings & { latency?: number };
    const avisos: string[] = [];
    if (ajustes.echoCancellation) avisos.push("O navegador manteve o cancelamento de eco ligado.");
    if (ajustes.noiseSuppression) avisos.push("O navegador manteve a supressão de ruído ligada.");
    if (ajustes.autoGainControl) avisos.push("O navegador manteve o controle automático de ganho ligado.");
    if (canais === 2 && (ajustes.channelCount ?? 1) < 2) avisos.push("Esta entrada só entregou um canal (mono).");
    this.fonteDoMicrofone = this.contexto.createMediaStreamSource(fluxo);
    this.fonteDoMicrofone.connect(this.no!);
    return {
      dispositivo: trilha.label || "Microfone",
      canais: ajustes.channelCount ?? 1,
      semProcessamento: avisos.filter((a) => !a.includes("canal")).length === 0,
      avisos,
      latenciaDeEntrada: Math.round((ajustes.latency ?? 0.01) * this.contexto.sampleRate),
    };
  }

  fecharEntrada() {
    this.fonteDoMicrofone?.disconnect();
    this.fonteDoMicrofone = null;
    for (const trilha of this.fluxo?.getTracks() ?? []) trilha.stop();
    this.fluxo = null;
  }

  get entradaAberta() {
    return this.fluxo !== null;
  }

  /**
   * Grava a partir de `de` (transporte), com os cliques da contagem. `aoCapturar` recebe os
   * pedaços na ordem; `aoTerminar` vem depois de `parar()`.
   */
  async gravar(de: number, metronomo: boolean, contagem: Clique[], fimDaContagem: number, aoCapturar: (pedaco: PedacoCapturado, quadroAlinhado: number) => void, aoTerminar: (quadroAlinhado: number) => void) {
    if (!this.fonteDoMicrofone) throw new Error("Escolha a entrada de áudio antes de gravar.");
    await this.contexto.resume();
    this.aoCapturar = aoCapturar;
    this.aoTerminarCaptura = (q) => {
      this.aoCapturar = null;
      this.aoTerminarCaptura = null;
      aoTerminar(q);
    };
    this.enviar({ tipo: "gravar", de, metronomo, contagem, fimDaContagem });
  }

  async fechar() {
    this.fecharEntrada();
    this.no?.disconnect();
    await this.contexto.close();
  }
}

/** Canais num SharedArrayBuffer, para o worklet ler sem cópia (a página é isolada: COOP/COEP). */
export function canaisCompartilhados(canais: Float32Array[]): Float32Array[] {
  if (typeof SharedArrayBuffer === "undefined" || !globalThis.crossOriginIsolated) return canais;
  return canais.map((c) => {
    const compartilhado = new Float32Array(new SharedArrayBuffer(c.length * 4));
    compartilhado.set(c);
    return compartilhado;
  });
}
