package br.com.smellsliketech.converter.musica

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.ui.TelaDaMusica
import br.com.smellsliketech.converter.ui.TemaDoConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A tela da criação musical no aparelho. Fica fora de CriacaoMusicalNoAparelhoTest porque uma
 * classe de teste que usa Compose não carrega no build R8 `validacao`.
 */
@RunWith(AndroidJUnit4::class)
class TelaDaMusicaTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private val app get() = contexto.applicationContext as Application

    private fun todos(no: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (no == null) emptyList() else listOf(no) + (0 until no.childCount).flatMap { todos(no.getChild(it)) }

    private fun no(rotulo: String): AccessibilityNodeInfo {
        val limite = SystemClock.uptimeMillis() + 40_000
        var descer = true
        var tentativa = 0
        do {
            if (Build.VERSION.SDK_INT >= 34) i.uiAutomation.clearCache()
            val arvore = todos()
            arvore.firstOrNull { it.contentDescription?.toString() == rotulo || it.text?.toString() == rotulo }?.let { return it }
            if (tentativa++ % 6 == 5) descer = !descer
            arvore.filter { it.isScrollable }.maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.height() }
                ?.performAction(if (descer) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            i.waitForIdleSync()
            Thread.sleep(300)
        } while (SystemClock.uptimeMillis() < limite)
        fotografar("musica-falha.png")
        error("Controle não encontrado: $rotulo")
    }

    private fun tocar(rotulo: String) {
        val b = Rect().also { no(rotulo).getBoundsInScreen(it) }
        val inicio = SystemClock.uptimeMillis()
        fun toque(acao: Int) {
            val propriedades = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coordenadas = arrayOf(MotionEvent.PointerCoords().apply { x = b.exactCenterX(); y = b.exactCenterY(); pressure = 1f; size = 1f })
            MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, 1, propriedades, coordenadas, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also { i.sendPointerSync(it); it.recycle() }
        }
        toque(MotionEvent.ACTION_DOWN); toque(MotionEvent.ACTION_UP)
        i.waitForIdleSync(); Thread.sleep(400)
    }

    private fun fotografar(nome: String) {
        i.uiAutomation.takeScreenshot()?.let { b -> File(contexto.filesDir, nome).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun telaCriaOProjetoEPercorreAsAbas() {
        i.uiAutomation.serviceInfo = i.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        lateinit var modelo: MusicaViewModel
        i.runOnMainSync { modelo = MusicaViewModel(app) }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            i.runOnMainSync { atividade.setContent { TemaDoConverter { TelaDaMusica(modelo, true, {}, {}) } } }
            i.waitForIdleSync(); Thread.sleep(600)
            fotografar("musica-1-inicio.png")
            tocar("Modelo Banda")
            val limite = SystemClock.uptimeMillis() + 20_000
            var aberto = false
            while (!aberto && SystemClock.uptimeMillis() < limite) { i.runOnMainSync { aberto = modelo.projeto != null }; Thread.sleep(100) }
            assertTrue("o projeto abriu", aberto)
            no("Faixa Bateria")
            fotografar("musica-2-faixas.png")
            tocar("Armar Voz")
            var armada = false
            i.runOnMainSync { armada = modelo.projeto!!.faixas.single { it.nome == "Voz" }.gravar }
            assertTrue("REC arma a faixa", armada)
            tocar("Mixer")
            no("Canal Master")
            fotografar("musica-3-mixer.png")
            tocar("FX")
            tocar("+ Efeito")
            tocar("Dinâmica: Compressor")
            var efeitos = 0
            i.runOnMainSync { efeitos = modelo.projeto!!.master.efeitos.size }
            assertEquals(1, efeitos)
            fotografar("musica-4-efeitos.png")
            tocar("Gravar")
            no("Usar o microfone")
            fotografar("musica-5-gravar.png")
            tocar("Separar")
            fotografar("musica-6-separar.png")
            tocar("Exportar")
            no("Exportar música")
            fotografar("musica-7-exportar.png")
            val id = modelo.projeto!!.id
            i.runOnMainSync { modelo.fechar() }
            Thread.sleep(800)
            BibliotecaDaMusica(contexto).apagar(id)
        } finally {
            atividade.finish()
        }
    }
}
