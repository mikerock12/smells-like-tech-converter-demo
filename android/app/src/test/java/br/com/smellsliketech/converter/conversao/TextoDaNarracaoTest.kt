package br.com.smellsliketech.converter.conversao

import org.junit.Assert.*
import org.junit.Test

class TextoDaNarracaoTest {
    @Test fun preservaConteudoENormalizaAcentos() {
        assertEquals("ATENÇÃO\nDIA 02/10\n1\nNÃO TERÃO AULA.",
            TextoDaNarracao.normalizar("  ATENC\u0327A\u0303O\n)\nDIA\t02/10\n1\nNÃO TERÃO AULA.\n!!!"))
        assertEquals("", TextoDaNarracao.normalizar(")...\n!"))
    }
    @Test fun rejeitaSaidaInvalidaOuSilenciosa() {
        for (samples in listOf(floatArrayOf(), floatArrayOf(0f), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY)))
            assertFalse(TextoDaNarracao.amostrasValidas(samples))
        assertTrue(TextoDaNarracao.amostrasValidas(floatArrayOf(.2f, -.2f)))
    }
    @Test fun repeticaoMantemTodasAsPalavrasENaoCortaPalavraUnica() {
        val texto = "Primeira parte do aviso. Segunda parte termina aqui."
        assertEquals(texto, TextoDaNarracao.partir(texto).joinToString(" "))
        assertTrue(TextoDaNarracao.partir("palavraunicasemespaco").isEmpty())
    }
}
