package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.estudio.Efeitos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Os efeitos nos pixels de verdade e na figurinha gerada. Sem Compose: roda também no APK com R8. */
@RunWith(AndroidJUnit4::class)
class EfeitosDaFigurinhaNoAparelhoTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private val compositor = CompositorDeFigurinhas(contexto)

    private fun quadrado(cor: Int = Color.RED, lado: Int = 200) = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888).apply { eraseColor(cor) }

    /** Uma camada que ocupa metade do lado, no centro. */
    private fun camada(e: Edicao) = CamadaDeFigurinha(nome = "c", imagem = quadrado(), edicao = e.copy(zoom = .5f))
    private fun quadro(c: CamadaDeFigurinha, tempo: Double, duracao: Double = 2.0, fim: Map<String, Double> = emptyMap()) =
        compositor.desenhar(listOf(c), instante = InstanteDosEfeitos(tempo, duracao, 10, fim))
    private fun tinta(b: Bitmap): Long {
        val pontos = IntArray(b.width * b.height)
        b.getPixels(pontos, 0, b.width, 0, 0, b.width, b.height)
        return pontos.sumOf { (it ushr 24).toLong() }
    }
    /** Esquerda, topo, direita e base do que tem tinta. */
    private fun caixa(b: Bitmap): IntArray {
        var l = b.width; var t = b.height; var r = -1; var f = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (Color.alpha(b.getPixel(x, y)) > 8) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); f = maxOf(f, y)
        }
        return intArrayOf(l, t, r, f)
    }

    @Test fun semInstanteAFiguraFicaParadaEDepoisDaEntradaVoltaAoLugar() {
        val parado = compositor.desenhar(listOf(camada(Edicao())))
        val efeito = camada(Edicao(efeitoEntrada = "cima", efeitoSaida = "direita", efeitoContinuo = "nenhum"))
        assertEquals(tinta(parado), tinta(compositor.desenhar(listOf(efeito))))
        assertTrue(caixa(parado).contentEquals(caixa(compositor.desenhar(listOf(efeito)))))
        assertTrue(caixa(parado).contentEquals(caixa(quadro(efeito, 1.0))))
    }

    @Test fun cadaEfeitoMexeNaFiguraEOTerminoDaSaidaDeixaOQuadroLimpo() {
        val parado = compositor.desenhar(listOf(camada(Edicao())))
        for ((id, _) in Efeitos.ENTRADAS.filter { it.first !in listOf("nenhum", "maquina") }) {
            // Em 0,3 s: no quique, 0,2 s é justamente o instante em que a figura toca o chão.
            val b = quadro(camada(Edicao(efeitoEntrada = id, duracaoEntrada = 1f)), .3)
            assertTrue("entrada $id", !caixa(b).contentEquals(caixa(parado)) || tinta(b) != tinta(parado))
        }
        for ((id, _) in Efeitos.SAIDAS.filter { it.first != "nenhum" }) {
            val b = quadro(camada(Edicao(efeitoSaida = id, duracaoSaida = 1f)), 1.7)
            assertTrue("saída $id", !caixa(b).contentEquals(caixa(parado)) || tinta(b) != tinta(parado))
        }
        for ((id, _) in Efeitos.CONTINUOS.filter { it.first != "nenhum" }) {
            val b = quadro(camada(Edicao(efeitoContinuo = id)), .23)
            val mudou = !caixa(b).contentEquals(caixa(parado)) || tinta(b) != tinta(parado) || b.getPixel(256, 256) != parado.getPixel(256, 256)
            assertTrue("contínuo $id", mudou)
        }
    }

    @Test fun surgirDeCimaVemDeForaDoQuadro() {
        val c = camada(Edicao(efeitoEntrada = "cima", duracaoEntrada = 1f))
        assertEquals("sai do quadro no começo", 0L, tinta(quadro(c, 0.0)))
        val meio = caixa(quadro(c, .1))
        val fim = caixa(quadro(c, .9))
        assertTrue("desce", meio[1] < fim[1])
    }

    @Test fun estilhacosEspalhamAFiguraEParticulasFuramSemSairDoLugar() {
        val parado = caixa(compositor.desenhar(listOf(camada(Edicao()))))
        val pedacos = caixa(quadro(camada(Edicao(efeitoEntrada = "estilhacos", duracaoEntrada = 1f)), .2))
        assertTrue("os pedaços passam da caixa", pedacos[0] < parado[0] && pedacos[2] > parado[2])
        val furada = quadro(camada(Edicao(efeitoEntrada = "desintegrar", duracaoEntrada = 1f)), .15)
        val inteira = compositor.desenhar(listOf(camada(Edicao())))
        assertTrue("some parte da figura", tinta(furada) in 1 until tinta(inteira))
        val c = caixa(furada)
        assertTrue("nada fora da figura", c[0] >= parado[0] && c[2] <= parado[2])
    }

    @Test fun giroNaoCortaACamadaNaBordaDoQuadro() {
        // Lado inteiro no quadro: girada, a quina passa da borda; o que importa é não sumir nem encolher.
        val c = CamadaDeFigurinha(nome = "c", imagem = quadrado(), edicao = Edicao(zoom = .9f, efeitoContinuo = "girar"))
        val b = compositor.desenhar(listOf(c), instante = InstanteDosEfeitos(.25, 2.0, 10))
        assertEquals("a quina chega à borda", 0, caixa(b)[0])
        assertEquals(255, Color.alpha(b.getPixel(256, 20)))
    }

    @Test fun arcoIrisTrocaACorSemMexerNaTransparencia() {
        val c = camada(Edicao(efeitoContinuo = "arcoiris"))
        val b = quadro(c, 1.0, duracao = 3.0)
        assertNotEquals(Color.RED, b.getPixel(256, 256))
        assertEquals(255, Color.alpha(b.getPixel(256, 256)))
        assertEquals(0, Color.alpha(b.getPixel(10, 10)))
    }

    @Test fun maquinaDeEscreverMostraAsLetrasNoLugarDefinitivo() {
        for (disposicao in DisposicaoDoTexto.entries) {
            // Letra de 40: na vertical, nove letras de 64 passariam da figurinha.
            val e = Edicao(texto = "OLÁ MUNDO", tamanhoDoTexto = 40f, disposicaoDoTexto = disposicao, efeitoEntrada = "maquina", duracaoEntrada = 1f, zoom = 1f)
            val c = CamadaDeFigurinha(nome = "t", imagem = quadrado(lado = 1), edicao = e, texto = true)
            fun instante(t: Double) = compositor.desenhar(listOf(c), instante = InstanteDosEfeitos(t, 2.0, 10))
            val comeco = instante(0.0)
            val meio = instante(.5)
            val inteiro = compositor.desenhar(listOf(c))
            assertTrue("$disposicao cresce", tinta(comeco) in 1 until tinta(meio) && tinta(meio) < tinta(inteiro))
            assertEquals("$disposicao termina inteiro", tinta(inteiro), tinta(instante(1.5)))
            // A primeira letra não dança: o que já apareceu continua igual até o fim.
            val a = caixa(comeco)
            val letra = Rect(a[0], a[1], a[2], a[3])
            var iguais = 0; var total = 0
            for (y in letra.top..letra.bottom step 3) for (x in letra.left..letra.right step 3) if (Color.alpha(comeco.getPixel(x, y)) > 200) {
                total++
                if (Color.alpha(inteiro.getPixel(x, y)) > 200) iguais++
            }
            assertTrue("$disposicao: $iguais de $total", total > 0 && iguais * 10 >= total * 9)
        }
    }

    @Test fun aSaidaDoVideoTerminaComOTrechoDaCamada() {
        val c = camada(Edicao(efeitoSaida = "fade", duracaoSaida = 1f))
        val fim = mapOf(c.id to 2.0)
        // Composição de 5 s, vídeo de 2 s: em 1,9 s ele já quase sumiu.
        val curto = quadro(c, 1.9, duracao = 5.0, fim = fim)
        val inteiro = quadro(c, 1.9, duracao = 5.0)
        assertTrue(tinta(curto) * 5 < tinta(inteiro))
    }

    // ---- A figurinha gerada ----

    private fun modelo(): FigurinhasViewModel {
        lateinit var modelo: FigurinhasViewModel
        i.runOnMainSync { modelo = FigurinhasViewModel(contexto.applicationContext as Application) }
        return modelo
    }
    private fun foto(nome: String): File = File(contexto.cacheDir, nome).apply {
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { drawColor(Color.rgb(30, 90, 200)); drawCircle(320f, 320f, 200f, Paint().apply { color = Color.YELLOW }) }
            outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
    }
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

    @Test fun imagemComEfeitoViraFigurinhaAnimadaComCapaEVoltaParaEditar() {
        val m = modelo()
        tela { m.abrir(Uri.fromFile(foto("efeito.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        assertFalse("imagem nasce estática", m.temAnimacao)
        tela {
            m.mudarEfeito(m.edicao.copy(efeitoEntrada = "fade", duracaoEntrada = 1f, efeitoContinuo = "pulsar"))
        }
        assertTrue(m.animada); assertTrue(m.temAnimacao)
        assertEquals(EfeitosDaFigurinha.DURACAO_SEM_MIDIA, m.duracaoDaComposicao, 1e-9)
        // A prévia toca sozinha e muda de quadro.
        esperar { m.tocandoEfeito && m.tempoDaPrevia > 0f }
        tela { m.pararEfeito() }
        esperar { !m.tocandoEfeito }
        tela { m.salvar(null, "Efeitos") }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        val figurinha = pacote.figurinhas.single()
        try {
            assertTrue("pacote de animadas", pacote.animado)
            val arquivo = m.pacotes.arquivo(pacote, figurinha.arquivo)
            val info = WebpAnimado.ler(arquivo.readBytes())
            assertEquals(30, info.quadros.size)
            assertEquals(3000L, info.duracaoMs)
            // A capa: mesmo com fade in, o primeiro quadro e o ícone da bandeja mostram a figura.
            val primeiro = BitmapFactory.decodeFile(arquivo.absolutePath)
            assertTrue("capa com figura", Color.alpha(primeiro.getPixel(256, 256)) > 200)
            m.pacotes.gerarBandeja(pacote)
            val bandeja = BitmapFactory.decodeFile(m.pacotes.arquivo(pacote, Pacotes.BANDEJA).absolutePath)
            assertTrue("bandeja com figura", Color.alpha(bandeja.getPixel(48, 48)) > 200)

            tela { m.editar(pacote, figurinha) }
            esperar { m.ocupado == null && m.previa != null && m.editando != null }
            assertEquals("fade", m.edicao.efeitoEntrada)
            assertEquals("pulsar", m.edicao.efeitoContinuo)
            assertTrue(m.animada)
            // Sem efeitos, a reaberta continua animada: é o tipo do pacote.
            tela { m.mudarEfeito(m.edicao.copy(efeitoEntrada = "nenhum", efeitoContinuo = "nenhum")) }
            assertTrue(m.animada && m.temAnimacao)
            tela { m.fecharEditor() }
        } finally {
            tela { m.excluir(pacote) }
        }
    }

    @Test fun estaticaReabertaNaoGanhaEfeitoETirarOEfeitoVoltaAEstatica() {
        val m = modelo()
        tela { m.abrir(Uri.fromFile(foto("sem-efeito.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "tremer")) }
        assertTrue(m.animada)
        tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "nenhum")) }
        assertFalse(m.animada); assertFalse(m.temAnimacao)
        esperar { !m.tocandoEfeito }
        tela { m.salvar(null, "Estaticas") }
        Thread.sleep(200)
        esperar { m.ocupado == null && m.origem == null }
        val pacote = m.pacoteAberto!!
        try {
            assertFalse(pacote.animado)
            tela { m.editar(pacote, pacote.figurinhas.single()) }
            esperar { m.ocupado == null && m.previa != null && m.editando != null }
            assertFalse(m.podeUsarEfeitos)
            tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "tremer")) }
            assertEquals("nenhum", m.edicao.efeitoContinuo)
            tela { m.fecharEditor() }
        } finally {
            tela { m.excluir(pacote) }
        }
    }

    @Test fun duracaoDaImagemComEfeitoChegaAosDezSegundos() {
        val m = modelo()
        tela { m.abrir(Uri.fromFile(foto("dez.png"))) }
        esperar { m.ocupado == null && m.previa != null }
        tela { m.mudarEfeito(m.edicao.copy(efeitoContinuo = "flutuar")); m.pararEfeito(); m.mudarDuracao(10f) }
        assertEquals(10.0, m.duracaoDaComposicao, 1e-9)
        tela { m.mudarDuracao(3f) }
        assertEquals(3.0, m.duracaoDaComposicao, 1e-9)
        tela { m.fecharEditor() }
    }
}
