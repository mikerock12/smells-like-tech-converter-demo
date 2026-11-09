package br.com.smellsliketech.converter.musica

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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * As capturas da criação musical para a Google Play e o portal: a demo (bateria, baixo,
 * teclado e voz) posta em files/demo-musica pelo adb vira um projeto, e cada aba é
 * fotografada. Sem a demo no aparelho, o teste não roda.
 */
@RunWith(AndroidJUnit4::class)
class CapturasDaMusicaTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext

    private fun todos(no: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (no == null) emptyList() else listOf(no) + (0 until no.childCount).flatMap { todos(no.getChild(it)) }

    private fun no(rotulo: String): AccessibilityNodeInfo {
        val limite = SystemClock.uptimeMillis() + 30_000
        do {
            if (Build.VERSION.SDK_INT >= 34) i.uiAutomation.clearCache()
            todos().firstOrNull { it.contentDescription?.toString() == rotulo || it.text?.toString() == rotulo }?.let { return it }
            Thread.sleep(300)
        } while (SystemClock.uptimeMillis() < limite)
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
        i.waitForIdleSync(); Thread.sleep(700)
    }

    private fun fotografar(nome: String) {
        Thread.sleep(500)
        i.uiAutomation.takeScreenshot()?.let { b -> File(contexto.filesDir, "capturas-musica/$nome").apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun capturasDaCriacaoMusical() {
        val demo = File(contexto.filesDir, "demo-musica")
        assumeTrue("demo ausente", File(demo, "voz.wav").exists())
        val biblioteca = BibliotecaDaMusica(contexto)
        val taxa = 48000
        var p = Musica.projetoDoModelo("banda", "musica-demo-android", "Minha banda")
        p = p.copy(faixas = p.faixas.filter { it.nome != "Guitarra" })
        val mapa = mapOf("Bateria" to "bateria", "Baixo" to "baixo", "Teclado" to "teclado", "Voz" to "voz")
        val midias = mapa.values.map { id ->
            val destino = biblioteca.midia(p.id, id).apply { parentFile?.mkdirs() }
            File(demo, "$id.wav").copyTo(destino, overwrite = true)
            FonteMapeada(destino).use { MidiaMusical(id, "$id.wav", "wav", taxa, it.canais, it.quadros) }
        }
        p = p.copy(
            midias = midias,
            faixas = p.faixas.map { f -> val id = mapa.getValue(f.nome); f.copy(clipes = listOf(ClipeMusical("c-$id", id, 0, 0, midias.first { it.id == id }.quadros, nome = f.nome)), pan = if (f.nome == "Teclado") 30.0 else if (f.nome == "Baixo") -15.0 else 0.0) },
            master = p.master.copy(efeitos = listOf(DspDaMusica.novoEfeito("limitador", "fx-lim"))),
        )
        p = p.copy(faixas = p.faixas.map { if (it.nome == "Voz") it.copy(efeitos = listOf(DspDaMusica.novoEfeito("compressor", "fx-c"), DspDaMusica.novoEfeito("equalizador", "fx-eq").com("presencaDb", 3.0), DspDaMusica.novoEfeito("reverberacao", "fx-r"))) else it })
        biblioteca.salvar(p)
        lateinit var modelo: MusicaViewModel
        i.runOnMainSync { modelo = MusicaViewModel(contexto.applicationContext as Application) }
        val atividade = i.startActivitySync(Intent().setClassName(contexto.packageName, "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            i.runOnMainSync { atividade.setContent { TemaDoConverter { TelaDaMusica(modelo, true, {}, {}) } } }
            i.waitForIdleSync(); Thread.sleep(800)
            fotografar("1-inicio.png")
            i.runOnMainSync { modelo.abrirSalvo(p.id) }
            no("Faixa Bateria")
            Thread.sleep(2500)
            i.runOnMainSync { modelo.irPara(taxa * 5L + taxa / 3) }
            Thread.sleep(400)
            fotografar("2-faixas.png")
            tocar("Mixer")
            i.runOnMainSync { modelo.tocarOuParar() }
            Thread.sleep(1500)
            fotografar("3-mixer.png")
            i.runOnMainSync { modelo.tocarOuParar() }
            tocar("FX")
            tocar("Master")
            tocar("Faixa: Voz")
            fotografar("4-efeitos.png")
            tocar("Gravar")
            fotografar("5-gravar.png")
            tocar("Separar")
            fotografar("6-separar.png")
            tocar("Exportar")
            fotografar("7-exportar.png")
            i.runOnMainSync { modelo.fechar() }
            Thread.sleep(800)
        } finally {
            atividade.finish()
            biblioteca.apagar(p.id)
        }
    }
}
