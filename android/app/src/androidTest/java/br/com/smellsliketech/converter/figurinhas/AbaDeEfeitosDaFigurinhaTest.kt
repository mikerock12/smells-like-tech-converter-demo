package br.com.smellsliketech.converter.figurinhas

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.ui.TelaDasFigurinhas
import br.com.smellsliketech.converter.ui.TemaDoConverter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A aba Efeitos de verdade: escolher, tocar na prévia, parar ao sair e a máquina só em texto. */
@RunWith(AndroidJUnit4::class)
class AbaDeEfeitosDaFigurinhaTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private fun tela(bloco: () -> Unit) = i.runOnMainSync(bloco)
    private fun esperar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < limite) {
            var ok = false
            tela { ok = condicao() }
            if (ok) return
            Thread.sleep(150)
        }
        error("tempo esgotado")
    }
    private fun modelo(): FigurinhasViewModel {
        lateinit var modelo: FigurinhasViewModel
        tela { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
        return modelo
    }
    private fun foto(nome: String): File = File(contexto.cacheDir, nome).apply {
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 90, 200))
            outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    private fun todos(no: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (no == null) emptyList() else listOf(no) + (0 until no.childCount).flatMap { todos(no.getChild(it)) }
    private fun no(rotulo: String): AccessibilityNodeInfo {
        val limite = SystemClock.uptimeMillis() + 60_000
        var tentativa = 0
        do {
            // A UIA guarda a árvore em cache e não vê o que a rolagem acabou de mostrar.
            if (Build.VERSION.SDK_INT >= 34) i.uiAutomation.clearCache()
            val arvore = todos()
            arvore.firstOrNull { it.contentDescription?.toString() == rotulo || it.text?.toString() == rotulo }?.let { return it }
            if (arvore.any { it.text?.toString() in listOf("System UI isn't responding", "Pixel Launcher isn't responding") }) {
                arvore.firstOrNull { it.viewIdResourceName == "android:id/aerr_close" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // A árvore do Compose só traz o que está à vista: arrasta um pouco, vai e volta até achar.
            val descer = tentativa++ / 10 % 2 == 0
            val rolaveis = arvore.filter { it.isScrollable }.map { no -> no to Rect().also { no.getBoundsInScreen(it) } }
            rolaveis.filter { it.second.height() < it.second.width() / 4 }.forEach { (fileira, _) ->
                fileira.performAction(if (descer) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            rolaveis.maxByOrNull { it.second.height() }?.second?.let { b ->
                val x = b.left + 4f
                arrastar(x, b.top + b.height() * (if (descer) .7f else .4f), x, b.top + b.height() * (if (descer) .4f else .7f))
            }
            i.waitForIdleSync()
            Thread.sleep(350)
        } while (SystemClock.uptimeMillis() < limite)
        fotografar("falha.png")
        error("Controle não encontrado: $rotulo")
    }
    private fun toque(inicio: Long, acao: Int, x: Float, y: Float) {
        val propriedades = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordenadas = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, 1, propriedades, coordenadas, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also { i.sendPointerSync(it); it.recycle() }
    }
    private fun arrastar(x: Float, y: Float, ateX: Float = x, ateY: Float = y) {
        val inicio = SystemClock.uptimeMillis()
        toque(inicio, MotionEvent.ACTION_DOWN, x, y)
        if (x != ateX || y != ateY) repeat(8) { n ->
            SystemClock.sleep(16); toque(inicio, MotionEvent.ACTION_MOVE, x + (ateX - x) * (n + 1) / 8, y + (ateY - y) * (n + 1) / 8)
        }
        // Parar antes de soltar: sem isso a rolagem continua sozinha e passa do controle procurado.
        if (x != ateX || y != ateY) { SystemClock.sleep(200); toque(inicio, MotionEvent.ACTION_MOVE, ateX, ateY) }
        toque(inicio, MotionEvent.ACTION_UP, ateX, ateY)
    }
    private fun tocar(rotulo: String) {
        val b = Rect().also { no(rotulo).getBoundsInScreen(it) }
        arrastar(b.centerX().toFloat(), b.centerY().toFloat())
        i.waitForIdleSync(); Thread.sleep(250)
    }

    private fun fotografar(nome: String) {
        i.uiAutomation.takeScreenshot()?.let { b -> File(contexto.filesDir, nome).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun abaEfeitosEscolheTocaEParaAoSair() {
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val m = modelo()
        tela { m.abrir(Uri.fromFile(foto("aba.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            tela { atividade.setContent { TemaDoConverter { TelaDasFigurinhas(m, true, {}, {}) } } }
            i.waitForIdleSync(); Thread.sleep(600)
            tocar("Efeitos")
            assertTrue(todos().none { it.text?.toString() == "Máquina de escrever" })
            tocar("Impacto (vem de perto e recua)")
            esperar { m.edicao.efeitoEntrada == "impacto" && m.tocandoEfeito }
            assertTrue("vira animada", m.animada)
            fotografar("aba-efeitos-topo.png")
            no("Parar a prévia")
            fotografar("aba-efeitos-tocando.png")
            no("Duração da entrada")
            tocar("Camadas")
            esperar { !m.tocandoEfeito }
            // Em texto, a máquina de escrever aparece.
            tela { m.adicionarTexto("oi") }
            tocar("Efeitos")
            assertTrue("o texto continua selecionado", m.textoSelecionado)
            tocar("Máquina de escrever")
            esperar { m.edicao.efeitoEntrada == "maquina" }
            tocar("Desfazer alteração")
            esperar { m.edicao.efeitoEntrada == "nenhum" }
        } finally {
            tela { m.fecharEditor() }
            atividade.finish()
        }
    }
}
