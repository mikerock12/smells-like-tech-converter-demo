"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { detectFormat } from "@/packages/converter-core/index.mjs";
import { registrarUso, restanteHoje } from "@/lib/plano/contadores";
import { usePlano } from "@/lib/plano/usePlano";
import { usePlugin } from "@/lib/plugin/usePlugin";
import { abrir, camada, conferirDocumento, hash, salvar, type Camada, type Documento } from "@/lib/image-editor/projeto";

async function lerFoto(fonte: Blob) {
  const tipo = detectFormat(new Uint8Array(await fonte.slice(0, 64).arrayBuffer()));
  if (!tipo || !["png", "jpg", "webp", "jxl"].includes(tipo)) throw new Error("Escolha uma foto PNG, JPEG, WebP ou JPEG XL.");
  const { decodificar } = await import("@/lib/converter/motores/imagem");
  const dados = await decodificar(fonte, tipo);
  if (dados.width * dados.height > 16 * 1024 ** 2) throw new Error("O limite do editor SDR é de 16 megapixels por imagem.");
  return createImageBitmap(dados);
}

function desenhar(canvas: HTMLCanvasElement, doc: Documento, fotos: Map<string, ImageBitmap>) {
  canvas.width = doc.canvas.width;
  canvas.height = doc.canvas.height;
  // O mesmo buffer alimenta a prévia e a leitura RGBA da exportação.
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("O navegador não disponibilizou o canvas.");
  for (const c of doc.layers) {
    if (!c.visivel) continue;
    ctx.save();
    ctx.translate(c.x, c.y);
    ctx.rotate(c.angulo * Math.PI / 180);
    ctx.scale(c.escala, c.escala);
    ctx.globalAlpha = c.opacidade;
    ctx.filter = "brightness(" + 2 ** c.exposicao + ") contrast(" + c.contraste + ") saturate(" + c.saturacao + ")";
    if (c.tipo === "texto") {
      ctx.fillStyle = c.cor;
      ctx.font = c.tamanho + "px sans-serif";
      ctx.textBaseline = "top";
      ctx.fillText(c.texto ?? "", 0, 0);
    } else {
      const foto = fotos.get(c.asset ?? "");
      if (foto) ctx.drawImage(foto, 0, 0);
    }
    ctx.restore();
  }
}

function baixar(blob: Blob, nome: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = nome;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}

const ajustes = [
  ["exposicao", "Exposição (EV)", -4, 4, 0.1], ["contraste", "Contraste", 0, 4, 0.05],
  ["saturacao", "Saturação", 0, 4, 0.05], ["opacidade", "Opacidade", 0, 1, 0.01],
  ["escala", "Escala", 0.01, 8, 0.01], ["angulo", "Rotação (°)", -180, 180, 1],
] as const;

const semAssinatura = () => () => {};

