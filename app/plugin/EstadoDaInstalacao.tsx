"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { PluginClient } from "@/lib/plugin/cliente";
import { resumirMaquina, type EstadoDoPlugin } from "@/lib/plugin/protocolo";

/**
 * Fecha o ciclo da instalação.
 *
 * Quem acabou de instalar um programa que não abre janela nenhuma fica sem saber se
 * deu certo. Este bloco responde isso na própria página: fica procurando, e no momento
 * em que o plugin aparece, mostra a máquina reconhecida.
 */
export default function EstadoDaInstalacao() {
  const cliente = useMemo(() => new PluginClient(), []);
  const [estado, setEstado] = useState<EstadoDoPlugin>({ situacao: "procurando" });
  const [procurando, setProcurando] = useState(false);

  const procurar = useCallback(async () => {
    setProcurando(true);
    const resultado = await cliente.procurar();
    setEstado(resultado);
    setProcurando(false);
    return resultado;
  }, [cliente]);

  useEffect(() => {
    let vivo = true;
    let temporizador: number | undefined;

    async function tentar() {
      const resultado = await cliente.procurar();
      if (!vivo) return;

      setEstado(resultado);

      // Enquanto não aparece, continua olhando de longe em longe: a pessoa pode estar
      // instalando agora mesmo, e a página deve reagir sozinha quando ele ligar.
      if (resultado.situacao !== "ligado") {
        temporizador = window.setTimeout(tentar, 4_000);
      }
    }

    void tentar();
    return () => {
      vivo = false;
      if (temporizador) window.clearTimeout(temporizador);
    };
  }, [cliente]);

  if (estado.situacao === "ligado") {
    const { maquina, operacoes, versao } = estado.apresentacao;
    const disponiveis = operacoes.filter((operacao) => operacao.disponivel).length;
    const semFfmpeg = !maquina.ffmpegPresente;

    return (
      <aside className="instalacao instalacao--pronta" aria-live="polite">
        <span className="instalacao__selo">instalado e ligado</span>
        <h2>Deu certo. O plugin está rodando aqui.</h2>
        <p className="instalacao__maquina">{resumirMaquina(maquina)}</p>
        <p className="instalacao__detalhe">
          Versão {versao} · {disponiveis} conversões liberadas
          {maquina.modelosDeTranscricao.length > 0
            ? ` · transcrição com ${maquina.modelosDeTranscricao.join(", ")}`
            : " · nenhum modelo de transcrição instalado"}
        </p>

        {semFfmpeg && (
          <p className="notice notice--warning">
            O FFmpeg não foi encontrado no PATH. O plugin está ligado, mas vídeo, áudio e
            transcrição só funcionam com ele instalado — <code>winget install Gyan.FFmpeg</code>.
          </p>
        )}

        <a className="primary-action" href="/converter">
          Ir para o conversor <span aria-hidden="true">→</span>
        </a>
      </aside>
    );
  }

  if (estado.situacao === "desatualizado") {
    return (
      <aside className="instalacao instalacao--aviso" aria-live="polite">
        <h2>Há um plugin aqui, mas de outra versão</h2>
        <p className="instalacao__detalhe">
          Ele fala a versão {estado.protocolo} do contrato e este site espera outra. Baixe acima e
          instale por cima — não precisa desinstalar antes.
        </p>
      </aside>
    );
  }

  return (
    <aside className="instalacao" aria-live="polite">
      <h2>Ainda não encontramos o plugin neste computador</h2>
      <p className="instalacao__detalhe">
        Esta página fica procurando sozinha. Assim que você instalar e ele ligar, o resultado
        aparece aqui — não precisa recarregar.
      </p>
      <button type="button" className="button" onClick={() => void procurar()} disabled={procurando}>
        {procurando ? "Procurando…" : "Procurar agora"}
      </button>
    </aside>
  );
}
