package br.com.smellsliketech.converter.figurinhas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacotesTest {
    private fun pacote(quantas: Int) = PacoteDeFigurinhas(
        "slt_teste", "Teste", Pacotes.AUTOR_PADRAO, animado = false, versao = 1,
        figurinhas = (1..quantas).map { Figurinha("%02d.webp".format(it), listOf("😀")) },
    )

    @Test fun whatsAppSoAceitaDeTresATrinta() {
        assertFalse(pacote(2).prontoParaWhatsApp)
        assertEquals(1, pacote(2).faltam)
        assertTrue(pacote(3).prontoParaWhatsApp)
        assertTrue(pacote(30).prontoParaWhatsApp)
        assertTrue(pacote(30).cheio)
        assertFalse(pacote(31).prontoParaWhatsApp)
    }

    @Test fun identificadorSoComOQueOWhatsAppAceita() {
        assertTrue(Pacotes.idValido("slt_abc-123.x"))
        assertFalse(Pacotes.idValido(""))
        assertFalse(Pacotes.idValido("../fora"))
        assertFalse(Pacotes.idValido("a/b"))
        assertFalse(Pacotes.idValido("x".repeat(129)))
    }

    @Test fun nomeDoPacoteLimpoECurto() {
        assertEquals("Meu pacote", Pacotes.nomeLimpo("  Meu   pacote "))
        assertEquals("Minhas figurinhas", Pacotes.nomeLimpo("   "))
        assertEquals(Regras.NOME_MAX, Pacotes.nomeLimpo("a".repeat(300)).length)
    }
}
