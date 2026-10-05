package br.com.smellsliketech.converter.conversao

import android.app.NotificationManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.MainActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Só no emulador de testes: PDF sintético, sem documentos/dados do usuário. */
@RunWith(AndroidJUnit4::class)
class SegundoPlanoTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentacao.targetContext
    @Before fun prepararNotificacao() {
        if (InstrumentationRegistry.getArguments().getString("notificacoes") != "negadas") {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
    }
    private fun principal(fazer: () -> Unit) = instrumentacao.runOnMainSync(fazer)
    private fun shell(comando: String): String = instrumentacao.uiAutomation.executeShellCommand(comando).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private suspend fun esperar(condicao: () -> Boolean) = withTimeout(900_000) {
        while (true) {
            var pronto = false
            principal { pronto = condicao() }
            if (pronto) break
            delay(250)
        }
    }

    @Test fun pdfDozePaginasContinuaComOutraTelaEApagado() = runBlocking {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val arquivo = File(context.cacheDir, "teste-12-paginas.pdf")
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        PDDocument().use { pdf ->
            repeat(12) { indice ->
                val pagina = PDPage()
                pdf.addPage(pagina)
                PDPageContentStream(pdf, pagina).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.setLeading(18f); stream.newLineAtOffset(40f, 740f)
                    for (linha in listOf(
                        "Pagina ${indice + 1}. Este documento testa uma conversao longa.",
                        "A voz brasileira deve continuar com a tela do celular apagada.",
                        "Trocar de aplicativo nao pode cancelar o trabalho iniciado.",
                        "Cada trecho gera audio real e atualiza a estimativa de tempo.",
                        "O resultado completo deve aparecer na pasta de downloads.",
                    )) { stream.showText(linha); stream.newLine() }
                    stream.endText()
                }
            }
            pdf.save(arquivo)
        }
        val entrada = Entrada(Uri.fromFile(arquivo), arquivo.name, Tipo.PDF, arquivo.length())
        val trabalho = Trabalho(UUID.randomUUID().toString(), "Teste PDF 12 páginas", listOf(arquivo.name))
        val fila = FilaDeConversoes.obter(context)
        var cenario: ActivityScenario<MainActivity>? = null
        val amostras = mutableListOf<String>()
        try {
            cenario = ActivityScenario.launch(MainActivity::class.java)
            principal {
                fila.enfileirar(trabalho) {
                    Motor.executar(context, Ferramenta.PDF_NARRAR, listOf(entrada), Opcoes("mp3"),
                        aoEstimarNarracao = { restante, etapa ->
                            synchronized(amostras) { amostras += "${android.os.SystemClock.elapsedRealtime()}: ${trabalho.progresso}% / $restante / $etapa" }
                            fila.estimar(trabalho, restante, etapa)
                        },
                    ) { fila.avancar(trabalho, it, estimar = false) }
                }
            }
            esperar { trabalho.etapa.startsWith("Gerando voz") || trabalho.terminado }
            principal { assertFalse(trabalho.erro, trabalho.terminado); assertNull(trabalho.restante) }
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 41 })
            cenario.recreate() // ViewModel novo deve enxergar o mesmo estado da fila.
            shell("am start -a android.settings.SETTINGS")
            var antes = 0
            principal { antes = trabalho.progresso }
            esperar { trabalho.progresso > antes || trabalho.terminado }
            principal { assertFalse(trabalho.erro, trabalho.terminado) }
            shell("input keyevent KEYCODE_SLEEP")
            delay(1_000)
            assertFalse("Tela deve estar apagada", context.getSystemService(PowerManager::class.java).isInteractive)
            principal { antes = trabalho.progresso }
            // Destruir a Activity também: o serviço é o dono da tarefa.
            cenario.close(); cenario = null
            esperar { trabalho.progresso > antes || trabalho.terminado }
            esperar { trabalho.terminado }
            principal {
                assertNull(trabalho.erro)
                assertEquals(100, trabalho.progresso)
                assertEquals(1, trabalho.resultados.size)
            }
            val audio = trabalho.resultados.single()
            assertTrue("MP3 completo deve ter tamanho relevante", audio.bytes > 100_000)
            assertTrue("Estimativa deve ser medida durante a voz", synchronized(amostras) { amostras.any { !it.contains(" / null / ") } })
            delay(1_000)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 41 })
            Log.i("SegundoPlanoTest", "12 páginas OK: ${audio.bytes} bytes; ${amostras.size} medições")
            File(context.getExternalFilesDir(null), "segundo-plano-042.txt").writeText(amostras.joinToString("\n"))
        } finally {
            principal { if (!trabalho.terminado) fila.cancelar(trabalho) }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            cenario?.close()
            trabalho.resultados.forEach { context.contentResolver.delete(it.uri, null, null) }
            arquivo.delete()
        }
    }

    @Test fun cancelarFilaNaoIniciaProximoTrabalhoCancelado() = runBlocking {
        val cenario = ActivityScenario.launch(MainActivity::class.java)
        val fila = FilaDeConversoes.obter(context)
        val primeiro = Trabalho(UUID.randomUUID().toString(), "Primeiro", emptyList())
        val segundo = Trabalho(UUID.randomUUID().toString(), "Segundo", emptyList())
        var segundoExecutou = false
        try {
            principal {
                fila.enfileirar(primeiro) { delay(60_000); emptyList() }
                fila.enfileirar(segundo) { segundoExecutou = true; emptyList() }
            }
            esperar { primeiro.etapa != "Na fila…" || primeiro.terminado }
            principal { assertNull(primeiro.erro) }
            principal { fila.cancelar(segundo); fila.cancelar(primeiro) }
            esperar { primeiro.terminado && segundo.terminado }
            principal {
                assertFalse(segundoExecutou)
                assertEquals("Cancelada.", primeiro.erro)
                assertEquals("Cancelada.", segundo.erro)
                assertTrue(primeiro.progresso < 100)
            }
        } finally { cenario.close() }
    }

    @Test fun notificacaoNegadaNaoImpedeConversao() = runBlocking {
        // A revogação mata o processo do app. Preparar via adb ANTES de iniciar o runner.
        assumeTrue("Executar separadamente com -e notificacoes negadas e permissão revogada",
            InstrumentationRegistry.getArguments().getString("notificacoes") == "negadas")
        assertFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        val cenario = ActivityScenario.launch(MainActivity::class.java)
        val fila = FilaDeConversoes.obter(context)
        val trabalho = Trabalho(UUID.randomUUID().toString(), "Notificação negada", emptyList())
        try {
            principal { fila.enfileirar(trabalho) { delay(3_000); emptyList() } }
            esperar { trabalho.terminado }
            principal { assertNull(trabalho.erro); assertEquals(100, trabalho.progresso) }
        } finally {
            cenario.close()
        }
    }

    @Test fun limiteDoAndroidEncerraComErroSemFingirSucesso() = runBlocking {
        val chave = "media_processing_fgs_timeout_duration"
        val anterior = shell("device_config get activity_manager $chave").trim()
        assertTrue(anterior == "null" || anterior.matches(Regex("[0-9]+")))
        val fila = FilaDeConversoes.obter(context)
        val trabalho = Trabalho(UUID.randomUUID().toString(), "Teste limite Android", emptyList())
        var cenario: ActivityScenario<MainActivity>? = null
        try {
            // Só no emulador de teste: não esperar seis horas para validar onTimeout.
            shell("device_config put activity_manager $chave 3000")
            cenario = ActivityScenario.launch(MainActivity::class.java)
            principal { fila.enfileirar(trabalho) { delay(60_000); emptyList() } }
            esperar { trabalho.etapa != "Na fila…" || trabalho.terminado }
            principal { assertNull(trabalho.erro) }
            cenario.close(); cenario = null
            shell("input keyevent KEYCODE_HOME")
            esperar { trabalho.terminado }
            principal {
                assertTrue(trabalho.erro.orEmpty().contains("limite"))
                assertTrue(trabalho.progresso < 100)
                assertNull(trabalho.restante)
            }
            delay(1_000)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 41 })
        } finally {
            if (anterior == "null") shell("device_config delete activity_manager $chave")
            else shell("device_config put activity_manager $chave $anterior")
            principal { if (!trabalho.terminado) fila.cancelar(trabalho) }
            cenario?.close()
        }
    }
}
