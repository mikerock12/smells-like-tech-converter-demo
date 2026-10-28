package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Do vídeo à figurinha animada pelo mesmo caminho da tela (ViewModel): abrir, salvar e ler o
 * WebP do pacote. Os vídeos ficam em filesDir/videos-de-teste (adb push + run-as cp); sem
 * eles, o teste é pulado.
 */
@RunWith(AndroidJUnit4::class)
class FigurinhaDeVideoTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext

    @Test fun videoViraAnimadaFluida() {
        val pasta = File(contexto.filesDir, "videos-de-teste")
        val videos = pasta.listFiles { f -> f.extension == "mp4" }.orEmpty().sortedBy { it.name }
        assumeTrue("sem vídeos em $pasta", videos.isNotEmpty())
        val falhas = mutableListOf<String>()
        for (video in videos) for (porSegundo in Regras.QUADROS_POR_SEGUNDO) {
            val segundos = 10.0
            lateinit var modelo: FigurinhasViewModel
            instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application); modelo.mudarQuadrosPorSegundo(porSegundo) }
            val comeco = System.currentTimeMillis()
            instrumentacao.runOnMainSync { modelo.abrir(Uri.fromFile(video)) }
            esperar { modelo.ocupado == null && (modelo.previa != null || modelo.aviso != null) }
            assertTrue("não abriu: ${modelo.aviso}", modelo.previa != null)
            val aberto0 = System.currentTimeMillis()
            // A prévia de um instante: a primeira converte o trecho; a segunda já sai da versão leve.
            val instantes = listOf(1.5f, 2.5f).map { t ->
                val antes = modelo.previa
                val inicioDoInstante = System.currentTimeMillis()
                instrumentacao.runOnMainSync { modelo.inicio = 0.0; modelo.fim = segundos; modelo.mostrarInstante(t) }
                esperar { modelo.previa !== antes && modelo.ocupado == null }
                System.currentTimeMillis() - inicioDoInstante
            }
            val aberto = System.currentTimeMillis()
            instrumentacao.runOnMainSync { modelo.inicio = 0.0; modelo.fim = segundos; modelo.salvar(null, "Teste ${video.nameWithoutExtension}") }
            Thread.sleep(200)
            esperar { modelo.ocupado == null }
            val pronto = System.currentTimeMillis()
            val pacote = modelo.pacoteAberto
            assertTrue("sem pacote: ${modelo.aviso}", pacote != null)
            val webp = Pacotes(contexto).arquivo(pacote!!, pacote.figurinhas.last().arquivo).readBytes()
            val info = WebpAnimado.ler(webp)
            val duracoes = info.quadros.map { it.duracaoMs }
            Log.i("FigurinhaDeVideo", "${video.name} ${porSegundo}/s: abrir ${aberto0 - comeco} ms, instantes $instantes ms, gerar ${pronto - aberto} ms, " +
                "${webp.size / 1024} KB, ${info.quadros.size} quadros, ${info.duracaoMs} ms, duracoes=${duracoes.distinct()}")
            Pacotes(contexto).excluir(pacote)
            // Mesma fluidez sempre: todo quadro dura 1/porSegundo (o normalizador alterna 66/67 ms)
            // e nenhum é pulado. Só quando o próprio vídeo repete um quadro (a câmera perdeu um) o
            // libwebp junta os dois iguais num só, com o dobro do tempo: a imagem é a mesma.
            val esperado = 1000.0 / porSegundo
            val foraDoRitmo = duracoes.filter { d -> kotlin.math.abs(d - esperado * kotlin.math.round(d / esperado)) > 1.0 || d < esperado - 1.0 }
            val repetidos = duracoes.count { it > esperado + 1.0 }
            if (foraDoRitmo.isNotEmpty() || repetidos > duracoes.size / 20) falhas += "${video.name} ${porSegundo}/s: duracoes=${duracoes.distinct()}"
            if (webp.size > Regras.ANIMADA_MAX_BYTES) falhas += "${video.name} ${porSegundo}/s: ${webp.size} bytes"
        }
        assertTrue(falhas.joinToString("; "), falhas.isEmpty())
        // A escolha fica guardada; volta ao padrão para não afetar o uso normal.
        instrumentacao.runOnMainSync { FigurinhasViewModel(contexto.applicationContext as Application).mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO) }
    }

    /**
     * Trecho menor que 10 s: a composição acompanha o tempo de vídeo disponível. Antes, uma
     * duração de 10 s escolhida antes continuava valendo e a figurinha terminava com quadros
     * vazios depois do vídeo (relato do dono com o trecho 12,091–15,061 s).
     */
    @Test fun duracaoAcompanhaOTrechoDisponivel() {
        val video = File(contexto.filesDir, "videos-de-teste").listFiles { f -> f.extension == "mp4" }.orEmpty().minByOrNull { it.length() }
        assumeTrue("sem vídeos de teste", video != null)
        lateinit var modelo: FigurinhasViewModel
        instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application); modelo.mudarQuadrosPorSegundo(10) }
        instrumentacao.runOnMainSync { modelo.abrir(Uri.fromFile(video)) }
        esperar { modelo.ocupado == null && (modelo.previa != null || modelo.aviso != null) }
        instrumentacao.runOnMainSync {
            modelo.mudarDuracao(10f)
            modelo.mudarTrecho(1.091, 4.061)
        }
        var duracao = 0.0
        var disponivel = 0.0
        instrumentacao.runOnMainSync { duracao = modelo.duracaoDaComposicao; disponivel = modelo.duracaoDisponivel }
        org.junit.Assert.assertEquals(2.97, disponivel, 0.001)
        org.junit.Assert.assertEquals("a composição não passa do vídeo", 2.97, duracao, 0.001)
        // Pedir mais que o disponível também fica no disponível.
        instrumentacao.runOnMainSync { modelo.mudarDuracao(10f); duracao = modelo.duracaoDaComposicao }
        org.junit.Assert.assertEquals(2.97, duracao, 0.001)
        instrumentacao.runOnMainSync { modelo.salvar(null, "Duração disponível") }
        Thread.sleep(200)
        esperar { modelo.ocupado == null && modelo.origem == null }
        val pacote = modelo.pacoteAberto!!
        try {
            val info = WebpAnimado.ler(Pacotes(contexto).arquivo(pacote, pacote.figurinhas.single().arquivo).readBytes())
            assertTrue("a figurinha tem ${info.duracaoMs} ms; o trecho tem 2.970 ms", info.duracaoMs in 2_800..3_100)
        } finally {
            Pacotes(contexto).excluir(pacote)
            instrumentacao.runOnMainSync { modelo.mudarQuadrosPorSegundo(Regras.QUADROS_POR_SEGUNDO_PADRAO) }
        }
    }

    /**
     * Pinçar e arrastar mudam a edição a cada ~16 ms. A prévia tem de acompanhar durante o
     * gesto, não só quando o dedo solta (cancelar e esperar a cada evento deixava a imagem
     * parada até o fim do movimento).
     */
    @Test fun previaAcompanhaOGesto() {
        val foto = File(contexto.cacheDir, "gesto.png")
        android.graphics.Bitmap.createBitmap(1600, 1200, android.graphics.Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).drawCircle(800f, 600f, 500f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
            foto.outputStream().use { compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        lateinit var modelo: FigurinhasViewModel
        instrumentacao.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
        instrumentacao.runOnMainSync { modelo.abrir(Uri.fromFile(foto)) }
        esperar { modelo.ocupado == null && (modelo.previa != null || modelo.aviso != null) }
        assertTrue("não abriu: ${modelo.aviso}", modelo.previa != null)
        val vistas = mutableSetOf<Any>()
        repeat(40) { passo ->
            instrumentacao.runOnMainSync {
                modelo.mudar(modelo.edicao.copy(zoom = 1f + passo * 0.05f, deslocamentoX = passo * 0.005f))
                modelo.previa?.let { vistas += it }
            }
            Thread.sleep(16)
        }
        foto.delete()
        assertTrue("só ${vistas.size} prévias durante o gesto", vistas.size >= 5)
    }

    private fun esperar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < limite) {
            var ok = false
            instrumentacao.runOnMainSync { ok = condicao() }
            if (ok) return
            Thread.sleep(250)
        }
        error("tempo esgotado")
    }
}
