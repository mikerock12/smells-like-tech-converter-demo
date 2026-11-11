package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class FonteWavTest {
    @Test fun leEstereoLongoSemDependerDoTamanhoDoArquivo() {
        val wav = File.createTempFile("musica-longa-", ".wav")
        val taxa = 48000
        val quadros = taxa * 720L
        val cabecalho = MasterDaMusica.cabecalhoWav(2, taxa, 32, quadros)
        try {
            RandomAccessFile(wav, "rw").use {
                it.write(cabecalho)
                it.setLength(cabecalho.size + quadros * 8)
                fun amostra(quadro: Long, canal: Int, valor: Float) {
                    it.seek(cabecalho.size + quadro * 8 + canal * 4)
                    it.writeInt(Integer.reverseBytes(valor.toBits()))
                }
                amostra(0, 1, .125f)
                amostra(8191, 0, .25f)
                amostra(8192, 0, .5f)
                amostra(8192, 1, -.5f)
                amostra(quadros - 1, 1, .75f)
            }
            val dados = FloatArray(3)
            FonteWav(wav, apagarAoFechar = true).use { fonte ->
                assertEquals(quadros, fonte.quadros)
                assertTrue(wav.exists())
                fonte.copiar(0, 8191, 3, dados)
                assertArrayEquals(floatArrayOf(.25f, .5f, 0f), dados, 0f)
                fonte.copiar(1, 8191, 3, dados)
                assertArrayEquals(floatArrayOf(0f, -.5f, 0f), dados, 0f)
                fonte.copiar(1, quadros - 1, 3, dados)
                assertArrayEquals(floatArrayOf(.75f, 0f, 0f), dados, 0f)
                fonte.copiar(1, -1, 3, dados)
                assertArrayEquals(floatArrayOf(0f, .125f, 0f), dados, 0f)
                fonte.close()
                fonte.copiar(1, 0, 3, dados)
                assertArrayEquals(FloatArray(3), dados, 0f)
            }
            assertFalse(wav.exists())
        } finally {
            wav.delete()
        }
    }
}
