"use client";

import { useEffect, useState } from "react";

import type { Preset } from "@/packages/converter-core/estudio.mjs";

export type ResultadoDoEstudio = { nome: string; url: string; bytes: number; doPlugin: boolean };

const LOJA = "https://play.google.com/store/apps/details?id=br.com.smellsliketech.converter";
const CORREIO = "converter@smellsliketech.com.br";

function tipoDo(nome: string): "video" | "imagem" | "audio" | "outro" {
  if (/\.(mp4|webm|mov|mkv)$/i.test(nome)) return "video";
  if (/\.(gif|png|jpe?g|webp)$/i.test(nome)) return "imagem";
  if (/\.(mp3|wav|m4a|ogg|flac)$/i.test(nome)) return "audio";
  return "outro";
}

const emailValido = (texto: string) => /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/i.test(texto.trim());

/**
 * O resultado da exportação, assim que ela termina: toca aqui mesmo, o compartilhamento do
 * navegador prioriza a rede escolhida no início (as outras ficam na mesma lista) e o feedback
 * é obrigatório, com nome, e-mail e mensagem, abrindo o app de e-mail para converter@.
 */
export default function ResultadoFinal({ resultado, preset, abrirPasta, fechar }: {
  resultado: ResultadoDoEstudio;
  preset: Preset | null;
  abrirPasta?: () => void;
  fechar: () => void;
}) {
  const [compartilhavel, setCompartilhavel] = useState(false);
  const [erro, setErro] = useState("");
  const [nome, setNome] = useState("");
  const [email, setEmail] = useState("");
  const [mensagem, setMensagem] = useState("");
  const tipo = tipoDo(resultado.nome);
  const rede = preset && preset.id !== "personalizado" ? preset.rede : null;

  useEffect(() => {
    let vivo = true;
    void (async () => {
      try {
        if (typeof navigator.share !== "function" || typeof navigator.canShare !== "function") return;
        const resposta = await fetch(resultado.url);
        const blob = await resposta.blob();
        const arquivo = new File([blob], resultado.nome, { type: blob.type || undefined });
        if (vivo) setCompartilhavel(navigator.canShare({ files: [arquivo] }));
      } catch { /* este navegador não compartilha arquivos: fica o Baixar */ }
    })();
    return () => { vivo = false; };
  }, [resultado]);

  const compartilhar = async () => {
    setErro("");
    try {
      const resposta = await fetch(resultado.url);
      const blob = await resposta.blob();
      const arquivo = new File([blob], resultado.nome, { type: blob.type || undefined });
      await navigator.share({ files: [arquivo], title: resultado.nome, text: rede ? `Feito no Smells Like Tech para ${rede}.` : "Feito no Smells Like Tech." });
    } catch (falha) {
      if ((falha as DOMException)?.name !== "AbortError") setErro("Não foi possível compartilhar; baixe o arquivo e envie pelo app da rede.");
    }
  };

  const valido = nome.trim() !== "" && emailValido(email) && mensagem.trim() !== "";
  const enviarFeedback = () => {
    const assunto = encodeURIComponent(`Feedback do estúdio — ${nome.trim()}`);
    const corpo = encodeURIComponent(`Nome: ${nome.trim()}\nE-mail: ${email.trim()}\n\n${mensagem.trim()}`);
    window.location.href = `mailto:${CORREIO}?subject=${assunto}&body=${corpo}`;
  };

  return (
    <div className="estudio__andamento" role="dialog" aria-modal="true" aria-label="Resultado da exportação">
      <div className="estudio__final">
        <h3>Pronto!</h3>
        <span>{resultado.nome} · {(resultado.bytes / 1_048_576).toFixed(1).replace(".", ",")} MB</span>
        {/* O resultado acabou de ser feito aqui; não existe arquivo de legendas para ele. */}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        {tipo === "video" && <video className="estudio__final-midia" src={resultado.url} controls autoPlay loop playsInline />}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {tipo === "imagem" && <img className="estudio__final-midia" src={resultado.url} alt={resultado.nome} />}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        {tipo === "audio" && <audio src={resultado.url} controls />}
        {tipo === "outro" && <a className="button" href={resultado.url} target="_blank" rel="noreferrer">Abrir {resultado.nome}</a>}
        <div className="estudio__linha">
          {compartilhavel && <button type="button" className="button button--primary" onClick={() => void compartilhar()}>Compartilhar</button>}
          <a className="button" href={resultado.url} download={resultado.nome}>Baixar</a>
          {resultado.doPlugin && abrirPasta && <button type="button" className="button" onClick={abrirPasta}>Abrir a pasta</button>}
        </div>
        {compartilhavel && rede && <small>Escolha o {rede} na lista do seu aparelho, que já aparece em primeiro; para outras redes, continue na mesma lista ou baixe o arquivo.</small>}
        {compartilhavel && !rede && <small>A lista do seu aparelho oferece todas as redes e apps instalados.</small>}
        {!compartilhavel && <small>Baixe o arquivo e envie pelo app da rede{rede ? ` (${rede})` : ""} que preferir.</small>}
        {erro && <p className="estudio__erro" role="alert">{erro}</p>}
        <a className="button" href={LOJA} target="_blank" rel="noreferrer">Avaliar na Google Play</a>
        <hr className="estudio__divisor" />
        <strong>Conte como foi</strong>
        <small>Nome, e-mail e feedback são obrigatórios. A mensagem abre no seu app de e-mail.</small>
        <label className="estudio__campo">Seu nome<input value={nome} autoComplete="name" onChange={(e) => setNome(e.target.value)} /></label>
        <label className="estudio__campo">Seu e-mail<input type="email" value={email} autoComplete="email" onChange={(e) => setEmail(e.target.value)} /></label>
        <label className="estudio__campo">Seu feedback<textarea rows={3} value={mensagem} onChange={(e) => setMensagem(e.target.value)} /></label>
        <button type="button" className="button button--primary" disabled={!valido} onClick={enviarFeedback}>Enviar feedback</button>
        <button type="button" className="button" onClick={fechar}>Fechar</button>
      </div>
    </div>
  );
}
