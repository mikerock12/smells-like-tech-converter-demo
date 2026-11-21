package br.com.smellsliketech.converter.conversao

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class JpegXlNoAparelhoTest {
    @Test fun codecDaOficinaPreservaPixelsETransparencia() = runBlocking {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val arquivo = File.createTempFile("teste-jxl-", ".bin", contexto.cacheDir)
        val original = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val pixels = intArrayOf(Color.RED, Color.argb(128, 0, 255, 0), Color.TRANSPARENT, Color.BLUE)
        original.setPixels(pixels, 0, 2, 0, 0, 2, 2)
        try {
            arquivo.outputStream().use { Imagem.comprimir(original, "jxl", 100, it) }
            val bytes = arquivo.readBytes()
            assertTrue(bytes.size > 12)
            assertTrue((bytes[0] == (-1).toByte() && bytes[1] == 10.toByte()) ||
                bytes.copyOfRange(4, 8).contentEquals("JXL ".toByteArray()))
            val entrada = Entrada(Uri.fromFile(arquivo), arquivo.name, Tipo.IMAGEM, arquivo.length())
            val resultado = Imagem.carregar(contexto, entrada)
            try {
                assertEquals(2, resultado.width)
                assertEquals(2, resultado.height)
                val lidos = IntArray(4)
                resultado.getPixels(lidos, 0, 2, 0, 0, 2, 2)
                assertArrayEquals(pixels, lidos)
            } finally { resultado.recycle() }
        } finally {
            original.recycle()
            arquivo.delete()
        }
        Unit
    }
}
