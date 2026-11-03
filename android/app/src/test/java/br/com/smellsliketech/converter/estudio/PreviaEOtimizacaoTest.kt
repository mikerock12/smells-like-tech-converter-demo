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
        // Cor, giro planar e máscara também saem do geq; 3D continua nele.
        val cor=FiltrosDeCamada.transformacao(Transformacao(brilho=.1),32,32,otimizar=true)
        assertFalse(cor.contains("geq"));assertTrue(cor.contains("lutrgb="))
        val giro=FiltrosDeCamada.transformacao(Transformacao(giro=37.0),32,32,otimizar=true)
        assertFalse(giro.contains("geq"));assertTrue(giro.contains("rotate="))
        val mascara=FiltrosDeCamada.transformacao(Transformacao(mascara="circulo"),32,32,otimizar=true)
        assertTrue(mascara.contains("alphamerge"));assertFalse(mascara.contains("format=gbrap,geq"))
        // Giro 3D estático também sai do geq por quadro: mapa de um quadro + remap.
        val inclinada=FiltrosDeCamada.transformacao(Transformacao(giroX=20.0,mascara="oval"),32,32,otimizar=true)
        assertFalse(inclinada.contains("format=gbrap,geq"));assertTrue(inclinada.contains("remap=fill=black@0"));assertTrue(inclinada.contains("loop=loop=-1:size=1"))
        assertTrue(FiltrosDeCamada.transformacao(Transformacao(giroX=20.0),32,32).contains("format=gbrap,geq"))
    }

    @Test fun `ken burns usa escala e overlay em vez de geq`() {
        val filtro=FiltrosDeCamada.transformacao(Animacao.kenBurns(duracao=60.0),480,854,otimizar=true,rotulo="cp0",fps=30)
        assertFalse(filtro.contains("geq"))
        assertTrue(filtro.contains("scale="))
        assertTrue(filtro.contains("overlay="))
        assertTrue(filtro.contains("n/30"))
        assertTrue(filtro.contains("eval=frame"))
    }
}
