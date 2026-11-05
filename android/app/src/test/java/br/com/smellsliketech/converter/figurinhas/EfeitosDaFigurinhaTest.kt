package br.com.smellsliketech.converter.figurinhas

import br.com.smellsliketech.converter.estudio.Efeitos
import br.com.smellsliketech.converter.estudio.EstadoDoEfeito
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A matemática dos efeitos da figurinha: o laço, a máquina, os estilhaços, a capa e o projeto. */
class EfeitosDaFigurinhaTest {
    private fun igual(a: EstadoDoEfeito, b: EstadoDoEfeito) {
        assertEquals(a.dx, b.dx, 1e-3); assertEquals(a.dy, b.dy, 1e-3)
        assertEquals(a.sx, b.sx, 1e-3); assertEquals(a.sy, b.sy, 1e-3)
        assertEquals(kotlin.math.sin(a.giro), kotlin.math.sin(b.giro), 1e-3)
        assertEquals(kotlin.math.cos(a.giro), kotlin.math.cos(b.giro), 1e-3)
    }

    @Test fun `sem efeito nada muda e a figura fica parada`() {
        assertFalse(EfeitosDaFigurinha.tem(Edicao()))
        igual(EstadoDoEfeito(), EfeitosDaFigurinha.estado(Edicao(), 1.0, 3.0))
        assertTrue(EfeitosDaFigurinha.tem(Edicao(efeitoSaida = "fade")))
    }

    @Test fun `todo efeito continuo fecha o laco da figurinha, em qualquer duracao`() {
        for ((id, _) in Efeitos.CONTINUOS.filter { it.first != "nenhum" }) for (duracao in listOf(0.7, 2.0, 3.0, 4.3, 10.0)) {
            val e = Edicao(efeitoContinuo = id)
            igual(EfeitosDaFigurinha.estado(e, 0.0, duracao), EfeitosDaFigurinha.estado(e, duracao, duracao))
        }
    }

    @Test fun `o arco-iris da uma volta inteira no matiz`() {
        val e = Edicao(efeitoContinuo = "arcoiris")
        assertEquals(360.0, EfeitosDaFigurinha.estado(e, 3.2, 3.2).matiz, 1e-6)
    }

    @Test fun `a duracao que ja fecha o laco nao muda o ritmo do estudio`() {
        assertEquals(1.5, EfeitosDaFigurinha.tempoEmLaco("girar", 1.5, 4.0), 1e-9)
        assertEquals(1.5, EfeitosDaFigurinha.tempoEmLaco("nenhum", 1.5, 3.3), 1e-9)
    }

    @Test fun `entrada e saida acontecem no comeco e no fim da camada`() {
        val e = Edicao(efeitoEntrada = "fade", duracaoEntrada = 1f, efeitoSaida = "esquerda", duracaoSaida = 1f)
        assertEquals(0.0, EfeitosDaFigurinha.estado(e, 0.0, 3.0).alfa, 1e-6)
        assertEquals(0.5, EfeitosDaFigurinha.estado(e, 0.5, 3.0).alfa, 1e-6)
        igual(EstadoDoEfeito(), EfeitosDaFigurinha.estado(e, 1.5, 3.0))
        assertTrue(EfeitosDaFigurinha.estado(e, 2.9, 3.0).dx < -0.7)
    }

    @Test fun `a maquina de escrever avanca letra a letra e termina com o texto inteiro`() {
        val e = Edicao(efeitoEntrada = "maquina", duracaoEntrada = 1f)
        assertEquals(1, EfeitosDaFigurinha.letrasVisiveis(10, e, 0.0, 3.0, 10))
        assertEquals(6, EfeitosDaFigurinha.letrasVisiveis(10, e, 0.5, 3.0, 10))
        assertNull(EfeitosDaFigurinha.letrasVisiveis(10, e, 2.0, 3.0, 10))
        assertNull(EfeitosDaFigurinha.letrasVisiveis(10, Edicao(efeitoEntrada = "fade"), 0.0, 3.0, 10))
    }

    @Test fun `a matriz de matiz em zero graus nao muda a cor e conserva o cinza em qualquer giro`() {
        val identidade = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertArrayEquals(identidade, EfeitosDaFigurinha.matrizDoMatiz(0.0), 1e-5f)
        val m = EfeitosDaFigurinha.matrizDoMatiz(77.0)
        for (linha in 0..2) assertEquals(1f, m[linha * 5] + m[linha * 5 + 1] + m[linha * 5 + 2], 1e-3f)
    }

    @Test fun `os estilhacos cobrem a caixa da figura e se espalham`() {
        val montados = EfeitosDaFigurinha.estilhacos(100, 200, 400, 380, 0.0)
        assertEquals(36, montados.size)
        assertEquals(300 * 180, montados.sumOf { it.largura * it.altura })
        montados.forEach {
            assertEquals(it.x + it.largura / 2f, it.centroX, 1e-3f)
            assertEquals(it.y + it.altura / 2f, it.centroY, 1e-3f)
            assertEquals(0f, it.giro, 1e-6f)
        }
        val soltos = EfeitosDaFigurinha.estilhacos(100, 200, 400, 380, 1.0)
        assertTrue(soltos.all { kotlin.math.hypot(it.centroX - 250f, it.centroY - 290f) > 200f })
        // Figura estreita: nenhum pedaço de largura zero.
        assertTrue(EfeitosDaFigurinha.estilhacos(10, 10, 13, 300, 0.5).all { it.largura > 0 && it.altura > 0 })
    }

