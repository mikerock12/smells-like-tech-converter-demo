package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Editar de novo uma figurinha do pacote: o projeto é guardado ao salvar, reabre com as mesmas
 * camadas e ajustes, a prévia final é a mesma figurinha que o Salvar grava, e salvar troca a
 * figurinha no lugar (sem acrescentar outra).
 */
@RunWith(AndroidJUnit4::class)
class EditarDeNovoTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext

    private fun modelo(): FigurinhasViewModel {
        lateinit var modelo: FigurinhasViewModel
        instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
        return modelo
    }

    private fun foto(nome: String, largura: Int, altura: Int): File = File(contexto.cacheDir, nome).apply {
        Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.rgb(30, 90, 200)); drawCircle(largura / 2f, altura / 2f, altura / 3f, Paint().apply { color = Color.YELLOW }) }
            outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    private fun esperar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < limite) {
            var ok = false
            instrumentacao.runOnMainSync { ok = condicao() }
            if (ok) return
            Thread.sleep(150)
        }
        error("tempo esgotado")
    }

    private fun naTela(bloco: () -> Unit) = instrumentacao.runOnMainSync(bloco)

    @Test fun reabreTrocaNoLugarEReaproveitaAPreviaFinal() {
        val m = modelo()
        val arquivo = foto("reeditar.png", 1600, 900)
        naTela { m.abrir(Uri.fromFile(arquivo)) }
        esperar { m.ocupado == null && m.previa != null }
        naTela {
            m.mudar(m.edicao.copy(zoom = 1.3f, giro = 15, filtro = Filtro.SEPIA))
            m.adicionarTexto("Olá")
            m.selecionarCamada(null)
            m.emojis = listOf("😀", "🔥")
            m.salvar(null, "Reeditar")
        }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        val figurinha = pacote.figurinhas.single()
        try {
            assertTrue("projeto guardado", m.podeEditar(pacote, figurinha))

            naTela { m.editar(pacote, figurinha) }
            esperar { m.ocupado == null && m.previa != null && m.editando != null }
            var antes: Edicao? = null
            naTela { antes = m.edicao }
            assertEquals(1.3f, antes!!.zoom, 0.0001f)
            assertEquals(15, antes!!.giro)
            assertEquals(Filtro.SEPIA, antes!!.filtro)
            assertEquals(1, m.camadas.size)
            assertEquals("Olá", m.camadas.single().edicao.texto)
            assertEquals(listOf("😀", "🔥"), m.emojis)

            // Preencher: a imagem deitada cobre a figurinha inteira.
            naTela { m.preencher() }
            var depois: Edicao? = null
            naTela { depois = m.edicao }
            val caixa = Guias.caixa(1600, 900, depois!!)!!
            assertEquals(0f, caixa.topo, 0.002f)
            assertEquals(1f, caixa.base, 0.002f)
            assertEquals(0, depois!!.giro)

            naTela { m.verComoFica() }
            Thread.sleep(200)
            esperar { m.ocupado == null && m.previaFinal != null }
            val previa = m.previaFinal!!.arquivo.readBytes()
            assertTrue(m.exibindoPreviaFinal)
            naTela { m.exibindoPreviaFinal = false; m.salvar(null, "") }
            Thread.sleep(200)
            esperar { m.ocupado == null && m.origem == null }

            val atualizado = m.pacotes.ler(pacote.id)!!
            assertEquals("troca no lugar", 1, atualizado.figurinhas.size)
            assertEquals(figurinha.arquivo, atualizado.figurinhas.single().arquivo)
            assertTrue(atualizado.versao > pacote.versao)
            assertArrayEquals("o Salvar grava a mesma figurinha da prévia final", previa, m.pacotes.arquivo(atualizado, figurinha.arquivo).readBytes())

            // Remover a figurinha leva o projeto junto.
            naTela { m.remover(atualizado, atualizado.figurinhas.single()) }
            assertFalse(m.pacotes.pastaDoProjeto(atualizado, figurinha.arquivo).exists())
        } finally {
            m.pacotes.ler(pacote.id)?.let { m.pacotes.excluir(it) }
            arquivo.delete()
        }
    }

    /** Vídeo: o projeto guarda só o trecho (leve) e reabre animada, na mesma taxa de quadros. */
    @Test fun videoReabreComOTrechoEATaxa() {
        val videos = File(contexto.filesDir, "videos-de-teste").listFiles { f -> f.extension == "mp4" }.orEmpty()
        assumeTrue("sem vídeos de teste", videos.isNotEmpty())
        val video = videos.minBy { it.length() }
        val m = modelo()
        naTela { m.mudarQuadrosPorSegundo(12); m.abrir(Uri.fromFile(video)) }
        esperar { m.ocupado == null && m.previa != null }
        naTela { m.inicio = 1.0; m.fim = 4.0; m.salvar(null, "Reeditar vídeo") }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        val figurinha = pacote.figurinhas.single()
        try {
            val pasta = m.pacotes.pastaDoProjeto(pacote, figurinha.arquivo)
            val trecho = File(pasta, "principal.mp4")
            assertTrue(trecho.isFile)
            assertTrue("trecho bem menor que o vídeo", trecho.length() < video.length())
            naTela { m.mudarQuadrosPorSegundo(10) }
            naTela { m.editar(pacote, figurinha) }
            esperar { m.ocupado == null && m.previa != null && m.editando != null }
            assertTrue(m.animada)
            assertEquals(12, m.quadrosPorSegundo)
            assertEquals(3.0, m.fim - m.inicio, 0.01)
            assertNotNull(m.origem?.copia)
            assertEquals(TipoDaOrigem.VIDEO, m.origem!!.tipo)
            naTela { m.fecharEditor() }
        } finally {
            m.pacotes.ler(pacote.id)?.let { m.pacotes.excluir(it) }
            naTela { m.mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO) }
        }
    }
}
