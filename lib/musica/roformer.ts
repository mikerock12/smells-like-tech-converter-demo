import { MODO_ROFORMER_EXPERIMENTAL } from "@/packages/converter-core/musica-modelos.mjs";

const bloco = 220500;
const passo = bloco / 2;
const transicao = bloco / 10;

export async function separarRoFormer(
  canais: Float32Array[], nucleo: { criarStftComplexa: (janela: number, passo: number, comprimento: number) => {
    analisar: (e: Float32Array, d: Float32Array) => Float32Array;
    sintetizar: (mascara: Float32Array, zerarDc: boolean) => Float32Array[];
    fechar: () => void;
  } }, executar: (espectro: Float32Array) => Promise<Float32Array>, progresso: (f: number) => void,
) {
  const comprimento = canais[0].length;
  if (!comprimento) throw new Error("O áudio está vazio.");
  const margem = Math.min(passo, comprimento - 1);
  const fim = comprimento + margem;
  const faixas = MODO_ROFORMER_EXPERIMENTAL.faixas.map((f) => ({ ...f, canais: [new Float32Array(comprimento), new Float32Array(comprimento)] }));
  const pesos = new Float32Array(comprimento);
  const pesosDoBloco = new Float32Array(bloco);
  const pcm = [new Float32Array(bloco), new Float32Array(bloco)];
  const stft = nucleo.criarStftComplexa(2048, 512, bloco);
  try {
    for (let inicio = -margem; inicio < fim; inicio += passo) {
      const validos = Math.min(bloco, fim - inicio);
      for (let i = 0; i < validos; i++) {
        let pos = Math.abs(inicio + i);
        if (pos >= comprimento) pos = 2 * (comprimento - 1) - pos;
        pcm[0][i] = canais[0][pos];
        pcm[1][i] = (canais[1] ?? canais[0])[pos];
      }
      for (const canal of pcm) {
        if (validos > bloco - validos) {
          for (let i = validos; i < bloco; i++) canal[i] = canal[2 * (validos - 1) - i];
        } else canal.fill(0, validos);
      }
      const primeiro = Math.max(0, -inicio);
      const ultimo = Math.min(validos, comprimento - inicio);
      for (let i = primeiro; i < ultimo; i++) {
        const entrada = inicio > -margem ? i / (transicao - 1) : 1;
        const saida = inicio + validos < fim ? (bloco - 1 - i) / (transicao - 1) : 1;
        pesosDoBloco[i] = Math.min(1, entrada, saida);
        pesos[inicio + i] += pesosDoBloco[i];
      }
      const espectro = stft.analisar(pcm[0], pcm[1]);
      const mascaras = await executar(espectro);
      if (mascaras.length !== 5 * espectro.length) throw new Error("O formato de saída do RoFormer não confere.");
      for (let f = 0; f < 5; f++) {
        const pronto = stft.sintetizar(mascaras.subarray(f * espectro.length, (f + 1) * espectro.length), true);
        for (let c = 0; c < 2; c++) {
          for (let i = primeiro; i < ultimo; i++) faixas[f].canais[c][inicio + i] += pronto[c][i] * pesosDoBloco[i];
        }
      }
      progresso(Math.min(1, (inicio + passo) / comprimento));
    }
    for (let c = 0; c < 2; c++) for (let i = 0; i < comprimento; i++) {
      let residuo = (canais[c] ?? canais[0])[i];
      for (let f = 0; f < 5; f++) {
        const valor = faixas[f].canais[c][i] / pesos[i];
        if (!Number.isFinite(valor)) throw new Error("O RoFormer devolveu uma amostra inválida.");
        faixas[f].canais[c][i] = valor;
        residuo -= valor;
      }
      faixas[5].canais[c][i] = residuo;
    }
    return faixas;
  } finally { stft.fechar(); }
}
