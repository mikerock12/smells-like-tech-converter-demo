package br.com.smellsliketech.converter.musica

import kotlin.math.abs
import kotlin.math.max

/**
 * Calibração do alinhamento por saída de áudio (fone Bluetooth, fone USB, alto-falante...).
 *
 * Os relógios do Android (AudioTrack e AudioRecord) já descontam boa parte do atraso, mas não
 * tudo: conversores, processamento do aparelho e, sobretudo, o Bluetooth deixam uma sobra que
 * empurra a tomada para depois do que a pessoa ouviu. A calibração mede essa sobra do jeito que
 * ela acontece na gravação de verdade: o app toca [BATIDAS] cliques e grava. Com fone, a pessoa
 * bate palmas junto com cada clique que ouve; com o alto-falante, o próprio clique volta pelo
 * microfone. A mediana do atraso de cada ataque em relação ao clique é a sobra, guardada por
 * saída (no aparelho, não no projeto) e descontada de toda tomada gravada por ela.
 */
object CalibracaoDaRota {
    const val BATIDAS = 12

    /** Busca do ataque em volta de cada clique: de 150 ms antes a 350 ms depois. */
    private const val ANTES = 0.150
    private const val DEPOIS = 0.350

    /** Espalhamento máximo (desvio absoluto mediano) para aceitar a medida. */
    const val ESPALHAMENTO_MAXIMO_MS = 15.0

    data class Resultado(val atraso: Long, val espalhamento: Long, val achados: Int)

    /** Quadros dos cliques: depois de 1 s de silêncio, um a cada meio segundo (120 bpm). */
    fun cliques(taxa: Int): List<Long> = List(BATIDAS) { taxa.toLong() + it * (taxa / 2L) }

    /**
     * Para cada clique esperado (quadro dentro da gravação), o primeiro ataque na janela em
     * volta dele. Devolve a mediana dos atrasos (pode ser negativa: palmas um pouco adiantadas),
     * ou null quando achou menos de dois terços dos cliques ou quando os ataques se espalharam
     * demais (palmas fora do tempo, barulho).
     */
    fun medir(gravado: FloatArray, esperados: List<Long>, taxa: Int): Resultado? {
        var pico = 0f
        for (v in gravado) pico = max(pico, abs(v))
        if (pico < 1e-3f) return null
        // Ruído de fundo: mediana do módulo; o limiar fica bem acima dele e abaixo do pico.
        val amostra = FloatArray(minOf(gravado.size, 48000)) { abs(gravado[(it.toLong() * gravado.size / minOf(gravado.size, 48000)).toInt()]) }
        amostra.sort()
        val fundo = amostra[amostra.size / 2]
        val limiar = max(pico * 0.3f, fundo * 8f)
        val antes = (ANTES * taxa).toLong()
        val depois = (DEPOIS * taxa).toLong()
        val atrasos = mutableListOf<Long>()
        for (e in esperados) {
            val de = max(0L, e - antes)
            val ate = minOf(gravado.size.toLong(), e + depois)
            var i = de
            while (i < ate) {
                if (abs(gravado[i.toInt()]) >= limiar) { atrasos += i - e; break }
                i++
            }
        }
        if (atrasos.size * 3 < esperados.size * 2) return null
        val mediana = mediana(atrasos)
        val espalhamento = mediana(atrasos.map { abs(it - mediana) })
        if (espalhamento * 1000.0 / taxa > ESPALHAMENTO_MAXIMO_MS) return null
        return Resultado(mediana, espalhamento, atrasos.size)
    }

    fun mediana(valores: List<Long>): Long {
        require(valores.isNotEmpty())
        val v = valores.sorted()
        return if (v.size % 2 == 1) v[v.size / 2] else Math.floorDiv(v[v.size / 2 - 1] + v[v.size / 2], 2L)
    }
}
