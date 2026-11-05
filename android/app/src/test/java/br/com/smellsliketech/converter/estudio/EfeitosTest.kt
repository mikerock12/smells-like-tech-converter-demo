package br.com.smellsliketech.converter.estudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os mesmos números de tests/estudio-efeitos.test.mjs; a receita é conferida pelos casos do estúdio. */
class EfeitosTest {
    @Test fun `sem efeito nao muda nada`() {
        assertEquals("", Efeitos.filtro(Transformacao(), 96, 64, 10, 2.0, "r"))
        assertFalse(Efeitos.tem(Transformacao()))
        assertEquals(EstadoDoEfeito(), Efeitos.estado(Transformacao(), .5, 2.0))
    }

    @Test fun `as janelas nao se invadem`() {
        assertEquals(4.0 to 1.0, Efeitos.janelas(Transformacao(efeitoEntrada = "fade", duracaoEntrada = 4.0, efeitoSaida = "cima", duracaoSaida = 4.0), 5.0))
        assertEquals(0.0 to 0.0, Efeitos.janelas(Transformacao(efeitoEntrada = "maquina", duracaoEntrada = 2.0), 5.0))
        val filtro = Efeitos.filtro(Transformacao(efeitoEntrada = "cima", duracaoEntrada = 1.0, efeitoSaida = "fade", duracaoSaida = 1.0), 96, 64, 10, 5.0, "r")
        assertTrue(filtro.contains("split=3") && filtro.contains("trim=start=1:end=4,") && filtro.contains("concat=n=3"))
    }

    @Test fun `estado parte de fora chega e sai`() {
        val t = Transformacao(efeitoEntrada = "esquerda", duracaoEntrada = 1.0, efeitoSaida = "zoom", duracaoSaida = 1.0)
        assertEquals(-1.0, Efeitos.estado(t, 0.0, 5.0).dx, 1e-9)
        assertEquals(0.0, Efeitos.estado(t, 2.0, 5.0).dx, 1e-9)
        assertTrue(Efeitos.estado(t, 4.99, 5.0).sx < .1)
        assertEquals(0.0, Efeitos.estado(Transformacao(efeitoEntrada = "desintegrar", duracaoEntrada = 1.0), 0.0, 5.0).particulas, 1e-9)
        assertTrue(Efeitos.estado(Transformacao(efeitoSaida = "estilhacos", duracaoSaida = 1.0), 4.9, 5.0).pedacos > .5)
        assertEquals(Math.PI, Efeitos.estado(Transformacao(efeitoContinuo = "girar"), 1.0, 5.0).giro, 1e-9)
    }

    @Test fun `quadro parado congela o efeito numa transformacao comum`() {
        val t = Transformacao(x = .3, y = .4, escala = .5, efeitoEntrada = "cima", duracaoEntrada = 1.0, efeitoSaida = "fade", duracaoSaida = 1.0)
        val inicio = Efeitos.congelar(t, 0.0, 5.0, 100, 100)
        assertEquals(-.6, inicio.y, 1e-9)
        assertFalse(Efeitos.tem(inicio))
        val meio = Efeitos.congelar(t, 2.0, 5.0, 100, 100)
        assertEquals(listOf(.3, .4, .5, 1.0), listOf(meio.x, meio.y, meio.escala, meio.opacidade).map { Math.round(it * 1e9) / 1e9 })
        assertEquals(.5, Efeitos.congelar(t, 4.5, 5.0, 100, 100).opacidade, 1e-9)
        assertEquals(null, Efeitos.congelar(Transformacao(efeitoContinuo = "girar"), 3.0, 5.0, 100, 100).problema())
    }

    @Test fun `maquina de escrever conta letras sem quebrar emoji`() {
        val texto = Texto("t", "Olá 🦝!", 1.0, 5.0, transformacao = Transformacao(efeitoEntrada = "maquina", duracaoEntrada = 1.0))
        assertEquals(Efeitos.Passos(6, 6, 1.0, 6.0), Efeitos.passosDaMaquina(texto, 30))
        assertEquals(listOf(1, 4, 6), listOf(0.0, .5, 2.0).map { Efeitos.letrasVisiveis(texto, it, 30) })
    }

    @Test fun `valores desconhecidos sao recusados e a previa direta nao aceita efeitos`() {
        assertEquals("Efeito de entrada desconhecido.", Transformacao(efeitoEntrada = "explodir").problema())
        assertEquals("Efeito de saída desconhecido.", Transformacao(efeitoSaida = "maquina").problema())
        assertEquals("A duração do efeito vai de 0,1 a 10 segundos.", Transformacao(duracaoSaida = 20.0).problema())
        val clipe = Clipe("c", "v.mp4", "video", inicio = 0.0, fim = 2.0, ajuste = "caber", transformacao = Transformacao(efeitoContinuo = "pulsar"))
        assertFalse(PreviaImediata.aceita(Projeto(largura = 64, altura = 64, clipes = listOf(clipe))))
    }

    @Test fun `efeitos sobrevivem ao json do projeto`() {
        val t = Transformacao(efeitoEntrada = "estilhacos", duracaoEntrada = 1.5, efeitoSaida = "desintegrar", duracaoSaida = .8, efeitoContinuo = "gelatina")
        assertEquals(t, Transformacao.deJson(t.paraJson()))
    }
}
