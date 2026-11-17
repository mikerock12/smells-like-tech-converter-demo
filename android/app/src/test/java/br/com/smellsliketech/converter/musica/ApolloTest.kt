package br.com.smellsliketech.converter.musica

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApolloTest {
    @Test
    fun blocosCobremOAudio() {
        assertEquals(listOf(0L), Apollo.inicios(1000))
        assertEquals(listOf(0L, 110_250L, 220_500L), Apollo.inicios(441_000))
        val n = 3 * 220_500L + 1234
        assertTrue(Apollo.inicios(n).last() + 220_500 >= n)
    }

    @Test
    fun modeloIdentidadeDevolveAEntrada() = runBlocking {
        val n = 2 * 220_500 + 777
        val e = FloatArray(n) { kotlin.math.sin(it / 37.0).toFloat() }
        val d = FloatArray(n) { kotlin.math.cos(it / 53.0).toFloat() }
        val fonte = object : FonteDeAudio {
            override val canais = 2
            override val quadros = n.toLong()
            override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
                val origem = if (canal == 0) e else d
                for (i in 0 until n) destino[i] = origem.getOrElse((inicio + i).toInt()) { 0f }
            }
        }
        val arquivo = File.createTempFile("apollo-", ".wav")
        try {
            var chamadas = 0
            EscritorDeWav(arquivo, 2, Apollo.TAXA).use { Apollo.restaurar(fonte, it, { b -> chamadas++; b.copyOf() }) {} }
            assertEquals(Apollo.inicios(n.toLong()).size, chamadas)
            FonteWav(arquivo).use { s ->
                assertEquals(n.toLong(), s.quadros)
                val a = FloatArray(n)
                val b = FloatArray(n)
                s.copiar(0, 0, n, a)
                s.copiar(1, 0, n, b)
                var erro = 0f
                for (i in 0 until n) erro = maxOf(erro, kotlin.math.abs(a[i] - e[i]), kotlin.math.abs(b[i] - d[i]))
                assertTrue("erro $erro", erro < 1e-5f)
            }
        } finally {
            arquivo.delete()
        }
    }
}
