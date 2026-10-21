package br.com.smellsliketech.converter.catalogos

import org.junit.Assert.*
import org.junit.Test

class PoliticaDoCatalogoTest {
    @Test fun cotaLocalExpiraSemPerderAsTentativasRecentes() {
        val agora = 10_000_000L
        val cheio = EstadoDoCatalogo(tentativas = (0 until 100).map { agora - 1000 + it })
        val bloqueado = PoliticaDoCatalogo.preparar(cheio, agora)
        assertEquals("limite", bloqueado.motivo)
        assertEquals(agora - 1000 + PoliticaDoCatalogo.HORA, bloqueado.tentarEm)
        val liberado = PoliticaDoCatalogo.preparar(bloqueado, bloqueado.tentarEm)
        assertEquals(99, liberado.tentativas.size)
        assertEquals(0L, liberado.tentarEm)
    }

    @Test fun limiteDoServidorRespeitaRetryAfterERetornaDepois() {
        val bloqueado = PoliticaDoCatalogo.resposta(EstadoDoCatalogo(), 429, 1000, "120")
        assertEquals(121_000L, bloqueado.tentarEm)
        assertEquals("limite", PoliticaDoCatalogo.preparar(bloqueado, 120_000).motivo)
        assertNull(PoliticaDoCatalogo.preparar(bloqueado, 121_000).motivo)
        assertEquals(0, PoliticaDoCatalogo.resposta(bloqueado, 200, 121_000).falhas)
    }

    @Test fun indisponibilidadeNaoEAnunciadaComoCota() {
        assertEquals("indisponivel", PoliticaDoCatalogo.resposta(EstadoDoCatalogo(), 401, 0).motivo)
        assertEquals(PoliticaDoCatalogo.HORA, PoliticaDoCatalogo.resposta(EstadoDoCatalogo(), 403, 0).tentarEm)
        assertEquals(30_000L, PoliticaDoCatalogo.resposta(EstadoDoCatalogo(), 0, 0).tentarEm)
        assertEquals(60_000L, PoliticaDoCatalogo.espera("inválido", 0))
        assertEquals(1000L, PoliticaDoCatalogo.espera("0", 0))
        assertEquals(120_000L, PoliticaDoCatalogo.espera("Thu, 01 Jan 1970 00:02:00 GMT", 0))
    }
}
