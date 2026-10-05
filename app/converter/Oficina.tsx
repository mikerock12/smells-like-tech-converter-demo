"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ChangeEvent, type DragEvent } from "react";

import { kindOf, getFormat } from "@/packages/converter-core/index.mjs";
import type { FormatId } from "@/packages/converter-core/index.mjs";
import {
  aceitaOsFormatos,
  extensoesAceitas,
  ferramenta as buscarFerramenta,
  ferramentasPara,
  type Ferramenta,
  type TipoDeMidia,
} from "@/packages/converter-core/catalogo.mjs";

import { limite as limiteDoPlano } from "@/packages/converter-core/precos.mjs";

import { opcoesPadrao, type Opcoes } from "@/lib/converter/ferramentas";
import { conferirLimites } from "@/lib/converter/limites";
import { Oficina as FilaLocal, identificar, type Job } from "@/lib/converter/queue";
import { anotarUso } from "@/lib/conta/uso";
import { receberCompartilhados } from "@/lib/pwa/compartilhados";
import { registrarUso } from "@/lib/plano/contadores";
import { usePlano } from "@/lib/plano/usePlano";
import { usePlugin } from "@/lib/plugin/usePlugin";
import { opcoesDoPlugin } from "@/lib/plugin/trabalhos";
import type { Trabalho } from "@/lib/plugin/protocolo";

import ConviteDoApp from "./ConviteDoApp";
import Fila, { type TrabalhoDoPlugin } from "./Fila";
import GrupoDeArquivos, { type Grupo, type Item } from "./GrupoDeArquivos";
import PluginPanel from "./PluginPanel";
import StatusOffline from "../StatusOffline";
import KokoroPanel from "./KokoroPanel";

const NO_JOBS: Job[] = [];
const SEM_OPCOES: Readonly<Opcoes> = Object.freeze({});
const ACEITOS = extensoesAceitas().join(",");

/**
 * A oficina.
 *
 * Solte qualquer arquivo: imagem, áudio, vídeo, PDF, legenda. Ele cai num cartão do
 * seu tipo, o cartão pergunta o que fazer, e a conversão acontece aqui — no navegador
 * ou, quando é pesada, pelo plugin na sua máquina. Nunca num servidor.
 */
