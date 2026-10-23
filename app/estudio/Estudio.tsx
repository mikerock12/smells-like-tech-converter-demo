"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type DragEvent } from "react";

import {
  AJUSTES,
  TRANSICOES,
  type Transicao,
  FONTES,
  NOTA_DOS_PRESETS,
  PRESETS,
  SAIDAS,
  avisos,
  cabeNoNavegador,
  duracaoDoClipe,
  duracaoLegivel,
  duracaoTotal,
  novoProjeto,
  presetPorId,
  problemas,
  saidaPorId,
  type AudioExtra,
  type Clipe,
  type IdDaSaida,
  type Preset,
  type Projeto,
  type Texto,
} from "@/packages/converter-core/estudio.mjs";
import { conferirEstudio, type BloqueioDoEstudio } from "@/packages/converter-core/estudio-plano.mjs";
import { limite as limiteDoPlano } from "@/packages/converter-core/precos.mjs";

import { ExportacaoCancelada, exportarNoNavegador, type Exportado } from "@/lib/estudio/exportar";
import { textosDaLegenda } from "@/packages/converter-core/estudio-legendas.mjs";
import { pedidoParaOPlugin } from "@/lib/estudio/plugin";
import { PreviaDoEstudio } from "@/lib/estudio/previa";
import { duracaoDaMidia } from "@/lib/converter/limites";
import { registrarUso, restanteHoje, usoDeHoje } from "@/lib/plano/contadores";
import { usePlano } from "@/lib/plano/usePlano";
import { usePlugin } from "@/lib/plugin/usePlugin";
import Paywall from "../converter/Paywall";
import ControlesDeCamada from "./ControlesDeCamada";
import LinhaDoTempo from "./LinhaDoTempo";
import ProjetosDoEstudio from "./ProjetosDoEstudio";
import NarracaoDoEstudio from "./NarracaoDoEstudio";
import PainelDeCamadas from "./PainelDeCamadas";

type Etapa = "destino" | "saida" | "editor";
type Aba = "clipes" | "camadas" | "textos" | "audio" | "exportar";
type Info = { nome: string; duracao: number | null };
type Resultado = { nome: string; url: string; bytes: number; doPlugin: boolean };
type Andamento = { etapa: string; fracao: number; restante: number | null; cancelar: () => void };

const CORES = ["#FFFFFF", "#000000", "#FFD54A", "#FF7A18", "#E53935", "#43A047", "#1E88E5", "#8E24AA"];
const IMAGEM = /\.(jpe?g|png|webp|avif|bmp|gif|heic|heif)$/i;

let proximoId = 1;
const novoId = (prefixo: string) => `${prefixo}${Date.now().toString(36)}${(proximoId++).toString(36)}`;

/**
 * O estúdio no navegador. Primeiro o destino (rede e formato), depois o que exportar, e então
 * o editor: clipes em sequência, textos, música e a exportação. Os arquivos ficam só nesta
 * página; projetos leves exportam aqui mesmo e os pesados (ou a transcrição) vão ao plugin.
 * As regras são as de packages/converter-core/estudio.mjs, as mesmas dos apps.
 */
