package br.com.smellsliketech.converter.conversao

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWavTest {
    @Test fun gravaBlocosSemPerderOFim() {
        val destino = File.createTempFile("pcm-kokoro-", ".wav")
        try {
            PcmWav(destino, 24000).use { wav ->
                wav.adicionar(floatArrayOf(0f, 1f, -1f)); wav.adicionar(floatArrayOf(0.5f))
            }
            val bytes = destino.readBytes()
            assertEquals(52, bytes.size)
            assertEquals(24000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
            assertEquals(8, Wav.ler(bytes).dados.size)
            assertEquals(16384.toShort(), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(50))
        } finally { destino.delete() }
    }
    @Test fun textoLongoNaoEhTruncado() {
        val texto = "Uma frase em português para narrar. ".repeat(200) + "FIM DO DOCUMENTO."
        val partes = Wav.dividirTexto(texto, 200)
        assertTrue(partes.size > 30)
        assertTrue(partes.all { it.length <= 200 })
        assertEquals(texto.replace(" ", ""), partes.joinToString("").replace(" ", ""))
    }
}
