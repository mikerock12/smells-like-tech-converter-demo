package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.estudio.Clipe
import br.com.smellsliketech.converter.estudio.Exportador
import br.com.smellsliketech.converter.estudio.Projeto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** As repetições gravadas de verdade no arquivo, e a máscara real do ML Kit quando o aparelho tem. */
@RunWith(AndroidJUnit4::class)
class RepeticoesNoAparelhoTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private val app = contexto.applicationContext as Application
    private fun tela(bloco: () -> Unit) = i.runOnMainSync(bloco)
    private fun esperar(condicao: () -> Boolean) {
        val limite = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < limite) {
            var ok = false
            tela { ok = condicao() }
            if (ok) return
            Thread.sleep(150)
        }
        error("tempo esgotado")
    }

    private fun foto(nome: String): File = File(contexto.cacheDir, nome).apply {
        Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.rgb(30, 90, 200)); drawCircle(160f, 160f, 100f, Paint().apply { color = Color.YELLOW }) }
            outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }

    private fun video(nome: String): File = runBlocking {
        val arquivo = File(contexto.cacheDir, nome)
        val png = foto("$nome.png")
        Ffmpeg.executar(contexto, listOf("-v", "error", "-y", "-loop", "1", "-i", png.absolutePath, "-t", "1", "-r", "10",
            "-c:v", "mpeg4", "-pix_fmt", "yuv420p", arquivo.absolutePath), null)
        arquivo
    }

    /** O número do bloco NETSCAPE do GIF: quantas vezes repete depois da primeira. */
    private fun loopDoGif(gif: ByteArray): Int {
        val marca = "NETSCAPE2.0".toByteArray()
        val posicao = (0..gif.size - marca.size).first { p -> marca.indices.all { gif[p + it] == marca[it] } }
        return (gif[posicao + marca.size + 2].toInt() and 255) or ((gif[posicao + marca.size + 3].toInt() and 255) shl 8)
    }

    @Test fun figurinhaGravaAsRepeticoesEVoltaParaEditar() {
        lateinit var m: FigurinhasViewModel
        tela { m = FigurinhasViewModel(app); m.abrir(Uri.fromFile(foto("repete.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "pulsar")); m.pararEfeito(); m.mudarRepeticoes(3) }
        esperar { !m.tocandoEfeito }
        tela { m.salvar(null, "Repeticoes") }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        val figurinha = pacote.figurinhas.single()
        try {
            val arquivo = m.pacotes.arquivo(pacote, figurinha.arquivo)
            assertEquals(3, WebpAnimado.ler(arquivo.readBytes()).repeticoes)
            // O decodificador do Android leu o mesmo número: 3 toques são 2 repetições.
            val desenho = ImageDecoder.decodeDrawable(ImageDecoder.createSource(arquivo))
            assertEquals(2, (desenho as AnimatedImageDrawable).repeatCount)
            tela { m.editar(pacote, figurinha) }
            esperar { m.ocupado == null && m.previa != null && m.editando != null }
            assertEquals(3, m.repeticoes)
            tela { m.fecharEditor() }
        } finally {
            tela { m.excluir(pacote) }
        }
    }

    @Test fun figurinhaSemEscolhaContinuaSemFim() {
        lateinit var m: FigurinhasViewModel
        tela { m = FigurinhasViewModel(app); m.abrir(Uri.fromFile(foto("sem-fim.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "tremer")); m.pararEfeito() }
        esperar { !m.tocandoEfeito }
        tela { m.salvar(null, "SemFim") }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        try {
            val arquivo = m.pacotes.arquivo(pacote, pacote.figurinhas.single().arquivo)
            assertEquals(0, WebpAnimado.ler(arquivo.readBytes()).repeticoes)
            val desenho = ImageDecoder.decodeDrawable(ImageDecoder.createSource(arquivo))
            assertEquals(AnimatedImageDrawable.REPEAT_INFINITE, (desenho as AnimatedImageDrawable).repeatCount)
        } finally {
            tela { m.excluir(pacote) }
        }
    }

    @Test fun conversorGravaAsRepeticoesNoGif() = runBlocking<Unit> {
        val origem = video("repete-conversor.mp4")
        val saida = File(contexto.cacheDir, "repete-conversor.gif")
        Ffmpeg.executar(contexto, Comandos.gif(origem.absolutePath, saida.absolutePath, Opcoes(formato = "gif", vezesDoGif = 5, larguraDoGif = 240, fpsDoGif = 8, fimSegundos = 1.0)), null)
        assertEquals(4, loopDoGif(saida.readBytes()))
        saida.delete(); origem.delete()
    }

    @Test fun estudioGravaAsRepeticoesNoGif() = runBlocking<Unit> {
        val origem = video("repete-estudio.mp4")
        val saida = File(contexto.cacheDir, "repete-estudio.gif")
        val projeto = Projeto(largura = 240, altura = 240, fps = 10, saida = "gif", repeticoes = 10,
            clipes = listOf(Clipe("c1", origem.absolutePath, "video", inicio = 0.0, fim = 1.0, ajuste = "caber", volume = 1.0)))
        Exportador.gerar(contexto, projeto, saida) {}
        assertEquals(9, loopDoGif(saida.readBytes()))
        saida.delete(); origem.delete()
    }

    /** Só onde o Google Play Services entrega o modelo; no AVD sem ele, o teste fica de fora. */
    @Test fun mascaraRealDoAparelhoNaoDeixaNevoaNoCanto() = runBlocking<Unit> {
        val imagem = android.graphics.BitmapFactory.decodeFile(foto("mascara.png").absolutePath)
        val mascara = try { Fundo.mascara(imagem) } catch (erro: Fundo.Indisponivel) { assumeNoException(erro); return@runBlocking }
        assertEquals(0, Color.alpha(mascara.getPixel(4, 4)))
        assertTrue(mascara.width == imagem.width)
    }
}
