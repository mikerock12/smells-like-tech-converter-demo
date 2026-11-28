package br.com.smellsliketech.converter.imagem

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AmpliadorSltiNoAparelhoTest {
    @Test fun quatroFasesConferemComReferenciaNumpy() {
        // A bancada fornece a cópia privada por argumento. O APK de teste não embute os pesos.
        val modelo = InstrumentationRegistry.getArguments().getString("pesosSlti")
        assumeNotNull(modelo)
        val entrada = Bitmap.createBitmap(8, 5, Bitmap.Config.ARGB_8888)
        for (y in 0 until 5) for (x in 0 until 8) {
            val base = x * 37 + y * 43
            entrada.setPixel(x, y, Color.rgb(base % 256, (base + 65) % 256, (base + 130) % 256))
        }
        val saida = AmpliadorSlti.aplicar(entrada, Base64.decode(modelo, Base64.DEFAULT))
        assertEquals(16, saida.width)
        assertEquals(10, saida.height)
        val sha = MessageDigest.getInstance("SHA-256")
        for (y in 0 until 10) for (x in 0 until 16) {
            val cor = saida.getPixel(x, y)
            sha.update(byteArrayOf(Color.red(cor).toByte(), Color.green(cor).toByte(), Color.blue(cor).toByte(), Color.alpha(cor).toByte()))
        }
        assertEquals("fa0b1441f6825c5e61e77bb55e5aec9f019c7709bc0ac774ce261711183799e5", sha.digest().joinToString("") { "%02x".format(it) })
        saida.recycle()
        entrada.recycle()
    }
}
