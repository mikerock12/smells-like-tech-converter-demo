package br.com.smellsliketech.converter.conversao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelogioTest {
    @Test fun aceitaDoisPontosEOsSeparadoresDoTecladoNumerico() {
        for (texto in listOf("0:21", "0-21", "0.21", "0 21", " 0:21 ")) assertEquals(texto, 21.0, Relogio.ler(texto)!!, 0.0)
        assertEquals(65.0, Relogio.ler("1-05")!!, 0.0)
        assertEquals(3723.0, Relogio.ler("1:02:03")!!, 0.0)
        assertEquals(3723.0, Relogio.ler("1-02-03")!!, 0.0)
    }

    @Test fun virgulaEhCasaDecimalDosSegundos() {
        assertEquals(65.5, Relogio.ler("1:05,5")!!, 1e-9)
        assertEquals(90.25, Relogio.ler("90,25")!!, 1e-9)
        assertEquals(90.0, Relogio.ler("90")!!, 0.0)
    }

    @Test fun recusaOQueNaoEhTempo() {
        for (texto in listOf("", "  ", "abc", "1:", ":30", "1:75", "1:2:3:4", "1,5:30", "-5")) assertNull(texto, Relogio.ler(texto))
    }

    @Test fun escreveComDecimoSoQuandoPrecisa() {
        assertEquals("0:21", Relogio.escrever(21.0))
        assertEquals("1:05,5", Relogio.escrever(65.5))
        assertEquals("1:02:03", Relogio.escrever(3723.0))
        assertEquals("0:00", Relogio.escrever(-3.0))
        assertEquals("0:10", Relogio.escrever(9.96))
        assertEquals(65.5, Relogio.ler(Relogio.escrever(65.52))!!, 1e-9)
    }
}
