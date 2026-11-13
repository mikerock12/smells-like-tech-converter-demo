/**
 * Gera native/slt-media-engine/tests/casos_gerados.h: os valores que a referência em JS dá
 * para a matemática da separação (máscara clássica e refinada), para o teste do núcleo C++
 * comparar. Rode depois de mudar a separação em musica-separacao.mjs:
 *
 *   node scripts/nativo-casos.mjs
 */

import { writeFileSync } from "node:fs";
import { estimativasFalsas, gerarMidia, indicesDeAmostra, resumo } from "../packages/converter-core/musica-casos.mjs";
import { MODELO_DE_SEPARACAO, ProcessadorEspectral, mascaraDaFaixa, modoDeSeparacao } from "../packages/converter-core/musica-separacao.mjs";

const m = MODELO_DE_SEPARACAO;

function separar({ comprimento, modo, gerador }, refinada) {
  const original = gerarMidia(gerador, comprimento, m.taxa);
  const definicao = modoDeSeparacao(modo);
  const p = new ProcessadorEspectral({ comprimento, janela: m.janela, passo: m.passo, quadrosPorTrecho: m.quadrosPorTrecho, saidas: definicao.faixas.length });
  const saidas = definicao.faixas.map(() => [new Float32Array(comprimento), new Float32Array(comprimento)]);
  const entrada = new Float32Array(2 * m.quadrosPorTrecho * m.bandas);
  const mascara = [new Float32Array(m.quadrosPorTrecho * p.B), new Float32Array(m.quadrosPorTrecho * p.B)];
  const ler = (c, i, n, d) => d.set(original[Math.min(c, original.length - 1)].subarray(i, i + n));
  for (let s = 0; s < p.trechos; s += 1) {
    const q = p.analisar(s, ler, original.length);
    p.magnitude(q, m.bandas, entrada);
    const estimativas = estimativasFalsas(entrada, m.quadrosPorTrecho, m.bandas, m.instrumentos.length);
    definicao.faixas.forEach((faixa, f) => {
      mascaraDaFaixa(estimativas, m.instrumentos.length, faixa.de.map((nome) => m.instrumentos.indexOf(nome)), q, m.quadrosPorTrecho, m.bandas, p.B, mascara, refinada ? p : null);
      p.sintetizar(s, f, mascara, (c, inicio, amostras, n) => saidas[f][c].set(amostras.subarray(0, n), inicio));
    });
  }
  const indices = indicesDeAmostra(comprimento);
  return definicao.faixas.map((faixa, f) => ({ de: faixa.de.map((nome) => m.instrumentos.indexOf(nome)), ...resumo(saidas[f][0], saidas[f][1], indices) }));
}

const casos = [
  { nome: "ruido_4_faixas", comprimento: 60000, modo: "4", gerador: { tipo: "ruido", semente: 21, amplitude: 0.5, canais: 2 } },
  { nome: "seno_2_faixas", comprimento: 30000, modo: "2", gerador: { tipo: "seno", frequencia: 440, amplitude: 0.5, canais: 1 } },
  // Mais de um trecho de inferência (512 quadros de 1.024 amostras), para conferir as emendas.
  { nome: "ruido_dois_trechos", comprimento: 540000, modo: "2", gerador: { tipo: "ruido", semente: 5, amplitude: 0.4, canais: 2 } },
];

const linhas = [
  "// Gerado por scripts/nativo-casos.mjs a partir da referência em JS. Não edite à mão.",
  "#pragma once",
  "#include <stdint.h>",
  "",
  "struct AmostraEsperada { uint32_t indice; double esquerdo; double direito; };",
  "struct FaixaEsperada { uint32_t quantosIndices; uint32_t indices[4]; double soma; double energia; double pico; AmostraEsperada amostras[16]; };",
  "struct CasoEsperado { const char* nome; uint32_t comprimento; uint32_t canais; uint32_t tipo; uint32_t semente; double frequencia; double amplitude; uint32_t refinada; uint32_t quantasFaixas; FaixaEsperada faixas[4]; };",
  "",
  "static const CasoEsperado CASOS[] = {",
];
for (const caso of casos) {
  for (const refinada of [false, true]) {
    const faixas = separar(caso, refinada);
    const g = caso.gerador;
    linhas.push(`  { "${caso.nome}${refinada ? "_refinada" : ""}", ${caso.comprimento}, ${g.canais}, ${g.tipo === "ruido" ? 1 : 0}, ${g.semente ?? 0}, ${g.frequencia ?? 0}, ${g.amplitude}, ${refinada ? 1 : 0}, ${faixas.length}, {`);
    for (const f of faixas) {
      linhas.push(`    { ${f.de.length}, { ${[...f.de, 0, 0, 0, 0].slice(0, 4).join(", ")} }, ${f.soma}, ${f.energia}, ${f.pico}, { ${f.amostras.map(([i, e, d]) => `{ ${i}, ${e}, ${d} }`).join(", ")} } },`);
    }
    linhas.push("  } },");
  }
}
linhas.push("};", "");
writeFileSync(new URL("../native/slt-media-engine/tests/casos_gerados.h", import.meta.url), linhas.join("\n"));
console.log(`${casos.length * 2} casos gravados.`);
