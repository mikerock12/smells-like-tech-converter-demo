"use client";

import { useEffect, useState, useSyncExternalStore } from "react";

import { acompanharOffline, ouvirAtualizacaoOffline, tentarOffline, verificandoAtualizacaoOffline, type EstadoOffline } from "@/lib/pwa/app";

const ouvirControle = (avisar: () => void) => {
  navigator.serviceWorker?.addEventListener("controllerchange", avisar);
  return () => navigator.serviceWorker?.removeEventListener("controllerchange", avisar);
};
const temControle = () => Boolean(navigator.serviceWorker?.controller);
const semAssinatura = () => () => {};
const compativel = () => "serviceWorker" in navigator && window.isSecureContext;

/** Não prometa offline antes de guardar também os motores ainda não usados. */
export default function StatusOffline() {
  const [estado, setEstado] = useState<EstadoOffline | null>(null);
  const [falhou, setFalhou] = useState(false);
  const controlado = useSyncExternalStore(ouvirControle, temControle, () => false);
  const suportado = useSyncExternalStore(semAssinatura, compativel, () => true);
  const verificando = useSyncExternalStore(ouvirAtualizacaoOffline, verificandoAtualizacaoOffline, () => true);
  useEffect(() => {
    const parar = acompanharOffline((atual) => { setEstado(atual); setFalhou(Boolean(atual.erro)); });
    const erro = () => setFalhou(true);
    window.addEventListener("slt-offline-erro", erro);
    return () => { parar(); window.removeEventListener("slt-offline-erro", erro); };
  }, []);

  const pronto = controlado && !verificando && estado?.preparado && !falhou && !estado.preparando;
  const porcentagem = estado?.total ? Math.floor(100 * estado.guardados / estado.total) : null;
  return (
    <div className="offline-status" data-offline-pronto={pronto ? "true" : "false"}>
      <p role="status">
        {!suportado ? "Este navegador não permite preparar o modo offline. Use um navegador compatível, fora do modo privado, ou mantenha a conexão." : pronto ? "Pronto para converter sem internet" : falhou ? "Modo offline ainda não está pronto. Verifique a conexão e o espaço disponível no navegador." : `Preparando uso sem internet${porcentagem === null ? "…" : ` — ${porcentagem}%`}. Aguarde antes de desconectar.`}
        {" "}<a href="#nota-offline" aria-label="Condições do uso sem internet">*</a>
      </p>
      {falhou && <button className="button button--ghost" type="button" onClick={tentarOffline}>Tentar preparar novamente</button>}
    </div>
  );
}
