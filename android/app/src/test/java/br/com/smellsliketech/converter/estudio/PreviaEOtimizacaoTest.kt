package br.com.smellsliketech.converter.estudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviaEOtimizacaoTest {
    private val clipe=Clipe("c","video.mp4","video",inicio=2.0,fim=12.0,ajuste="caber")
    private val projeto=Projeto(largura=1080,altura=1080,fps=30,fundo=FundoDoProjeto("#FF7A18"),clipes=listOf(clipe))

    @Test fun `previa direta so aceita efeitos representados pelo player`() {
        assertTrue(PreviaImediata.aceita(projeto))
        assertFalse(PreviaImediata.aceita(projeto.copy(clipes=listOf(clipe.copy(ajuste="caber-desfocado")))))
        assertFalse(PreviaImediata.aceita(projeto.copy(clipes=listOf(clipe.copy(volume=1.5)))))
        assertFalse(PreviaImediata.aceita(projeto.copy(clipes=listOf(clipe.copy(transformacao=Transformacao(mescla="multiplicar"))))))
        assertFalse(PreviaImediata.aceita(projeto.copy(clipes=listOf(clipe.copy(transformacao=Transformacao(removerFundo=true))))))
        assertFalse(PreviaImediata.aceita(projeto.copy(textos=listOf(Texto("t","texto",0.0,1.0)))))
        assertFalse(PreviaImediata.aceita(projeto.copy(camadas=listOf(Camada("l","foto.png")))))
        assertTrue(PreviaImediata.aceita(projeto.copy(camadas=listOf(Camada("l","foto.png",visivel=false)))))
        assertFalse(PreviaImediata.aceita(projeto.copy(clipes=listOf(clipe,clipe.copy(id="c2",transicao=Transicao("fade"))))))
    }

    @Test fun `tempo da previa respeita corte e velocidade`() {
        val rapido=clipe.copy(velocidade=2.0)
        assertEquals(6.0,PreviaImediata.tempoNaOrigem(rapido,2.0),0.0)
        assertEquals(2.0,PreviaImediata.tempoNaOrigem(rapido,-1.0),0.0)
        assertEquals(12.0,PreviaImediata.tempoNaOrigem(rapido,99.0),0.0)
    }

    @Test fun `fundo solido e identidade nao passam mais pelo geq`() {
        val fundo=FiltrosDeCamada.fundo(FundoDoProjeto("#01020304"),32,32,10,2.0,otimizar=true)
        assertFalse(fundo.contains("geq"))
        assertTrue(fundo.contains("lutrgb=r=1:g=2:b=3:a=4"))
        assertTrue(FiltrosDeCamada.fundo(FundoDoProjeto("#01020304"),32,32,10,2.0).contains("geq"))
        assertEquals("format=rgba",FiltrosDeCamada.transformacao(Transformacao(),32,32,otimizar=true))
        assertTrue(FiltrosDeCamada.transformacao(Transformacao(),32,32).contains("geq"))
        assertTrue(FiltrosDeCamada.transformacao(Transformacao(brilho=.1),32,32,otimizar=true).contains("geq"))
    }
}