export default function Estudio() {
  const [etapa, setEtapa] = useState<Etapa>("destino");
  const [projeto, setProjeto] = useState<Projeto | null>(null);
  const [arquivos, setArquivos] = useState<ReadonlyMap<string, File>>(() => new Map());
  const [infos, setInfos] = useState<Record<string, Info>>({});
  const [instante, setInstante] = useState(0);
  const [tocando, setTocando] = useState(false);
  const [aba, setAba] = useState<Aba>("clipes");
  const [aberto, setAberto] = useState<string | null>(null);
  const [andamento, setAndamento] = useState<Andamento | null>(null);
  const [resultado, setResultado] = useState<Resultado | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [bloqueio, setBloqueio] = useState<BloqueioDoEstudio | null>(null);
  const [personalizado, setPersonalizado] = useState({ largura: "1080", altura: "1080" });

  const total = projeto ? duracaoTotal(projeto) : 0;

  // ==================== assistente ====================

  const escolherDestino = (preset: Preset, largura?: number, altura?: number) => {
    if (projeto) {
      const novo = novoProjeto(preset.id, projeto.saida, largura && altura ? { largura, altura } : null);
      setProjeto({ ...projeto, destino: novo.destino, largura: novo.largura, altura: novo.altura, fps: novo.fps });
      setEtapa("editor");
    } else {
      setProjeto(novoProjeto(preset.id, "video", largura && altura ? { largura, altura } : null));
      setEtapa("saida");
    }
  };

  const escolherSaida = (saida: IdDaSaida) => {
    setProjeto((p) => (p ? { ...p, saida } : p));
    setEtapa("editor");
  };

  // ==================== edição ====================

  const mudar = useCallback((mudanca: (p: Projeto) => Projeto) => {
    setProjeto((p) => (p ? mudanca(p) : p));
    setResultado(null);
  }, []);

  const adicionarArquivos = async (lista: readonly File[]) => {
    const midias = lista.filter((arquivo) => arquivo.type.startsWith("video/") || arquivo.type.startsWith("image/") || IMAGEM.test(arquivo.name));
    if (midias.length === 0) {
      setErro("Escolha vídeos ou fotos. Músicas entram pela aba Áudio.");
      return;
    }
    const novosArquivos = new Map(arquivos);
    const novasInfos: Record<string, Info> = {};
    const clipes: Clipe[] = [];
    for (const arquivo of midias) {
      const chave = novoId("a");
      novosArquivos.set(chave, arquivo);
      const foto = arquivo.type.startsWith("image/") || IMAGEM.test(arquivo.name);
      const duracao = foto ? null : await duracaoDaMidia(arquivo);
      novasInfos[chave] = { nome: arquivo.name, duracao };
      clipes.push(foto
        ? { id: novoId("c"), arquivo: chave, tipo: "imagem", duracao: 3, ajuste: "caber-desfocado" }
        : { id: novoId("c"), arquivo: chave, tipo: "video", inicio: 0, fim: duracao ?? 10, ajuste: "caber-desfocado", volume: 1 });
    }
    setArquivos(novosArquivos);
    setInfos((atuais) => ({ ...atuais, ...novasInfos }));
    mudar((p) => ({ ...p, clipes: [...p.clipes, ...clipes] }));
  };

  const adicionarMusica = async (arquivo: File, entrada = 0, inteira = false) => {
    const chave = novoId("a");
    const duracao = (await duracaoDaMidia(arquivo)) ?? 30;
    setArquivos((atuais) => new Map(atuais).set(chave, arquivo));
    setInfos((atuais) => ({ ...atuais, [chave]: { nome: arquivo.name, duracao } }));
    const id = novoId("m");
    mudar((p) => ({
      ...p,
      audios: [...p.audios, { id, arquivo: chave, entrada, inicio: 0, fim: inteira ? duracao : Math.min(duracao, duracaoTotal(p) || duracao), volume: inteira ? 1 : 0.8, fadeEntrada: 0, fadeSaida: inteira ? 0 : 1 }],
    }));
    setAberto(id);
  };

  const adicionarCamada = (arquivo: File) => {
    if (!projeto || !total) { setErro("Adicione uma imagem ou vídeo na aba Clipes antes."); return; }
    if ((projeto.camadas?.length ?? 0) >= 12) { setErro("Use até 12 camadas adicionais."); return; }
    const tipo = arquivo.type === "image/gif" || /\.gif$/i.test(arquivo.name) ? "gif" : arquivo.type.startsWith("video/") ? "video" : "imagem";
    const chave = novoId("a"), id = novoId("camada");
    setArquivos(atuais => new Map(atuais).set(chave, arquivo));
    mudar(p => ({ ...p, versao: 4, camadas: [...(p.camadas ?? []), {
      id, arquivo: chave, tipo, inicio: Math.min(instante, Math.max(0, total - .1)), fim: total,
      ordem: (p.camadas?.length ?? 0) + 1, transformacao: { escala: .5 },
    }] }));
  };

  const mudarClipe = (id: string, m: (c: Clipe) => Clipe) => mudar((p) => ({ ...p, clipes: p.clipes.map((c) => (c.id === id ? m(c) : c)) }));
  const mudarTexto = (id: string, m: (t: Texto) => Texto) => mudar((p) => ({ ...p, textos: p.textos.map((t) => (t.id === id ? m(t) : t)) }));
  const mudarAudio = (id: string, m: (a: AudioExtra) => AudioExtra) => mudar((p) => ({ ...p, audios: p.audios.map((a) => (a.id === id ? m(a) : a)) }));
  const moverClipe = (id: string, passo: number) =>
    mudar((p) => {
      const lista = [...p.clipes];
      const de = lista.findIndex((c) => c.id === id);
      const para = Math.max(0, Math.min(lista.length - 1, de + passo));
      if (de < 0 || de === para) return p;
      lista.splice(para, 0, lista.splice(de, 1)[0]);
      return { ...p, clipes: lista };
    });

  const adicionarTexto = () => {
    const id = novoId("t");
    const inicio = Math.min(instante, Math.max(0, total - 0.5));
    mudar((p) => ({
      ...p,
      textos: [...p.textos, { id, texto: "Seu texto aqui", inicio, fim: Math.min(total || 3, inicio + 3), x: 0.5, y: 0.15, tamanho: 0.06, cor: "#FFFFFF", contorno: "#000000", caixa: null, fonte: "anton" }],
    }));
    setAberto(id);
  };

  const abrirSalvo = async (p: Projeto, midias: ReadonlyMap<string,File>) => {
    const novasInfos: Record<string,Info> = {};
    for(const [ref,f] of midias) novasInfos[ref]={nome:f.name,duracao:IMAGEM.test(f.name)?null:await duracaoDaMidia(f).catch(()=>null)};
    setTocando(false);setProjeto(p);setArquivos(midias);setInfos(novasInfos);setInstante(0);setResultado(null);setErro(null);setEtapa("editor");
  };
  const projetos = <ProjetosDoEstudio projeto={projeto} arquivos={arquivos} instante={instante} mudar={mudar} abrir={abrirSalvo} ocupado={!!andamento}/>;

  const comecarDeNovo = () => {
    if (!window.confirm("Começar outro projeto? Este será descartado (os arquivos originais continuam no seu computador).")) return;
    setProjeto(null);
    setArquivos(new Map());
    setInfos({});
    setInstante(0);
    setResultado(null);
    setEtapa("destino");
  };

  // ==================== exportar ====================

  const { plano, pro } = usePlano();
  const { cliente, ligado, apresentacao } = usePlugin();
  const [modeloLegendas,setModeloLegendas]=useState("base");
  const gerarLegendas=async()=>{
    if(!projeto || !ligado) return;
    if(projeto.versao>=4&&(apresentacao?.versaoDoEstudio??1)<4) {setErro("Atualize o plugin para usar projetos da Etapa 3 e gerar suas legendas.");return;}
    const original=projeto;
    const trava=conferirEstudio({...projeto,saida:"texto"},pro?"pro":plano,{exportacoesHoje:usoDeHoje("estudio"),transcricaoRestante:restanteHoje(plano,"transcricao")});
    if(trava) {setBloqueio(trava);return;}
    let cancelado=false;let id="";
    setErro(null);setTocando(false);
    const cancelar=()=>{cancelado=true;if(id) void cliente.cancelar(id);};
    setAndamento({etapa:"Gerando legendas",fracao:0,restante:null,cancelar});
    try {
      const pedido=pedidoParaOPlugin({...projeto,saida:"texto"},arquivos,modeloLegendas);
      pedido.opcoes.TranscriptionFormat="srt";
      const trabalho=await cliente.converterArquivos(pedido.arquivos,pedido.opcoes,"legendas");id=trabalho.id;
      if(cancelado) await cliente.cancelar(id);
      const final=await new Promise<typeof trabalho>(resolve=>{const parar=cliente.acompanhar(id,atual=>{
        setAndamento({etapa:"Gerando legendas",fracao:atual.progresso??0,restante:null,cancelar});
        if(atual.encerrado) {parar();resolve(atual);}
      });});
      if(cancelado) throw new ExportacaoCancelada();if(final.erro) throw new Error(final.erro);
      if(!final.arquivos.length || !final.arquivos[0].nome.toLowerCase().endsWith(".srt")) throw new Error("Atualize o plugin para gerar legendas temporizadas.");
      const resposta=await fetch(cliente.enderecoDoArquivo(final.id,final.arquivos[0].indice));
      if(!resposta.ok) throw new Error("Não foi possível ler as legendas do plugin.");
      const textos=textosDaLegenda(await resposta.text(),duracaoTotal(original));
      mudar(p=>p===original?({...p,textos:[...p.textos.filter(t=>!t.id.startsWith("legenda-")),...textos]}):p);
      if(!pro) registrarUso("transcricao",Math.ceil(duracaoTotal(original)/60));
    } catch(e) {if(!(e instanceof ExportacaoCancelada)) setErro(e instanceof Error?e.message:String(e));}
    finally {setAndamento(null);}
  };

  const exportar = async (forcarNavegador = false) => {
    if (!projeto) return;
    setErro(null);
    setBloqueio(null);
    setResultado(null);
    const peloPlugin = !forcarNavegador && ligado && (projeto.saida === "texto" || !cabeNoNavegador(projeto));
    if(peloPlugin&&projeto.versao>=4&&(apresentacao?.versaoDoEstudio??1)<4) {setErro("Atualize o plugin para exportar projetos da Etapa 3.");return;}
    const travou = conferirEstudio(projeto, pro ? "pro" : plano, { exportacoesHoje: usoDeHoje("estudio"), transcricaoRestante: restanteHoje(plano, "transcricao") });
    if (travou) {
      setBloqueio(travou);
      return;
    }
    let cancelado = false;
    const comeco = performance.now();
    const cancelar = () => {
      cancelado = true;
    };
    let ultimo = "";
    // Uma atualização por ponto percentual (a exportação avisa a cada quadro).
    const atualizar = (etapaAtual: string, fracao: number) => {
      const porcento = Math.floor(Math.max(0, Math.min(1, fracao)) * 100);
      const chave = `${etapaAtual}|${porcento}`;
      if (chave === ultimo) return;
      ultimo = chave;
      const passados = (performance.now() - comeco) / 1000;
      setAndamento({ etapa: etapaAtual, fracao, restante: fracao > 0.03 ? (passados * (1 - fracao)) / fracao : null, cancelar });
    };
    atualizar(peloPlugin ? "Enviando ao plugin" : "Preparando", 0);

    try {
      if (peloPlugin) {
        const pedido = pedidoParaOPlugin(projeto, arquivos);
        const trabalho = await cliente.converterArquivos(pedido.arquivos, pedido.opcoes, `estudio-${projeto.destino}`);
        const final = await new Promise<typeof trabalho>((resolva) => {
          const parar = cliente.acompanhar(trabalho.id, (atual) => {
            if (cancelado && !atual.encerrado) {
              void cliente.cancelar(trabalho.id);
            }
            atualizar(atual.etapa || "Montando no plugin", atual.progresso ?? 0);
            if (atual.encerrado) {
              parar();
              resolva(atual);
            }
          });
        });
        if (final.erro) throw new Error(final.erro);
        if (cancelado || final.arquivos.length === 0) throw new ExportacaoCancelada();
        if (!pro) registrarUso(projeto.saida === "texto" ? "transcricao" : "estudio", projeto.saida === "texto" ? Math.ceil(total / 60) : 1);
        const arquivo = final.arquivos[0];
        setResultado({ nome: arquivo.nome, url: cliente.enderecoDoArquivo(final.id, arquivo.indice), bytes: arquivo.bytes, doPlugin: true });
      } else {
        const pronto: Exportado = await exportarNoNavegador(projeto, arquivos, atualizar, () => cancelado);
        if (!pro) registrarUso("estudio");
        const url = URL.createObjectURL(new Blob([pronto.bytes], { type: pronto.mime }));
        setResultado({ nome: pronto.nome, url, bytes: pronto.bytes.byteLength, doPlugin: false });
      }
    } catch (falha) {
      if (!(falha instanceof ExportacaoCancelada)) setErro(falha instanceof Error ? falha.message : String(falha));
    } finally {
      setAndamento(null);
    }
  };

  // ==================== telas ====================

  if (etapa === "destino" || !projeto) {
    return (
      <main className="estudio">
        {projetos}
        <EscolhaDoDestino
          atual={projeto?.destino ?? null}
          voltar={projeto ? () => setEtapa("editor") : null}
          escolher={escolherDestino}
          personalizado={personalizado}
          mudarPersonalizado={setPersonalizado}
        />
      </main>
    );
  }

  if (etapa === "saida") {
    return (
      <main className="estudio">
        <section className="estudio__assistente" aria-labelledby="saida-titulo">
          <button type="button" className="estudio__voltar" onClick={() => setEtapa("destino")}>← Voltar</button>
          <h1 id="saida-titulo">O que você quer no fim?</h1>
          <p className="estudio__dica">Dá para trocar depois, na aba Exportar.</p>
          <div className="estudio__saidas">
            {SAIDAS.map((saida) => (
              <button key={saida.id} type="button" className={`estudio__cartao${projeto.saida === saida.id ? " estudio__cartao--ativo" : ""}`} onClick={() => escolherSaida(saida.id)}>
                <strong>{saida.titulo} · {saida.extensao.toUpperCase()}</strong>
                <span>{saida.descricao}{saida.id === "texto" ? " Pelo plugin." : ""}</span>
              </button>
            ))}
          </div>
        </section>
      </main>
    );
  }

  const preset = presetPorId(projeto.destino);
  return (
    <main className="estudio estudio--editor">
      <div className="estudio__topo">
        <div>
          <button type="button" className="estudio__titulo" onClick={() => setEtapa("destino")} title="Trocar o destino">
            {preset && preset.id !== "personalizado" ? `${preset.rede} · ${preset.nome}` : "Personalizado"}
            <small>{projeto.largura}×{projeto.altura} · trocar</small>
          </button>
        </div>
        <button type="button" className="button" onClick={comecarDeNovo}>Novo projeto</button>
      </div>

      {projetos}
      <LinhaDoTempo projeto={projeto} instante={instante} irPara={setInstante} selecionar={(id,tipo)=>{setAberto(id);setAba(tipo);}} />
      <div className="estudio__grade">
        <Previa
          projeto={projeto}
          arquivos={arquivos}
          instante={instante}
          tocando={tocando}
          irPara={setInstante}
          tocar={setTocando}
          aoSoltar={adicionarArquivos}
        />

        <section className="estudio__painel" aria-label="Edição">
          <div className="estudio__abas" role="tablist">
            {([["clipes", "Clipes"], ["camadas", "Camadas"], ["textos", "Textos e emoji"], ["audio", "Áudio"], ["exportar", "Exportar"]] as const).map(([id, rotulo]) => (
              <button key={id} type="button" role="tab" aria-selected={aba === id} className={`estudio__aba${aba === id ? " estudio__aba--ativa" : ""}`} onClick={() => setAba(id)}>
                {rotulo}
              </button>
            ))}
          </div>

          {aba === "clipes" && (
            <div className="estudio__lista">
              <EscolherArquivos rotulo="Adicionar vídeos ou fotos" aceita="video/*,image/*" multiplos aoEscolher={adicionarArquivos} />
              {projeto.clipes.length === 0 && <p className="estudio__dica">Os clipes tocam um depois do outro, na ordem da lista. Também dá para arrastar arquivos para a prévia.</p>}
              {projeto.clipes.map((clipe, indice) => (
                <CartaoDoClipe
                  key={clipe.id}
                  clipe={clipe}
                  indice={indice}
                  quantidade={projeto.clipes.length}
                  info={infos[clipe.arquivo]}
                  aberto={aberto === clipe.id}
                  abrir={() => setAberto(aberto === clipe.id ? null : clipe.id)}
                  mudar={(m) => mudarClipe(clipe.id, m)}
                  mover={(passo) => moverClipe(clipe.id, passo)}
                  remover={() => mudar((p) => ({ ...p, clipes: p.clipes.filter((c) => c.id !== clipe.id) }))}
                />
              ))}
            </div>
          )}

          {aba === "textos" && (
            <div className="estudio__lista">
              <button type="button" className="button button--primary estudio__largo" onClick={adicionarTexto} disabled={projeto.clipes.length === 0}>Adicionar texto</button>
              {projeto.clipes.length === 0 && <p className="estudio__dica">Adicione um vídeo ou foto antes: o texto aparece por cima deles.</p>}
              <p className="estudio__dica">Gere a legenda do áudio final. Após mudar cortes, velocidade ou transições, gere novamente; revise os textos antes de exportar.</p>
              <label className="estudio__campo">Modelo para legendar<select value={modeloLegendas} onChange={e=>setModeloLegendas(e.target.value)}>{["tiny","base","small","medium"].map(m=><option key={m} value={m}>{m}</option>)}</select></label>
              <button type="button" className="button" disabled={!ligado || !!andamento || !projeto.clipes.length} onClick={()=>void gerarLegendas()}>Gerar legendas automáticas</button>
              {!ligado && <p>As legendas automáticas usam o Whisper do plugin para Windows. Conecte o plugin para gerar.</p>}
              <label className="estudio__campo">Estilo de todas as legendas<select defaultValue="" onChange={e=>{const estilo=e.target.value;mudar(p=>({...p,textos:p.textos.map(t=>t.id.startsWith("legenda-")?{...t,cor:estilo==="amarela"?"#FFD54A":"#FFFFFF",contorno:"#000000",caixa:estilo==="caixa"?"#000000B3":null}:t)}));}}><option value="" disabled>Escolher estilo</option><option value="branca">Branca com contorno</option><option value="amarela">Amarela com contorno</option><option value="caixa">Branca com caixa</option></select></label>
              {projeto.textos.map((texto) => (
                <CartaoDoTexto
                  key={texto.id}
                  texto={texto}
                  total={total}
                  instante={instante}
                  aberto={aberto === texto.id}
                  abrir={() => setAberto(aberto === texto.id ? null : texto.id)}
                  mudar={(m) => mudarTexto(texto.id, m)}
                  remover={() => mudar((p) => ({ ...p, textos: p.textos.filter((t) => t.id !== texto.id) }))}
                />
              ))}
            </div>
          )}

          {aba === "camadas" && <PainelDeCamadas instante={instante} projeto={projeto} nomes={arquivos} mudar={mudar} importar={adicionarCamada} />}

          {aba === "audio" && (
            <div className="estudio__lista">
              <NarracaoDoEstudio projeto={projeto} instante={instante} desabilitado={!!andamento||!projeto.clipes.length} adicionar={(f,entrada)=>adicionarMusica(f,entrada,true)} />
              <EscolherArquivos rotulo="Adicionar música ou áudio" aceita="audio/*,video/*" aoEscolher={(lista) => lista[0] && void adicionarMusica(lista[0])} />
              {projeto.audios.length === 0 && <p className="estudio__dica">A música toca por cima do som dos vídeos. Escolha o trecho, onde ela entra, o volume e o fade.</p>}
              {projeto.audios.map((audio) => (
                <CartaoDoAudio
                  key={audio.id}
                  audio={audio}
                  info={infos[audio.arquivo]}
                  total={total}
                  aberto={aberto === audio.id}
                  abrir={() => setAberto(aberto === audio.id ? null : audio.id)}
                  mudar={(m) => mudarAudio(audio.id, m)}
                  remover={() => mudar((p) => ({ ...p, audios: p.audios.filter((a) => a.id !== audio.id) }))}
                />
              ))}
            </div>
          )}

          {aba === "exportar" && (
            <PainelDeExportar
              projeto={projeto}
              instante={instante}
              ligado={ligado}
              mudar={mudar}
              exportar={exportar}
              ocupado={andamento !== null}
              pro={pro}
            />
          )}

          {bloqueio && <Paywall bloqueio={bloqueio} />}
          {erro && <p className="estudio__erro" role="alert">{erro}</p>}
          {resultado && (
            <div className="estudio__resultado">
              <strong>Pronto</strong>
              <span>{resultado.nome} · {(resultado.bytes / 1_048_576).toFixed(1).replace(".", ",")} MB</span>
              <div className="estudio__linha">
                <a className="button button--primary" href={resultado.url} download={resultado.nome}>Baixar</a>
                {resultado.doPlugin && <button type="button" className="button" onClick={() => void cliente.abrirPasta()}>Abrir a pasta</button>}
              </div>
            </div>
          )}
        </section>
      </div>

      {andamento && <DialogoDeAndamento andamento={andamento} />}
    </main>
  );
}

