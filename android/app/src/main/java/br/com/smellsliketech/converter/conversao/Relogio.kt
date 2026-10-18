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

    /** "m:ss", "h:mm:ss" e um décimo quando houver ("1:05,5"); arredonda para o décimo. */
    fun escrever(segundos: Double): String {
        val decimos = (segundos.coerceAtLeast(0.0) * 10).roundToLong()
        val inteiros = decimos / 10
        val horas = inteiros / 3600
        val minutos = (inteiros % 3600) / 60
        val resto = inteiros % 60
        val base = if (horas > 0) "%d:%02d:%02d".format(Locale.ROOT, horas, minutos, resto)
        else "%d:%02d".format(Locale.ROOT, minutos, resto)
        return if (decimos % 10 == 0L) base else "$base,${decimos % 10}"
    }

    /** Arredonda para o décimo de segundo, a precisão que a tela mostra. */
    fun decimo(segundos: Double): Double = (segundos * 10).roundToLong() / 10.0
}
