/**
 * Importação de Web Worker do Vite.
 *
 * `?worker` devolve uma classe pronta e deixa o empacotador cuidar da URL — sem isso,
 * `new URL(..., import.meta.url)` acaba resolvido para `file://` durante a
 * transformação de servidor e o navegador recusa criar o worker.
 */
declare module "*?worker" {
  const WorkerFactory: new (options?: { name?: string }) => Worker;
  export default WorkerFactory;
}
// SPDX-License-Identifier: GPL-3.0-or-later — declarações do cliente navegador.