export default function Editor({ titulo = "Estúdio de imagem" }: { titulo?: string }) {
  const pronto = useSyncExternalStore(semAssinatura, () => true, () => false);
  const { plano, pro } = usePlano();
  const plugin = usePlugin();
  const [usarPlugin, escolherPlugin] = useState(false);
  const [doc, definir] = useState<Documento | null>(null);
  const [selecionada, selecionar] = useState("");
  const [historico, guardar] = useState<Documento[]>([]);
  const [futuro, refazer] = useState<Documento[]>([]);
  const [ocupado, ocupar] = useState(false);
  const [erro, avisar] = useState("");
  const [formato, formatoPara] = useState("jxl");
  const aceitaProjeto = plugin.apresentacao?.operacoes.some(op =>
    op.id === "image.renderProject" && op.disponivel && op.saidas.includes(formato));
  const tela = useRef<HTMLCanvasElement>(null);
  const arquivos = useRef(new Map<string, Blob>());
  const imagens = useRef(new Map<string, ImageBitmap>());
  const trava = useRef(false);
  const ativa = doc?.layers.find(c => c.id === selecionada);
  useEffect(() => {
    if (doc && tela.current) desenhar(tela.current, doc, imagens.current);
  }, [doc]);
  useEffect(() => {
    const cache = imagens.current;
    return () => { for (const foto of cache.values()) foto.close(); cache.clear(); };
  }, []);

  async function executar(acao: () => Promise<void>) {
    if (trava.current) return;
    trava.current = true;
    ocupar(true);
    avisar("");
    try { await acao(); } catch (e) { avisar(e instanceof Error ? e.message : "Falha ao processar a imagem."); }
    finally { trava.current = false; ocupar(false); }
  }
  function mudar(proximo: Documento) {
    conferirDocumento(proximo);
    if (doc) guardar([...historico.slice(-49), doc]);
    refazer([]);
    definir(proximo);
  }
  function editar(dados: Partial<Camada>) {
    if (doc && ativa) mudar({ ...doc, layers: doc.layers.map(c => c === ativa ? { ...c, ...dados } : c) });
  }
  async function importar(f: File) {
    if (f.name.toLowerCase().endsWith(".sltimage")) {
      const projeto = await abrir(f);
      const novos = new Map<string, ImageBitmap>();
      try {
        let pixels = 0;
        for (const [id, fonte] of projeto.fontes) {
          const imagem = await lerFoto(fonte);
          novos.set(id, imagem);
          pixels += imagem.width * imagem.height;
          if (pixels > 32 * 1024 ** 2) throw new Error("As camadas excedem o limite de memória do editor.");
        }
      } catch (e) { for (const foto of novos.values()) foto.close(); throw e; }
      for (const foto of imagens.current.values()) foto.close();
      imagens.current.clear();
      for (const [id, foto] of novos) imagens.current.set(id, foto);
      arquivos.current = projeto.fontes;
      guardar([]); refazer([]); definir(projeto.doc); selecionar(projeto.doc.layers.at(-1)?.id ?? "");
      return;
    }
    if (f.size > 64 * 1024 ** 2) throw new Error("O limite por arquivo é de 64 MiB.");
    const id = await hash(new Uint8Array(await f.arrayBuffer()));
    const foto = imagens.current.get(id) ?? await lerFoto(f);
    if (!imagens.current.has(id)) {
      const pixels = [...imagens.current.values()].reduce((s, i) => s + i.width * i.height, foto.width * foto.height);
      const bytes = [...arquivos.current.values()].reduce((s, b) => s + b.size, f.size);
      if (pixels > 32 * 1024 ** 2 || bytes > 127 * 1024 ** 2) {
        foto.close();
        throw new Error("As camadas excedem o limite de memória do editor.");
      }
    }
    const c = { ...camada("foto", f.name), asset: id };
    const base: Documento = doc ?? { documentId: crypto.randomUUID(), schemaVersion: 1, title: f.name.replace(/\.[^.]+$/, ""),
      canvas: { width: foto.width, height: foto.height, colorSpace: "sRGB", pixelFormat: "RGBA8" }, layers: [] };
    const proximo = { ...base, layers: [...base.layers, c] };
    try { conferirDocumento(proximo); } catch (e) { if (!imagens.current.has(id)) foto.close(); throw e; }
    imagens.current.set(id, foto); arquivos.current.set(id, f);
    mudar(proximo); selecionar(c.id);
  }
  function mover(delta: number) {
    if (!doc || !ativa) return;
    const lista = [...doc.layers];
    const i = lista.indexOf(ativa);
    if (i + delta < 0 || i + delta >= lista.length) return;
    [lista[i], lista[i + delta]] = [lista[i + delta], lista[i]];
    mudar({ ...doc, layers: lista });
  }
  async function exportar() {
    if (!doc || !tela.current) return;
    if (!pro && restanteHoje(plano, "estudio") === 0) throw new Error("Você atingiu o limite diário de exportações do Estúdio no plano grátis.");
    if (usarPlugin && aceitaProjeto) {
      const pacote = await salvar(doc, arquivos.current);
      const arquivo = new File([pacote], "projeto.sltimage", { type: pacote.type });
      let trabalho = await plugin.cliente.converterArquivo(arquivo, { operation: "image.renderProject", OutputFormat: formato });
      while (!trabalho.encerrado) {
        await new Promise(resolve => setTimeout(resolve, 600));
        trabalho = await plugin.cliente.consultar(trabalho.id);
      }
      const saida = trabalho.arquivos[0];
      if (!saida) throw new Error(trabalho.erro || trabalho.mensagem || "O plugin não produziu a imagem.");
      const resposta = await fetch(plugin.cliente.enderecoDoArquivo(trabalho.id, saida.indice));
      if (!resposta.ok) throw new Error("Falha ao baixar a exportação do plugin.");
      baixar(await resposta.blob(), doc.title + "." + formato);
      if (!pro) registrarUso("estudio");
      return;
    }
    const ctx = tela.current.getContext("2d");
    if (!ctx) throw new Error("Canvas indisponível.");
    const { achatar, codificar } = await import("@/lib/converter/motores/imagem");
    const pixels = ctx.getImageData(0, 0, doc.canvas.width, doc.canvas.height);
    const tipo = formato as "jxl" | "png" | "jpg" | "webp";
    const dados = await codificar(tipo === "jpg" ? achatar(pixels, "#ffffff") : pixels, tipo, tipo === "jxl" ? 100 : 92);
    baixar(new Blob([dados], { type: "image/" + (tipo === "jpg" ? "jpeg" : tipo) }), doc.title + "." + tipo);
    if (!pro) registrarUso("estudio");
  }
  return <main className="foto-editor"><header><div><a href="/estudio">Estúdio</a><h1>{titulo}</h1></div>
    <p>SDR · sRGB · 8 bits</p></header>
    <fieldset disabled={!pronto || ocupado}><legend className="sr-only">Ferramentas de imagem</legend>
      <div className="foto-barra"><label>{doc ? "Adicionar foto ou abrir projeto" : "Abrir foto ou projeto"}
        <input type="file" accept=".jpg,.jpeg,.png,.webp,.jxl,.sltimage" onChange={e => {
          const f = e.target.files?.[0]; e.target.value = ""; if (f) void executar(() => importar(f));
        }} /></label>
        <button disabled={!doc || doc.layers.length >= 64} onClick={() => {
          if (!doc) return; const c = { ...camada("texto", "Texto"), texto: "Texto" };
          mudar({ ...doc, layers: [...doc.layers, c] }); selecionar(c.id);
        }}>Adicionar texto</button>
        <button disabled={!historico.length} onClick={() => { if (doc) refazer([...futuro, doc]); definir(historico.at(-1)!); guardar(historico.slice(0, -1)); }}>Desfazer</button>
        <button disabled={!futuro.length} onClick={() => { if (doc) guardar([...historico, doc]); definir(futuro.at(-1)!); refazer(futuro.slice(0, -1)); }}>Refazer</button>
      </div>
      <div className="foto-area"><aside><h2>Camadas</h2>{doc?.layers.toReversed().map(c =>
        <button key={c.id} aria-pressed={c.id === selecionada} onClick={() => selecionar(c.id)}>{c.nome}</button>)}
        {ativa && <><button onClick={() => mover(1)}>Para frente</button><button onClick={() => mover(-1)}>Para trás</button>
          <button onClick={() => editar({ visivel: !ativa.visivel })}>{ativa.visivel ? "Ocultar" : "Mostrar"}</button></>}
      </aside><section aria-label="Prévia da composição">{doc ? <canvas ref={tela} aria-label={doc.title} /> :
        <div><h2>Abra sua imagem</h2><p>JPEG, PNG, WebP, JPEG XL ou projeto .sltimage</p></div>}</section>
      <aside><h2>Propriedades</h2>{ativa && <>{ativa.tipo === "texto" && <>
        <label>Texto<input value={ativa.texto} maxLength={4096} onChange={e => editar({ texto: e.target.value })} /></label>
        <label>Cor<input type="color" value={ativa.cor} onChange={e => editar({ cor: e.target.value })} /></label>
        <label>Tamanho<input type="number" min={1} max={4096} value={ativa.tamanho} onChange={e => editar({ tamanho: Math.max(1, Math.min(4096, Number(e.target.value))) })} /></label></>}
        {(["x", "y"] as const).map(k => <label key={k}>Posição {k.toUpperCase()}<input type="number" value={ativa[k]} onChange={e => editar({ [k]: Math.max(-32768, Math.min(32768, Number(e.target.value))) })} /></label>)}
        {ajustes.map(([k, nome, min, max, step]) => <label key={k}>{nome} <output>{ativa[k]}</output><input type="range" min={min} max={max} step={step} value={ativa[k]} onChange={e => editar({ [k]: Number(e.target.value) })} /></label>)}</>}</aside></div>
      <div className="foto-barra"><button disabled={!doc} onClick={() => void executar(async () => { if (doc) baixar(await salvar(doc, arquivos.current), doc.title + ".sltimage"); })}>Salvar projeto</button>
        <label>Formato de exportação<select value={formato} onChange={e => formatoPara(e.target.value)}><option value="jxl">JPEG XL sem perdas dos pixels</option>
          <option value="png">PNG</option><option value="jpg">JPEG</option><option value="webp">WebP</option></select></label>
        {aceitaProjeto && <label><input type="checkbox" checked={usarPlugin} onChange={e => escolherPlugin(e.target.checked)} />Renderizar no plugin Windows</label>}
        <button disabled={!doc} onClick={() => void executar(exportar)}>Exportar imagem</button></div>
    </fieldset><p role="status">{ocupado ? "Processando no dispositivo…" : erro}</p>
  </main>;
}
