package br.com.smellsliketech.converter.estudio

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exporta um projeto do estúdio no aparelho, com o FFmpeg e o codificador do celular de verdade.
 * Precisa de /data/local/tmp/estudio-video.mp4 (com som), estudio-foto.jpg e estudio-musica.mp3
 * (adb push); sem eles, o teste é pulado.
 */
@RunWith(AndroidJUnit4::class)
class EstudioExportadorTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext

    private fun trazer(nome: String): File {
        val destino = File(contexto.filesDir, "estudio-teste/$nome").apply { parentFile?.mkdirs() }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/$nome")).use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        }
        return destino
    }

    private fun projeto(saida: String, video: File, foto: File, musica: File) = Projeto(
        destino = "personalizado", largura = 540, altura = 960, fps = 30, saida = saida,
        clipes = listOf(
            Clipe("c1", video.absolutePath, "video", inicio = 0.5, fim = 2.5, ajuste = "caber-desfocado", volume = 1.0),
            Clipe("c2", foto.absolutePath, "imagem", duracao = 1.5, ajuste = "preencher"),
            Clipe("c3", video.absolutePath, "video", inicio = 0.0, fim = 1.5, ajuste = "caber", mudo = true),
        ),
        textos = listOf(Texto("t1", "Olá, estúdio!", 0.0, 3.0, y = 0.2, tamanho = 0.07, cor = "#FFFF00", contorno = "#000000", fonte = "bangers")),
        audios = listOf(AudioExtra("m1", musica.absolutePath, entrada = 1.0, inicio = 0.0, fim = 10.0, volume = 0.5, fadeEntrada = 0.5, fadeSaida = 1.0)),
        quadro = 1.0,
    )

    @Test fun exportaVideoGifImagemEAudio() = runBlocking {
        assumeTrue("FFmpeg do app", Ffmpeg.disponivel(contexto))
        val video = trazer("estudio-video.mp4")
        val foto = trazer("estudio-foto.jpg")
        val musica = trazer("estudio-musica.mp3")
        assumeTrue("mídias de teste em /data/local/tmp", video.length() > 10_000 && foto.length() > 1_000 && musica.length() > 1_000)

        for (saida in listOf("video", "gif", "imagem", "audio")) {
            val inicio = System.nanoTime()
            val progresso = mutableListOf<Int>()
            val resultado = Exportador.exportar(contexto, projeto(saida, video, foto, musica), "tiny") { progresso += it }
            val copia = File(contexto.cacheDir, "conferir-${resultado.nome}")
            contexto.contentResolver.openInputStream(resultado.uri)!!.use { entrada -> copia.outputStream().use { entrada.copyTo(it) } }
            Log.i("EstudioExportador", "$saida: ${resultado.nome} ${resultado.bytes} bytes em ${(System.nanoTime() - inicio) / 1_000_000} ms; progresso ${progresso.firstOrNull()}..${progresso.lastOrNull()}")
            assertTrue(saida, copia.length() > 500)
            assertEquals(saida, 100, progresso.last())
            when (saida) {
                "video" -> {
                    val sondagem = Ffmpeg.sondar(contexto, copia)
                    assertTrue("vídeo com som", sondagem.temVideo && sondagem.temAudio)
                    assertEquals(540 to 960, sondagem.largura to sondagem.altura)
                    assertTrue("duração ${sondagem.duracaoSegundos}", (sondagem.duracaoSegundos ?: 0.0) in 4.7..5.4)
                }
                "gif" -> assertEquals(480, Ffmpeg.sondar(contexto, copia).largura)
                "imagem" -> BitmapFactory.decodeFile(copia.absolutePath).let { assertEquals(540 to 960, it.width to it.height) }
                "audio" -> Ffmpeg.sondar(contexto, copia).let { assertTrue(it.temAudio && !it.temVideo) }
            }
            copia.delete()
            contexto.contentResolver.delete(resultado.uri, null, null)
        }
        File(contexto.filesDir, "estudio-teste").deleteRecursively()
        Unit
    }

    @Test fun textoTransparenteComLetraNoLugar() {
        val imagem = DesenhoDoTexto(contexto).imagem(
            Texto("t", "Promoção de verão com várias palavras para quebrar a linha", 0.0, 1.0, y = 0.2, tamanho = 0.06, caixa = "#FF000080", fonte = "anton"),
            540, 960,
        )
        assertEquals(0, android.graphics.Color.alpha(imagem.getPixel(5, 900)))
        assertTrue(android.graphics.Color.alpha(imagem.getPixel(270, 192)) > 0)
        assertEquals(0x80FF0000.toInt(), DesenhoDoTexto.cor("#FF000080"))
        assertEquals(0xFF112233.toInt(), DesenhoDoTexto.cor("#112233"))
    }

    @Test fun perspectivaECamadaTemporizadaNoFfmpegDoAndroid() = runBlocking {
        assertTrue("FFmpeg do aplicativo disponível", Ffmpeg.disponivel(contexto))
        val pasta = File(contexto.cacheDir, "camadas-instrumentado").apply { mkdirs() }
        fun foto(nome: String, cor: Int): File {
            val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(cor)
            return File(pasta, nome).also { arquivo ->
                arquivo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val base = foto("base.png", android.graphics.Color.BLUE)
            val extra = foto("extra.png", android.graphics.Color.RED)
            val projeto = Projeto(largura = 128, altura = 128, fps = 10, saida = "imagem",
                clipes = listOf(Clipe("base", base.absolutePath, "imagem", duracao = 2.0, ajuste = "caber")),
                camadas = listOf(Camada("extra", extra.absolutePath, inicio = .5, fim = 1.5,
                    transformacao = Transformacao(escala = .6, giro = 17.0, giroX = 25.0, giroY = -15.0))))
            for (instante in listOf(.1, 1.0, 1.8)) {
                val destino = File(pasta, "quadro.png")
                Exportador.gerar(contexto, projeto.copy(quadro = instante), destino) {}
                val imagem = BitmapFactory.decodeFile(destino.absolutePath)
                try {
                    assertEquals(128 to 128, imagem.width to imagem.height)
                    val centro = imagem.getPixel(64, 64)
                    if (instante == 1.0) assertTrue("camada vermelha no centro", android.graphics.Color.red(centro) > 200)
                    else assertTrue("camada fora do intervalo", android.graphics.Color.blue(centro) > 200)
                    assertTrue("base preservada fora da perspectiva", android.graphics.Color.blue(imagem.getPixel(5, 5)) > 200)
                } finally { imagem.recycle() }
            }
            val video = File(pasta, "composto.mp4")
            Exportador.gerar(contexto, projeto.copy(saida = "video"), video) {}
            val sondagem = Ffmpeg.sondar(contexto, video)
            assertTrue(sondagem.temVideo)
            assertTrue("duração composta", (sondagem.duracaoSegundos ?: 0.0) in 1.9..2.2)
        } finally { pasta.deleteRecursively() }
    }
}
