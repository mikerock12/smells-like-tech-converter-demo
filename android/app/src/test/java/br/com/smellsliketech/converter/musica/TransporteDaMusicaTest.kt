package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os mesmos números de tests/musica-transporte.test.mjs. */
class TransporteDaMusicaTest {
    private fun projeto() = Musica.novoProjeto("t", "T", 48000).copy(
        faixas = listOf(FaixaMusical("f", "F", clipes = listOf(ClipeMusical("c", "m", 48000L * 10, duracao = 48000L * 50)))),
    )

    @Test
    fun saltosFicamDentroDaMusica() {
        val p = projeto()
        assertEquals(0L, Musica.posicaoDoTransporte(p, 48000L * 30, "inicio"))
        assertEquals(48000L * 15, Musica.posicaoDoTransporte(p, 48000L * 30, "voltar"))
        assertEquals(0L, Musica.posicaoDoTransporte(p, 48000L * 10, "voltar"))
        assertEquals(48000L * 45, Musica.posicaoDoTransporte(p, 48000L * 30, "avancar"))
        assertEquals(48000L * 60, Musica.posicaoDoTransporte(p, 48000L * 50, "avancar"))
        assertEquals(48000L * 45, Musica.posicaoDoTransporte(p, 0, "final"))
        assertEquals(48000L * 30, Musica.posicaoDoTransporte(p, 0, "barra", 0.5))
        assertEquals(48000L * 60, Musica.posicaoDoTransporte(p, 0, "barra", 2.0))
        assertEquals(0L, Musica.posicaoDoTransporte(Musica.novoProjeto("v"), 999, "avancar"))
        assertThrows(IllegalArgumentException::class.java) { Musica.posicaoDoTransporte(p, 0, "pular") }
    }

    @Test
    fun paraNoFimSalvoEmLoopOuGravando() {
        val p = projeto()
        assertFalse(Musica.chegouAoFim(p, 48000L * 60 - 1))
        assertTrue(Musica.chegouAoFim(p, 48000L * 60))
        assertFalse(Musica.chegouAoFim(p, 48000L * 60, gravando = true))
        assertFalse(Musica.chegouAoFim(p.copy(loop = Loop(true, 0, 48000)), 48000L * 70))
        assertFalse(Musica.chegouAoFim(Musica.novoProjeto("v"), 100))
    }
}
