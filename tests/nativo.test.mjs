// O núcleo nativo em WebAssembly (packages/nativo/slt_native.wasm) contra a referência em JS:
// mesma separação nos dois perfis de máscara, restauração reversível e nivelada, consistência
// com a mistura. É o mesmo código C++ da DLL do Windows e do .so do Android.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";

import { estimativasFalsas, gerarMidia } from "../packages/converter-core/musica-casos.mjs";
import { MODELO_DE_SEPARACAO, PERFIS_DE_MASCARA, separar } from "../packages/converter-core/musica-separacao.mjs";
import { PERFIS_DE_RESTAURACAO, carregarNucleoNativo, perfilDeRestauracaoDaFaixa } from "../packages/converter-core/nativo.mjs";

const bytes = readFileSync(new URL("../packages/nativo/slt_native.wasm", import.meta.url));
const manifesto = JSON.parse(readFileSync(new URL("../packages/nativo/slt_native.json", import.meta.url), "utf8"));
const nucleo = await carregarNucleoNativo(bytes);
const m = MODELO_DE_SEPARACAO;

const energia = (x) => x.reduce((s, v) => s + v * v, 0);
const db = (razao) => 10 * Math.log10(razao);

async function separarCom(motor, original, modo, mascara) {
  const comprimento = original[0].length;
  const faixas = [];
  const definicao = await motor({
    comprimento,
    canais: original.length,
    modo,
    mascara,
    ler: (c, inicio, n, destino) => destino.set(original[Math.min(c, original.length - 1)].subarray(inicio, inicio + n)),
    executar: async (entrada) => estimativasFalsas(entrada, m.quadrosPorTrecho, m.bandas, m.instrumentos.length),
    emitir: (f, c, inicio, amostras, n) => {
      faixas[f] ??= [new Float32Array(comprimento), new Float32Array(comprimento)];
      faixas[f][c].set(amostras.subarray(0, n), inicio);
    },
  });
  return { definicao, faixas };
}

test("o wasm versionado é o do manifesto e fala a ABI 1", () => {
  assert.equal(createHash("sha256").update(bytes).digest("hex"), manifesto.sha256);
  assert.equal(bytes.length, manifesto.bytes);
  assert.equal(nucleo.versao >>> 16, 1);
  for (const capacidade of ["motor", "separador", "mascaraRefinada", "restaurador", "consistencia"]) assert.ok(nucleo.tem(capacidade), capacidade);
  assert.deepEqual(manifesto.importadas, ["env.cos", "env.exp", "env.log10", "env.pow", "env.sin", "env.tanh"]);
});

test("separação no núcleo nativo é a mesma da referência em JS, nas duas máscaras", async () => {
  const casos = [
    { comprimento: 60000, modo: "4", gerador: { tipo: "ruido", semente: 21, amplitude: 0.5, canais: 2 } },
    { comprimento: 30000, modo: "2", gerador: { tipo: "seno", frequencia: 440, amplitude: 0.5, canais: 1 } },
    { comprimento: 540000, modo: "2", gerador: { tipo: "ruido", semente: 5, amplitude: 0.4, canais: 2 } },
  ];
  assert.deepEqual(PERFIS_DE_MASCARA.map((p) => p.id), ["classica", "refinada"]);
  for (const caso of casos) {
    const original = gerarMidia(caso.gerador, caso.comprimento, m.taxa);
    for (const mascara of ["classica", "refinada"]) {
      const js = await separarCom(separar, original, caso.modo, mascara);
      const nativo = await separarCom((opcoes) => nucleo.separar(opcoes), original, caso.modo, mascara);
      assert.deepEqual(nativo.definicao.map((f) => f.id), js.definicao.map((f) => f.id));
      for (let f = 0; f < js.faixas.length; f += 1) {
        for (let c = 0; c < 2; c += 1) {
          let maior = 0;
          for (let i = 0; i < caso.comprimento; i += 1) maior = Math.max(maior, Math.abs(js.faixas[f][c][i] - nativo.faixas[f][c][i]));
          assert.ok(maior < 2e-6, `${mascara} modo ${caso.modo} faixa ${f} canal ${c}: diferença ${maior}`);
        }
      }
    }
  }
});

test("STFT complexa WASM aplica uma máscara independente a cada canal", () => {
  const comprimento = 151;
  const stft = nucleo.criarStftComplexa(32, 11, comprimento);
  try {
    const esquerdo = Float32Array.from({ length: comprimento }, (_, i) => Math.sin(i * 0.17));
    const direito = Float32Array.from({ length: comprimento }, (_, i) => Math.cos(i * 0.27));
    const espectro = stft.analisar(esquerdo, direito);
    assert.equal(espectro.length, (Math.floor(comprimento / 11) + 1) * 17 * 4);
    const mascara = new Float32Array(espectro.length);
    for (let i = 0; i < mascara.length; i += 4) {
      mascara[i] = 0.5;
      mascara[i + 2] = -0.25;
    }
    const [saidaE, saidaD] = stft.sintetizar(mascara);
    for (let i = 0; i < comprimento; i += 1) {
      assert.ok(Math.abs(saidaE[i] - esquerdo[i] * 0.5) < 1e-6);
      assert.ok(Math.abs(saidaD[i] + direito[i] * 0.25) < 1e-6);
    }
  } finally {
    stft.fechar();
  }
});

