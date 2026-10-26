package br.com.smellsliketech.converter.catalogos

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MiniaturasTest {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pngGrandeViraMiniaturaEGiphyNaoEGuardadoEmCache() = runBlocking {
        val imagem = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val dados = ByteArrayOutputStream().use { saida ->
            imagem.compress(Bitmap.CompressFormat.PNG, 100, saida); imagem.recycle(); saida.toByteArray()
        }
        val chamadas = AtomicInteger()
        val carregador = MiniaturasDoCatalogo(contexto) {
            addInterceptor { cadeia ->
                chamadas.incrementAndGet()
                Response.Builder().request(cadeia.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(dados.toResponseBody("image/png".toMediaType())).build()
            }
        }
        val item = StickerDoCatalogo("teste", "Teste", "https://media.giphy.com/teste.png", "https://media.giphy.com/teste.png", "giphy", "Teste")
        repeat(2) {
            val desenho = carregador.carregar(item) as BitmapDrawable
            assertTrue(desenho.bitmap.width <= 160)
            assertTrue(desenho.bitmap.height <= 160)
            assertEquals(Color.BLUE, desenho.bitmap.getPixel(80, 60))
            assertTrue("No máximo 160×160×4 bytes decodificados", desenho.bitmap.allocationByteCount <= 160 * 160 * 4)
        }
        assertEquals("Cada pedido GIPHY passa pelo transporte, sem cache de mídia", 2, chamadas.get())
    }

    @Test fun gifNotoContinuaAnimavelEDimensionadoEImportaSemRede() = runBlocking {
        val dados = contexto.assets.open("stickers/noto/catalogo.json").bufferedReader().use { JSONArray(it.readText()) }
        val primeiro = dados.getJSONObject(0)
        val caminho = "stickers/noto/${primeiro.getString("codigo")}.gif"
        val item = StickerDoCatalogo(primeiro.getString("codigo"), primeiro.getString("nome"), caminho, caminho, "noto", "Google Noto")
        val desenho = MiniaturasDoCatalogo(contexto).carregar(item)
        assertTrue("Animação GIF preservada", desenho is Animatable)
        assertTrue(desenho.intrinsicWidth in 1..160)
        assertTrue(desenho.intrinsicHeight in 1..160)
        val catalogo = CatalogoDeStickers.obter(contexto, "figurinhas")
        val animado = catalogo.importar(item, true)
        val parado = catalogo.importar(item, false)
        try {
            assertTrue(animado.length() > 0)
            val cabecalho = ByteArray(3)
            java.io.DataInputStream(animado.inputStream()).use { it.readFully(cabecalho) }
            assertTrue(cabecalho.contentEquals("GIF".toByteArray()))
            val bitmap = android.graphics.BitmapFactory.decodeFile(parado.absolutePath)
            assertNotNull(bitmap); bitmap.recycle()
        } finally { animado.delete(); parado.delete() }
    }
}