// ==================== assistente ====================

function EscolhaDoDestino({
  atual,
  voltar,
  escolher,
  personalizado,
  mudarPersonalizado,
}: {
  atual: string | null;
  voltar: (() => void) | null;
  escolher: (preset: Preset, largura?: number, altura?: number) => void;
  personalizado: { largura: string; altura: string };
  mudarPersonalizado: (valor: { largura: string; altura: string }) => void;
}) {
  const grupos = useMemo(() => {
    const mapa = new Map<string, Preset[]>();
    for (const preset of PRESETS) if (preset.id !== "personalizado") mapa.set(preset.rede, [...(mapa.get(preset.rede) ?? []), preset]);
    return [...mapa.entries()];
  }, []);
  const largura = Number(personalizado.largura);
  const altura = Number(personalizado.altura);
  const valido = largura >= 16 && largura <= 4096 && altura >= 16 && altura <= 4096;
  return (
    <section className="estudio__assistente" aria-labelledby="destino-titulo">
      {voltar && <button type="button" className="estudio__voltar" onClick={voltar}>← Voltar ao editor</button>}
      <span className="estudio__selo">Estúdio · no seu computador</span>
      <h1 id="destino-titulo">Para onde é?</h1>
      <p className="estudio__dica">Escolha a rede e o formato: o estúdio ajusta o tamanho do quadro e avisa se passar do tempo que ela costuma aceitar. Nada é enviado para a internet.</p>
      {grupos.map(([rede, lista]) => (
        <div key={rede} className="estudio__rede">
          <h2>{rede}</h2>
          <div className="estudio__presets">
            {lista.map((preset) => (
              <button key={preset.id} type="button" className={`estudio__preset${atual === preset.id ? " estudio__cartao--ativo" : ""}`} onClick={() => escolher(preset)}>
                <span className="estudio__formato" style={{ aspectRatio: `${preset.largura} / ${preset.altura}` }} aria-hidden="true" />
                <span>
                  <strong>{preset.nome}</strong>
                  <small>{preset.largura}×{preset.altura}{preset.duracaoMaxima ? ` · até ${duracaoLegivel(preset.duracaoMaxima)}` : ""}</small>
                </span>
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="estudio__rede">
        <h2>Tamanho personalizado</h2>
        <div className="estudio__linha">
          <label className="estudio__campo">Largura (px)
            <input inputMode="numeric" value={personalizado.largura} onChange={(e) => mudarPersonalizado({ ...personalizado, largura: e.target.value.replace(/\D/g, "").slice(0, 4) })} />
          </label>
          <label className="estudio__campo">Altura (px)
            <input inputMode="numeric" value={personalizado.altura} onChange={(e) => mudarPersonalizado({ ...personalizado, altura: e.target.value.replace(/\D/g, "").slice(0, 4) })} />
          </label>
          <button type="button" className="button" disabled={!valido} onClick={() => escolher(presetPorId("personalizado")!, largura, altura)}>Usar este tamanho</button>
        </div>
      </div>
      <p className="estudio__dica">{NOTA_DOS_PRESETS}</p>
    </section>
  );
}

// ==================== prévia ====================

function Previa({
  projeto,
  arquivos,
  instante,
  tocando,
  irPara,
  tocar,
  aoSoltar,
}: {
  projeto: Projeto;
  arquivos: ReadonlyMap<string, File>;
  instante: number;
  tocando: boolean;
  irPara: (t: number) => void;
  tocar: (sim: boolean) => void;
  aoSoltar: (arquivos: File[]) => void;
}) {
  const tela = useRef<HTMLCanvasElement>(null);
  const previa = useRef<PreviaDoEstudio | null>(null);
  const [erroDaPrevia, setErroDaPrevia] = useState<string | null>(null);
  const total = duracaoTotal(projeto);
  const escala = Math.min(1, 720 / Math.max(projeto.largura, projeto.altura));
  const largura = Math.round(projeto.largura * escala);
  const altura = Math.round(projeto.altura * escala);

  useEffect(() => {
    if (!tela.current) return;
    const atual = new PreviaDoEstudio(tela.current);
    previa.current = atual;
    return () => {
      atual.liberar();
      previa.current = null;
    };
  }, []);

  useEffect(() => {
    let atual = true;
    if (!tocando) void previa.current?.desenhar(projeto, arquivos, instante)
      .then(() => { if (atual) setErroDaPrevia(null); })
      .catch((erro: unknown) => {
        if (atual && !(erro instanceof DOMException && erro.name === "AbortError"))
          setErroDaPrevia(erro instanceof Error ? erro.message : "Não foi possível desenhar a prévia.");
      });
    return () => { atual = false; };
  }, [projeto, arquivos, instante, tocando, largura, altura]);

  useEffect(() => {
    if (!tocando || !previa.current) return;
    setErroDaPrevia(null);
    return previa.current.tocar(projeto, arquivos, instante >= total - 0.05 ? 0 : instante, irPara, () => tocar(false),
      erro => setErroDaPrevia(erro instanceof Error ? erro.message : "Não foi possível reproduzir a prévia."));
    // Recomeça só quando a pessoa aperta tocar; mudar o projeto durante a reprodução para.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tocando]);

  const soltar = (evento: DragEvent<HTMLDivElement>) => {
    evento.preventDefault();
    aoSoltar([...evento.dataTransfer.files]);
  };

  return (
    <section className="estudio__previa" aria-label="Prévia">
      {erroDaPrevia && <p role="alert">{erroDaPrevia}</p>}
      <div className="estudio__tela" onDragOver={(e) => e.preventDefault()} onDrop={soltar}>
        <canvas ref={tela} width={largura} height={altura} style={{ aspectRatio: `${projeto.largura} / ${projeto.altura}` }} aria-label="Prévia do projeto" />
        {projeto.clipes.length === 0 && <p className="estudio__vazio">Adicione vídeos ou fotos (ou arraste para cá)</p>}
      </div>
      {total > 0 && (
        <>
          <div className="estudio__tempo">
            <button type="button" className="button button--primary" onClick={() => tocar(!tocando)} aria-label={tocando ? "Pausar" : "Tocar"}>
              {tocando ? "❚❚ Pausar" : "▶ Tocar"}
            </button>
            <span>{tempo(instante)} / {tempo(total)}</span>
          </div>
          <input
            className="estudio__linha-do-tempo"
            type="range"
            min={0}
            max={total}
            step={0.05}
            value={Math.min(instante, total)}
            onChange={(e) => {
              tocar(false);
              irPara(Number(e.target.value));
            }}
            aria-label="Posição na linha do tempo"
          />
        </>
      )}
    </section>
  );
}

// ==================== cartões ====================

function CartaoDoClipe({
  clipe,
  indice,
  quantidade,
  info,
  aberto,
  abrir,
  mudar,
  mover,
  remover,
}: {
  clipe: Clipe;
  indice: number;
  quantidade: number;
  info: Info | undefined;
  aberto: boolean;
  abrir: () => void;
  mudar: (m: (c: Clipe) => Clipe) => void;
  mover: (passo: number) => void;
  remover: () => void;
}) {
  const duracaoDoArquivo = info?.duracao ?? clipe.fim ?? 10;
  return (
    <div className="estudio__item">
      <div className="estudio__cabeca">
        <button type="button" className="estudio__nome" onClick={abrir} aria-expanded={aberto}>
          <strong>{indice + 1}. {info?.nome ?? (clipe.tipo === "imagem" ? "Foto" : "Vídeo")}</strong>
          <small>
            {clipe.tipo === "imagem" ? "Foto" : "Vídeo"} · {tempo(duracaoDoClipe(clipe))} · {AJUSTES.find((a) => a.id === clipe.ajuste)?.titulo}
            {clipe.mudo ? " · sem som" : ""}
          </small>
        </button>
        <button type="button" className="estudio__icone" onClick={() => mover(-1)} disabled={indice === 0} aria-label="Subir">↑</button>
        <button type="button" className="estudio__icone" onClick={() => mover(1)} disabled={indice === quantidade - 1} aria-label="Descer">↓</button>
        <button type="button" className="estudio__icone" onClick={remover} aria-label="Remover">✕</button>
      </div>
      {aberto && (
        <div className="estudio__detalhe">
          {clipe.tipo === "imagem" ? (
            <Controle rotulo={`Na tela por ${decimal(clipe.duracao ?? 3)} s`}>
              <input type="range" min={0.5} max={15} step={0.5} value={clipe.duracao ?? 3} onChange={(e) => mudar((c) => ({ ...c, duracao: Number(e.target.value) }))} />
            </Controle>
          ) : (
            <>
              <Controle rotulo={`Começa em ${tempo(clipe.inicio ?? 0)}`}>
                <input type="range" min={0} max={duracaoDoArquivo} step={0.05} value={clipe.inicio ?? 0}
                  onChange={(e) => mudar((c) => ({ ...c, inicio: Math.min(Number(e.target.value), (c.fim ?? duracaoDoArquivo) - 0.1) }))} />
              </Controle>
              <Controle rotulo={`Termina em ${tempo(clipe.fim ?? duracaoDoArquivo)}`}>
                <input type="range" min={0} max={duracaoDoArquivo} step={0.05} value={clipe.fim ?? duracaoDoArquivo}
                  onChange={(e) => mudar((c) => ({ ...c, fim: Math.max(Number(e.target.value), (c.inicio ?? 0) + 0.1) }))} />
              </Controle>
            </>
          )}
          <Controle rotulo="Enquadramento">
            <div className="estudio__chips">
              {AJUSTES.map((ajuste) => (
                <button key={ajuste.id} type="button" className={`estudio__chip${clipe.ajuste === ajuste.id ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((c) => ({ ...c, ajuste: ajuste.id }))} title={ajuste.descricao}>
                  {ajuste.titulo}
                </button>
              ))}
            </div>
          </Controle>
          {clipe.tipo==="video" && <Controle rotulo={`Velocidade: ${(clipe.velocidade??1).toFixed(2)}×`}><input type="range" min={.25} max={4} step={.05} value={clipe.velocidade??1} onChange={e=>mudar(c=>({...c,velocidade:Number(e.target.value)}))}/></Controle>}
          <label className="estudio__campo">Transição para o próximo clipe<select value={clipe.transicao?.tipo??"corte"} onChange={e=>mudar(c=>({...c,transicao:{tipo:e.target.value as Transicao["tipo"],duracao:c.transicao?.duracao??.5}}))}>{TRANSICOES.map(t=><option key={t.id} value={t.id}>{t.titulo}</option>)}</select></label>
          {clipe.transicao && clipe.transicao.tipo!=="corte" && <Controle rotulo={`Duração da transição: ${clipe.transicao.duracao.toFixed(1)} s`}><input type="range" min={.1} max={5} step={.1} value={clipe.transicao.duracao} onChange={e=>mudar(c=>({...c,transicao:{tipo:c.transicao!.tipo,duracao:Number(e.target.value)}}))}/><small>Limitada à metade dos clipes vizinhos. O último clipe termina sem transição.</small></Controle>}
          <ControlesDeCamada duracao={duracaoDoClipe(clipe)} valor={clipe.transformacao} mudar={transformacao => mudar(c => ({ ...c, transformacao }))} />
          {clipe.tipo === "video" && (
            <>
              <label className="estudio__chave">
                <input type="checkbox" checked={!clipe.mudo} onChange={(e) => mudar((c) => ({ ...c, mudo: !e.target.checked }))} /> Som do vídeo
              </label>
              {!clipe.mudo && (
                <Controle rotulo={`Volume: ${Math.round((clipe.volume ?? 1) * 100)}%`}>
                  <input type="range" min={0} max={2} step={0.05} value={clipe.volume ?? 1} onChange={(e) => mudar((c) => ({ ...c, volume: Number(e.target.value) }))} />
                </Controle>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function CartaoDoTexto({
  texto,
  total,
  instante,
  aberto,
  abrir,
  mudar,
  remover,
}: {
  texto: Texto;
  total: number;
  instante: number;
  aberto: boolean;
  abrir: () => void;
  mudar: (m: (t: Texto) => Texto) => void;
  remover: () => void;
}) {
  return (
    <div className="estudio__item">
      <div className="estudio__cabeca">
        <button type="button" className="estudio__nome" onClick={abrir} aria-expanded={aberto}>
          <strong>{texto.texto.trim() || "(vazio)"}</strong>
          <small>{tempo(texto.inicio)} → {tempo(texto.fim)}</small>
        </button>
        <button type="button" className="estudio__icone" onClick={remover} aria-label="Remover">✕</button>
      </div>
      {aberto && (
        <div className="estudio__detalhe">
          <label className="estudio__campo">Texto
            <textarea rows={2} maxLength={300} value={texto.texto} onChange={(e) => mudar((t) => ({ ...t, texto: e.target.value }))} />
          </label>
          <div className="estudio__chips">
            {["😀", "😂", "😍", "😎", "🔥", "❤️", "🎉", "👍"].map(emoji => <button key={emoji} type="button" className="button" onClick={() => mudar(t => ({ ...t, texto: t.texto === "Seu texto aqui" ? emoji : t.texto + emoji }))}>{emoji}</button>)}
          </div>
          <ControlesDeCamada duracao={texto.fim-texto.inicio} valor={texto.transformacao} permitirRecorte={false} mudar={transformacao => mudar(t => ({ ...t, transformacao }))} />
          <Controle rotulo="Fonte">
            <div className="estudio__chips">
              {FONTES.map((fonte) => (
                <button key={fonte.id} type="button" className={`estudio__chip${texto.fonte === fonte.id ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((t) => ({ ...t, fonte: fonte.id }))}>{fonte.nome}</button>
              ))}
            </div>
          </Controle>
          <Controle rotulo="Cor">
            <div className="estudio__chips">
              {CORES.map((cor) => (
                <button key={cor} type="button" className={`estudio__cor${texto.cor.toUpperCase() === cor ? " estudio__cor--ativa" : ""}`} style={{ background: cor }} onClick={() => mudar((t) => ({ ...t, cor }))} aria-label={`Cor ${cor}`} />
              ))}
            </div>
          </Controle>
          <label className="estudio__chave">
            <input type="checkbox" checked={texto.contorno !== null} onChange={(e) => mudar((t) => ({ ...t, contorno: e.target.checked ? (t.cor.toUpperCase() === "#000000" ? "#FFFFFF" : "#000000") : null }))} /> Contorno
          </label>
          <label className="estudio__chave">
            <input type="checkbox" checked={texto.caixa !== null} onChange={(e) => mudar((t) => ({ ...t, caixa: e.target.checked ? "#000000AA" : null }))} /> Caixa de fundo
          </label>
          <Controle rotulo={`Tamanho: ${Math.round(texto.tamanho * 1000) / 10}% da altura`}>
            <input type="range" min={0.03} max={0.16} step={0.005} value={texto.tamanho} onChange={(e) => mudar((t) => ({ ...t, tamanho: Number(e.target.value) }))} />
          </Controle>
          <Controle rotulo="Posição">
            <div className="estudio__chips">
              {([["Em cima", 0.15], ["No meio", 0.5], ["Embaixo", 0.85]] as const).map(([rotulo, y]) => (
                <button key={rotulo} type="button" className={`estudio__chip${Math.abs(texto.y - y) < 0.01 ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((t) => ({ ...t, y, x: 0.5 }))}>{rotulo}</button>
              ))}
            </div>
          </Controle>
          <Controle rotulo={`Altura: ${Math.round(texto.y * 100)}%`}>
            <input type="range" min={0} max={1} step={0.01} value={texto.y} onChange={(e) => mudar((t) => ({ ...t, y: Number(e.target.value) }))} />
          </Controle>
          {total > 0 && (
            <>
              <Controle rotulo={`Aparece em ${tempo(texto.inicio)}`}>
                <input type="range" min={0} max={total} step={0.1} value={Math.min(texto.inicio, total)} onChange={(e) => mudar((t) => ({ ...t, inicio: Math.min(Number(e.target.value), t.fim - 0.1) }))} />
              </Controle>
              <Controle rotulo={`Some em ${tempo(texto.fim)}`}>
                <input type="range" min={0} max={total} step={0.1} value={Math.min(texto.fim, total)} onChange={(e) => mudar((t) => ({ ...t, fim: Math.max(Number(e.target.value), t.inicio + 0.1) }))} />
              </Controle>
              <div className="estudio__linha">
                <button type="button" className="button" onClick={() => mudar((t) => ({ ...t, inicio: instante, fim: Math.max(t.fim, instante + 0.5) }))}>Começa aqui</button>
                <button type="button" className="button" onClick={() => mudar((t) => ({ ...t, fim: Math.max(instante, t.inicio + 0.5) }))}>Termina aqui</button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

function CartaoDoAudio({
  audio,
  info,
  total,
  aberto,
  abrir,
  mudar,
  remover,
}: {
  audio: AudioExtra;
  info: Info | undefined;
  total: number;
  aberto: boolean;
  abrir: () => void;
  mudar: (m: (a: AudioExtra) => AudioExtra) => void;
  remover: () => void;
}) {
  const duracaoDoArquivo = info?.duracao ?? audio.fim;
  return (
    <div className="estudio__item">
      <div className="estudio__cabeca">
        <button type="button" className="estudio__nome" onClick={abrir} aria-expanded={aberto}>
          <strong>{info?.nome ?? "Áudio"}</strong>
          <small>Trecho {tempo(audio.inicio)}–{tempo(audio.fim)} · entra em {tempo(audio.entrada ?? 0)} · {Math.round((audio.volume ?? 1) * 100)}%</small>
        </button>
        <button type="button" className="estudio__icone" onClick={remover} aria-label="Remover">✕</button>
      </div>
      {aberto && (
        <div className="estudio__detalhe">
          <Controle rotulo={`Começa a música em ${tempo(audio.inicio)}`}>
            <input type="range" min={0} max={duracaoDoArquivo} step={0.1} value={audio.inicio} onChange={(e) => mudar((a) => ({ ...a, inicio: Math.min(Number(e.target.value), a.fim - 0.1) }))} />
          </Controle>
          <Controle rotulo={`Termina a música em ${tempo(audio.fim)}`}>
            <input type="range" min={0} max={duracaoDoArquivo} step={0.1} value={audio.fim} onChange={(e) => mudar((a) => ({ ...a, fim: Math.max(Number(e.target.value), a.inicio + 0.1) }))} />
          </Controle>
          {total > 0 && (
            <Controle rotulo={`Entra no vídeo em ${tempo(audio.entrada ?? 0)}`}>
              <input type="range" min={0} max={total} step={0.1} value={Math.min(audio.entrada ?? 0, total)} onChange={(e) => mudar((a) => ({ ...a, entrada: Number(e.target.value) }))} />
            </Controle>
          )}
          <Controle rotulo={`Volume: ${Math.round((audio.volume ?? 1) * 100)}%`}>
            <input type="range" min={0} max={2} step={0.05} value={audio.volume ?? 1} onChange={(e) => mudar((a) => ({ ...a, volume: Number(e.target.value) }))} />
          </Controle>
          <Controle rotulo="Fade de entrada">
            <div className="estudio__chips">
              {[0, 1, 2, 3].map((s) => (
                <button key={s} type="button" className={`estudio__chip${(audio.fadeEntrada ?? 0) === s ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((a) => ({ ...a, fadeEntrada: s }))}>{s} s</button>
              ))}
            </div>
          </Controle>
          <Controle rotulo="Fade de saída">
            <div className="estudio__chips">
              {[0, 1, 2, 3].map((s) => (
                <button key={s} type="button" className={`estudio__chip${(audio.fadeSaida ?? 0) === s ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((a) => ({ ...a, fadeSaida: s }))}>{s} s</button>
              ))}
            </div>
          </Controle>
        </div>
      )}
    </div>
  );
}

function PainelDeExportar({
  projeto,
  instante,
  ligado,
  mudar,
  exportar,
  ocupado,
  pro,
}: {
  projeto: Projeto;
  instante: number;
  ligado: boolean;
  mudar: (m: (p: Projeto) => Projeto) => void;
  exportar: (forcarNavegador?: boolean) => void;
  ocupado: boolean;
  pro: boolean;
}) {
  const erros = problemas(projeto);
  const alertas = avisos(projeto);
  const leve = cabeNoNavegador(projeto);
  const saida = saidaPorId(projeto.saida);
  const precisaDoPlugin = projeto.saida === "texto";
  return (
    <div className="estudio__lista">
      <Controle rotulo="Exportar como">
        <div className="estudio__chips">
          {SAIDAS.map((s) => (
            <button key={s.id} type="button" className={`estudio__chip${projeto.saida === s.id ? " estudio__chip--ativo" : ""}`} onClick={() => mudar((p) => ({ ...p, saida: s.id }))}>{s.titulo}</button>
          ))}
        </div>
      </Controle>
      <p className="estudio__dica">{saida?.descricao} · {projeto.largura}×{projeto.altura} · {duracaoLegivel(duracaoTotal(projeto))}</p>
      {projeto.saida === "imagem" && (
        <div className="estudio__linha">
          <span>Quadro em {tempo(projeto.quadro)}</span>
          <button type="button" className="button" onClick={() => mudar((p) => ({ ...p, quadro: instante }))}>Usar o ponto da prévia ({tempo(instante)})</button>
        </div>
      )}
      {erros.map((e) => <p key={e} className="estudio__erro">• {e}</p>)}
      {!pro && !precisaDoPlugin && <CotaDoGratis />}
      {alertas.map((a) => <p key={a} className="estudio__aviso">• {a}</p>)}
      {precisaDoPlugin && !ligado && (
        <p className="estudio__aviso">A transcrição usa o Whisper pelo plugin, no seu computador. <a href="/plugin">Instale o plugin grátis</a> e abra esta página de novo.</p>
      )}
      {!precisaDoPlugin && !leve && (
        <p className="estudio__aviso">
          {ligado
            ? "Projeto grande: vai ser montado pelo plugin, com o FFmpeg da sua máquina."
            : "Projeto grande para o navegador (mais de 3 minutos ou acima de 1080p). Com o plugin fica bem mais rápido; dá para tentar aqui mesmo, com paciência."}
        </p>
      )}
      <button
        type="button"
        className="button button--primary estudio__largo"
        disabled={erros.length > 0 || ocupado || (precisaDoPlugin && !ligado)}
        onClick={() => exportar()}
      >
        Exportar {saida?.titulo.toLowerCase()}
        {(precisaDoPlugin || (!leve && ligado)) ? " pelo plugin" : " no navegador"}
      </button>
      {!precisaDoPlugin && ligado && !leve && (
        <button type="button" className="button estudio__largo" disabled={erros.length > 0 || ocupado} onClick={() => exportar(true)}>Exportar no navegador mesmo assim</button>
      )}
    </div>
  );
}

// ==================== peças ====================

/** O que ainda cabe hoje no grátis, dito antes de a pessoa esbarrar no limite. */
function CotaDoGratis() {
  const porDia = limiteDoPlano("gratis", "estudio") ?? 0;
  const duracao = limiteDoPlano("gratis", "estudioDuracao") ?? 0;
  // O editor só existe no navegador (depois de escolher o destino), então o localStorage já
  // está à mão; cada exportação re-renderiza o painel e relê o contador.
  const restante = restanteHoje("gratis", "estudio") ?? porDia;
  return (
    <p className="estudio__dica">
      Grátis: {restante} de {porDia} exportações hoje · projetos até {duracaoLegivel(duracao)} ·
      remoção de fundo em fotos, stickers e GIFs. <a href="/precos?origem=estudio">No Pro, sem limite e com fundo removido também em vídeo.</a>
    </p>
  );
}

function EscolherArquivos({ rotulo, aceita, multiplos = false, aoEscolher }: { rotulo: string; aceita: string; multiplos?: boolean; aoEscolher: (arquivos: File[]) => void }) {
  const campo = useRef<HTMLInputElement>(null);
  const escolhidos = (evento: ChangeEvent<HTMLInputElement>) => {
    aoEscolher([...(evento.target.files ?? [])]);
    evento.target.value = "";
  };
  return (
    <>
      <button type="button" className="button button--primary estudio__largo" onClick={() => campo.current?.click()}>{rotulo}</button>
      <input ref={campo} type="file" accept={aceita} multiple={multiplos} hidden onChange={escolhidos} />
    </>
  );
}

function Controle({ rotulo, children }: { rotulo: string; children: React.ReactNode }) {
  return (
    <div className="estudio__controle">
      <span>{rotulo}</span>
      {children}
    </div>
  );
}

function DialogoDeAndamento({ andamento }: { andamento: Andamento }) {
  const porcentagem = Math.floor(Math.max(0, Math.min(1, andamento.fracao)) * 100);
  const { restante } = andamento;
  return (
    <div className="estudio__andamento" role="dialog" aria-modal="true" aria-label="Exportando">
      <div>
        <strong>{andamento.etapa}…</strong>
        <div className="estudio__barra"><span style={{ width: `${porcentagem}%` }} /></div>
        <span>{porcentagem}%{restante !== null ? ` · faltam cerca de ${duracaoLegivel(Math.max(1, restante))}` : ""}</span>
        <button type="button" className="button" onClick={andamento.cancelar}>Cancelar</button>
      </div>
    </div>
  );
}

/** "01:05,3": minutos, segundos e décimos. */
function tempo(segundos: number): string {
  const decimos = Math.max(0, Math.round(segundos * 10));
  return `${String(Math.floor(decimos / 600)).padStart(2, "0")}:${String(Math.floor(decimos / 10) % 60).padStart(2, "0")},${decimos % 10}`;
}

function decimal(valor: number): string {
  return Number.isInteger(valor) ? String(valor) : valor.toFixed(1).replace(".", ",");
}
