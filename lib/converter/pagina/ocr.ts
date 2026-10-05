import { outputFileName } from "@/packages/converter-core/index.mjs";

import { ConversionError, type ContextoDoMotor, type Saida } from "../protocol";

/**
 * Reconhecimento de texto no navegador, com o Tesseract compilado para WASM.
 *
 * O motor (~4 MB) é servido da nossa origem — copiado de node_modules pelo
 * scripts/copiar-motores.mjs antes do build. Os três idiomas também vêm da nossa origem
 * e entram na preparação offline, mesmo antes de reconhecer a primeira imagem.
 * A imagem em si nunca sai da máquina: ela entra num worker local e sai como texto.
 */

const CAMINHO_DO_MOTOR = "/motores/tesseract";
const DADOS_DE_IDIOMA = "/motores/tesseract/idiomas";

export async function reconhecerTexto(contexto: ContextoDoMotor): Promise<{ saidas: Saida[]; nota: string }> {
  const { entradas, opcoes, progresso } = contexto;
  const idioma = String(opcoes.idioma ?? "por");

  let criarWorker: typeof import("tesseract.js").createWorker;
  try {
    ({ createWorker: criarWorker } = await import("tesseract.js"));
  } catch {
    throw new ConversionError("ocr_indisponivel", "O motor de reconhecimento não carregou. Verifique a conexão e tente de novo.");
  }

  progresso("carregando o motor", 0.02);
  let worker: Awaited<ReturnType<typeof criarWorker>>;
  try {
    worker = await criarWorker(idioma, 1, {
      workerPath: `${CAMINHO_DO_MOTOR}/worker.min.js`,
      corePath: `${CAMINHO_DO_MOTOR}/`,
      langPath: DADOS_DE_IDIOMA,
      workerBlobURL: false,
      logger: (mensagem: { status?: string; progress?: number }) => {
        if (mensagem.status === "loading language traineddata") progresso("baixando o idioma", 0.05 + (mensagem.progress ?? 0) * 0.15);
      },
    });
  } catch (erro) {
    throw new ConversionError(
      "ocr_nao_carregou",
      `Não consegui preparar o reconhecimento de texto (${erro instanceof Error ? erro.message : "falha ao carregar"}). Aguarde o aviso de modo offline pronto ou reconecte para concluir a preparação.`,
    );
  }

  const saidas: Saida[] = [];
  let caracteres = 0;

  try {
    for (const [indice, entrada] of entradas.entries()) {
      if (contexto.cancelado()) return { saidas: [], nota: "" };
      progresso("reconhecendo", 0.2 + (indice / entradas.length) * 0.8);
      const { data } = await worker.recognize(entrada.arquivo);
      const texto = data.text.replace(/[ \t]+\n/g, "\n").trim();
      if (!texto) {
        throw new ConversionError("sem_texto", `Não encontrei texto legível em “${entrada.nome}”. Tente uma imagem mais nítida ou com mais contraste.`);
      }
      caracteres += texto.length;
      const bytes = new TextEncoder().encode(`${texto}\n`);
      saidas.push({
        nome: outputFileName(entrada.nome, "txt"),
        mime: "text/plain",
        bytes: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer,
      });
    }
  } finally {
    await worker.terminate();
  }

  return { saidas, nota: `${caracteres} caracteres reconhecidos` };
}
