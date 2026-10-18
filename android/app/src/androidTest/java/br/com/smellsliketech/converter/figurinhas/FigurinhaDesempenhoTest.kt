package br.com.smellsliketech.converter.figurinhas

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Mede o caminho da figurinha animada com um vídeo de verdade (10 s), etapa por etapa. Precisa
 * de /data/local/tmp/figurinha-video.mp4 no aparelho (adb push); sem ele, o teste é pulado.
 */
@RunWith(AndroidJUnit4::class)
class FigurinhaDesempenhoTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext

    @Test fun animadaDeDezSegundosDeVideoReal() = runBlocking {
        assumeTrue("FFmpeg do app", Ffmpeg.disponivel(contexto))
        val video = File(contexto.cacheDir, "figurinha-video.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/figurinha-video.mp4")).use { entrada ->
            video.outputStream().use { entrada.copyTo(it) }
        }
        assumeTrue("vídeo de teste em /data/local/tmp", video.length() > 100_000)

        val inicio = System.nanoTime()
        fun agora() = (System.nanoTime() - inicio) / 1_000_000_000.0
        val quadros = Producao.quadros(contexto, video, 20.0, 30.0, transparencia = false) { }
        val extracao = agora()
        val renderizador = Renderizador(contexto)
        val edicao = Edicao(texto = "teste", contorno = true)
        val pasta = quadros.arquivos.first().parentFile!!
        // Os 10 primeiros num núcleo só, para comparar com o paralelo (o desenharTodos apaga a origem).
        val serie = Producao.Quadros(quadros.arquivos.take(10), quadros.porSegundo)
        val resto = Producao.Quadros(quadros.arquivos.drop(10), quadros.porSegundo)
        val crus = Producao.QuadrosCrus(File(pasta, "desenhados.argb"), quadros.porSegundo)
        var porQuadroEmSerie = 0.0
        crus.use {
            Producao.desenharTodos(serie, crus, desenho = { renderizador.desenhar(it, null, edicao) }, paralelos = 1) { }
            porQuadroEmSerie = (agora() - extracao) / serie.arquivos.size
            Producao.desenharTodos(resto, crus, desenho = { renderizador.desenhar(it, null, edicao) }) { }
        }
        val desenho = agora()
        val porQuadroEmParalelo = (desenho - extracao - porQuadroEmSerie * serie.arquivos.size) / resto.arquivos.size
        val tentativas = mutableListOf<String>()
        val webp = Producao.animada(contexto, crus, aoTentar = { tentativa -> tentativas += tentativa })
        val compactacao = agora()
        Log.i(
            "FigurinhaDesempenho",
            ("quadros=${quadros.arquivos.size} fps=${quadros.porSegundo} extracao=%.1fs desenho=%.1fs compactacao=%.1fs bytes=${webp.size} tentativas=$tentativas " +
                "por quadro: serie=%.0fms paralelo=%.0fms nucleos=${Runtime.getRuntime().availableProcessors()}")
                .format(extracao, desenho - extracao, compactacao - desenho, porQuadroEmSerie * 1000, porQuadroEmParalelo * 1000),
        )
        pasta.deleteRecursively()
        video.delete()
        assertTrue(webp.size <= Regras.ANIMADA_MAX_BYTES)
    }

    /**
     * Começo de uma gravação de tela, quase parado: a figurinha ainda tem de sair animada e com
     * imagem (o WhatsApp recusa estática num pacote animado).
     */
    @Test fun trechoQuaseParadoSaiAnimado() = runBlocking {
        assumeTrue("FFmpeg do app", Ffmpeg.disponivel(contexto))
        val video = File(contexto.cacheDir, "figurinha-video.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/figurinha-video.mp4")).use { entrada ->
            video.outputStream().use { entrada.copyTo(it) }
        }
        assumeTrue("vídeo de teste em /data/local/tmp", video.length() > 100_000)
        val quadros = Producao.quadros(contexto, video, 0.0, 3.0, transparencia = false) { }
        val pasta = quadros.arquivos.first().parentFile!!
        val crus = Producao.QuadrosCrus(File(pasta, "desenhados.argb"), quadros.porSegundo)
        val renderizador = Renderizador(contexto)
        crus.use { Producao.desenharTodos(quadros, crus, desenho = { renderizador.desenhar(it, null, Edicao()) }) { } }
        val webp = Producao.animada(contexto, crus)
        pasta.deleteRecursively()
        video.delete()
        Log.i("FigurinhaDesempenho", "parado: ${webp.size} bytes, ${quadros.arquivos.size} quadros, ${Producao.descrever(webp)}")
        assertTrue("VP8X", String(webp.copyOfRange(12, 16)) == "VP8X")
        assertTrue("animada", webp[20].toInt() and 0x02 != 0)
        assertTrue("tem imagem", Producao.temImagem(webp))
    }

    /** O quadro do vídeo e o desenho dele saem opacos. */
    @Test fun quadroDeVideoSaiOpaco() = runBlocking {
        assumeTrue("FFmpeg do app", Ffmpeg.disponivel(contexto))
        val video = File(contexto.cacheDir, "figurinha-video.mp4")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/figurinha-video.mp4")).use { entrada ->
            video.outputStream().use { entrada.copyTo(it) }
        }
        assumeTrue("vídeo de teste em /data/local/tmp", video.length() > 100_000)
        val unico = Producao.quadro(contexto, video, 0.0)
        val quadros = Producao.quadros(contexto, video, 0.0, 2.0, transparencia = false) { }
        val fonte = android.graphics.BitmapFactory.decodeFile(quadros.arquivos.first().absolutePath)
        val desenho = Renderizador(contexto).desenhar(fonte, null, Edicao())
        fun alfa(b: android.graphics.Bitmap) = android.graphics.Color.alpha(b.getPixel(b.width / 2, b.height / 2))
        Log.i("FigurinhaDesempenho", "quadro ${unico.width}x${unico.height} cfg=${unico.config} alfa=${alfa(unico)} cor=${Integer.toHexString(unico.getPixel(unico.width / 2, unico.height / 2))}; " +
            "jpg ${fonte.width}x${fonte.height} cfg=${fonte.config} alfa=${alfa(fonte)}; desenho alfa=${alfa(desenho)} cor=${Integer.toHexString(desenho.getPixel(256, 256))}")
        quadros.arquivos.first().parentFile?.deleteRecursively()
        video.delete()
        assertTrue("quadro opaco", alfa(unico) == 255)
        assertTrue("desenho opaco", alfa(desenho) == 255)
    }
}
