"use client";

import { lerOpcoesDoRestaurador, nomeDoRestaurado, resumoDoDiagnostico, textoDoAviso, ETAPAS_DO_RESTAURADOR } from "@/packages/converter-core/restaurador.mjs";
import { FORMATOS_DE_EXPORTACAO } from "@/packages/converter-core/musica-master.mjs";
import { conferirSaida, detalhesDosProblemas, picoDb } from "@/packages/converter-core/validacao-de-saida.mjs";
import { codificar, formatosDisponiveis } from "@/lib/musica/exportar";
import { restaurarAudio } from "@/lib/musica/restaurador";

import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";
import { decodificarAudio } from "./audio";

/**
 * Restaurar áudio com IA (experimental), no navegador: lê, diagnostica, planeja e repara no
 * núcleo nativo (WebAssembly), confere a saída e grava no formato escolhido. O original não
 * muda e nada sai do aparelho. Sem defeitos suficientes, o áudio sai como entrou (avisado).
 */
export async function restaurar(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const opcoes = lerOpcoesDoRestaurador(String(contexto.opcoes.restauracao || "{}"));
  const entrada = contexto.entradas[0];
  contexto.progresso("Lendo o áudio", 0.02);
  const pcm = await decodificarAudio(entrada.arquivo, entrada.formato);
  const quadros = pcm.canais[0]?.length ?? 0;
  if (!quadros) throw new ConversionError("sem_audio", "O arquivo não tem áudio.");
  if (quadros > pcm.sampleRate * 60 * 60) throw new ConversionError("longo_demais", "No navegador, a restauração aceita até 1 hora. Para áudios maiores, use o plugin ou o aplicativo.");
  const controle = new AbortController();
  const vigia = setInterval(() => { if (contexto.cancelado()) controle.abort(); }, 300);
  let pronto;
  try {
    pronto = await restaurarAudio(pcm.canais, pcm.sampleRate, opcoes, (etapa, f) => contexto.progresso(etapa, 0.05 + 0.85 * f), controle.signal);
  } catch (erro) {
    if (contexto.cancelado()) throw new ConversionError("cancelado", "Cancelado.");
    console.error("[audio.restaurar] a restauração falhou:", erro);
    throw new ConversionError("restauracao_falhou", "Não foi possível restaurar este áudio neste navegador. Tente pelo plugin ou pelo aplicativo.", `restaurar: ${erro instanceof Error ? erro.message : String(erro)}`);
  } finally {
    clearInterval(vigia);
  }
  const canais = pronto.canais.length === 1 ? [pronto.canais[0], pronto.canais[0]] : pronto.canais;
  // Conferência antes do "pronto": tamanho, amostras válidas e som.
  const problemas = conferirSaida({ faixasDeAudio: 1, picoDaSaidaDb: picoDb(canais), picoDaEntradaDb: picoDb(pcm.canais) });
  if (canais[0].length !== quadros) problemas.push({ etapa: "conferir", mensagem: `a duração mudou (${canais[0].length} contra ${quadros} quadros)` });
  if (canais.some((c) => { for (let i = 0; i < c.length; i += 101) if (!Number.isFinite(c[i])) return true; return false; })) problemas.push({ etapa: "reparar", mensagem: "amostras inválidas (NaN/Inf)" });
  if (problemas.length) throw new ConversionError("saida_invalida", "A restauração não produziu um áudio válido. O original não foi alterado.", detalhesDosProblemas(problemas));

  contexto.progresso("Gravando o arquivo", 0.93);
  // FLAC depende do codificador do navegador; sem ele, sai em WAV 24 bits (sem perda também).
  const disponiveis = await formatosDisponiveis(pcm.sampleRate);
  const pedido = disponiveis.includes(opcoes.formato) ? opcoes.formato : "wav-24";
  const formato = FORMATOS_DE_EXPORTACAO.find((f) => f.id === pedido) ?? FORMATOS_DE_EXPORTACAO[0];
  const blob = await codificar(formato.id, canais[0], canais[1], pcm.sampleRate);
  const saidas: Saida[] = [{ nome: nomeDoRestaurado(entrada.nome, formato.extensao), mime: blob.type || "application/octet-stream", bytes: await blob.arrayBuffer(), duracao: quadros / pcm.sampleRate }];
  if (opcoes.relatorio) {
    // Só medidas e versões: nada do áudio, nenhum caminho do aparelho.
    const relatorio = { ferramenta: "audio.restaurar", versao: pronto.plano.versao, perfil: pronto.plano.perfil, intensidade: opcoes.intensidade, diagnostico: pronto.diagnostico, etapas: pronto.plano.etapas, parametros: pronto.plano.parametros, avisos: pronto.plano.avisos, estatisticas: pronto.estatisticas, motor: pronto.plano.ia ? "Apollo (ONNX, CC BY-SA 4.0) e núcleo nativo (WebAssembly)" : "núcleo nativo (WebAssembly)", ia: pronto.plano.ia };
    saidas.push({ nome: nomeDoRestaurado(entrada.nome, "json").replace(/\.json$/, "-relatorio.json"), mime: "application/json", bytes: new TextEncoder().encode(JSON.stringify(relatorio, null, 2)).buffer as ArrayBuffer });
  }
  const etapas = pronto.plano.etapas.map((e) => ETAPAS_DO_RESTAURADOR.find((x) => x.id === e.id)?.rotulo ?? e.id).join(", ");
  const nota = pronto.plano.semMudanca
    ? `${textoDoAviso(pronto.plano.avisos.at(-1) ?? "sem_defeitos_suficientes")} ${resumoDoDiagnostico(pronto.diagnostico)}`
    : `Restaurado: ${etapas} · ${resumoDoDiagnostico(pronto.diagnostico)} · experimental`;
  if (pedido !== opcoes.formato) return { saidas, nota: `${nota} · Este navegador não grava ${opcoes.formato.toUpperCase()}; saiu em WAV 24 bits.` };
  return { saidas, nota };
}
