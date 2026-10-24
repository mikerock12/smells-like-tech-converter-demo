package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.ui.TelaDasFigurinhas
import br.com.smellsliketech.converter.ui.TemaDoConverter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Verifica o arquivo carregado pelo VideoView real, os pixels exportados e a reedição. */
@RunWith(AndroidJUnit4::class)
class PlayerDaCamadaTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as Application
    private fun tela(f: () -> Unit) = i.runOnMainSync(f)
    private fun esperar(f: () -> Boolean) {
        val limite = SystemClock.uptimeMillis() + 90_000
        while (SystemClock.uptimeMillis() < limite) {
            var ok = false; tela { ok = f() }; if (ok) return; Thread.sleep(100)
        }
        error("Player/camada não ficou pronto")
    }
    private fun azul(b: Bitmap?) = b?.let { val c = it.getPixel(it.width / 2, it.height / 2); Color.blue(c) > Color.red(c) + 100 } ?: false
    private fun amarelo(b: Bitmap?) = b?.let { val c = it.getPixel(it.width / 2, it.height / 2); Color.red(c) > 150 && Color.blue(c) < 80 } ?: false
    private fun nos(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nos(n.getChild(it)) }
    private fun videos(v: View): List<VideoView> = when (v) {
        is VideoView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { videos(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun mostrarPlayer(a: ComponentActivity, duracaoMs: Int) {
        repeat(80) {
            var pronto = false; var visivel = false
            tela { videos(a.window.decorView).forEach { v ->
                val b = Rect()
                if (v.getGlobalVisibleRect(b) && b.height() > 40) {
                    visivel = true
                    pronto = v.duration in (duracaoMs - 100)..(duracaoMs + 100)
                }
            } }
            if (pronto) return
            if (!visivel) nos().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(350)
        }
        error("O player não abriu o vídeo de $duracaoMs ms")
    }
    private suspend fun video(nome: String, segundos: Int, cor: (Int) -> Int): File {
        val arquivo = File(app.cacheDir, nome)
        val crus = Producao.QuadrosCrus(File(app.cacheDir, "$nome.argb"), 10)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        crus.use { repeat(segundos * 10) { n ->
            bitmap.eraseColor(cor(n))
            Canvas(bitmap).drawRect((n * 10 % 400).toFloat(), 0f, (n * 10 % 400 + 40).toFloat(), 30f, Paint().apply { color = Color.GREEN })
            crus.gravar(bitmap)
        } }
        bitmap.recycle()
        try {
            Ffmpeg.executar(app, listOf("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "argb", "-s", "512x512", "-framerate", "10", "-i", crus.arquivo.absolutePath,
                "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", arquivo.absolutePath), null)
        } finally { crus.arquivo.delete() }
        return arquivo
    }

    @Test fun alternaVideoCortaSoACamadaEReabreOTrechoCorreto() = runBlocking {
        val principal = video("principal-player.mp4", 2) { Color.RED }
        val sobreposta = video("camada-player.mp4", 4) { if (it < 20) Color.YELLOW else Color.BLUE }
        lateinit var m: FigurinhasViewModel
        tela { m = FigurinhasViewModel(app); m.abrir(Uri.fromFile(principal)) }
        esperar { m.previa != null && m.ocupado == null }
        tela { m.mudarTrecho(0.0, 2.0); m.adicionarMidia(Uri.fromFile(sobreposta)) }
        esperar { m.camadas.size == 1 && m.ocupado == null }
        val id = m.camadas.single().id
        tela { m.mudar(m.edicao.copy(zoom = 1f)); m.mudarQuadrosPorSegundo(8) }
        val a = i.startActivitySync(Intent().setClassName(app.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var pacoteId: String? = null
        try {
            tela { a.setContent { TemaDoConverter { TelaDasFigurinhas(m, true, {}, {}) } } }
            mostrarPlayer(a, 4000)
            tela { m.selecionarCamada(null) }; mostrarPlayer(a, 2000)
            tela { m.selecionarCamada(id) }; mostrarPlayer(a, 4000)
            tela { m.adicionarTexto("Camada de texto") }
            val texto = m.camadaSelecionada!!
            esperar { videos(a.window.decorView).isEmpty() }
            tela { m.excluirCamada(texto); m.selecionarCamada(id) }; mostrarPlayer(a, 4000)
            tela { m.mudarTrechoSelecionado(2.0, 3.0); m.mostrarInstanteDaSelecionada(2.5) }
            esperar { m.ocupado == null && azul(m.previa) && azul(m.base) }
            assertEquals(0.5f, m.tempoDaPrevia, .001f)
            assertEquals(0.0, m.inicio, 0.0); assertEquals(2.0, m.fim, 0.0)
            assertEquals(2.0, m.inicioSelecionado, 0.0); assertEquals(3.0, m.fimSelecionado, 0.0)
            tela { m.desfazer() }; esperar { m.ocupado == null && amarelo(m.previa) }
            assertEquals(0.0, m.inicioSelecionado, 0.0)
            tela { m.refazer() }; esperar { m.ocupado == null && azul(m.previa) }
            tela { m.selecionarCamada(null) }
            assertEquals(0.0, m.inicioSelecionado, 0.0); assertEquals(2.0, m.fimSelecionado, 0.0)
            tela { m.selecionarCamada(id); m.mudarAnimada(false); m.verComoFica() }
            esperar { m.previaFinal?.animada == false && m.ocupado == null }
            val estatica = android.graphics.BitmapFactory.decodeFile(m.previaFinal!!.arquivo.absolutePath)
            assertTrue("Estática usa o início do corte da camada", azul(estatica)); estatica.recycle()
            tela { m.exibindoPreviaFinal = false; m.mudarAnimada(true); m.verComoFica() }
            esperar { m.previaFinal?.animada == true && m.ocupado == null }
            val webp = m.previaFinal!!.arquivo.readBytes()
            val informacao = WebpAnimado.ler(webp)
            assertEquals(2000L, informacao.duracaoMs)
            // Cada ANMF é um quadro completo independente; o decoder Android lê seu WebP estático.
            informacao.quadros.forEach { quadro ->
                val anmf = quadro.posicaoDoTempo - 20
                val tamanho = (0..3).fold(0) { n, k -> n or ((webp[anmf + 4 + k].toInt() and 255) shl (8 * k)) }
                val estatico = webp.copyOfRange(0, 30) + webp.copyOfRange(anmf + 24, anmf + 8 + tamanho)
                repeat(4) { k -> estatico[4 + k] = ((estatico.size - 8) ushr (8 * k)).toByte() }
                estatico[20] = (estatico[20].toInt() and 0xfd).toByte() // sem a flag de animação
                val imagem = android.graphics.BitmapFactory.decodeByteArray(estatico, 0, estatico.size)
                assertNotNull("Quadro exportado decodificável", imagem)
                assertTrue("Animada repete só o trecho escolhido da camada", azul(imagem)); imagem.recycle()
            }
            tela { m.exibindoPreviaFinal = false; m.salvar(null, "Player de camada") }
            esperar { m.origem == null && m.ocupado == null }
            val pacote = m.pacoteAberto!!; pacoteId = pacote.id
            val figurinha = pacote.figurinhas.single()
            val pasta = m.pacotes.pastaDoProjeto(pacote, figurinha.arquivo)
            val salvo = Projetos.ler(pasta)!!
            assertEquals(0.0, salvo.camadas.single().inicio, 0.0)
            assertEquals(1.0, salvo.camadas.single().fim!!, .001)
            // Compatibilidade: projetos anteriores não possuem os dois campos novos.
            val json = File(pasta, Projetos.ARQUIVO); val original = json.readText()
            val antigo = JSONObject(original).put("versao", 1)
            antigo.getJSONArray("camadas").getJSONObject(0).apply { remove("inicio"); remove("fim") }
            json.writeText(antigo.toString())
            assertEquals(0.0, Projetos.ler(pasta)!!.camadas.single().inicio, 0.0)
            assertNull(Projetos.ler(pasta)!!.camadas.single().fim)
            json.writeText(original)
            tela { m.editar(pacote, figurinha) }
            esperar { m.editando != null && m.ocupado == null && azul(m.previa) }
            tela { m.selecionarCamada(id); m.mostrarInstante(1.5f) }
            esperar { m.ocupado == null && azul(m.previa) }
            assertEquals(0.0, m.inicioSelecionado, 0.0); assertEquals(1.0, m.fimSelecionado, .001)
            File(app.filesDir, "player-da-camada.json").writeText("{\"playerAlternado2s4s\":true,\"corteIndependente\":true,\"estaticaAnimadaAzuis\":true,\"historicoReedicaoLegado\":true}")
        } finally {
            tela { m.fecharEditor(); m.mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO); a.finish() }
            pacoteId?.let { m.pacotes.ler(it)?.let(m.pacotes::excluir) }
            principal.delete(); sobreposta.delete()
        }
    }
}
