package br.com.smellsliketech.converter.figurinhas

import br.com.smellsliketech.converter.conversao.TempoDoCorte
import org.junit.Assert.*
import org.junit.Test

class TempoDaCamadaTest {
    @Test fun videosSomemNoFimDoTrechoSemVoltarAoInicio() {
        assertEquals(4, TempoDaCamada.indice(.5, 1.0, 8, 8))
        assertNull(TempoDaCamada.indice(1.0, 1.0, 8, 8))
        assertNull(TempoDaCamada.indice(3.5, 1.0, 8, 8))
        assertEquals(7, TempoDaCamada.indice(1.0, 1.0, 8, 8, ultimoNaBorda = true))
    }
    @Test fun arquivoCurtoNaoCongelaNemRepeteAteOFimMarcado() {
        assertNull(TempoDaCamada.indice(2.0, 5.0, 10, 20))
        assertEquals(19, TempoDaCamada.indice(1.99, 5.0, 10, 20, arredondar = true))
    }
    @Test fun cortesFracionariosRespeitamOTempoEAcabamNoFimExato() {
        assertEquals(8, TempoDaCamada.indice(1.04, 1.05, 8, 9))
        assertNull(TempoDaCamada.indice(1.05, 1.05, 8, 9))
        assertEquals(12, TempoDaCamada.indice(.99996, 2.0, 12, 24, arredondar = true))
        assertNull(TempoDaCamada.indice(Double.NaN, 1.0, 8, 8))
    }
    @Test fun gifContinuaRepetindo() {
        assertEquals(4, TempoDaCamada.indice(1.5, 1.0, 8, 8, repetir = true))
    }
    @Test fun digitacaoAceitaPrecisaoDeMilissegundos() {
        assertEquals(10.125, TempoDoCorte.ler("10.125")!!, .000001)
        assertEquals(10.125, TempoDoCorte.ler("10,125")!!, .000001)
        assertEquals(65.125, TempoDoCorte.ler("1:05,125")!!, .000001)
        assertEquals(3723.125, TempoDoCorte.ler("01:02:03.125")!!, .000001)
        assertEquals(65.0, TempoDoCorte.ler("1-05")!!, .000001)
    }
    @Test fun digitacaoRecusaValoresInvalidos() {
        for (s in listOf("", "NaN", "Infinity", "-1", "0:60", "1:2:3:4", "abc")) assertNull(s, TempoDoCorte.ler(s))
    }
}
