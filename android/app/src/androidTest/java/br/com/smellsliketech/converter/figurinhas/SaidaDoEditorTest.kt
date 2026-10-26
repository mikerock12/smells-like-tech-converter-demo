package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.estudio.EstudioViewModel
import br.com.smellsliketech.converter.ui.TelaDasFigurinhas
import br.com.smellsliketech.converter.ui.TelaDoEstudio
import br.com.smellsliketech.converter.ui.TemaDoConverter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercita os caminhos reais de Voltar e dos ícones, sem perder edições ao cancelar. */
@RunWith(AndroidJUnit4::class)
class SaidaDoEditorTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as Application
    private fun tela(f: () -> Unit) = i.runOnMainSync(f)
    private fun esperar(f: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < fim) {
            var ok = false; tela { ok = f() }; if (ok) return
            Thread.sleep(100)
        }
        error("Editor não ficou pronto")
    }
    private fun nos(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nos(n.getChild(it)) }
    private fun no(texto: String): AccessibilityNodeInfo {
        val fim = SystemClock.uptimeMillis() + 15_000
        do {
            nos().firstOrNull { it.text?.toString() == texto || it.contentDescription?.toString() == texto }?.let { return it }
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < fim)
        error("Controle não encontrado: $texto")
    }
    private fun tocar(texto: String) {
        val b = Rect(); no(texto).getBoundsInScreen(b)
        val inicio = SystemClock.uptimeMillis()
        val propriedades = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordenadas = arrayOf(MotionEvent.PointerCoords().apply { x = b.centerX().toFloat(); y = b.centerY().toFloat(); pressure = 1f; size = 1f })
        for (acao in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, 1, propriedades, coordenadas, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0).also { i.sendPointerSync(it); it.recycle() }
        }
        i.waitForIdleSync(); Thread.sleep(200)
    }
    private fun voltar() { i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); i.waitForIdleSync(); Thread.sleep(200) }
    private fun atividade() = i.startActivitySync(Intent().setClassName(app.packageName,
        "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
    private fun imagem(nome: String): File = File(app.cacheDir, nome).also { arquivo ->
        Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE); arquivo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
    }

    @Test fun figurinhaPedeConfirmacaoEPreservaEdicoesAteConfirmar() {
        val arquivo = imagem("saida-figurinha.png")
        lateinit var m: FigurinhasViewModel
        tela { m = FigurinhasViewModel(app); m.abrir(Uri.fromFile(arquivo)) }
        esperar { m.previa != null && m.ocupado == null }
        tela { m.mudar(m.edicao.copy(giro = 37)) }
        val a = atividade()
        try {
            tela { a.setContent { TemaDoConverter { TelaDasFigurinhas(m, true, {}, {}) } } }
            no("Cancelar edição")
            voltar(); no("Sair da edição?")
            assertTrue(nos().any { it.text?.toString()?.contains("perderá as edições") == true })
            assertNotNull(m.origem); assertEquals(37, m.edicao.giro)
            // Voltar no próprio diálogo só fecha a pergunta.
            voltar(); no("Cancelar edição")
            assertNotNull(m.origem); assertEquals(37, m.edicao.giro)
            tocar("Cancelar edição"); no("Sair da edição?")
            tocar("Continuar editando"); no("Cancelar edição")
            assertNotNull(m.origem); assertEquals(37, m.edicao.giro)
            tocar("Cancelar edição"); no("Sair da edição?")
            i.uiAutomation.takeScreenshot()?.let { b ->
                File(app.filesDir, "saida-figurinha.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
            }
            tocar("Sair da edição"); esperar { m.origem == null }
        } finally { tela { m.fecharEditor(); a.finish() }; arquivo.delete() }
    }

    @Test fun videoPedeConfirmacaoSemApagarORascunhoAutomatico() = runBlocking {
        val png = imagem("saida-video.png")
        val arquivo = File(app.cacheDir, "saida-video.mp4")
        Ffmpeg.executar(app, listOf("-v", "error", "-y", "-loop", "1", "-i", png.absolutePath,
            "-t", "2", "-r", "10", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", arquivo.absolutePath), null)
        lateinit var m: EstudioViewModel
        tela {
            m = EstudioViewModel(app); m.novoProjeto(); m.escolherDestino("personalizado", 512, 512)
            m.escolherSaida("video"); m.adicionarMidias(listOf(Uri.fromFile(arquivo)))
        }
        esperar { m.previa != null && m.ocupado == null }
        val id = m.projeto!!.clipes.single().id
        tela { m.mudarClipe(id) { it.copy(inicio = .25, fim = 1.75) } }
        esperar { m.ocupado == null }
        val a = atividade()
        var saiu = false
        try {
            tela { a.setContent { TemaDoConverter { TelaDoEstudio(m, true, {}, {}, { saiu = true }) } } }
            no("Fechar editor (projeto mantido)")
            voltar(); no("Sair da edição?")
            assertFalse(saiu)
            assertTrue(nos().any { it.text?.toString()?.contains("rascunho automático") == true })
            voltar(); no("Fechar editor (projeto mantido)"); assertFalse(saiu)
            tocar("Fechar editor (projeto mantido)"); no("Sair da edição?")
            tocar("Continuar editando"); assertFalse(saiu)
            assertEquals(.25, m.projeto!!.clipes.single().inicio!!, .0001)
            assertEquals(1.75, m.projeto!!.clipes.single().fim!!, .0001)
            tocar("Fechar editor (projeto mantido)"); no("Sair da edição?")
            i.uiAutomation.takeScreenshot()?.let { b ->
                File(app.filesDir, "saida-video.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
            }
            tocar("Sair da edição"); esperar { saiu }
            lateinit var restaurado: EstudioViewModel
            tela { restaurado = EstudioViewModel(app) }
            assertEquals(.25, restaurado.projeto!!.clipes.single().inicio!!, .0001)
            assertEquals(1.75, restaurado.projeto!!.clipes.single().fim!!, .0001)
            File(app.filesDir, "saida-editores.json").writeText("{\"voltarEIconeProtegidos\":true,\"cancelarPreservaEdicoes\":true,\"sairExigeConfirmacao\":true,\"rascunhoEstudioMantido\":true}")
        } finally { tela { m.novoProjeto(); a.finish() }; png.delete(); arquivo.delete() }
    }
}
