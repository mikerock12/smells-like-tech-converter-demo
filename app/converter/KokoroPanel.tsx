"use client";
import { useEffect, useState } from "react";
import { kokoroPronto, prepararKokoro, tamanhoKokoro } from "@/lib/converter/pagina/kokoro";

export default function KokoroPanel() {
  const [pronto, setPronto] = useState(false);
  const [baixando, setBaixando] = useState(false);
  const [percentual, setPercentual] = useState(0);
  const [erro, setErro] = useState("");
  const [megabytes, setMegabytes] = useState<number | null>(null);
  useEffect(() => {
    void kokoroPronto().then(setPronto).catch(() => {});
    void tamanhoKokoro().then((bytes) => setMegabytes(Math.ceil(bytes / 1024 ** 2))).catch(() => {});
  }, []);
  async function preparar() {
    setBaixando(true); setErro("");
    try { await prepararKokoro(setPercentual); setPronto(true); }
    catch (e) { setErro(e instanceof Error ? e.message : "Falha no preparo."); }
    finally { setBaixando(false); }
  }
  return <section className="panel" aria-label="Preparação da narração Kokoro-82M" data-kokoro-pronto={pronto}>
    <h2 className="panel__title">Narração Kokoro-82M</h2>
    <p className="panel__descricao">{pronto ? "Modelo guardado neste navegador. Dora, Alex e Santa disponíveis para narrar offline.*" : `Prepare uma vez com internet${megabytes ? ` (${megabytes} MB)` : ""}. Depois, textos, PDFs, imagens e documentos são narrados no seu aparelho, sem envio de arquivos.`}</p>
    {!pronto && <button type="button" className="button" onClick={preparar} disabled={baixando}>{baixando ? `Baixando modelo: ${Math.floor(percentual)}%` : "Preparar narração offline"}</button>}
    {erro && <p role="alert">{erro}</p>}
    <p className="panel__nota">* Limpar os dados do site ou o navegador remover este armazenamento exige preparar novamente. Windows e Android já incluem as vozes no instalador.</p>
  </section>;
}
