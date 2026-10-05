package br.com.smellsliketech.converter.conversao

import java.util.Locale

/**
 * Legendas SRT e WebVTT, como o SubtitleConverter do aplicativo para Windows: lê as duas,
 * tira as marcas que não são fala ("[Música]", "(risos)"), renumera e grava SRT, VTT ou só
 * o texto. Kotlin puro: os testes rodam no computador.
 */
object Legenda {
    data class Trecho(val inicioMs: Long, val fimMs: Long, val texto: String)

    private val TEMPO = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val SETA = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
    /** Marcas que o Whisper escreve quando não há fala. */
    private val SEM_FALA = Regex("""^\s*[\[(♪*].*[\])♪*]\s*$""")

    fun ler(texto: String): List<Trecho> {
        val trechos = mutableListOf<Trecho>()
        val linhas = texto.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').lines()
        var indice = 0
        while (indice < linhas.size) {
            val seta = SETA.find(linhas[indice])
            if (seta == null) { indice++; continue }
            val inicio = milissegundos(seta.groupValues[1])
            val fim = milissegundos(seta.groupValues[2])
            indice++
            val fala = mutableListOf<String>()
            while (indice < linhas.size && linhas[indice].isNotBlank() && SETA.find(linhas[indice]) == null) {
                fala += linhas[indice].trim()
                indice++
            }
            if (inicio != null && fim != null) trechos += Trecho(inicio, maxOf(inicio, fim), fala.joinToString("\n"))
        }
        return trechos
    }

    /** Sem marcas de não-fala e sem tags (<i>, {\an8}), dentro da duração, se informada. */
    fun limpar(trechos: List<Trecho>, duracaoMs: Long? = null): List<Trecho> = trechos.mapNotNull { trecho ->
        val texto = trecho.texto.lines()
            .map { it.replace(Regex("""<[^>]+>"""), "").replace(Regex("""\{\\[^}]*\}"""), "").trim() }
            .filter { it.isNotEmpty() && !SEM_FALA.matches(it) }
            .joinToString("\n")
        if (texto.isEmpty()) return@mapNotNull null
        val fim = if (duracaoMs != null && duracaoMs > 0) minOf(trecho.fimMs, duracaoMs) else trecho.fimMs
        if (duracaoMs != null && duracaoMs > 0 && trecho.inicioMs >= duracaoMs) return@mapNotNull null
        trecho.copy(fimMs = maxOf(trecho.inicioMs, fim), texto = texto)
    }

    fun srt(trechos: List<Trecho>): String = buildString {
        trechos.forEachIndexed { indice, trecho ->
            append(indice + 1).append('\n')
            append(tempo(trecho.inicioMs, ',')).append(" --> ").append(tempo(trecho.fimMs, ',')).append('\n')
            append(trecho.texto).append("\n\n")
        }
    }

    fun deslocar(trechos: List<Trecho>, segundos: Double): List<Trecho> {
        if (!segundos.isFinite() || segundos !in -3600.0..3600.0) throw ErroDeConversao("Atraso deve ficar entre -3600 e 3600 segundos.")
        val ms = (segundos * 1000).toLong()
        return trechos.mapNotNull {
            val fim = it.fimMs + ms
            if (fim <= 0) null else it.copy(inicioMs = (it.inicioMs + ms).coerceAtLeast(0), fimMs = fim)
        }
    }

    fun vtt(trechos: List<Trecho>): String = buildString {
        append("WEBVTT\n\n")
        trechos.forEach { trecho ->
            append(tempo(trecho.inicioMs, '.')).append(" --> ").append(tempo(trecho.fimMs, '.')).append('\n')
            append(trecho.texto).append("\n\n")
        }
    }

    /** Só a fala, um trecho por linha. */
    fun texto(trechos: List<Trecho>): String = trechos.joinToString("\n") { it.texto.replace('\n', ' ') } + "\n"

    fun gravar(trechos: List<Trecho>, formato: String): String = when (formato) {
        "srt" -> srt(trechos)
        "vtt" -> vtt(trechos)
        "txt" -> texto(trechos)
        else -> throw ErroDeConversao("Formato de legenda não suportado: $formato.")
    }

    /** A fala da legenda como texto corrido, para narrar. */
    fun falaCorrida(texto: String): String = limpar(ler(texto)).joinToString(" ") { it.texto.replace('\n', ' ') }

    private fun milissegundos(valor: String): Long? {
        val partes = TEMPO.matchEntire(valor.trim())?.destructured ?: return null
        val (horas, minutos, segundos, fracao) = partes
        val ms = fracao.padEnd(3, '0').take(3).toLong()
        return ((horas.ifEmpty { "0" }.toLong() * 60 + minutos.toLong()) * 60 + segundos.toLong()) * 1000 + ms
    }

    private fun tempo(ms: Long, separador: Char): String {
        val horas = ms / 3_600_000
        val minutos = ms / 60_000 % 60
        val segundos = ms / 1000 % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", horas, minutos, segundos, separador, ms % 1000)
    }
}
