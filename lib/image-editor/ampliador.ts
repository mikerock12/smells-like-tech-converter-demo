import { avaliarChave, lerChaveGuardada, podeInstalarAmpliador } from "@/lib/plano/chave";
import { hash } from "./projeto";

// SHA-256 do modelo de ampliação criado pela SLTI, também registrado no manifesto de proveniência.
const digest = "1905ec3cc0ab29ea395326c2362f1466cb92bf8211e4aa5769308489c4e00dc3";
async function instalar(): Promise<number[][]> {
  const chave = lerChaveGuardada()?.replace(/\s+/g, "") ?? null;
  const estado = await avaliarChave(chave);
  if (!podeInstalarAmpliador(estado))
    throw new Error("Ative o plano Pro para usar o ampliador SLTI.");
  const cache = await globalThis.caches?.open("slt-ampliador-licenciado").catch(() => undefined);
  const id = new URL("/api/motores/ampliador-slti/" + digest, location.origin).href;
  const instalado = await cache?.match(id).catch(() => undefined);
  if (instalado) {
    const bytes = new Uint8Array(await instalado.arrayBuffer());
    if (await hash(bytes) === digest) return JSON.parse(new TextDecoder().decode(bytes)).kernels;
  }
  const resposta = await fetch("/api/motores/ampliador-slti", {
    method: "POST", headers: { Authorization: "Bearer " + chave }, cache: "no-store", redirect: "error"
  });
  if (!resposta.ok) throw new Error(resposta.status === 403 ? "O ampliador exige uma licença Pro ativa." : "Falha ao instalar o ampliador SLTI. Tente novamente.");
  const bytes = new Uint8Array(await resposta.arrayBuffer());
  if (await hash(bytes) !== digest) throw new Error("O arquivo do ampliador falhou na verificação SHA-256.");
  await cache?.put(id, new Response(bytes)).catch(() => undefined);
  return JSON.parse(new TextDecoder().decode(bytes)).kernels;
}

export async function ampliarComSlti(imagem: ImageData): Promise<ImageData> {
  if (imagem.width * imagem.height > 4 * 1024 ** 2 || Math.max(imagem.width, imagem.height) > 8192)
    throw new Error("A saída 2× do editor admite até 16 megapixels e 16.384 pixels por lado.");
  const kernels = await instalar();
  const { default: Fabrica } = await import("./ampliador.worker?worker");
  const worker = new Fabrica({ name: "ampliador-slti" });
  try {
    return await new Promise<ImageData>((resolve, reject) => {
      worker.onmessage = ({ data }) => resolve(data);
      worker.onerror = () => reject(new Error("Falha ao ampliar a composição no dispositivo."));
      worker.postMessage({ imagem, kernels }, [imagem.data.buffer]);
    });
  } finally { worker.terminate(); }
}
