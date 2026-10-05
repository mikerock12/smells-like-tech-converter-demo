package br.com.smellsliketech.converter.conversao

import java.text.Normalizer

object TextoDaNarracao {
    private val legivel = Regex("[\\p{L}\\p{N}]")
    fun normalizar(texto: String): String = Normalizer.normalize(texto, Normalizer.Form.NFC)
        .lineSequence().filter { legivel.containsMatchIn(it) }
        .map { it.replace(Regex("[\\t\\u00a0]+"), " ").trim() }.joinToString("\n").trim()
    fun amostrasValidas(samples: FloatArray): Boolean = samples.isNotEmpty() &&
        samples.all { it.isFinite() } && samples.any { kotlin.math.abs(it) > .00001f }
    fun partir(texto: String): List<String> {
        val corte = texto.indices.filter { it >= 8 && texto.length - it >= 8 && texto[it].isWhitespace() }
            .minByOrNull { kotlin.math.abs(it - texto.length / 2) } ?: return emptyList()
        val partes = listOf(texto.substring(0, corte).trim(), texto.substring(corte).trim())
        return partes.takeIf { it.all(legivel::containsMatchIn) } ?: emptyList()
    }
}
