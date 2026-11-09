package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mesmos valores do teste "− e + dos ajustes" de tests/musica.test.mjs. */
class AjusteFinoDaMusicaTest {
    @Test fun passoNaGradeRelativoELimites() {
        assertEquals(-2.5, Musica.ajusteFino(-3.2, 1, 0.5, -60.0, 12.0), 1e-9)
        assertEquals(-3.5, Musica.ajusteFino(-3.2, -1, 0.5, -60.0, 12.0), 1e-9)
        assertEquals(12.0, Musica.ajusteFino(11.8, 1, 0.5, -60.0, 12.0), 1e-9)
        assertEquals(0.4, Musica.ajusteFino(0.3, 1, 0.1, 0.0, 1.0), 1e-9)
        assertEquals(1020.0, Musica.ajusteFino(1000.0, 1, 0.02, 20.0, 20000.0, true), 1e-9)
        assertEquals(20.0, Musica.ajusteFino(20.0, -1, 0.02, 20.0, 2000.0, true), 1e-9)
        assertEquals(20.0, Musica.ajusteFino(21.0, -1, 0.02, 20.0, 2000.0, true), 1e-9)
    }

    @Test fun passoDoParametro() {
        assertEquals(Musica.Passo(0.5, false), Musica.passoDoParametro(-15.0, 15.0, "dB"))
        assertEquals(Musica.Passo(0.02, true), Musica.passoDoParametro(20.0, 2000.0, "Hz"))
        assertEquals(0.01, Musica.passoDoParametro(0.0, 1.0, "").passo, 1e-12)
        assertEquals(0.2, Musica.passoDoParametro(1.0, 20.0, "").passo, 1e-12)
        assertEquals(0.1, Musica.passoDoParametro(0.3, 8.0, "").passo, 1e-12)
        assertEquals(10.0, Musica.passoDoParametro(1.0, 2000.0, "ms").passo, 1e-12)
    }

    @Test fun nomeDaSeparacaoComecaPeloQueEla() {
        assertEquals("voz_Minha música", Musica.nomeDaFaixaSeparada("Voz", "Minha música"))
        assertEquals("bateria_Teclado", Musica.nomeDaFaixaSeparada("Bateria", "Teclado"))
    }
}
