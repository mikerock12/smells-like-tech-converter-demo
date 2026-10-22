package br.com.smellsliketech.converter.figurinhas

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A geometria das guias: caixa, encaixe no centro, nas bordas, em outra camada e no giro reto. */
@RunWith(AndroidJUnit4::class)
class GuiasTest {
    @Test fun imagemDeitadaCabeCentralizada() {
        val caixa = Guias.caixa(1600, 900, Edicao())!!
        assertEquals(0f, caixa.esquerda, 0.001f)
        assertEquals(1f, caixa.direita, 0.001f)
        assertEquals(0.5f, caixa.centroY, 0.001f)
        assertEquals(900f / 1600, caixa.base - caixa.topo, 0.001f)
    }

    @Test fun pertoDoCentroEncaixaEPisca() {
        val alinhado = Guias.alinhar(Edicao(zoom = 0.5f, deslocamentoX = 0.01f, deslocamentoY = -0.012f), 800, 800)
        assertEquals(0f, alinhado.edicao.deslocamentoX, 0.0001f)
        assertEquals(0f, alinhado.edicao.deslocamentoY, 0.0001f)
        assertTrue(alinhado.guias.any { it.vertical && it.posicao == 0.5f && it.tipo == Guias.Tipo.CENTRO })
        assertTrue(alinhado.guias.any { !it.vertical && it.posicao == 0.5f && it.tipo == Guias.Tipo.CENTRO })
    }

    @Test fun longeDasLinhasNaoMexe() {
        val bruta = Edicao(zoom = 0.4f, deslocamentoX = 0.13f, deslocamentoY = 0.07f, giro = 17)
        val alinhado = Guias.alinhar(bruta, 800, 800)
        assertEquals(bruta, alinhado.edicao)
        assertTrue(alinhado.guias.isEmpty())
    }

    @Test fun bordaEncostaNaBordaDaFigurinha() {
        // Quadrado com metade do lado: a borda esquerda fica em 0,25 - desloc. Perto de 0, encaixa.
        val alinhado = Guias.alinhar(Edicao(zoom = 0.5f, deslocamentoX = -0.24f), 800, 800)
        val caixa = Guias.caixa(800, 800, alinhado.edicao)!!
        assertEquals(0f, caixa.esquerda, 0.001f)
        assertTrue(alinhado.guias.any { it.vertical && it.posicao == 0f && it.tipo == Guias.Tipo.BORDA })
    }

    @Test fun alinhaComACamadaDeTras() {
        val atras = Guias.Caixa(0.1f, 0.2f, 0.6f, 0.7f)
        val alinhado = Guias.alinhar(Edicao(zoom = 0.3f, deslocamentoX = -0.15f + 0.008f, deslocamentoY = 0.27f), 800, 800, listOf(atras))
        val caixa = Guias.caixa(800, 800, alinhado.edicao)!!
        assertEquals(atras.centroX, caixa.centroX, 0.001f)
        assertTrue(alinhado.guias.any { it.vertical && it.tipo == Guias.Tipo.CAMADA })
    }

    @Test fun giroQuaseRetoEndireita() {
        assertEquals(0, Guias.alinhar(Edicao(giro = 2), 800, 600).edicao.giro)
        assertEquals(90, Guias.alinhar(Edicao(giro = 88), 800, 600).edicao.giro)
        assertEquals(-180, Guias.alinhar(Edicao(giro = 178), 800, 600).edicao.giro)
        assertEquals(10, Guias.alinhar(Edicao(giro = 10), 800, 600).edicao.giro)
        assertTrue(Guias.alinhar(Edicao(giro = 2), 800, 600).reta)
    }

    @Test fun preencherCobreAFigurinhaInteira() {
        val zoom = Guias.zoomParaPreencher(1600, 900, Edicao())
        val caixa = Guias.caixa(1600, 900, Edicao(zoom = zoom))
        assertNotNull(caixa)
        assertEquals(0f, caixa!!.topo, 0.001f)
        assertEquals(1f, caixa.base, 0.001f)
        assertTrue(caixa.esquerda < 0f && caixa.direita > 1f)
        // Com contorno, o zoom compensa a margem que o contorno tira.
        val comContorno = Edicao(contorno = true)
        val caixaComContorno = Guias.caixa(1600, 900, comContorno.copy(zoom = Guias.zoomParaPreencher(1600, 900, comContorno)))!!
        assertEquals(1f, caixaComContorno.base - caixaComContorno.topo, 0.001f)
    }
}