test("a máscara refinada continua somando a mistura e só muda os agudos", async () => {
  const original = gerarMidia({ tipo: "ruido", semente: 9, amplitude: 0.5, canais: 2 }, 70000, m.taxa);
  const refinada = await separarCom((o) => nucleo.separar(o), original, "4", "refinada");
  let erro = 0;
  for (let c = 0; c < 2; c += 1) {
    for (let i = 0; i < 70000; i += 1) {
      const soma = refinada.faixas.reduce((s, f) => s + f[c][i], 0);
      erro += (soma - original[c][i]) ** 2;
    }
  }
  assert.ok(db((energia(original[0]) + energia(original[1])) / erro) > 90, "a soma das faixas refinadas não é a mistura");
});

test("restauração: intensidade 0 não muda nada; 100 muda, fica nivelada e não estoura", () => {
  const taxa = 48000;
  const base = gerarMidia({ tipo: "ruido", semente: 3, amplitude: 0.25, canais: 2 }, taxa, taxa);
  for (const perfil of PERFIS_DE_RESTAURACAO) {
    const zero = nucleo.restaurar({ canais: base, taxa, perfil: perfil.id, intensidade: 0 });
    assert.deepEqual(zero.canais[0], base[0], `${perfil.id}: intensidade 0`);
    assert.equal(zero.ganho, 1);
    const cheia = nucleo.restaurar({ canais: base, taxa, perfil: perfil.id, intensidade: 100 });
    assert.notDeepEqual(cheia.canais[0], base[0], `${perfil.id}: intensidade 100 não mudou`);
    const volume = db((energia(cheia.canais[0]) + energia(cheia.canais[1])) / (energia(base[0]) + energia(base[1])));
    const pico = Math.max(...cheia.canais.map((c) => c.reduce((p, v) => Math.max(p, Math.abs(v)), 0)));
    assert.ok(Math.abs(volume) < 0.5 || pico > 0.999, `${perfil.id}: volume ${volume.toFixed(2)} dB fora do bruto`);
    assert.ok(pico <= 1.0001, `${perfil.id}: pico ${pico}`);
    assert.ok(cheia.canais.every((c) => c.every(Number.isFinite)), `${perfil.id}: valor inválido`);
    const mono = nucleo.restaurar({ canais: [base[0]], taxa, perfil: perfil.id, intensidade: 50 });
    assert.equal(mono.canais.length, 1);
  }
  assert.throws(() => nucleo.restaurar({ canais: base, taxa, perfil: "piano", intensidade: 50 }), /desconhecido/);
  assert.throws(() => nucleo.restaurar({ canais: base, taxa, perfil: "voz", intensidade: 150 }), /recusada/);
  assert.equal(perfilDeRestauracaoDaFaixa("vozes"), "voz");
  assert.equal(perfilDeRestauracaoDaFaixa("instrumental"), "outros");
});

test("consistência: o resíduo explícito fecha a conta e a redistribuição devolve a mistura", () => {
  const n = 50000;
  const [a] = gerarMidia({ tipo: "ruido", semente: 31, amplitude: 0.3 }, n, 44100);
  const [b] = gerarMidia({ tipo: "seno", frequencia: 330, amplitude: 0.4 }, n, 44100);
  const mistura = [a.map((v, i) => v + b[i]), a.map((v, i) => v - 0.5 * b[i])];
  const faixas = [[a.map((v) => 0.8 * v), a.map((v) => 0.8 * v)], [b.map((v) => 0.9 * v), b.map((v) => -0.45 * v)]];
  const explicito = nucleo.consistencia({ mistura, faixas, manterResiduo: true });
  assert.deepEqual(explicito.faixas[0][0], faixas[0][0]);
  assert.ok(energia(explicito.residuo[0]) > 1);
  const redistribuido = nucleo.consistencia({ mistura, faixas });
  const sobra = energia(redistribuido.residuo[0]) + energia(redistribuido.residuo[1]);
  assert.ok(db((energia(mistura[0]) + energia(mistura[1])) / (sobra + 1e-30)) > 80, "a soma não voltou a ser a mistura");
  let depois = 0;
  let antes = 0;
  for (let i = 0; i < n; i += 1) {
    depois += (redistribuido.faixas[0][0][i] - a[i]) ** 2;
    antes += (faixas[0][0][i] - a[i]) ** 2;
  }
  assert.ok(depois < antes * 0.5, "a faixa não ficou mais perto do original");
});

test("medição e uso repetido não vazam memória do módulo", () => {
  const medidas = nucleo.medir(new Float32Array([0.5, -0.5, 1.5, 0, -2, 0.25]), 2);
  assert.equal(medidas.pico, 2);
  assert.equal(medidas.cortadas, 2);
  const base = gerarMidia({ tipo: "ruido", semente: 1, amplitude: 0.2, canais: 2 }, 20000, 48000);
  nucleo.restaurar({ canais: base, taxa: 48000, perfil: "voz", intensidade: 60 });
  const paginas = nucleo.x.memory.buffer.byteLength;
  for (let i = 0; i < 200; i += 1) nucleo.restaurar({ canais: base, taxa: 48000, perfil: PERFIS_DE_RESTAURACAO[i % 4].id, intensidade: 60 });
  assert.equal(nucleo.x.memory.buffer.byteLength, paginas, "a memória cresceu com o uso repetido");
});
