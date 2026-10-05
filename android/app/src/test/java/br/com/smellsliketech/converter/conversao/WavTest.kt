package br.com.smellsliketech.converter.conversao

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    /** Um WAV PCM mono de 16 bits com as amostras dadas; `dataZerado` imita motores que gravam em fluxo. */
    private fun wav(amostras: ByteArray, dataZerado: Boolean = false): ByteArray {
        val formato = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(22050).putInt(44100).putShort(2).putShort(16).array()
        return ByteBuffer.allocate(12 + 8 + 16 + 8 + amostras.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + amostras.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); put(formato)
            put("data".toByteArray()); putInt(if (dataZerado) 0 else amostras.size); put(amostras)
        }.array()
    }

    @Test
    fun juntaAsPartesMantendoOFormatoEOAudioEmOrdem() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(5, 6)
        val junto = Wav.juntar(listOf(wav(a), wav(b, dataZerado = true)))
        val lido = Wav.ler(junto)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), lido.dados)
        assertEquals(16, lido.formato.size)
        val tamanhoRiff = ByteBuffer.wrap(junto, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(junto.size - 8, tamanhoRiff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recusaQuemNaoEWav() {
        Wav.ler("isto não é um wav".toByteArray())
    }

    @Test
    fun divideTextoLongoNoFimDasFrasesSemPerderNada() {
        val frase = "Esta é uma frase de teste com algumas palavras. "
        val texto = frase.repeat(30)
        val partes = Wav.dividirTexto(texto, 200)
        assertTrue(partes.all { it.length <= 200 })
        assertTrue(partes.all { it.endsWith(".") })
        assertEquals(texto.replace(" ", ""), partes.joinToString("").replace(" ", ""))
        assertEquals(listOf("curto"), Wav.dividirTexto("  curto  ", 200))
    }
}
