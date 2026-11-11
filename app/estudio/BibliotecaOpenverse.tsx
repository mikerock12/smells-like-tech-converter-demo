"use client";

import { useEffect, useRef, useState } from "react";
import type { CategoriaOpenverse, ResultadoOpenverse, ProvenienciaOpenverse } from "@/packages/converter-core/openverse.mjs";
import { baixarAudioOpenverse, consultarBiblioteca, ErroOpenverse } from "@/lib/estudio/openverse-cliente";

const estados = { APPROVED: "CC0 revisada", PENDING: "Licença em verificação", BLOCKED: "Indisponível", RECHECK: "Aguardando nova revisão" };

type ResultadoDaBusca = { resultados: ResultadoOpenverse[]; proxima: boolean };

export default function BibliotecaOpenverse({ adicionar, pararEstudio, podeAdicionar }: {
  adicionar: (arquivo: File, duracao: number, proveniencia: ProvenienciaOpenverse) => void;
  pararEstudio: () => void;
  podeAdicionar: boolean;
}) {
  const [aberto, setAberto] = useState(false);
  const [categoria, setCategoria] = useState<CategoriaOpenverse>("music");
  const [termo, setTermo] = useState("");
  const [pagina, setPagina] = useState(1);
  const [busca, setBusca] = useState<ResultadoDaBusca | null>(null);
  const [carregando, setCarregando] = useState(false);
  const [download, setDownload] = useState("");
  const [erro, setErro] = useState("");
  const [tentativa, setTentativa] = useState(0);
  const [previa, setPrevia] = useState<{ titulo: string; url: string } | null>(null);
  const [esperar, setEsperar] = useState(0);
  const podeBuscarApos = useRef(0);
  const baixando = useRef<AbortController | null>(null);
  const cancelarDownload = () => baixando.current?.abort();

  useEffect(() => {
    if (!aberto || Date.now() < podeBuscarApos.current) return;
    const controle = new AbortController();
    const id = setTimeout(() => {
      setBusca(null);
      setErro("");
      setCarregando(true);
      void consultarBiblioteca<ResultadoDaBusca>({ category: categoria, q: termo, page: String(pagina) }, controle.signal)
        .then((resultado) => { if (!controle.signal.aborted) setBusca(resultado); })
        .catch((e: unknown) => {
          if (controle.signal.aborted) return;
          setErro(navigator.onLine ? e instanceof Error ? e.message : "Erro ao consultar a biblioteca." : "Sem conexão com a internet.");
          if (e instanceof ErroOpenverse && e.aguardar) {
            podeBuscarApos.current = Date.now() + e.aguardar * 1000;
            setEsperar(e.aguardar);
          }
        }).finally(() => { if (!controle.signal.aborted) setCarregando(false); });
    }, 400);
    return () => { clearTimeout(id); controle.abort(); };
  }, [aberto, categoria, termo, pagina, tentativa]);

  useEffect(() => {
    if (!esperar) return;
    const id = setTimeout(() => setEsperar(0), esperar * 1000);
    return () => clearTimeout(id);
  }, [esperar]);

  useEffect(() => () => { baixando.current?.abort(); }, []);
  useEffect(() => () => { if (previa) URL.revokeObjectURL(previa.url); }, [previa]);

  async function obter(item: ResultadoOpenverse, inserir: boolean) {
    if (baixando.current || item.estado !== "APPROVED") return;
    const controle = new AbortController();
    baixando.current = controle;
    setPrevia(null);
    setErro("");
    setDownload("Conferindo a faixa…");
    try {
      const { arquivo, duracao, proveniencia } = await baixarAudioOpenverse(item, controle.signal, (bytes, total) =>
        setDownload(total > 0 ? `Baixando áudio · ${Math.min(100, Math.round(bytes * 100 / total))}%` : `Baixando áudio · ${(bytes / 1048576).toFixed(1)} MiB`));
      controle.signal.throwIfAborted();
      if (inserir) adicionar(arquivo, duracao, proveniencia);
      else {
        pararEstudio();
        setPrevia({ titulo: item.titulo, url: URL.createObjectURL(arquivo) });
      }
    } catch (e) {
      if (!controle.signal.aborted) setErro(e instanceof Error ? e.message : "Não foi possível baixar a faixa.");
    } finally {
      if (baixando.current === controle) { baixando.current = null; setDownload(""); }
    }
  }

  return (
    <details className="estudio__card" open={aberto} onToggle={(e) => {
      const visivel = e.currentTarget.open;
      setAberto(visivel);
      if (!visivel) { cancelarDownload(); setPrevia(null); }
    }}>
      <summary>Músicas grátis</summary>
      <div className="estudio__lista">
        <p className="estudio__dica">Busca de músicas disponibilizada com o uso da API Openverse. O Smells Like Tech Converter é independente e não é endossado nem certificado pelo Openverse.</p>
        <label>Categoria <select value={categoria} onChange={(e) => { setCategoria(e.target.value as CategoriaOpenverse); setPagina(1); setPrevia(null); }}>
          <option value="music">Músicas</option><option value="sound_effect">Efeitos sonoros</option>
        </select></label>
        <label>Pesquisar <input type="search" maxLength={200} value={termo} onChange={(e) => { setTermo(e.target.value); setPagina(1); }} /></label>
        {carregando && <p role="status">Buscando faixas…</p>}
        {erro && <div role="status">{erro} {!esperar && <button type="button" className="button" onClick={() => setTentativa((n) => n + 1)}>Tentar novamente</button>}</div>}
        {download && <div role="status">{download} <button type="button" className="button" onClick={cancelarDownload}>Cancelar</button></div>}
        {previa && <div>
          <p>{previa.titulo}</p>
          {/* eslint-disable-next-line jsx-a11y/media-has-caption -- Prévia de uma faixa, identificada pelo título. */}
          <audio controls src={previa.url} autoPlay onPlay={pararEstudio} aria-label={previa.titulo} />
          <button type="button" className="button" onClick={() => setPrevia(null)}>Fechar prévia</button>
        </div>}
        {busca?.resultados.length === 0 && <p>Nenhuma faixa encontrada. Tente outro termo.</p>}
        {busca?.resultados.map((item) => (
          <article className="estudio__card" key={item.id}>
            <strong>{item.titulo}</strong>
            <p>{[item.autor, item.fonte, item.duracaoMs ? `${Math.round(item.duracaoMs / 1000)} s` : null, ...item.generos].filter(Boolean).join(" · ")}</p>
            <p>{estados[item.estado]} · <a href={item.origem} target="_blank" rel="noreferrer">Origem</a> · <a href={item.licenca} target="_blank" rel="noreferrer">CC0 1.0</a></p>
            {item.estado === "APPROVED" && <div className="estudio__acoes">
              <button type="button" className="button" disabled={!!download} onClick={() => void obter(item, false)}>Ouvir</button>
              <button type="button" className="button" disabled={!podeAdicionar || !!download} onClick={() => void obter(item, true)}>Adicionar</button>
            </div>}
          </article>
        ))}
        {busca && (pagina > 1 || busca.proxima) && <div className="estudio__acoes">
          <button type="button" className="button" disabled={pagina === 1} onClick={() => setPagina(pagina - 1)}>Anterior</button>
          <span>Página {pagina}</span>
          <button type="button" className="button" disabled={!busca.proxima} onClick={() => setPagina(pagina + 1)}>Próxima</button>
        </div>}
      </div>
    </details>
  );
}