export default function Oficina({
  ferramentaInicial = null,
  formatoInicial = null,
  opcoesIniciais = SEM_OPCOES,
  titulo = null,
  privacidade = null,
}: {
  ferramentaInicial?: string | null;
  formatoInicial?: FormatId | null;
  /** Ajustes que a página de busca já deixa escolhidos ("normalizar": ligado). */
  opcoesIniciais?: Readonly<Opcoes>;
  /** H1 da página de busca, igual ao que a pessoa digitou. */
  titulo?: string | null;
  /** A linha de privacidade que abre a página de busca, com o teste para conferir. */
  privacidade?: string | null;
}) {
  const [grupos, setGrupos] = useState<Grupo[]>([]);
  const [texto, setTexto] = useState("");
  const [dragging, setDragging] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  const [trabalhosDoPlugin, setTrabalhosDoPlugin] = useState<TrabalhoDoPlugin[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  const acompanhamentos = useRef(new Map<string, () => void>());

  const { plano, pro } = usePlano();
  const { cliente, estado, ligado, apresentacao } = usePlugin();

  const fila = useMemo(() => new FilaLocal(), []);
  const jobs = useSyncExternalStore(fila.subscribe, fila.getSnapshot, () => NO_JOBS);

  useEffect(() => {
    const mapa = acompanhamentos.current;
    return () => {
      fila.dispose();
      for (const parar of mapa.values()) parar();
    };
  }, [fila]);

  const inicial = ferramentaInicial ? buscarFerramenta(ferramentaInicial) : null;

  // ==================== entrada de arquivos ====================

  const adicionarItens = useCallback(
    (novos: { item: Item; tipo: TipoDeMidia }[]) => {
      if (novos.length === 0) return;
      setGrupos((atuais) => {
        const proximos = [...atuais];
        for (const { item, tipo } of novos) {
          const indice = proximos.findIndex((grupo) => grupo.tipo === tipo);
          if (indice >= 0) {
            const grupo = proximos[indice];
            const itens = [...grupo.itens, item];
            // Um formato novo no cartão pode tirar a ferramenta escolhida do alcance
            // (um PNG entrando junto de GIFs no "GIF → vídeo").
            const atual = buscarFerramenta(grupo.ferramenta);
            const formatos = itens.map((existente) => existente.formato ?? null);
            if (atual && !aceitaOsFormatos(atual, formatos)) {
              const outra = escolherFerramenta(tipo, null, formatos);
              proximos[indice] = { ...grupo, itens, ferramenta: outra.id, opcoes: opcoesPadrao(outra.id, null), aviso: null, bloqueio: null };
            } else {
              proximos[indice] = { ...grupo, itens, aviso: null, bloqueio: null };
            }
            continue;
          }
          const ferramenta = escolherFerramenta(tipo, inicial, [item.formato ?? null]);
          proximos.push({
            id: crypto.randomUUID(),
            tipo,
            itens: [item],
            ferramenta: ferramenta.id,
            opcoes:
              inicial?.id === ferramenta.id
                ? { ...opcoesPadrao(ferramenta.id, formatoInicial), ...opcoesIniciais }
                : opcoesPadrao(ferramenta.id, null),
            peloPlugin: false,
            aviso: null,
            bloqueio: null,
            convertendo: false,
          });
        }
        return proximos;
      });
    },
    [inicial, formatoInicial, opcoesIniciais],
  );

  const addFiles = useCallback(
    async (files: FileList | File[] | null) => {
      if (!files || files.length === 0) return;
      setAviso(null);

      const novos: { item: Item; tipo: TipoDeMidia }[] = [];
      const recusados: string[] = [];

      for (const file of Array.from(files)) {
        const formato = await identificar(file);
        const tipo = formato ? kindOf(formato) : null;
        if (!formato || !tipo || ferramentasPara(tipo, [formato]).length === 0) {
          recusados.push(file.name);
          continue;
        }
        novos.push({ item: { id: crypto.randomUUID(), nome: file.name, bytes: file.size, arquivo: file, formato }, tipo });
      }

      adicionarItens(novos);
      if (recusados.length > 0) {
        setAviso(
          `${recusados.length === 1 ? `“${recusados[0]}” não é` : `${recusados.length} arquivos não são`} de um tipo que a oficina converte. Aceitamos imagem, áudio, vídeo, PDF e legenda.`,
        );
      }
    },
    [adicionarItens],
  );

  // Arquivos compartilhados com o app (Android: Compartilhar → Converter) entram como se
  // tivessem sido soltos aqui. Eles vêm do Cache Storage do aparelho, não da rede.
  useEffect(() => {
    void receberCompartilhados().then((recebidos) => {
      if (!recebidos) return;
      if (recebidos.falhou) {
        setAviso("O arquivo compartilhado não chegou até a oficina. Abra o app pela tela inicial e compartilhe de novo.");
        return;
      }
      void addFiles(recebidos.arquivos);
    });
  }, [addFiles]);

  const onDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      setDragging(false);
      void addFiles(event.dataTransfer.files);
    },
    [addFiles],
  );

  const onSelect = useCallback(
    (event: ChangeEvent<HTMLInputElement>) => {
      void addFiles(event.target.files);
      event.target.value = "";
    },
    [addFiles],
  );

  const doDisco = useCallback(
    (escolhido: { caminho: string; nome: string; bytes: number }) => {
      const extensao = escolhido.nome.split(".").pop()?.toLowerCase() ?? "";
      const formato = (getFormat(extensao) ? extensao : ({ jpeg: "jpg", m4v: "mp4", tif: "tiff", heif: "heic", oga: "ogg", opus: "ogg", wave: "wav", markdown: "md", htm: "html" } as Record<string, string>)[extensao]) as FormatId | undefined;
      const tipo = formato ? kindOf(formato) : null;
      if (!formato || !tipo || ferramentasPara(tipo, [formato]).length === 0) {
        setAviso(`“${escolhido.nome}” não é de um tipo que a oficina converte.`);
        return;
      }
      adicionarItens([{ item: { id: crypto.randomUUID(), nome: escolhido.nome, bytes: escolhido.bytes, caminho: escolhido.caminho, formato: formato ?? null }, tipo }]);
    },
    [adicionarItens],
  );

  const narrar = useCallback(() => {
    const conteudo = texto.trim();
    if (!conteudo) return;
    const arquivo = new File([conteudo], "narracao.txt", { type: "text/plain" });
    adicionarItens([{ item: { id: crypto.randomUUID(), nome: `texto (${conteudo.length} caracteres).txt`, bytes: arquivo.size, arquivo, formato: "txt" }, tipo: "texto" }]);
    setTexto("");
  }, [adicionarItens, texto]);

  // ==================== grupos ====================

  const mudarGrupo = useCallback((id: string, mudanca: Partial<Grupo>) => {
    setGrupos((atuais) =>
      atuais.map((grupo) => {
        if (grupo.id !== id) return grupo;
        const proximo = { ...grupo, ...mudanca };
        if (mudanca.ferramenta && mudanca.ferramenta !== grupo.ferramenta) {
          proximo.opcoes = opcoesPadrao(mudanca.ferramenta, null);
        }
        // Mudou a escolha: o limite que travou pode não valer mais.
        if (mudanca.ferramenta !== undefined || mudanca.opcoes !== undefined || mudanca.peloPlugin !== undefined) {
          proximo.bloqueio = null;
        }
        return proximo;
      }),
    );
  }, []);

  const removerItem = useCallback((grupoId: string, itemId: string) => {
    setGrupos((atuais) =>
      atuais
        .map((grupo) => (grupo.id === grupoId ? { ...grupo, itens: grupo.itens.filter((item) => item.id !== itemId), bloqueio: null } : grupo))
        .filter((grupo) => grupo.itens.length > 0),
    );
  }, []);

  const moverItem = useCallback((grupoId: string, itemId: string, direcao: -1 | 1) => {
    setGrupos((atuais) =>
      atuais.map((grupo) => {
        if (grupo.id !== grupoId) return grupo;
        const itens = [...grupo.itens];
        const de = itens.findIndex((item) => item.id === itemId);
        const para = de + direcao;
        if (de < 0 || para < 0 || para >= itens.length) return grupo;
        [itens[de], itens[para]] = [itens[para], itens[de]];
        return { ...grupo, itens };
      }),
    );
  }, []);

  const fecharGrupo = useCallback((id: string) => setGrupos((atuais) => atuais.filter((grupo) => grupo.id !== id)), []);

  // ==================== conversão ====================

  const acompanhar = useCallback(
    (id: string, trabalho: Trabalho) => {
      const parar = cliente.acompanhar(trabalho.id, (atualizado) => {
        setTrabalhosDoPlugin((atuais) => atuais.map((item) => (item.id === id ? { ...item, trabalho: atualizado } : item)));
        if (atualizado.encerrado) acompanhamentos.current.delete(id);
      });
      acompanhamentos.current.set(id, parar);
    },
    [cliente],
  );

  const converter = useCallback(
    async (grupo: Grupo) => {
      const ferramenta = buscarFerramenta(grupo.ferramenta);
      if (!ferramenta) return;
      mudarGrupo(grupo.id, { convertendo: true, aviso: null, bloqueio: null });

      try {
        const caracteres = grupo.tipo === "texto" ? grupo.itens.reduce((soma, item) => soma + item.bytes, 0) : 0;
        const bloqueio = await conferirLimites(plano, ferramenta, grupo.itens, { caracteres });
        if (bloqueio) {
          mudarGrupo(grupo.id, { convertendo: false, bloqueio });
          return;
        }

        const doDisco = grupo.itens.some((item) => item.caminho);
        const viaPlugin = !ferramenta.navegador || doDisco || (grupo.peloPlugin && ferramenta.plugin !== null && ligado);

        if (!viaPlugin) {
          const arquivos = grupo.itens.map((item) => item.arquivo).filter((arquivo): arquivo is File => Boolean(arquivo));
          await fila.add(ferramenta.id, arquivos, grupo.opcoes);
          if (!pro && ferramenta.limite === "ocr") registrarUso("ocr", arquivos.length);
        } else {
          if (!ligado || !apresentacao) {
            mudarGrupo(grupo.id, { convertendo: false, aviso: "O plugin não está ligado neste computador." });
            return;
          }
          const operacao = apresentacao.operacoes.find((item) => item.id === ferramenta.plugin);
          if (ferramenta.id === "voz.narrar" && !apresentacao.maquina.idsDasVozes?.includes(String(grupo.opcoes.voz ?? "pf_dora"))) {
            mudarGrupo(grupo.id, { convertendo: false, aviso: "Atualize o plugin para 0.4.0 ou mais novo, com Kokoro-82M. Para arquivos escolhidos no navegador, você também pode narrar localmente sem plugin." });
            return;
          }
          if (!operacao?.disponivel) {
            mudarGrupo(grupo.id, {
              convertendo: false,
              aviso: `O plugin desta máquina não consegue “${ferramenta.titulo}” agora${operacao?.impedimento ? `: ${operacao.impedimento}` : ""}.`,
            });
            return;
          }

          const opcoes = opcoesDoPlugin(ferramenta, grupo.opcoes);
          for (const item of grupo.itens) {
            const id = crypto.randomUUID();
            setTrabalhosDoPlugin((atuais) => [...atuais, { id, titulo: ferramenta.titulo, nome: item.nome, bytes: item.bytes, trabalho: null, erro: null }]);
            try {
              const nomeDeSaida = item.nome.replace(/\.[^.]+$/, "");
              const criado = item.caminho
                ? await cliente.converterCaminho(item.caminho, opcoes, nomeDeSaida)
                : await cliente.converterArquivo(item.arquivo!, opcoes, nomeDeSaida);
              setTrabalhosDoPlugin((atuais) => atuais.map((registro) => (registro.id === id ? { ...registro, trabalho: criado } : registro)));
              acompanhar(id, criado);
            } catch (erro) {
              setTrabalhosDoPlugin((atuais) =>
                atuais.map((registro) => (registro.id === id ? { ...registro, erro: erro instanceof Error ? erro.message : "O plugin recusou a conversão." } : registro)),
              );
            }
          }
          if (!pro) {
            if (ferramenta.limite === "transcricao") registrarUso("transcricao", Math.ceil(grupo.itens.length));
            else if (ferramenta.limite === "narracao") registrarUso("narracao", caracteres);
            else if (ferramenta.limite === "ocr") registrarUso("ocr", grupo.itens.length);
            else registrarUso("pesado", grupo.itens.length);
          }
        }

        anotarUso(ferramenta.id, grupo.itens.length, grupo.itens.reduce((soma, item) => soma + item.bytes, 0));
        fecharGrupo(grupo.id);
      } catch (erro) {
        mudarGrupo(grupo.id, { convertendo: false, aviso: erro instanceof Error ? erro.message : "Não foi possível começar." });
      }
    },
    [acompanhar, apresentacao, cliente, fecharGrupo, fila, ligado, mudarGrupo, plano, pro],
  );

  const cancelarNoPlugin = useCallback(
    (id: string) => {
      const registro = trabalhosDoPlugin.find((item) => item.id === id);
      if (registro?.trabalho) void cliente.cancelar(registro.trabalho.id);
    },
    [cliente, trabalhosDoPlugin],
  );

  const removerDoPlugin = useCallback((id: string) => {
    acompanhamentos.current.get(id)?.();
    acompanhamentos.current.delete(id);
    setTrabalhosDoPlugin((atuais) => atuais.filter((item) => item.id !== id));
  }, []);

  const aceitaTexto = !inicial || inicial.entradas.includes("texto");
  const dica = inicial
    ? `${inicial.titulo} — ${inicial.entradas.map((tipo) => ({ image: "imagem", audio: "áudio", video: "vídeo", pdf: "PDF", legenda: "legenda", texto: "texto", documento: "documento" })[tipo]).join(", ")}`
    : "IMAGEM · ÁUDIO · VÍDEO · PDF · LEGENDA — vários de uma vez, misturados";

  return (
    <main className="converter">
      <header className="converter__header">
        <div className="converter__topo">
          <p className="converter__eyebrow">{inicial ? inicial.titulo : "A oficina"}</p>
          <span className={`plano-selo plano-selo--${plano}`}>{pro ? "Pro" : "plano grátis"}</span>
        </div>
        <h1>{titulo ?? (inicial ? tituloInicial(inicial, formatoInicial) : "Converta e edite qualquer arquivo sem enviar nada para lugar nenhum")}</h1>
        {privacidade ? (
          <p className="converter__privacidade">
            <span aria-hidden="true">●</span> {privacidade}
          </p>
        ) : (
          <p className="converter__lead">
            Solte o arquivo, escolha o que fazer, baixe. Tudo acontece dentro do seu navegador, no seu computador. Sem
            upload, sem cadastro, sem marca d&apos;água. <strong>Depois da preparação, pode desligar a internet e continuar convertendo.</strong>{" "}<a href="#nota-offline" aria-label="Condições do uso sem internet">*</a>
          </p>
        )}
        <StatusOffline />
      </header>

      <div
        className={`dropzone${dragging ? " dropzone--active" : ""}`}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <p className="dropzone__title">Arraste seus arquivos aqui</p>
        <p className="dropzone__hint">{dica}</p>
        <button type="button" className="button button--primary" onClick={() => inputRef.current?.click()}>
          Escolher arquivos
        </button>
        <input ref={inputRef} type="file" accept={ligado ? undefined : ACEITOS} multiple hidden onChange={onSelect} aria-label="Escolher arquivos para converter" />
      </div>

      {!pro && (
        <p className="converter__limites">
          Grátis: {limiteDoPlano("gratis", "lote")} arquivos por vez · vídeo até {limiteDoPlano("gratis", "video")} min · PDF até{" "}
          {limiteDoPlano("gratis", "pdf")} páginas · sem marca d&apos;água.{" "}
          <a className="converter__link" href="/precos?origem=oficina">
            O Pro tira o teto
          </a>
          .
        </p>
      )}

      <ConviteDoApp />

      {aviso && <p className="notice notice--warning">{aviso}</p>}

      {grupos.map((grupo) => (
        <GrupoDeArquivos
          key={grupo.id}
          grupo={grupo}
          plano={plano}
          pluginLigado={ligado}
          onChange={(mudanca) => mudarGrupo(grupo.id, mudanca)}
          onRemoveItem={(itemId) => removerItem(grupo.id, itemId)}
          onMoveItem={(itemId, direcao) => moverItem(grupo.id, itemId, direcao)}
          onConverter={() => void converter(grupo)}
          onFechar={() => fecharGrupo(grupo.id)}
        />
      ))}

      <Fila
        jobs={jobs}
        trabalhosDoPlugin={trabalhosDoPlugin}
        cliente={cliente}
        onCancel={(id) => fila.cancel(id)}
        onRemove={(id) => fila.remove(id)}
        onRetry={(id) => fila.retry(id)}
        onClear={() => {
          fila.clearFinished();
          setTrabalhosDoPlugin((atuais) => atuais.filter((item) => item.trabalho && !item.trabalho.encerrado && !item.erro));
        }}
        onCancelPlugin={cancelarNoPlugin}
        onRemovePlugin={removerDoPlugin}
      />

      {aceitaTexto && (
        <section className="panel panel--texto" aria-labelledby="narrar-titulo">
          <h2 id="narrar-titulo" className="panel__title">
            Texto → narração
          </h2>
          <p className="panel__descricao">
            Cole um texto e ele vira um áudio em português, com Kokoro-82M no seu aparelho. Nada do que você
            escrever sai deste computador.
          </p>
          <textarea
            className="texto-para-narrar"
            value={texto}
            onChange={(evento) => setTexto(evento.target.value)}
            rows={4}
            placeholder="Cole aqui o texto para narrar…"
            aria-label="Texto para narrar"
          />
          <div className="panel__actions">
            <button type="button" className="button button--primary" onClick={narrar} disabled={!texto.trim()}>
              Preparar narração
            </button>
            <small className="panel__nota">{texto.length.toLocaleString("pt-BR")} caracteres</small>
          </div>
        </section>
      )}

      <KokoroPanel />
      <PluginPanel cliente={cliente} estado={estado} onArquivoDoDisco={doDisco} />

      <footer className="converter__footer">
        <p>
          Nenhum arquivo é enviado para servidor. Abra as ferramentas de desenvolvedor na aba Rede e confira: durante
          a conversão seus arquivos ficam no aparelho. O preparo inicial baixa somente motores e modelos públicos.
        </p>
        <p>
          Vídeo de gigabytes, transcrição ou PDF para Word? Isso roda pelo{" "}
          <a className="converter__link" href="/plugin">
            plugin para Windows
          </a>
          , também na sua máquina.
        </p>
      </footer>
    </main>
  );
}

function escolherFerramenta(tipo: TipoDeMidia, inicial: Ferramenta | null, formatos: readonly (FormatId | null)[] = []): Ferramenta {
  if (inicial && inicial.entradas.includes(tipo) && aceitaOsFormatos(inicial, formatos)) return inicial;
  const candidatas = ferramentasPara(tipo, formatos);
  // Sem ferramenta especial para o formato, a primeira do navegador é a genérica do tipo.
  return candidatas.find((item) => item.navegador && !item.formatosDeEntrada) ?? candidatas.find((item) => item.navegador) ?? candidatas[0];
}

function tituloInicial(ferramenta: Ferramenta, formato: FormatId | null): string {
  if (formato) {
    return `${ferramenta.titulo}: sai em ${getFormat(formato)?.label ?? formato.toUpperCase()}, sem enviar nada`;
  }
  return `${ferramenta.titulo}, sem enviar nada para lugar nenhum`;
}
