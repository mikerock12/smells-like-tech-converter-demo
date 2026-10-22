package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Do vídeo à figurinha animada pelo mesmo caminho da tela (ViewModel): abrir, salvar e ler o
 * WebP do pacote. Os vídeos ficam em filesDir/videos-de-teste (adb push + run-as cp); sem
 * eles, o teste é pulado.
 */
@RunWith(AndroidJUnit4::class)
class FigurinhaDeVideoTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext

    @Test fun videoViraAnimadaFluida() {
        val pasta = File(contexto.filesDir, "videos-de-teste")
        val videos = pasta.listFiles { f -> f.extension == "mp4" }.orEmpty().sortedBy { it.name }
        assumeTrue("sem vídeos em $pasta", videos.isNotEmpty())
        for (video in videos) for (segundos in listOf(3.0, 10.0)) {
            lateinit var modelo: FigurinhasViewModel
            instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
            val comeco = System.currentTimeMillis()
            instrumentacao.runOnMainSync { modelo.abrir(Uri.fromFile(video)) }
            esperar { modelo.ocupado == null && (modelo.previa != null || modelo.aviso != null) }
            assertTrue("não abriu: ${modelo.aviso}", modelo.previa != null)
            val aberto0 = System.currentTimeMillis()
            // A prévia de um instante: a primeira converte o trecho; a segunda já sai da versão leve.
            val instantes = listOf(1.5f, 2.5f).map { t ->
                val antes = modelo.previa
                val inicioDoInstante = System.currentTimeMillis()
                instrumentacao.runOnMainSync { modelo.inicio = 0.0; modelo.fim = segundos; modelo.mostrarInstante(t) }
                esperar { modelo.previa !== antes && modelo.ocupado == null }
                System.currentTimeMillis() - inicioDoInstante
            }
            val aberto = System.currentTimeMillis()
            instrumentacao.runOnMainSync { modelo.inicio = 0.0; modelo.fim = segundos; modelo.salvar(null, "Teste ${video.nameWithoutExtension}") }
            Thread.sleep(200)
            esperar { modelo.ocupado == null }
            val pronto = System.currentTimeMillis()
            val pacote = modelo.pacoteAberto
            assertTrue("sem pacote: ${modelo.aviso}", pacote != null)
            val webp = Pacotes(contexto).arquivo(pacote!!, pacote.figurinhas.last().arquivo).readBytes()
            val info = WebpAnimado.ler(webp)
            val duracoes = info.quadros.map { it.duracaoMs }
            Log.i("FigurinhaDeVideo", "${video.name} ${segundos}s: abrir ${aberto0 - comeco} ms, instantes $instantes ms, gerar ${pronto - aberto} ms, " +
                "${webp.size / 1024} KB, ${info.quadros.size} quadros, ${info.duracaoMs} ms, duracoes=${duracoes.distinct()}")
            Pacotes(contexto).excluir(pacote)
        }
    }

    /**
     * Pinçar e arrastar mudam a edição a cada ~16 ms. A prévia tem de acompanhar durante o
     * gesto, não só quando o dedo solta (cancelar e esperar a cada evento deixava a imagem
     * parada até o fim do movimento).
     */
    @Test fun previaAcompanhaOGesto() {
        val foto = File(contexto.cacheDir, "gesto.png")
        android.graphics.Bitmap.createBitmap(1600, 1200, android.graphics.Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).drawCircle(800f, 600f, 500f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
            foto.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        lateinit var modelo: FigurinhasViewModel
        instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
        instrumentacao.runOnMainSync { modelo.abrir(Uri.fromFile(foto)) }
        esperar { modelo.ocupado == null && (modelo.previa != null || modelo.aviso != null) }
        assertTrue("não abriu: ${modelo.aviso}", modelo.previa != null)
        val vistas = mutableSetOf<Any>()
        repeat(40) { passo ->
            instrumentacao.runOnMainSync {
                modelo.mudar(modelo.edicao.copy(zoom = 1f + passo * 0.05f, deslocamentoX = passo * 0.005f))
                modelo.previa?.let { vistas += it }
            }
            Thread.sleep(16)
        }
        foto.delete()
        assertTrue("só ${vistas.size} prévias durante o gesto", vistas.size >= 5)
    }

    private fun esperar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < limite) {
            var ok = false
            instrumentacao.runOnMainSync { ok = condicao() }
            if (ok) return
            Thread.sleep(250)
        }
        error("tempo esgotado")
    }
}
