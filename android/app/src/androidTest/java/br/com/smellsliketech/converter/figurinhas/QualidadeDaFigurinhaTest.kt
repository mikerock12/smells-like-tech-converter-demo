package br.com.smellsliketech.converter.figurinhas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QualidadeDaFigurinhaTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val contexto = inst.targetContext
    private val pasta get() = File(contexto.filesDir, "qualidade").apply { mkdirs() }

    private fun conferir(bytes: ByteArray, quantidade: Int) {
        val info = WebpAnimado.ler(bytes)
        assertEquals(quantidade, info.quadros.size)
        assertTrue(info.quadros.all { it.largura == 512 && it.altura == 512 && it.x == 0 && it.y == 0 })
        assertTrue(info.quadros.all { bytes[it.posicaoDoTempo + 3].toInt() == 2 })
        assertTrue(info.quadros.all { it.duracaoMs == 100 })
    }

    @Test fun transparenciaNaoReaproveitaODesenhoAnterior() = runBlocking {
        val crus = Producao.QuadrosCrus(File(pasta, "transparencia.argb"), 10)
        crus.use {
            for (x in listOf(40f, 200f, 360f, -200f)) {
                val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                Canvas(b).drawRect(x, 150f, x + 80, 230f, Paint().apply { color = Color.WHITE })
                crus.gravar(b);b.recycle()
            }
        }
        val resultado = Producao.animada(contexto, crus)
        conferir(resultado.webp, 4)
        File(pasta, "transparencia.webp").writeBytes(resultado.webp)
        val vazios = Producao.QuadrosCrus(File(contexto.cacheDir, "vazios.argb"), 10)
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        vazios.use { repeat(3) { vazios.gravar(b) } };b.recycle()
        try {
            val erro = runCatching { Producao.animada(contexto, vazios) }.exceptionOrNull()
            assertNotNull("recusa animação completamente transparente", erro)
            assertTrue(erro!!.message.orEmpty().contains("vazia"))
        } finally { vazios.arquivo.delete() }
    }

    @Test fun videoAnexadoMantemOsQuadrosSemRastros() = runBlocking {
        val video = File(pasta, "fonte.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/1001559818.mp4")).use { entrada ->
            video.outputStream().use { entrada.copyTo(it) }
        }
        assumeTrue("Vídeo anexado enviado por adb", video.length() > 1_000_000)
        val inicio = System.currentTimeMillis()
        val quadros = Producao.quadros(contexto, video, 0.0, 10.0, false, 512, 10) {}
        val crus = Producao.QuadrosCrus(File(pasta, "referencia.argb"), 10)
        val render = Renderizador(contexto)
        crus.use { Producao.desenharTodos(quadros, crus, { render.desenhar(it, null, Edicao()) }) {} }
        quadros.arquivos.first().parentFile?.deleteRecursively()
        val resultado = Producao.animada(contexto, crus, { Log.i("QualidadeFigurinha", it) })
        val info = WebpAnimado.ler(resultado.webp)
        conferir(resultado.webp, kotlin.math.round(resultado.segundos * 10).toInt())
        assertTrue(resultado.webp.size <= Regras.ANIMADA_MAX_BYTES)
        File(pasta, "depois.webp").writeBytes(resultado.webp)
        Ffmpeg.executar(contexto, listOf("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "argb", "-s", "512x512", "-framerate", "10", "-i", crus.arquivo.absolutePath,
            "-c:v", "libwebp_anim", "-lossless", "0", "-quality", "25", "-compression_level", "4", "-pix_fmt", "yuva420p", "-loop", "0", File(pasta, "antes-q25.webp").absolutePath), null)
        Log.i("QualidadeFigurinha", "${resultado.webp.size} bytes, ${info.quadros.size} quadros, ${info.duracaoMs} ms, ${System.currentTimeMillis()-inicio} ms")
        Unit
    }
}
