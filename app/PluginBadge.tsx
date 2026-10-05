"use client";

import { useEffect, useMemo, useState } from "react";

import { PluginClient } from "@/lib/plugin/cliente";
import { resumirMaquina, type EstadoDoPlugin } from "@/lib/plugin/protocolo";

/**
 * Diz, na landing, se o plugin já está ligado neste computador.
 *
 * Quem tem vê a própria máquina reconhecida — é a prova mais direta de que a promessa
 * é real. Quem não tem vê uma linha calma explicando o que ele faz; nunca um alerta,
 * nunca um pedido de instalação no meio do caminho.
 */
export default function PluginBadge() {
  const cliente = useMemo(() => new PluginClient(), []);
  const [estado, setEstado] = useState<EstadoDoPlugin>({ situacao: "procurando" });

  useEffect(() => {
    let vivo = true;
    void cliente.procurar().then((resultado) => {
      if (vivo) setEstado(resultado);
    });
    return () => {
      vivo = false;
    };
  }, [cliente]);

  if (estado.situacao === "procurando") {
    // Espaço reservado com a mesma altura, para a seção não pular quando a resposta chega.
    return <div className="plugin-badge plugin-badge--vazio" aria-hidden="true" />;
  }

  if (estado.situacao === "ligado") {
    const { maquina, operacoes } = estado.apresentacao;
    const disponiveis = operacoes.filter((operacao) => operacao.disponivel).length;

    return (
      <aside className="plugin-badge plugin-badge--ligado" aria-live="polite">
        <span className="plugin-badge__selo">plugin ligado</span>
        <div>
          <p className="plugin-badge__titulo">Reconhecemos o seu computador</p>
          <p className="plugin-badge__detalhe">
            {resumirMaquina(maquina)} — {disponiveis} conversões liberadas nesta máquina.
          </p>
        </div>
        <a className="button button--primary" href="/converter">
          Usar agora
        </a>
      </aside>
    );
  }

  if (estado.situacao === "desatualizado") {
    return (
      <aside className="plugin-badge plugin-badge--aviso" aria-live="polite">
        <p className="plugin-badge__detalhe">
          Há um plugin instalado aqui, mas de uma versão que este site não sabe conversar. Reinstale
          para voltar a usar as conversões pesadas.
        </p>
      </aside>
    );
  }

  return (
    <aside className="plugin-badge">
      <div>
        <p className="plugin-badge__detalhe">
          O plugin não está ligado neste computador. Sem ele a página converte imagem, áudio, vídeo e
          PDF normalmente; com ele, vídeo de gigabytes, transcrição, narração, PDF escaneado inteiro e
          PDF para Word passam a rodar aqui também — e continuam sem sair da sua máquina.
        </p>
      </div>
      <a className="button" href="/plugin">
        Ver o plugin
      </a>
    </aside>
  );
}
