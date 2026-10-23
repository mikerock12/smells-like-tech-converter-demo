package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.ui.TelaDasFigurinhas
import br.com.smellsliketech.converter.ui.TemaDoConverter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercita a tela real, o despacho multitouch e os pixels da máscara. */
@RunWith(AndroidJUnit4::class)
class EditorFixoTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private fun tela(bloco: () -> Unit) = i.runOnMainSync(bloco)
    private fun esperar(condicao: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < fim) {
            var ok = false; tela { ok = condicao() }
            if (ok) return
            Thread.sleep(100)
        }
        error("A tela não ficou pronta")
    }
    private fun todos(no: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (no == null) emptyList() else listOf(no) + (0 until no.childCount).flatMap { todos(no.getChild(it)) }
    private fun no(descricao: String): AccessibilityNodeInfo {
        val limite = SystemClock.uptimeMillis() + 20_000
        do {
            val arvore = todos()
            arvore.firstOrNull { it.contentDescription?.toString() == descricao }?.let { return it }
            arvore.firstOrNull { it.text?.toString() == descricao }?.let { return it }
            // O AVD pode apresentar ANR do launcher/System UI durante o boot. Nunca fechar ANR do app.
            if (arvore.any { it.text?.toString() in listOf("System UI isn't responding", "Pixel Launcher isn't responding") }) {
                arvore.firstOrNull { it.viewIdResourceName == "android:id/aerr_close" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            Thread.sleep(200)
        } while (SystemClock.uptimeMillis() < limite)
        error("Controle não encontrado: $descricao")
    }
    private fun caixa(descricao: String) = Rect().also { no(descricao).getBoundsInScreen(it) }
    private fun tocar(descricao: String) {
        val b = caixa(descricao); gesto(b.centerX().toFloat(), b.centerY().toFloat())
        i.waitForIdleSync(); Thread.sleep(150)
    }
    private fun gesto(x: Float, y: Float, ateX: Float = x, ateY: Float = y) {
        val inicio = SystemClock.uptimeMillis()
        fun enviar(acao: Int, px: Float, py: Float) {
            enviarToque(inicio, acao, px, py)
        }
        enviar(MotionEvent.ACTION_DOWN, x, y)
        if (x != ateX || y != ateY) repeat(8) { n ->
            SystemClock.sleep(16); enviar(MotionEvent.ACTION_MOVE, x + (ateX - x) * (n + 1) / 8, y + (ateY - y) * (n + 1) / 8)
        }
        enviar(MotionEvent.ACTION_UP, ateX, ateY)
    }
    private fun enviarToque(inicio: Long, acao: Int, x: Float, y: Float) {
        val propriedades = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordenadas = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, 1, propriedades, coordenadas, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also { i.sendPointerSync(it); it.recycle() }
    }
    private fun inclinar(b: Rect) {
        val inicio = SystemClock.uptimeMillis()
        val props = Array(2) { n -> MotionEvent.PointerProperties().apply { id = n; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun enviar(acao: Int, quantidade: Int, dy: Float) {
            val coords = Array(quantidade) { n -> MotionEvent.PointerCoords().apply {
                x = b.centerX() + (if (n == 0) -40f else 40f); y = b.centerY() + dy; pressure = 1f; size = 1f
            } }
            val e = MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, quantidade, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            i.sendPointerSync(e); e.recycle()
        }
        enviar(MotionEvent.ACTION_DOWN, 1, 0f)
        enviar(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
        repeat(8) { SystemClock.sleep(16); enviar(MotionEvent.ACTION_MOVE, 2, (it + 1) * 4f) }
        enviar(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 32f)
        enviar(MotionEvent.ACTION_UP, 1, 32f)
    }

    @Test fun previaRodapeInclinacaoPincelZoomEHistorico() {
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val arquivo = File(contexto.filesDir, "editor-fixo-teste.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE); arquivo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        lateinit var modelo: FigurinhasViewModel
        tela { modelo = FigurinhasViewModel(contexto.applicationContext as Application); modelo.abrir(Uri.fromFile(arquivo)) }
        esperar { modelo.previa != null && modelo.ocupado == null }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            tela { atividade.setContent { TemaDoConverter { TelaDasFigurinhas(modelo, true, {}, {}) } } }
            i.waitForIdleSync(); Thread.sleep(600)
            val previa = caixa("Prévia da figurinha")
            val salvar = caixa("Salvar no pacote")
            val cancelar = caixa("Cancelar edição")
            assertTrue(salvar.bottom <= atividade.resources.displayMetrics.heightPixels)
            assertEquals(salvar.top, cancelar.top)
            val rolavel = todos().first { it.actionList.any { acao -> acao.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            rolavel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            i.waitForIdleSync(); Thread.sleep(350)
            assertEquals(previa, caixa("Prévia da figurinha")); assertEquals(salvar, caixa("Salvar no pacote"))
            inclinar(previa)
            i.waitForIdleSync()
            esperar { modelo.edicao.inclinacaoX > 3f }
            assertEquals(0f, modelo.edicao.deslocamentoY, .0001f)
            tocar("Desfazer alteração")
            esperar { modelo.edicao.inclinacaoX == 0f }
            tocar("Fundo")
            tocar("Pincel (ajuste à mão)")
            i.waitForIdleSync(); Thread.sleep(300)
            val area = caixa("Prévia da figurinha")
            val x = area.centerX().toFloat(); val y = area.centerY() + 38 * atividade.resources.displayMetrics.density
            gesto(x, y, x + 12, y)
            i.waitForIdleSync()
            assertNull("Primeiro arraste apenas posiciona", modelo.mascara)
            Thread.sleep(350)
            gesto(x, y)
            SystemClock.sleep(70)
            gesto(x, y, x + 25, y)
            esperar { modelo.mascara != null }
            assertEquals(0, Color.alpha(modelo.mascara!!.getPixel(128, 128)))
            tocar("Desfazer alteração")
            esperar { modelo.mascara == null }
            tocar("Refazer alteração")
            esperar { modelo.mascara != null }
            val zoom = modelo.edicao.zoom
            tocar("Desenhar caixa de zoom")
            gesto(area.left + area.width() * .25f, area.top + area.height() * .25f, area.left + area.width() * .75f, area.top + area.height() * .75f)
            i.waitForIdleSync(); Thread.sleep(150)
            assertTrue(todos().any { it.text?.toString() == "2×" })
            assertEquals("Zoom de inspeção não muda exportação", zoom, modelo.edicao.zoom, 0f)
            tocar("Ver imagem inteira")
            tela {
                modelo.adicionarTexto("Camada de teste")
                val id = modelo.camadaSelecionada!!
                modelo.excluirCamada(id)
                modelo.desfazer()
                assertTrue("Camada excluída pode ser recuperada", modelo.camadas.any { it.id == id })
                modelo.refazer()
                assertFalse(modelo.camadas.any { it.id == id })
            }
            i.waitForIdleSync()
            val botaoSalvar = caixa("Salvar no pacote")
            val tempo = SystemClock.uptimeMillis()
            enviarToque(tempo, MotionEvent.ACTION_DOWN, botaoSalvar.centerX().toFloat(), botaoSalvar.centerY().toFloat())
            try {
                SystemClock.sleep(900)
                i.uiAutomation.takeScreenshot()?.let { imagem ->
                    File(contexto.filesDir, "editor-tooltip.png").outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    imagem.recycle()
                }
                val janelas = i.uiAutomation.windows.mapNotNull { it.root }.flatMap { todos(it) }
                assertTrue("Toque longo explica o ícone", janelas.any { it.text?.toString() == "Salvar no pacote" })
            } finally {
                enviarToque(tempo, MotionEvent.ACTION_UP, botaoSalvar.centerX().toFloat(), botaoSalvar.centerY().toFloat())
            }
        } finally {
            i.uiAutomation.takeScreenshot()?.let { imagem ->
                File(contexto.filesDir, "editor-fixo.png").outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) }
                imagem.recycle()
            }
            tela { modelo.fecharEditor(); atividade.finish() }
            arquivo.delete()
        }
    }
}
