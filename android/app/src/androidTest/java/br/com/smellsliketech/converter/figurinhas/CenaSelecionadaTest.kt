package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.estudio.EstudioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Vídeo vermelho/azul detecta regressões de pixels, não apenas o valor do cursor. */
@RunWith(AndroidJUnit4::class)
class CenaSelecionadaTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as Application
    private fun tela(f: () -> Unit) = i.runOnMainSync(f)
    private fun esperar(f: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < fim) {
            var ok = false; tela { ok = f() }; if (ok) return; Thread.sleep(100)
        }
        error("A prévia não mostrou a cena esperada")
    }
    private fun azul(b: Bitmap?) = b?.let { val p = it.getPixel(it.width / 2, it.height / 2); Color.blue(p) > Color.red(p) + 100 } ?: false

    @Test fun ajustesEHistoricoContinuamNoQuadroEscolhido() = runBlocking {
        app.cacheDir.mkdirs()
        val video = File(app.cacheDir, "cena-selecionada.mp4")
        val crus = Producao.QuadrosCrus(File(app.cacheDir, "cena-selecionada.argb"), 10)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        crus.use { repeat(20) { n -> bitmap.eraseColor(if (n < 10) Color.RED else Color.BLUE); crus.gravar(bitmap) } }
        bitmap.recycle()
        Ffmpeg.executar(app, listOf("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "argb", "-s", "512x512", "-framerate", "10", "-i", crus.arquivo.absolutePath,
            "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", video.absolutePath), null)
        lateinit var modelo: FigurinhasViewModel
        tela { modelo = FigurinhasViewModel(app); modelo.mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO); modelo.abrir(Uri.fromFile(video)) }
        try {
            esperar { modelo.previa != null && modelo.ocupado == null }
            tela { modelo.mudarTrecho(0.0, 2.0); modelo.mostrarInstante(1.5f) }
            esperar { azul(modelo.previa) && modelo.ocupado == null }
            var anterior = modelo.previa
            tela { modelo.mudar(modelo.edicao.copy(brilho = .1f)) }
            esperar { modelo.previa !== anterior }
            assertTrue("Brilho deve manter a cena azul", azul(modelo.previa))
            assertEquals(1.5f, modelo.tempoDaPrevia, 0f)
            tela { modelo.mostrarInstante(.2f); modelo.mostrarInstante(1.6f); modelo.mudar(modelo.edicao.copy(inclinacaoY = 3f)) }
            esperar { modelo.tempoDaPrevia == 1.6f && azul(modelo.previa) }
            anterior = modelo.previa
            tela { modelo.mostrarInstante(2f) }
            esperar { modelo.previa !== anterior }
            assertTrue("Fim deve mostrar o último quadro, sem repetir a capa", azul(modelo.previa))
            anterior = modelo.previa
            tela { modelo.desfazer() }
            esperar { modelo.previa !== anterior }
            assertTrue("Desfazer mantém a cena", azul(modelo.previa))
            var cursor = 0f
            repeat(12) { cursor = AjusteFino.aplicar(cursor, 1, 1f / 12, 0f..2f) }
            anterior = modelo.previa
            tela { modelo.mudarQuadrosPorSegundo(12); modelo.mostrarInstante(cursor) }
            esperar { modelo.previa !== anterior && modelo.ocupado == null }
            assertTrue("Doze passos a 12 fps chegam ao quadro de 1 segundo", azul(modelo.previa))
        } finally { tela { modelo.fecharEditor(); modelo.mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO) } }

        lateinit var estudio: EstudioViewModel
        tela {
            estudio = EstudioViewModel(app); estudio.novoProjeto(); estudio.escolherDestino("personalizado", 512, 512); estudio.escolherSaida("video")
            estudio.adicionarMidias(listOf(Uri.fromFile(video)))
        }
        try {
            esperar { estudio.previa != null && estudio.ocupado == null }
            val id = estudio.projeto!!.clipes.first().id
            tela { estudio.mudarClipe(id) { it.copy(velocidade = 2.0) }; estudio.mostrarInstanteDoClipe(id, 1.5) }
            esperar { azul(estudio.previa) }
            assertEquals(.75, estudio.instante, .001)
            val anterior = estudio.previa
            tela { estudio.mudarClipe(id) { it.copy(transformacao = br.com.smellsliketech.converter.estudio.Transformacao(giroX = 3.0)) } }
            esperar { estudio.previa !== anterior }
            assertTrue("Estúdio mantém a cena ao transformar", azul(estudio.previa))
            assertEquals(.75, estudio.instante, .001)
            File(app.filesDir, "cena-selecionada.json").writeText("{\"figurinhas\":true,\"estudio\":true,\"fimSemRepetirCapa\":true}")
        } finally { tela { estudio.novoProjeto() }; video.delete(); crus.arquivo.delete() }
    }
}
