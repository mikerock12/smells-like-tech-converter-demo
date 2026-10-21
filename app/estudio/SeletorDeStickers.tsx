"use client";

import { useEffect, useRef, useState } from "react";
import { CatalogoDeStickers, estadoDoCatalogo, type ResultadoDoCatalogo, type ItemDoCatalogo } from "@/packages/converter-core/catalogo-stickers.mjs";
import noto from "@/packages/converter-core/stickers-noto.json";

let instancia: CatalogoDeStickers | null = null;
const normalizar = (texto: string) => texto.toLocaleLowerCase("pt-BR").normalize("NFD").replace(/\p{M}/gu, "");
function catalogo() {
  if (instancia) return instancia;
  let estado = estadoDoCatalogo();
  try {
    const salvo = JSON.parse(localStorage.getItem("estudio-cota-giphy") ?? "null");
    if (salvo && Array.isArray(salvo.tentativas) && Number.isFinite(salvo.tentarEm) && Number.isFinite(salvo.falhas)) estado = salvo;
  } catch { /* Sem armazenamento, a cota continua em memória. */ }
  instancia = new CatalogoDeStickers({
    chave: process.env.NEXT_PUBLIC_GIPHY_EDITOR_API_KEY ?? "",
    estado,
    salvarEstado: valor => localStorage.setItem("estudio-cota-giphy", JSON.stringify(valor)),
    buscarNoto: async consulta => {
      const termos = normalizar(consulta).split(/\s+/).filter(Boolean);
      return noto.filter(item => termos.every(termo => normalizar(item.nome + " " + item.termos).includes(termo)))
        .map(item => ({ id: item.codigo, nome: item.nome, previa: "/stickers/noto/" + item.codigo + ".gif",
          url: "/stickers/noto/" + item.codigo + ".gif", pagina: "https://googlefonts.github.io/noto-emoji-files/",
          autor: "Google Noto · CC BY 4.0", fonte: "noto" as const }));
    },
  });
  return instancia;
}

export default function SeletorDeStickers({ adicionar, fechar }: { adicionar: (arquivo: File) => void; fechar: () => void }) {
  const [consulta, setConsulta] = useState("");
  const [pagina, setPagina] = useState(0);
  const [animado, setAnimado] = useState(true);
  const [resultado, setResultado] = useState<ResultadoDoCatalogo | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const download = useRef<AbortController | null>(null);
  useEffect(() => () => { download.current?.abort(); }, []);
  useEffect(() => {
    const controle = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const buscar = async () => {
      try {
        const proximo = await catalogo().buscar(consulta, { pagina, signal: controle.signal });
        if (controle.signal.aborted) return;
        setResultado(proximo);
        if (proximo.tentarEm) timer = setTimeout(() => { void buscar(); }, Math.max(1000, proximo.tentarEm - Date.now()));
      } catch { if (!controle.signal.aborted) setErro("Não foi possível abrir o catálogo."); }
    };
    timer = setTimeout(() => { void buscar(); }, 550);
    return () => { controle.abort(); clearTimeout(timer); };
  }, [consulta, pagina]);

  const escolher = async (item: ItemDoCatalogo) => {
    if (download.current) return;
    const controle = new AbortController();
    download.current = controle;
    setOcupado(true); setErro(null);
    try {
      const resposta = await fetch(item.url, { signal: controle.signal, credentials: "omit", referrerPolicy: "no-referrer", cache: item.fonte === "giphy" ? "no-store" : "default" });
      if (!resposta.ok || Number(resposta.headers.get("content-length") ?? 0) > 32_000_000) throw new Error();
      if (!resposta.body) throw new Error();
      const leitor = resposta.body.getReader();
      const partes: Uint8Array<ArrayBuffer>[] = [];
      let tamanho = 0;
      try {
        while (true) {
          const { done, value } = await leitor.read();
          if (done) break;
          tamanho += value.byteLength;
          if (tamanho > 32_000_000) { await leitor.cancel(); throw new Error(); }
          partes.push(new Uint8Array(value));
        }
      } finally { leitor.releaseLock(); }
      const blob = new Blob(partes, { type: resposta.headers.get("content-type") ?? "image/gif" });
      let midia = blob;
      if (!animado) {
        const bitmap = await createImageBitmap(blob);
        try {
          const tela = new OffscreenCanvas(bitmap.width, bitmap.height);
          tela.getContext("2d")!.drawImage(bitmap, 0, 0);
          midia = await tela.convertToBlob({ type: "image/png" });
        } finally { bitmap.close(); }
      }
      if (controle.signal.aborted) return;
      // O nome do arquivo vira o título da camada.
      const nome = item.nome.replace(/[\\/:*?"<>|]/g, "").trim().slice(0, 40) || "Sticker";
      adicionar(new File([midia], nome + (animado ? ".gif" : ".png"), { type: animado ? "image/gif" : "image/png" }));
      fechar();
    } catch { if (!controle.signal.aborted) setErro("Não foi possível adicionar este sticker. Tente outro."); }
    finally { download.current = null; if (!controle.signal.aborted) setOcupado(false); }
  };

  return <section className="estudio__detalhe" aria-label="Catálogo de stickers">
    <div className="estudio__linha"><strong>Stickers e GIF stickers</strong><button type="button" className="button" onClick={() => { download.current?.abort(); fechar(); }}>Fechar</button></div>
    <label className="estudio__campo">Procurar
      <input maxLength={50} value={consulta} onChange={e => { setConsulta(e.target.value); setPagina(0); setResultado(null); }} />
    </label>
    <label><input type="checkbox" checked={animado} onChange={e => setAnimado(e.target.checked)} /> Adicionar com animação</label>
    <p>{resultado?.fonte === "giphy" ? "Powered by GIPHY" : "Google Noto · CC BY 4.0"}</p>
    {resultado?.aviso && <p role="status">{resultado.aviso}</p>}
    {!resultado && <p role="status">Procurando…</p>}
    {resultado?.itens.length === 0 && <p>Nenhum sticker encontrado. Tente outro termo.</p>}
    {erro && <p role="alert">{erro}</p>}
    <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(90px,1fr))", gap: 8 }}>
      {resultado?.itens.map(item => <button className="button" type="button" key={item.id} disabled={ocupado} onClick={() => void escolher(item)} title={item.nome}>
        {/* As URLs do fornecedor são preservadas, sem otimizador/proxy de imagens. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={item.previa} alt={item.nome} loading="lazy" referrerPolicy="no-referrer" width={90} height={90} style={{ objectFit: "contain", maxWidth: "100%" }} />
      </button>)}
    </div>
    <div className="estudio__linha">
      {pagina > 0 && <button className="button" type="button" onClick={() => { setPagina(pagina - 1); setResultado(null); }}>Anterior</button>}
      {resultado?.proximaPagina != null && <button className="button" type="button" onClick={() => { setPagina(resultado.proximaPagina!); setResultado(null); }}>Próxima</button>}
    </div>
  </section>;
}
