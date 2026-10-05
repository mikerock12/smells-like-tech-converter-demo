package br.com.smellsliketech.converter.conversao

import org.junit.Assert.*
import org.junit.Test

class ProgressoDaNarracaoTest {
    @Test fun `OCR rapido nao participa do ritmo da voz`() {
        var agora = 90_000L // Leitura/OCR e carregamento já passaram.
        val estado = ProgressoDaNarracao(List(30) { 200 }) { agora }
        assertNull(estado.restante())
        repeat(2) { agora += 10_000; estado.concluirTrecho(); assertNull(estado.restante()) }
        agora += 10_000; estado.concluirTrecho()
        assertEquals(17, estado.porcentagem)
        assertEquals(270, estado.restante()) // 27 trechos de 10 s, não ~20 s pelo OCR.
    }

    @Test fun `barra mede caracteres e nao o numero de blocos`() {
        var agora = 0L
        val estado = ProgressoDaNarracao(listOf(10, 10, 180)) { agora }
        agora += 1_000; estado.concluirTrecho()
        assertEquals(13, estado.porcentagem)
        agora += 1_000; estado.concluirTrecho()
        assertEquals(17, estado.porcentagem)
        agora += 18_000; estado.concluirTrecho()
        assertEquals(85, estado.porcentagem)
        assertNull(estado.restante()) // Salvar/codificar não é extrapolado como voz.
    }

    @Test fun `relogio nao inventa progresso nem chega a zero enquanto gera`() {
        var agora = 0L
        val estado = ProgressoDaNarracao(List(10) { 100 }) { agora }
        repeat(3) { agora += 10_000; estado.concluirTrecho() }
        assertEquals(70, estado.restante())
        val porcentagem = estado.porcentagem
        agora += 8_000
        assertEquals(62, estado.restante())
        agora += 3_000
        assertEquals(porcentagem, estado.porcentagem)
        assertTrue(estado.restante()!! > 0)
    }

    @Test fun `ritmo recente acompanha mudanca de desempenho sem herdar preparacao`() {
        var agora = 0L
        val estado = ProgressoDaNarracao(List(25) { 100 }) { agora }
        repeat(3) { agora += 5_000; estado.concluirTrecho() }
        assertEquals(110, estado.restante())
        repeat(8) { agora += 20_000; estado.concluirTrecho() }
        assertEquals(280, estado.restante()) // Janela de 8 trechos, 14 restantes.
    }

    @Test fun `trecho muito atrasado oculta ETA em vez de faze la crescer a cada segundo`() {
        var agora = 0L
        val estado = ProgressoDaNarracao(List(10) { 100 }) { agora }
        repeat(3) { agora += 10_000; estado.concluirTrecho() }
        val feito = estado.porcentagem
        agora += 300_000
        assertNull(estado.restante())
        assertEquals(feito, estado.porcentagem)
        estado.concluirTrecho()
        assertNotNull(estado.restante()) // Nova medida permite recalibrar.
    }
}
