/// <reference lib="webworker" />
/**
 * Escreve WAV float32 direto no armazenamento do navegador (OPFS), com acesso síncrono: cada
 * pedaço vai para o disco na hora e o `flush` a cada segundo deixa a tomada a salvo mesmo se a
 * aba fechar no meio. O cabeçalho só ganha o tamanho no fim; uma gravação interrompida abre
 * do mesmo jeito (lerWav usa o que existe no arquivo).
 */

import { cabecalhoWav } from "./wav-cabecalho";

type Pedido =
  | { tipo: "abrir"; id: number; caminho: string[]; canais: number; taxa: number }
  | { tipo: "escrever"; id: number; canais: Float32Array[] }
  | { tipo: "fechar"; id: number };

type Aberto = { alca: FileSystemSyncAccessHandle; canais: number; taxa: number; quadros: number; ultimoFlush: number };

const abertos = new Map<number, Aberto>();

async function pasta(caminho: string[]): Promise<FileSystemDirectoryHandle> {
  let atual = await navigator.storage.getDirectory();
  for (const parte of caminho) atual = await atual.getDirectoryHandle(parte, { create: true });
  return atual;
}

self.onmessage = async (evento: MessageEvent<Pedido>) => {
  const p = evento.data;
  try {
    if (p.tipo === "abrir") {
      const dir = await pasta(p.caminho.slice(0, -1));
      const arquivo = await dir.getFileHandle(p.caminho[p.caminho.length - 1], { create: true });
      const alca = await arquivo.createSyncAccessHandle();
      alca.truncate(0);
      alca.write(cabecalhoWav(p.canais, p.taxa, 32, 0), { at: 0 });
      abertos.set(p.id, { alca, canais: p.canais, taxa: p.taxa, quadros: 0, ultimoFlush: performance.now() });
      self.postMessage({ tipo: "aberto", id: p.id });
    } else if (p.tipo === "escrever") {
      const a = abertos.get(p.id);
      if (!a) return;
      const n = p.canais[0].length;
      const intercalado = new Float32Array(n * a.canais);
      for (let i = 0; i < n; i += 1) for (let c = 0; c < a.canais; c += 1) intercalado[i * a.canais + c] = p.canais[Math.min(c, p.canais.length - 1)][i];
      a.alca.write(new Uint8Array(intercalado.buffer), { at: 44 + a.quadros * a.canais * 4 });
      a.quadros += n;
      if (performance.now() - a.ultimoFlush > 1000) {
        a.alca.flush();
        a.ultimoFlush = performance.now();
      }
    } else if (p.tipo === "fechar") {
      const a = abertos.get(p.id);
      if (!a) return;
      a.alca.write(cabecalhoWav(a.canais, a.taxa, 32, a.quadros), { at: 0 });
      a.alca.flush();
      a.alca.close();
      abertos.delete(p.id);
      self.postMessage({ tipo: "fechado", id: p.id, quadros: a.quadros });
    }
  } catch (erro) {
    self.postMessage({ tipo: "erro", id: p.id, mensagem: erro instanceof Error ? erro.message : String(erro) });
  }
};
