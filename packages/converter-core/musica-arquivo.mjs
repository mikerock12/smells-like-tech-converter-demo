/**
 * O arquivo portátil .sltmusic: um ZIP com `projeto.json` e as mídias em `midias/`, por
 * caminho relativo, para abrir igual no site, no Windows e no Android.
 *
 * Regras (espelhadas em MusicArchive.cs e ArquivoDaMusica.kt, casos em musica-casos/):
 * - só entram `projeto.json` e `midias/<id>.<ext>` de áudio; nada de pastas, "..", executáveis;
 * - teto de descompressão (4 GB no total, 2 GB por mídia, 16 MB de JSON) e razão de
 *   compressão suspeita (zip bomb) recusados antes de extrair;
 * - WAV e JSON vão comprimidos; FLAC/MP3/M4A/OGG/Opus já vêm comprimidos e vão guardados.
 */

import { problemasDaMusica, VERSAO_DA_MUSICA } from "./musica.mjs";
import { validarEfeito } from "./musica-dsp.mjs";

export const EXTENSAO_DA_MUSICA = "sltmusic";
export const LIMITES_DO_ARQUIVO = { total: 4 * 1024 ** 3, porMidia: 2 * 1024 ** 3, projeto: 16 * 1024 ** 2, entradas: 2048, razao: 200 };
export const EXTENSOES_DE_AUDIO = ["wav", "flac", "mp3", "m4a", "aac", "ogg", "opus"];
const COMPRIMIDOS = new Set(["flac", "mp3", "m4a", "aac", "ogg", "opus"]);
const CAMINHO_DE_MIDIA = /^midias\/[a-z0-9][a-z0-9-]{0,63}\.(wav|flac|mp3|m4a|aac|ogg|opus)$/;

export function caminhoDaMidia(midia) {
  const extensao = (midia.formato ?? "wav").toLowerCase();
  return `midias/${midia.id}.${EXTENSOES_DE_AUDIO.includes(extensao) ? extensao : "wav"}`;
}

/** Comprimir a entrada no ZIP? (já comprimido vai guardado, sem perder tempo) */
export function comprimirEntrada(caminho) {
  const extensao = caminho.split(".").pop().toLowerCase();
  return !COMPRIMIDOS.has(extensao);
}

/**
 * Confere a lista de entradas de um ZIP antes de extrair: `[{nome, tamanho, comprimido}]`
 * (tamanho descomprimido e comprimido, em bytes). Devolve o primeiro problema ou null.
 */
export function conferirEntradas(entradas) {
  const L = LIMITES_DO_ARQUIVO;
  if (entradas.length > L.entradas) return "O arquivo tem entradas demais.";
  const nomes = new Set();
  let total = 0;
  let temProjeto = false;
  for (const entrada of entradas) {
    const nome = entrada.nome;
    if (nomes.has(nome)) return "O arquivo tem entradas repetidas.";
    nomes.add(nome);
    if (nome === "projeto.json") {
      temProjeto = true;
      if (entrada.tamanho > L.projeto) return "O projeto.json é grande demais.";
    } else if (!CAMINHO_DE_MIDIA.test(nome)) return `Entrada não permitida no projeto: ${nome.slice(0, 80)}`;
    else if (entrada.tamanho > L.porMidia) return "Uma mídia do projeto passa de 2 GB.";
    if (!(entrada.tamanho >= 0) || !(entrada.comprimido >= 0)) return "Entrada com tamanho inválido.";
    if (entrada.tamanho > 10 * 1024 ** 2 && entrada.tamanho / Math.max(1, entrada.comprimido) > L.razao) return "O arquivo parece uma bomba de compressão e foi recusado.";
    total += entrada.tamanho;
    if (total > L.total) return "O projeto passa de 4 GB descomprimido.";
  }
  if (!temProjeto) return "O arquivo não tem projeto.json.";
  return null;
}

/** Entradas que o ZIP deve ter para este projeto: projeto.json e uma por mídia. */
export function entradasDoProjeto(projeto) {
  return [{ caminho: "projeto.json", comprimir: true }, ...projeto.midias.map((midia) => ({ caminho: caminhoDaMidia(midia), midia: midia.id, comprimir: comprimirEntrada(caminhoDaMidia(midia)) }))];
}

/** Confere um projeto lido de um .sltmusic: versão, regras e mídias presentes no ZIP. */
export function conferirProjetoDoArquivo(projeto, nomes) {
  if (projeto?.versao > VERSAO_DA_MUSICA) return [`Este projeto foi salvo numa versão mais nova (${projeto.versao}). Atualize o aplicativo para abrir.`];
  const erros = problemasDaMusica(projeto, validarEfeito);
  for (const midia of projeto?.midias ?? []) if (!nomes.includes(caminhoDaMidia(midia))) erros.push(`A mídia ${midia.nome ?? midia.id} não está no arquivo.`);
  return erros;
}
