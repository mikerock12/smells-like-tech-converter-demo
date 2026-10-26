package br.com.smellsliketech.converter

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.ui.TemaDoConverter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JanelaDoAppTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun tela(f: () -> Unit) = i.runOnMainSync(f)
    private fun nos(n: AccessibilityNodeInfo? = i.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nos(n.getChild(it)) }
    private fun caixa(texto: String): Rect {
        val fim = SystemClock.uptimeMillis() + 15_000
        do {
            val arvore = nos()
            // O AVD pode abrir ANR do sistema ao trocar o recorte. Nunca fechar
            // um ANR do Converter; no sistema, esperar sem encerrar o processo.
            if (arvore.any { it.text?.toString() in listOf("Process system isn't responding", "System UI isn't responding", "Pixel Launcher isn't responding") })
                arvore.firstOrNull { it.text?.toString() == "Wait" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            arvore.firstOrNull { it.text?.toString() == texto }?.let { n -> return Rect().also { n.getBoundsInScreen(it) } }
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < fim)
        error("Controle não encontrado: $texto")
    }
    @Test fun barrasRecorteETecladoNaoCobremOsControles() {
        val app = i.targetContext
        val a = i.startActivitySync(Intent().setClassName(app.packageName,
            "br.com.smellsliketech.converter.EditorDeTesteActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        try {
            val paisagem = InstrumentationRegistry.getArguments().getString("paisagem") == "true"
            if (paisagem) {
                tela { a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val limite = SystemClock.uptimeMillis() + 20_000
                var girou = false
                do {
                    tela { girou = a.window.decorView.width > a.window.decorView.height }
                    if (girou) break
                    Thread.sleep(100)
                } while (SystemClock.uptimeMillis() < limite)
                assertTrue("A janela precisa estar realmente em paisagem", girou)
            }
            tela { a.setContent { TemaDoConverter {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                        Text("Topo protegido")
                        Spacer(Modifier.weight(1f))
                        OutlinedTextField("", {}, label = { Text("Campo do teclado") }, singleLine = true)
                        Row { Button(onClick = {}) { Text("Salvar protegido") }; Button(onClick = {}) { Text("Cancelar protegido") } }
                    }
                }
            } } }
            i.waitForIdleSync()
            val topo = caixa("Topo protegido")
            val salvar = caixa("Salvar protegido")
            val cancelar = caixa("Cancelar protegido")
            var altura = 0; var largura = 0; var limites = androidx.core.graphics.Insets.NONE
            tela {
                altura = a.window.decorView.height; largura = a.window.decorView.width
                limites = ViewCompat.getRootWindowInsets(a.window.decorView)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                if (Build.VERSION.SDK_INT >= 30) assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, a.window.attributes.layoutInDisplayCutoutMode)
            }
            assertTrue(topo.top >= limites.top)
            for (b in listOf(topo, salvar, cancelar)) {
                assertTrue(b.left >= limites.left); assertTrue(b.right <= largura - limites.right)
                assertTrue(b.bottom <= altura - limites.bottom)
            }
            val campo = caixa("Campo do teclado")
            i.uiAutomation.executeShellCommand("input tap ${campo.centerX()} ${campo.centerY()}").close()
            val fim = SystemClock.uptimeMillis() + 15_000
            var teclado = 0
            do {
                tela { val insets = ViewCompat.getRootWindowInsets(a.window.decorView)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) teclado = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom }
                if (teclado > 0) break
                Thread.sleep(100)
            } while (SystemClock.uptimeMillis() < fim)
            assertTrue("Teclado real aberto", teclado > 0)
            Thread.sleep(500)
            assertTrue("Salvar visível acima do teclado", caixa("Salvar protegido").bottom <= altura - teclado)
            assertTrue("Cancelar visível acima do teclado", caixa("Cancelar protegido").bottom <= altura - teclado)
            if (paisagem) assertTrue(largura > altura)
            val nome = "janela-${largura}x${altura}-nav${limites.bottom}"
            i.uiAutomation.takeScreenshot()?.let { b ->
                java.io.File(app.filesDir, "$nome-ime.png").outputStream().use {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }; b.recycle()
            }
            java.io.File(app.filesDir, "$nome.json").writeText(
                "{\"sdk\":${Build.VERSION.SDK_INT},\"largura\":$largura,\"altura\":$altura,\"ime\":$teclado,\"topo\":${limites.top},\"esquerda\":${limites.left},\"direita\":${limites.right},\"rodape\":${limites.bottom},\"controlesProtegidos\":true}")
        } finally {
            java.io.File(app.filesDir, "janela-arvore.txt").writeText(nos().joinToString("\n") { "${it.className}: ${it.text} / ${it.contentDescription}" })
            i.uiAutomation.takeScreenshot()?.let { b ->
                java.io.File(app.filesDir, "janela-diagnostico.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
            }
            tela { a.finish() }
        }
    }
}
