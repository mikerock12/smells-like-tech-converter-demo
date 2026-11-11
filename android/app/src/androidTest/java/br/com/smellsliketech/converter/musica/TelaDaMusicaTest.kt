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
import kotlin.math.sin

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
            arvore.filter { it.isScrollable }.forEach {
                it.performAction(if (descer) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
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

    @Test fun listaApareceAoEntrarESaidaPerguntaSemSalvar() {
        val biblioteca = BibliotecaDaMusica(contexto)
        val salvo = Musica.projetoDoModelo("vazio", "projeto-lista", "Projeto que estava salvo")
        biblioteca.salvar(salvo)
        lateinit var modelo: MusicaViewModel
        i.runOnMainSync { modelo = MusicaViewModel(app); modelo.definirSalvamentoAutomatico(false) }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            i.runOnMainSync { atividade.setContent { TemaDoConverter { TelaDaMusica(modelo, true, {}, {}) } } }
            no("Projeto que estava salvo")
            tocar("Modelo Em branco")
            Thread.sleep(500)
            i.runOnMainSync { modelo.mudar { it.copy(titulo = "Não salvar esta mudança") }; modelo.fechar() }
            no("Salvar o projeto?")
            tocar("Continuar editando")
            assertTrue(modelo.projeto != null)
            i.runOnMainSync { modelo.fechar() }
            tocar("Sair sem salvar")
            Thread.sleep(500)
            assertTrue(modelo.projeto == null)
            assertTrue(runCatching { biblioteca.ler("projeto-lista") }.isSuccess)
        } finally {
            biblioteca.apagar(salvo.id)
            modelo.projeto?.let { biblioteca.apagar(it.id) }
            atividade.finish()
        }
    }

    @Test fun tempoETomMudamOClipeNaTela() {
        val biblioteca = BibliotecaDaMusica(contexto)
        val p = Musica.projetoDoModelo("vazio", "projeto-tempo-tom", "Tempo")
        val midia = MidiaMusical("seno", "Seno.wav", "wav", 48000, 1, 48000)
        val clipe = ClipeMusical("clipe-seno", "seno", 0, 0, 48000, nome = "Seno")
        val projeto = p.copy(midias = listOf(midia), faixas = listOf(p.faixas[0].copy(nome = "Teste", clipes = listOf(clipe))))
        EscritorDeWav(biblioteca.midia(p.id, "seno"), 1, 48000).use { it.escrever(FloatArray(48000) { i -> (0.4 * sin(2 * Math.PI * 440 * i / 48000)).toFloat() }) }
        biblioteca.salvar(projeto)
        lateinit var modelo: MusicaViewModel
        i.runOnMainSync { modelo = MusicaViewModel(app); modelo.definirSalvamentoAutomatico(false) }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            i.runOnMainSync { atividade.setContent { TemaDoConverter { TelaDaMusica(modelo, true, {}, {}) } }; modelo.abrir(projeto) }
            tocar("Tempo e tom")
            tocar("Aumentar Velocidade de Seno")
            var atual: ClipeMusical? = null
            i.runOnMainSync { atual = modelo.projeto!!.faixas[0].clipes[0] }
            assertEquals(1.01, atual!!.velocidade!!, 1e-9)
            tocar("Aumentar Transposição de Seno")
            i.runOnMainSync { atual = modelo.projeto!!.faixas[0].clipes[0] }
            assertEquals(1.0, atual!!.semitons!!, 1e-9)
            fotografar("musica-tempo-tom.png")
        } finally {
            biblioteca.apagar(p.id)
            atividade.finish()
        }
    }
}
