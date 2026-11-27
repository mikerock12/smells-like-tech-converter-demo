package br.com.smellsliketech.converter.imagem

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjetoImagemNoAparelhoTest {
    @Test fun recortaProjetoDoChromiumEPreservaCamadas() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val (doc, fontes) = assets.open("editor-navegador.sltimage").use { ProjetoImagem.abrir(it) }
        try {
            val novo = ProjetoImagem.recortar(doc, 8, 4, 12, 10)
            assertEquals(32, doc.getJSONObject("canvas").getInt("width"))
            val camadas = novo.getJSONArray("layers")
            for (i in 0 until camadas.length()) {
                val antiga = doc.getJSONArray("layers").getJSONObject(i)
                assertEquals(antiga.getDouble("x") - 8, camadas.getJSONObject(i).getDouble("x"), 0.0)
                assertEquals(antiga.getDouble("y") - 4, camadas.getJSONObject(i).getDouble("y"), 0.0)
            }
            camadas.getJSONObject(1).put("visivel", false)
            val imagem = ProjetoImagem.render(novo, fontes)
            try {
                assertEquals(12, imagem.width)
                assertEquals(10, imagem.height)
                for (y in 0 until imagem.height) for (x in 0 until imagem.width)
                    assertEquals(if (x < 8) Color.rgb(36, 156, 214) else Color.TRANSPARENT, imagem.getPixel(x, y))
            } finally { imagem.recycle() }
        } finally { fontes.values.forEach { it.bitmap.recycle() } }
    }

    @Test fun abreProjetoDoChromiumEditaTextoEPreservaOriginal() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val (doc, fontes) = instrumentacao.context.assets.open("editor-navegador.sltimage").use { ProjetoImagem.abrir(it) }
        val camadas = doc.getJSONArray("layers")
        assertEquals(2, camadas.length())
        val texto = camadas.getJSONObject(1)
        assertEquals("A", texto.getString("texto"))
        texto.put("visivel", false).put("texto", "Android")
        val arquivo = File(instrumentacao.targetContext.cacheDir, "editor-android.sltimage")
        arquivo.outputStream().use { ProjetoImagem.salvar(doc, fontes, it) }
        val (reaberto, originais) = arquivo.inputStream().use { ProjetoImagem.abrir(it) }
        assertEquals("Android", reaberto.getJSONArray("layers").getJSONObject(1).getString("texto"))
        val foto = camadas.getJSONObject(0).getString("asset")
        assertArrayEquals(fontes.getValue(foto).bytes, originais.getValue(foto).bytes)
        val bitmap = ProjetoImagem.render(reaberto, originais)
        try {
            assertEquals(32, bitmap.width)
            assertEquals(24, bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                assertEquals(if (x < 16) Color.rgb(36, 156, 214) else Color.TRANSPARENT, bitmap.getPixel(x, y))
            }
        } finally {
            bitmap.recycle()
            fontes.values.forEach { it.bitmap.recycle() }
            originais.values.forEach { it.bitmap.recycle() }
        }
    }
}
