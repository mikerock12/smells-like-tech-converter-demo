package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class CalibracaoDaRotaTest {
    private val taxa = 48000

    /** Gravação sintética: ruído baixo e um ataque (palma ou clique) em cada posição. */
    private fun gravacao(ataques: List<Long>, ruido: Float = 0.002f, amplitude: Float = 0.6f): FloatArray {
        val n = (CalibracaoDaRota.cliques(taxa).last() + taxa).toInt()
        var x = 0x12345678
        val sinal = FloatArray(n) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            ruido * ((x and 0xffff) / 32768f - 1f)
        }
        for (a in ataques) {
            for (i in 0 until 2400) {
                val k = (a + i).toInt()
                if (k in sinal.indices) sinal[k] += amplitude * exp(-i / 300.0).toFloat() * sin(i * 0.9).toFloat()
            }
        }
        return sinal
    }

    @Test fun cliquesACada120Bpm() {
        val c = CalibracaoDaRota.cliques(taxa)
        assertEquals(CalibracaoDaRota.BATIDAS, c.size)
        assertEquals(48000L, c.first())
        assertEquals(24000L, c[1] - c[0])
    }

    @Test fun medeOAtrasoDoBluetooth() {
        // Fone Bluetooth com 37 ms de sobra: cada palma chega 37 ms depois do clique, com um
        // pouco de imprecisão humana (±4 ms) que a mediana descarta.
        val esperados = CalibracaoDaRota.cliques(taxa)
        val jitter = listOf(3, -2, 4, 0, -4, 1, 2, -1, 0, 3, -3, 1)
        val ataques = esperados.mapIndexed { i, e -> e + (37 + jitter[i]) * taxa / 1000L }
        val r = CalibracaoDaRota.medir(gravacao(ataques), esperados, taxa)
        assertNotNull(r)
        assertTrue("atraso ${r!!.atraso * 1000.0 / taxa} ms", abs(r.atraso * 1000.0 / taxa - 37.5) <= 1.5)
        assertEquals(12, r.achados)
    }

    @Test fun aceitaPalmasUmPoucoAdiantadas() {
        val esperados = CalibracaoDaRota.cliques(taxa)
        val ataques = esperados.map { it - 8 * taxa / 1000L }
        val r = CalibracaoDaRota.medir(gravacao(ataques), esperados, taxa)!!
        assertTrue(abs(r.atraso * 1000.0 / taxa + 8.0) <= 1.0)
    }

    @Test fun recusaPalmasForaDoTempo() {
        val esperados = CalibracaoDaRota.cliques(taxa)
        val espalhados = listOf(0, 90, -60, 200, 20, -120, 150, 40, -30, 260, 10, -100)
        val ataques = esperados.mapIndexed { i, e -> e + espalhados[i] * taxa / 1000L }
        assertNull(CalibracaoDaRota.medir(gravacao(ataques), esperados, taxa))
    }

    @Test fun recusaQuandoFaltamCliques() {
        val esperados = CalibracaoDaRota.cliques(taxa)
        val ataques = esperados.take(5).map { it + 20 * taxa / 1000L }
        assertNull(CalibracaoDaRota.medir(gravacao(ataques), esperados, taxa))
    }

    @Test fun recusaSilencio() {
        assertNull(CalibracaoDaRota.medir(FloatArray(taxa * 8), CalibracaoDaRota.cliques(taxa), taxa))
    }

    @Test fun medianaParEImpar() {
        assertEquals(3L, CalibracaoDaRota.mediana(listOf(5L, 1L, 3L)))
        assertEquals(2L, CalibracaoDaRota.mediana(listOf(4L, 1L, 3L, 2L)))
        assertEquals(-3L, CalibracaoDaRota.mediana(listOf(-4L, -2L)))
    }

    @Test fun aTomadaVoltaOAtrasoCalibrado() {
        // O clique tocado no quadro 96.000 do transporte voltou 37 ms depois na tomada: com a
        // sobra calibrada como entrada, o clipe começa onde a pessoa ouviu.
        val p = Musica.novoProjeto("t", "T", taxa)
        val plano = Musica.planoDaGravacao(p, 0)
        val sobra = 37.0 * taxa / 1000.0
        val alinhado = 0L
        val semCalibrar = Musica.clipeDaTomada(p, "c", "m", alinhado, taxa * 4L, plano)!!
        val calibrado = Musica.clipeDaTomada(p.copy(latencia = Latencia(sobra, 0.0, true, null)), "c", "m", alinhado, taxa * 4L, plano)!!
        assertEquals(semCalibrar.inicioNaMidia + Musica.arredondar(sobra), calibrado.inicioNaMidia)
    }
}
