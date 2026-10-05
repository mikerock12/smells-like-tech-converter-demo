package br.com.smellsliketech.converter.conversao

import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import org.junit.Assert.*
import org.junit.Test

class ParidadeTest {
    @Test fun `legenda adianta sem gerar tempos negativos`() {
        val trechos = listOf(Legenda.Trecho(1000, 2000, "primeiro"), Legenda.Trecho(2500, 4000, "segundo"))
        assertEquals(listOf(Legenda.Trecho(0, 1000, "segundo")), Legenda.deslocar(trechos, -3.0))
        assertEquals(1500L, Legenda.deslocar(trechos, 0.5).first().inicioMs)
        assertThrows(ErroDeConversao::class.java) { Legenda.deslocar(trechos, Double.NaN) }
    }
    @Test fun `repeticao de gif entra antes da entrada em ambos os codificadores`() {
        val opcoes = Opcoes("mp4", repeticoesDoGif = 3)
        val sondagem = br.com.smellsliketech.converter.conversao.ffmpeg.Sondagem(temVideo = true, largura = 320, altura = 240)
        val software = Comandos.video("e.gif", "s.mp4", opcoes, sondagem, br.com.smellsliketech.converter.conversao.ffmpeg.Codificador.MPEG4)
        val celular = Comandos.videoParaOCelular("e.gif", null, opcoes, sondagem, br.com.smellsliketech.converter.conversao.ffmpeg.Quadros(320, 240, 30))
        for (argumentos in listOf(software, celular)) {
            assertEquals("2", argumentos[argumentos.indexOf("-stream_loop") + 1])
            assertTrue(argumentos.indexOf("-stream_loop") < argumentos.indexOf("-i"))
        }
    }
    @Test fun `voz de rede nunca e escolhida nem quando e a preferida`() {
        val vozes = listOf(VozLocal("rede", "pt", "BR", true, false), VozLocal("local", "pt", "BR", false, false))
        assertEquals("local", VozesLocais.escolher(vozes, "rede"))
        assertNull(VozesLocais.escolher(vozes.take(1), "rede"))
    }
    @Test fun `voz por baixar nao e usada e ptBR tem preferencia`() {
        val vozes = listOf(VozLocal("falta", "pt", "BR", false, true), VozLocal("portugal", "pt", "PT", false, false), VozLocal("brasil", "pt", "BR", false, false))
        assertEquals("brasil", VozesLocais.escolher(vozes, "portugal"))
        assertEquals("portugal", VozesLocais.escolher(vozes.take(2), null))
        assertNull(VozesLocais.escolher(listOf(VozLocal("ingles", "en", "US", false, false)), null))
    }
    @Test fun `gif para video nao aparece para fotos nem lotes mistos`() {
        assertTrue(Ferramenta.GIF_PARA_VIDEO in Ferramenta.para(Tipo.IMAGEM, listOf("gif")))
        assertFalse(Ferramenta.GIF_PARA_VIDEO in Ferramenta.para(Tipo.IMAGEM, listOf("gif", "png")))
        assertFalse(Ferramenta.GIF_PARA_VIDEO in Ferramenta.para(Tipo.IMAGEM, emptyList()))
        assertTrue(Ferramenta.PDF_COMPRIMIR in Ferramenta.para(Tipo.PDF))
        assertTrue(Ferramenta.AUDIO_EDITAR in Ferramenta.para(Tipo.AUDIO))
    }
    @Test fun `audio inclui volume velocidade fades e calcula fim depois do corte`() {
        val opcoes = Opcoes("mp3", inicioSegundos = 2.0, fimSegundos = 10.0, velocidade = 2.0, volume = 1.5, fadeEntrada = 1.0, fadeSaida = 1.0)
        val argumentos = Comandos.audio("e.wav", "s.mp3", opcoes, 20.0)
        assertEquals("atempo=2,volume=1.5,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=1,afade=t=out:st=3:d=1", argumentos[argumentos.indexOf("-af") + 1])
    }
    @Test fun `fade longo respeita duracao e valores invalidos sao recusados`() {
        val args = Comandos.audio("e", "s", Opcoes("mp3", fadeSaida = 10.0), 2.0)
        assertTrue("afade=t=out:st=0:d=2" in args[args.indexOf("-af") + 1])
        listOf(Opcoes("mp3", velocidade = Double.NaN), Opcoes("mp3", volume = -1.0), Opcoes("mp3", fadeSaida = Double.POSITIVE_INFINITY), Opcoes("mp3", inicioSegundos = 3.0, fimSegundos = 2.0)).forEach {
            assertThrows(ErroDeConversao::class.java) { Comandos.audio("e", "s", it) }
        }
        assertThrows(ErroDeConversao::class.java) { Comandos.audio("e", "s", Opcoes("mp3", fadeSaida = 2.0)) }
    }
}
