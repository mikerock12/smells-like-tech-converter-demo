package br.com.smellsliketech.converter.musica

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Estimativa local de andamento e tonalidade; o resultado deve ser conferido de ouvido. */
data class AnaliseMusical(val bpm: Int?, val tonalidade: String?, val confiancaDaTonalidade: Double)

object AnaliseDaMusica {
    private val NOTAS = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val MAIOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MENOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private fun mono(canais: Array<FloatArray>): FloatArray {
        val n = canais.firstOrNull()?.size ?: 0
        val saida = FloatArray(n)
        if (canais.isEmpty()) return saida
        for (i in 0 until n) {
            var v = 0.0
            for (canal in canais) if (i < canal.size) v += canal[i]
            saida[i] = (v / canais.size).toFloat()
        }
        return saida
    }

    private fun bpm(sinal: FloatArray, taxa: Int): Int? {
        val passo = max(128, Musica.arredondar(taxa / 100.0).toInt())
        val janela = passo * 2
        val quantos = sinal.size / passo
        if (quantos < 16) return null
        val energia = DoubleArray(quantos)
        for (i in 0 until quantos) {
            var soma = 0.0
            val inicio = i * passo
            var j = 0
            while (j < janela && inicio + j < sinal.size) { soma += sinal[inicio + j] * sinal[inicio + j]; j += 2 }
            energia[i] = sqrt(soma / (janela / 2))
        }
        val fluxo = DoubleArray(quantos)
        for (i in 2 until quantos) fluxo[i] = max(0.0, energia[i] - (energia[i - 1] + energia[i - 2]) / 2)
        val media = fluxo.average()
        for (i in fluxo.indices) fluxo[i] = max(0.0, fluxo[i] - media)
        var melhorBpm: Int? = null
        var melhor = 0.0
        for (bpm in 40..240) {
            val atraso = max(2.0, 60.0 * taxa / (bpm * passo))
            val inteiro = floor(atraso).toInt()
            val fracao = atraso - inteiro
            var soma = 0.0
            var peso = 0
            for (i in inteiro + 1 until quantos) {
                val anterior = fluxo[i - inteiro] * (1 - fracao) + fluxo[i - inteiro - 1] * fracao
                soma += fluxo[i] * anterior
                peso++
            }
            var nota = if (peso > 0) soma / peso else 0.0
            if (bpm in 70..180) nota *= 1.03
            if (nota > melhor) { melhor = nota; melhorBpm = bpm }
        }
        return melhorBpm
    }

    private fun correlacao(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; ab += x * y; aa += x * x; bb += y * y }
        return ab / sqrt(max(1e-12, aa * bb))
    }

    private fun tonalidade(sinal: FloatArray, taxa: Int): Pair<String?, Double> {
        val n = 2048
        if (sinal.size < n * 4) return null to 0.0
        val fft = Fft(n)
        val re = DoubleArray(n); val im = DoubleArray(n)
        val croma = DoubleArray(12)
        val salto = max(n, (sinal.size - n) / 2400)
        var usados = 0
        var inicio = 0
        while (inicio + n <= sinal.size) {
            for (i in 0 until n) { re[i] = sinal[inicio + i] * (0.5 - 0.5 * cos(2 * Math.PI * i / n)); im[i] = 0.0 }
            fft.transformar(re, im, -1)
            for (k in 2 until n / 2) {
                val frequencia = k * taxa.toDouble() / n
                if (frequencia < 55 || frequencia > 4000) continue
                val nota = Musica.arredondar(12 * log2(frequencia / 440)).toInt() + 69
                croma[((nota % 12) + 12) % 12] += sqrt(re[k] * re[k] + im[k] * im[k])
            }
            usados++
            inicio += salto
        }
        if (usados == 0 || croma.all { it < 1e-9 }) return null to 0.0
        var melhor = Double.NEGATIVE_INFINITY
        var resultado: String? = null
        for ((modo, perfil) in listOf("maior" to MAIOR, "menor" to MENOR)) {
            for (nota in 0 until 12) {
                val girado = DoubleArray(12) { croma[(it + nota) % 12] }
                val valor = correlacao(girado, perfil)
                if (valor > melhor) { melhor = valor; resultado = "${NOTAS[nota]} $modo" }
            }
        }
        return resultado to melhor.coerceIn(0.0, 1.0)
    }

    fun analisar(canais: Array<FloatArray>, taxa: Int): AnaliseMusical {
        val sinal = mono(canais)
        val (tom, confianca) = tonalidade(sinal, taxa)
        return AnaliseMusical(bpm(sinal, taxa), tom, confianca)
    }

    private class Fft(private val n: Int) {
        private val cossenos = DoubleArray(n / 2) { cos(2 * Math.PI * it / n) }
        private val senos = DoubleArray(n / 2) { sin(2 * Math.PI * it / n) }
        private val inverso = IntArray(n)
        init {
            val bits = log2(n.toDouble()).toInt()
            for (i in 0 until n) {
                var r = 0
                for (b in 0 until bits) r = r or (((i shr b) and 1) shl (bits - 1 - b))
                inverso[i] = r
            }
        }
        fun transformar(re: DoubleArray, im: DoubleArray, sinal: Int) {
            for (i in 0 until n) {
                val j = inverso[i]
                if (j > i) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
            }
            var tamanho = 2
            while (tamanho <= n) {
                val metade = tamanho / 2
                val passo = n / tamanho
                var inicio = 0
                while (inicio < n) {
                    for (k in 0 until metade) {
                        val wr = cossenos[k * passo]; val wi = sinal * senos[k * passo]
                        val a = inicio + k; val b = a + metade
                        val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    }
                    inicio += tamanho
                }
                tamanho *= 2
            }
        }
    }
}
