/** Progresso real por caracteres; OCR, download e inicialização ficam fora da amostra. */
export class ProgressoNarracao {
  constructor(tamanhos, relogio = () => performance.now()) {
    if (!tamanhos.length || tamanhos.some((n) => !Number.isFinite(n) || n <= 0)) throw new Error("Blocos inválidos.");
    this.tamanhos = [...tamanhos];
    this.total = tamanhos.reduce((a, b) => a + b, 0);
    this.relogio = relogio;
    this.inicio = relogio();
    this.concluidos = 0;
    this.caracteres = 0;
    this.amostras = [];
  }
  concluirBloco() {
    if (this.concluidos >= this.tamanhos.length) return;
    const agora = this.relogio();
    const tamanho = this.tamanhos[this.concluidos++];
    this.amostras.push({ ms: Math.max(1, agora - this.inicio), tamanho });
    if (this.amostras.length > 8) this.amostras.shift();
    this.caracteres += tamanho;
    this.inicio = agora;
  }
  retrato() {
    const fracao = this.caracteres / this.total;
    const ms = this.amostras.reduce((n, a) => n + a.ms, 0);
    if (this.concluidos < 3 || ms < 5000 || this.concluidos === this.tamanhos.length)
      return { fracao, segundosRestantes: null };
    const ritmo = ms / this.amostras.reduce((n, a) => n + a.tamanho, 0);
    const previsto = this.tamanhos[this.concluidos] * ritmo;
    const decorrido = Math.max(0, this.relogio() - this.inicio);
    if (decorrido > Math.max(30000, previsto * 2)) return { fracao, segundosRestantes: null };
    const restante = (this.total - this.caracteres - this.tamanhos[this.concluidos]) * ritmo +
      Math.max(previsto - decorrido, previsto * 0.2);
    return { fracao, segundosRestantes: Math.ceil(restante / 1000) };
  }
}
