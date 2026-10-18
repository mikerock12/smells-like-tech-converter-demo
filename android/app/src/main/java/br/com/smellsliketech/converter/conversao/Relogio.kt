package br.com.smellsliketech.converter.conversao

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Tempos do corte como as pessoas digitam no celular. O teclado numérico do Android não tem
 * ":", então "1-05", "1.05" e "1 05" valem o mesmo que "1:05". A vírgula é a casa decimal
 * dos segundos ("1:05,5"). Também aceita só segundos ("90") e horas ("1:02:03").
 */
object Relogio {
    private val SEPARADORES = Regex("[-:. ]+")

    fun ler(texto: String): Double? {
        val limpo = texto.trim()
        if (limpo.isEmpty()) return null
        val partes = limpo.split(SEPARADORES)
        if (partes.size > 3 || partes.any { it.isEmpty() }) return null
        var total = 0.0
        partes.forEachIndexed { indice, parte ->
            val ultima = indice == partes.lastIndex
            val numero = (if (ultima) parte.replace(',', '.') else parte).toDoubleOrNull() ?: return null
            if (numero < 0 || (!ultima && parte.contains(','))) return null
            if (indice > 0 && numero >= 60) return null
            total = total * 60 + numero
        }
        return total
    }

    /**
     * "m:ss" ou "h:mm:ss", com os milissegundos quando houver ("1:05,5", "1:05,135"); arredonda
     * para o milissegundo, a precisão das marcações do player.
     */
    fun escrever(segundos: Double): String {
        val milesimos = (segundos.coerceAtLeast(0.0) * 1000).roundToLong()
        val inteiros = milesimos / 1000
        val horas = inteiros / 3600
        val minutos = (inteiros % 3600) / 60
        val resto = inteiros % 60
        val base = if (horas > 0) "%d:%02d:%02d".format(Locale.ROOT, horas, minutos, resto)
        else "%d:%02d".format(Locale.ROOT, minutos, resto)
        val fracao = "%03d".format(Locale.ROOT, milesimos % 1000).trimEnd('0')
        return if (fracao.isEmpty()) base else "$base,$fracao"
    }

    /** A posição na linha do tempo do player, sempre completa: "00:01:05,135". */
    fun completo(segundos: Double): String {
        val milesimos = (segundos.coerceAtLeast(0.0) * 1000).roundToLong()
        val inteiros = milesimos / 1000
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, inteiros / 3600, (inteiros % 3600) / 60, inteiros % 60, milesimos % 1000)
    }

    /** Arredonda para o milissegundo, a precisão das marcações. */
    fun milesimo(segundos: Double): Double = (segundos * 1000).roundToLong() / 1000.0
}
