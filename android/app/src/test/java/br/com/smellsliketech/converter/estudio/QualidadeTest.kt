package br.com.smellsliketech.converter.estudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Baixa/média/alta e a prévia leve: os mesmos números de estudio-qualidade.mjs e StudioQuality.cs. */
class QualidadeTest {
    private val reels = Projeto(largura = 1080, altura = 1920, fps = 30, clipes = listOf(Clipe("c", "v.mp4", "video", inicio = 0.0, fim = 60.0, ajuste = "caber")))

    @Test fun `baixa vai ate 720p e a previa fica leve`() {
        assertEquals(listOf("baixa", "media", "alta"), Qualidade.TODAS.map { it.id })
        val baixa = Qualidade.porId("baixa").aplicar(reels)
        assertEquals(1280, maxOf(baixa.largura, baixa.altura))
        assertEquals(0, baixa.largura % 2)
        assertEquals(reels.largura to reels.altura, Qualidade.porId("alta").aplicar(reels).let { it.largura to it.altura })
        val previa = Qualidade.PREVIA.aplicar(reels)
        assertEquals(480, maxOf(previa.largura, previa.altura))
        assertEquals(12, previa.fps)
        assertEquals("alta", Qualidade.porId("desconhecida").id)
        assertEquals(listOf(55, 70, 82), Qualidade.TODAS.map { it.qualidadeDoVideo })
    }

    @Test fun `tempo estimado cresce com a qualidade e com o recorte`() {
        val v = Velocidade()
        val tempos = Qualidade.TODAS.map { Estimativa.segundos(reels, it, v) }
        assertTrue(tempos[0] < tempos[1] && tempos[1] < tempos[2])
        assertTrue(Estimativa.segundos(reels, Qualidade.PREVIA, v) < tempos[0])
        val recortado = reels.copy(clipes = listOf(reels.clipes[0].copy(transformacao = Transformacao(removerFundo = true))))
        assertEquals(60.0 * 30, Estimativa.quadrosRecortados(recortado), 0.0)
        assertTrue(Estimativa.segundos(recortado, Qualidade.porId("alta"), v) > tempos[2] * 2)
        assertEquals("cerca de 15 s", Estimativa.legivel(12.0))
        assertEquals("cerca de 3 min", Estimativa.legivel(130.0))
        assertEquals("cerca de 3 h 10 min", Estimativa.legivel(3 * 3600.0 + 600))
    }

    @Test fun `velocidade aprende sem aceitar absurdos`() {
        val v = Velocidade().aprender(.05, null)
        assertTrue(v.segundosPorMegapixelQuadro > Velocidade().segundosPorMegapixelQuadro && v.segundosPorMegapixelQuadro < .05)
        assertEquals(Velocidade(), Velocidade().aprender(999.0, -1.0))
    }
}
