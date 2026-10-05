"use client";

import { useCallback, useState } from "react";

import type { PluginClient } from "@/lib/plugin/cliente";
import { resumirMaquina, type EstadoDoPlugin } from "@/lib/plugin/protocolo";

/**
 * O plugin, visto de dentro da oficina.
 *
 * Quem tem o plugin ligado vê a própria máquina reconhecida e ganha um botão a mais:
 * escolher o arquivo pelo seletor do Windows, que devolve o caminho no disco sem copiar
 * nada — o único jeito viável para um vídeo de vários gigabytes. Quem não tem vê uma
 * linha discreta contando que ele existe. Nunca um alerta.
 */
export default function PluginPanel({
  cliente,
  estado,
  onArquivoDoDisco,
}: {
  cliente: PluginClient;
  estado: EstadoDoPlugin;
  onArquivoDoDisco: (escolhido: { caminho: string; nome: string; bytes: number }) => void;
}) {
  const [aviso, setAviso] = useState<string | null>(null);
  const [abrindo, setAbrindo] = useState(false);

  const escolher = useCallback(async () => {
    setAviso(null);
    setAbrindo(true);
    try {
      const escolhido = await cliente.escolherArquivo();
      if (escolhido) onArquivoDoDisco(escolhido);
    } catch (erro) {
      setAviso(erro instanceof Error ? erro.message : "Não foi possível abrir o seletor.");
    } finally {
      setAbrindo(false);
    }
  }, [cliente, onArquivoDoDisco]);

  if (estado.situacao === "procurando") return null;

  if (estado.situacao === "ausente") {
    return (
      <section className="plugin plugin--convite" aria-labelledby="plugin-titulo">
        <h2 id="plugin-titulo" className="panel__title">
          Arquivo pesado? Transcrição? PDF para Word?
        </h2>
        <p>
          O plugin é um programinha opcional que liga esta página ao seu computador. Com ele, vídeo de gigabytes,
          transcrição com o Whisper, narração e PDF para Word rodam aqui mesmo — no seu processador e na sua placa de
          vídeo, sem enviar nada para servidor nenhum.
        </p>
        <p className="plugin__acoes">
          <a className="button button--primary" href="/plugin">
            Baixar o plugin
          </a>
        </p>
      </section>
    );
  }

  if (estado.situacao === "desatualizado") {
    return (
      <section className="plugin plugin--convite">
        <h2 className="panel__title">Plugin desatualizado</h2>
        <p>
          O plugin instalado fala a versão {estado.protocolo} deste contrato, e o site espera outra.{" "}
          <a className="converter__link" href="/plugin">
            Baixe de novo
          </a>{" "}
          para voltar a usar as funções pesadas.
        </p>
      </section>
    );
  }

  const { maquina, versao } = estado.apresentacao;

  return (
    <section className="plugin plugin--ligado" aria-labelledby="plugin-titulo">
      <div className="panel__head">
        <h2 id="plugin-titulo" className="panel__title">
          Plugin ligado · v{versao}
        </h2>
        <span className="plugin__selo">usando o seu computador</span>
      </div>

      <p className="plugin__maquina">{resumirMaquina(maquina)}</p>
      <p className="plugin__detalhe">
        {maquina.aceleradoresDeVideo.length > 0
          ? `Aceleração por hardware: ${maquina.aceleradoresDeVideo.join(", ")}. `
          : "Sem aceleração de vídeo por hardware nesta máquina. "}
        {maquina.ffmpegPresente ? "" : "FFmpeg não encontrado: vídeo, áudio e transcrição ficam indisponíveis. "}
        {maquina.modelosDeTranscricao.length > 0
          ? `Transcrição com ${maquina.modelosDeTranscricao.join(", ")}.`
          : "Nenhum modelo de transcrição instalado (baixe pelo aplicativo ou pela primeira transcrição)."}
      </p>

      <div className="plugin__acoes">
        <button type="button" className="button button--primary" onClick={escolher} disabled={abrindo}>
          {abrindo ? "Abrindo o seletor…" : "Escolher arquivo grande do computador"}
        </button>
        <button type="button" className="button" onClick={() => void cliente.abrirPasta()}>
          Abrir a pasta dos resultados
        </button>
      </div>
      <p className="plugin__detalhe">
        Pelo seletor, o arquivo não passa pelo navegador: um vídeo de dez gigabytes entra na fila na hora.
      </p>

      {aviso && <p className="notice notice--warning">{aviso}</p>}
    </section>
  );
}
