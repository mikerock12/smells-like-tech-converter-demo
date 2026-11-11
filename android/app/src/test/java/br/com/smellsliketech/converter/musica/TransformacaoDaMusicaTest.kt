package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class TransformacaoDaMusicaTest {
    private val clipe = ClipeMusical("c", "m", 0, 0, 48000)
    private val midia = MidiaMusical("m", "Seno", "wav", 48000, 1, 48000)

    @Test
    fun volumeEPanTemRotulosDeZeroACem() {
        assertEquals(100, Musica.volumeParaPercentual(0.0))
        assertEquals(50, Musica.volumeParaPercentual(-6.0))
        assertEquals(0.0, Musica.percentualParaVolume(100.0), 1e-9)
        assertEquals(-60.0, Musica.percentualParaVolume(0.0), 1e-9)
        assertEquals("0", Musica.textoDoPan(0.0))
        assertEquals("+1D −1L", Musica.textoDoPan(1.0))
        assertEquals("+100L −100D", Musica.textoDoPan(-100.0))
    }

    @Test
    fun velocidadeMantemTrechoEAjustaDuracao() {
        val rapido = TransformacaoDaMusica.comVelocidade(clipe, midia, 2.0)
        assertEquals(24000, rapido.duracao)
        val lento = TransformacaoDaMusica.comVelocidade(rapido, midia, 0.5)
        assertEquals(96000, lento.duracao)
        assertEquals(1.2, TransformacaoDaMusica.velocidadeParaIgualar(120.0, 100.0), 1e-9)
        assertNotEquals("m", TransformacaoDaMusica.chaveDaFonte(rapido))
    }

    @Test
    fun transformacaoPreservaDuracaoNosDoisModos() {
        val entrada = FloatArray(48000) { (sin(2 * Math.PI * 440 * it / 48000) * 0.5).toFloat() }
        for (preservar in listOf(true, false)) {
            val saida = TransformacaoDaMusica.transformar(arrayOf(entrada), 48000, 2.0, 2.0, preservar)
            assertTrue(abs(saida[0].size - 24000) <= 2)
            assertTrue(saida[0].any { abs(it) > 0.1 })
        }
    }

    @Test
    fun analiseReconhece120BpmEDoMaior() {
        val taxa = 48000
        val entrada = FloatArray(taxa * 8)
        var tempo = 0.0
        while (tempo < 8) {
            val inicio = Musica.arredondar(tempo * taxa).toInt()
            for (i in 0 until 1200) entrada[inicio + i] += (0.8 * exp(-i / 180.0)).toFloat()
            tempo += 0.5
        }
        for (i in entrada.indices) for (f in listOf(261.6256, 329.6276, 391.9954)) entrada[i] += (0.08 * sin(2 * Math.PI * f * i / taxa)).toFloat()
        val r = AnaliseDaMusica.analisar(arrayOf(entrada), taxa)
        assertEquals(120, r.bpm)
        assertEquals("C maior", r.tonalidade)
    }

    @Test
    fun mixerUsaFontePreparadaEProjetoGuardaTransformacao() {
        val projeto = Musica.novoProjeto("p", "P", 48000)
        val c = TransformacaoDaMusica.comVelocidade(clipe, midia, 2.0)
        val p = projeto.copy(midias = listOf(midia), faixas = listOf(Musica.novaFaixa("f", "F", 0).copy(clipes = listOf(c))))
        val fontes = mapOf(
            "m" to FonteNaMemoria(arrayOf(FloatArray(48000) { 0.25f })),
            TransformacaoDaMusica.chaveDaFonte(c) to FonteNaMemoria(arrayOf(FloatArray(24000) { 0.5f })),
        )
        val saida = MisturadorDaMusica.renderizar(p, fontes, 0, 24000)
        assertEquals(0.5f, saida.first[100])
        assertTrue(Musica.problemas(p).isEmpty())
        val lido = ProjetoMusical.deTexto(p.paraJson().toString())
        assertEquals(2.0, lido.faixas[0].clipes[0].velocidade!!, 1e-9)
    }
}
