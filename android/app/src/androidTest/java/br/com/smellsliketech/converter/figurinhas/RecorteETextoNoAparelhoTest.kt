package br.com.smellsliketech.converter.figurinhas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O recorte (quadrado, círculo e livre) e o texto como objeto, desenhados de verdade: o que
 * fica fora do recorte some, o pedaço recortado ocupa o lugar da mídia inteira, e o texto
 * vertical, em escada e movido acompanha a edição.
 */
@RunWith(AndroidJUnit4::class)
class RecorteETextoNoAparelhoTest {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderizador = Renderizador(contexto)
    private val compositor = CompositorDeFigurinhas(contexto, renderizador)

    /** Quatro quadrantes: vermelho, verde (em cima), azul, amarelo (embaixo). */
    private fun quadrantes(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.RED })
            drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.GREEN })
            drawRect(0f, 200f, 200f, 400f, Paint().apply { color = Color.BLUE })
            drawRect(200f, 200f, 400f, 400f, Paint().apply { color = Color.YELLOW })
        }
    }

    private fun alfa(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y))

    @Test fun recorteQuadradoOcupaOLugarDaMidiaInteira() {
        // Só o quadrante verde: ele passa a caber na figurinha inteira.
        val edicao = Edicao(recorte = Recorte(FormaDoRecorte.QUADRADO, listOf(0.5f, 0f, 1f, 0.5f)))
        val figurinha = renderizador.desenhar(quadrantes(), null, edicao)
        assertEquals(Color.GREEN, figurinha.getPixel(30, 30))
        assertEquals(Color.GREEN, figurinha.getPixel(480, 480))
        assertEquals(Color.GREEN, figurinha.getPixel(256, 256))
    }

    @Test fun recorteEmCirculoDeixaOsCantosTransparentes() {
        val edicao = Edicao(recorte = Recorte(FormaDoRecorte.CIRCULO, listOf(0f, 0f, 1f, 1f)))
        val figurinha = renderizador.desenhar(quadrantes(), null, edicao)
        assertEquals(0, alfa(figurinha, 4, 4))
        assertEquals(0, alfa(figurinha, 507, 507))
        assertEquals(Color.RED, figurinha.getPixel(150, 150))
        assertEquals(Color.YELLOW, figurinha.getPixel(362, 362))
    }

    @Test fun recorteLivreSoMantemOTriangulo() {
        // Triângulo com a ponta em cima e a base embaixo, na mídia inteira.
        val edicao = Edicao(recorte = Recorte(FormaDoRecorte.LIVRE, listOf(0.5f, 0f, 1f, 1f, 0f, 1f)))
        val figurinha = renderizador.desenhar(quadrantes(), null, edicao)
        assertEquals(0, alfa(figurinha, 20, 20))
        assertEquals(0, alfa(figurinha, 492, 20))
        assertEquals(Color.BLUE, figurinha.getPixel(200, 480))
    }

    @Test fun pincelAcompanhaOPedacoRecortado() {
        // Recorte do quadrante amarelo: o meio da figurinha é o meio desse quadrante (75%, 75%).
        val edicao = Edicao(recorte = Recorte(FormaDoRecorte.QUADRADO, listOf(0.5f, 0.5f, 1f, 1f)))
        val (x, y, _) = renderizador.pontoNaImagem(0.5f, 0.5f, 0.01f, 400, 400, edicao)
        assertEquals(0.75f, x, 0.01f)
        assertEquals(0.75f, y, 0.01f)
    }

    @Test fun textoVerticalEEmEscadaTemOFormatoCerto() {
        val base = Edicao(texto = "ABCDE", tamanhoDoTexto = 64f)
        val horizontal = renderizador.texturaDoTexto(base)
        val vertical = renderizador.texturaDoTexto(base.copy(disposicaoDoTexto = DisposicaoDoTexto.VERTICAL))
        val subindo = renderizador.texturaDoTexto(base.copy(disposicaoDoTexto = DisposicaoDoTexto.SUBINDO))
        // Uma linha só: mais larga que alta, e a altura de uma linha da fonte (letra × 2 de resolução).
        assertTrue("horizontal ${horizontal.width}×${horizontal.height}", horizontal.width > horizontal.height && horizontal.height < 64 * RESOLUCAO_DO_TEXTO * 2.4f)
        assertTrue("vertical ${vertical.width}×${vertical.height}", vertical.height > 3 * vertical.width)
        // Na escada, a primeira letra fica embaixo à esquerda e a última em cima à direita.
        fun tinta(b: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int) =
            (x0 until x1 step 2).any { x -> (y0 until y1 step 2).any { y -> alfa(b, x, y) > 0 } }
        val w = subindo.width; val h = subindo.height
        assertTrue("A embaixo à esquerda", tinta(subindo, 0, w / 5, h / 2, h))
        assertTrue("E em cima à direita", tinta(subindo, w * 4 / 5, w, 0, h / 2))
        assertTrue("nada em cima à esquerda", !tinta(subindo, 0, w / 6, 0, h / 4))
    }

    @Test fun textoSeMoveEAproximaComoObjeto() {
        val texto = CamadaDeFigurinha(nome = "Texto", imagem = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), texto = true,
            edicao = Edicao(texto = "OI", tamanhoDoTexto = 64f, corDoTexto = Color.WHITE, corDoContornoDoTexto = Color.TRANSPARENT))
        fun linhas(b: Bitmap): IntRange {
            val ys = (0 until b.height).filter { y -> (0 until b.width step 4).any { x -> alfa(b, x, y) > 0 } }
            return ys.first()..ys.last()
        }
        val parado = linhas(compositor.desenhar(listOf(texto)))
        // No meio, no tamanho natural: cerca de 64 px de letra em volta da linha do meio.
        assertTrue("$parado", 256 in parado && parado.last - parado.first in 40..110)
        val descido = linhas(compositor.desenhar(listOf(texto.copy(edicao = texto.edicao.copy(deslocamentoY = 0.3f)))))
        assertTrue("desceu ${descido.first - parado.first} px", kotlin.math.abs(descido.first - parado.first - 154) <= 3)
        val maior = linhas(compositor.desenhar(listOf(texto.copy(edicao = texto.edicao.copy(zoom = 2f)))))
        assertEquals(2.0, (maior.last - maior.first).toDouble() / (parado.last - parado.first), 0.15)
    }
}