    @Test fun `as particulas somem aos poucos e nao ficam no meio do caminho`() {
        fun quantas(limite: Double) = (0 until 86).sumOf { y -> (0 until 86).count { x -> EfeitosDaFigurinha.particulaVisivel(x, y, 86, limite) } }
        assertEquals(0, quantas(0.0))
        assertEquals(86 * 86, quantas(1.0))
        assertTrue(quantas(0.3) < quantas(0.5) && quantas(0.5) < quantas(0.8))
    }

    @Test fun `a capa pula o quadro vazio da entrada e deixa o resto como esta`() {
        assertEquals(0, WebpAnimado.quadroDeCapa(listOf(100, 100, 100)))
        assertEquals(0, WebpAnimado.quadroDeCapa(listOf(100, 80, 0)))
        assertEquals(0, WebpAnimado.quadroDeCapa(listOf(0, 0, 0)))
        assertEquals(0, WebpAnimado.quadroDeCapa(emptyList()))
        assertEquals(3, WebpAnimado.quadroDeCapa(listOf(0, 30, 60, 90, 100, 100)))
    }

    /** Um WebP mínimo com quadros de tamanhos diferentes, marcados pelo primeiro byte. */
    private fun webp(quantos: Int): ByteArray {
        fun u(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        fun pedaco(nome: String, dados: ByteArray) = nome.toByteArray() + u(dados.size, 4) + dados + if (dados.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        val vp8x = pedaco("VP8X", byteArrayOf(2, 0, 0, 0) + u(511, 3) + u(511, 3))
        val anim = pedaco("ANIM", ByteArray(6))
        val quadros = (0 until quantos).map { i ->
            pedaco("ANMF", u(0, 3) + u(0, 3) + u(511, 3) + u(511, 3) + u(100, 3) + byteArrayOf(2) + ByteArray(1 + i) { (i + 1).toByte() })
        }.reduce { a, b -> a + b }
        val corpo = "WEBP".toByteArray() + vp8x + anim + quadros
        return "RIFF".toByteArray() + u(corpo.size, 4) + corpo
    }

    @Test fun `comecar noutro quadro roda a ordem sem perder nem mudar nenhum`() {
        val original = webp(4)
        assertArrayEquals(original, WebpAnimado.comecarEm(original, 0))
        val rodado = WebpAnimado.comecarEm(original, 2)
        assertEquals(original.size, rodado.size)
        val info = WebpAnimado.ler(rodado)
        assertEquals(4, info.quadros.size)
        assertEquals(listOf(3, 4, 1, 2), info.quadros.map { rodado[it.posicaoDoTempo + 4].toInt() })
        assertEquals(400, info.duracaoMs)
    }

    @Test fun `os efeitos voltam do projeto e projeto antigo ou estranho abre sem efeito`() {
        val e = Edicao(efeitoEntrada = "maquina", duracaoEntrada = 1.5f, efeitoSaida = "desintegrar", duracaoSaida = 2f, efeitoContinuo = "gelatina")
        assertEquals(e, Projetos.edicaoDeJson(Projetos.edicaoParaJson(e)))
        val antiga = Projetos.edicaoParaJson(Edicao()).apply { remove("efeitoEntrada"); remove("duracaoEntrada"); remove("efeitoSaida"); remove("duracaoSaida"); remove("efeitoContinuo") }
        assertEquals(Edicao(), Projetos.edicaoDeJson(antiga))
        val estranha = Projetos.edicaoParaJson(Edicao()).put("efeitoEntrada", "../x").put("duracaoSaida", 99.0)
        val lida = Projetos.edicaoDeJson(estranha)
        assertEquals("nenhum", lida.efeitoEntrada)
        assertEquals(10f, lida.duracaoSaida, 1e-6f)
    }

    @Test fun `o projeto da figurinha com efeitos atravessa o arquivo`() {
        val pasta = Files.createTempDirectory("efeitos").toFile()
        try {
            val texto = CamadaSalva("t", "Texto", null, null, null, Edicao(texto = "oi", efeitoEntrada = "maquina"), texto = true)
            val principal = CamadaSalva("principal", "foto", TipoDaOrigem.IMAGEM, "principal.png", null, Edicao(efeitoContinuo = "pulsar"))
            java.io.File(pasta, "principal.png").writeBytes(byteArrayOf(1))
            Projetos.gravar(pasta, ProjetoSalvo(true, 10, 0.0, 0.0, 0, null, listOf("😀"), principal, listOf(texto)))
            val lido = Projetos.ler(pasta)!!
            assertEquals("pulsar", lido.principal.edicao.efeitoContinuo)
            assertEquals("maquina", lido.camadas.single().edicao.efeitoEntrada)
        } finally {
            pasta.deleteRecursively()
        }
    }
}
