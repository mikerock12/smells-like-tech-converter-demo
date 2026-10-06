import test from "node:test";
import assert from "node:assert/strict";
import { ProgressoNarracao } from "../packages/kokoro/progresso.mjs";

test("narração: caracteres reais, sem chute de progresso ou ETA antes da amostra", () => {
  let agora = 900000; // OCR/carregamento anterior não entra no cálculo.
  const p = new ProgressoNarracao([100, 200, 100, 600], () => agora);
  assert.equal(p.retrato().segundosRestantes, null);
  agora += 2000; p.concluirBloco();
  assert.equal(p.retrato().fracao, 0.1);
  agora += 4000; p.concluirBloco();
  assert.equal(p.retrato().fracao, 0.3);
  assert.equal(p.retrato().segundosRestantes, null);
  agora += 2000; p.concluirBloco();
  assert.deepEqual(p.retrato(), { fracao: 0.4, segundosRestantes: 12 });
  agora += 6000;
  assert.deepEqual(p.retrato(), { fracao: 0.4, segundosRestantes: 6 });
  agora += 60000; // Suspensão/lentidão invalida a previsão, não inventa porcentagem.
  assert.deepEqual(p.retrato(), { fracao: 0.4, segundosRestantes: null });
  p.concluirBloco();
  assert.deepEqual(p.retrato(), { fracao: 1, segundosRestantes: null });
});

test("narração: janela recente de oito blocos acompanha mudança de velocidade", () => {
  let agora = 0;
  const p = new ProgressoNarracao(Array(30).fill(100), () => agora);
  for (let n = 0; n < 3; n++) { agora += 2000; p.concluirBloco(); }
  assert.equal(p.retrato().segundosRestantes, 54);
  for (let n = 0; n < 8; n++) { agora += 4000; p.concluirBloco(); }
  assert.equal(p.retrato().segundosRestantes, 76);
});
