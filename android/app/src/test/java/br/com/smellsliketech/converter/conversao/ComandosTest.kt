package br.com.smellsliketech.converter.conversao

import br.com.smellsliketech.converter.conversao.ffmpeg.Codificador
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Quadros
import br.com.smellsliketech.converter.conversao.ffmpeg.Sondagem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComandosTest {
    private fun depois(argumentos: List<String>, opcao: String) = argumentos[argumentos.indexOf(opcao) + 1]

    @Test
    fun `audio usa o codificador certo de cada formato`() {
        val esperado = mapOf(
            "mp3" to "libmp3lame", "aac" to "aac", "m4a" to "aac", "opus" to "libopus",
            "ogg" to "libvorbis", "wma" to "wmav2", "wav" to "pcm_s16le", "flac" to "flac",
        )
        for ((formato, codificador) in esperado) {
            val argumentos = Comandos.audio("/e/a.ogg", "/s/a.$formato", Opcoes(formato = formato))
            assertEquals(formato, codificador, depois(argumentos, "-c:a"))
            assertEquals("/s/a.$formato", argumentos.last())
            assertEquals("0:a:0", depois(argumentos, "-map"))
        }
    }

    @Test
    fun `audio sem perda nao leva bitrate e o resto leva o padrao ou o escolhido`() {
        assertFalse("-b:a" in Comandos.audio("e", "s.flac", Opcoes(formato = "flac", bitrate = 320)))
        assertEquals("192k", depois(Comandos.audio("e", "s.mp3", Opcoes(formato = "mp3")), "-b:a"))
        assertEquals("128k", depois(Comandos.audio("e", "s.opus", Opcoes(formato = "opus")), "-b:a"))
        assertEquals("320k", depois(Comandos.audio("e", "s.mp3", Opcoes(formato = "mp3", bitrate = 320)), "-b:a"))
    }

    @Test
    fun `corte vira ss antes da entrada e t com a duracao`() {
        val argumentos = Comandos.audio("e.mp3", "s.mp3", Opcoes(formato = "mp3", inicioSegundos = 5.5, fimSegundos = 20.0))
        assertTrue(argumentos.indexOf("-ss") < argumentos.indexOf("-i"))
        assertEquals("5.5", depois(argumentos, "-ss"))
        assertEquals("14.5", depois(argumentos, "-t"))
    }

    @Test
    fun `taxa, canais e normalizar`() {
        val argumentos = Comandos.audio("e", "s.mp3", Opcoes(formato = "mp3", taxaDeAmostragem = 44_100, canais = 1, normalizar = true))
        assertEquals("44100", depois(argumentos, "-ar"))
        assertEquals("1", depois(argumentos, "-ac"))
        assertTrue(depois(argumentos, "-af").startsWith("loudnorm"))
    }

    @Test
    fun `valores fora das listas sao recusados`() {
        assertThrows(ErroDeConversao::class.java) { Comandos.audio("e", "s", Opcoes(formato = "exe")) }
        assertThrows(ErroDeConversao::class.java) { Comandos.audio("e", "s", Opcoes(formato = "mp3", bitrate = 999)) }
        assertThrows(ErroDeConversao::class.java) { Comandos.audio("e", "s", Opcoes(formato = "opus", taxaDeAmostragem = 44_100)) }
        assertThrows(ErroDeConversao::class.java) { Comandos.video("e", "s", Opcoes(formato = "mp4", alturaDoVideo = 123), Sondagem(), Codificador.MPEG4) }
    }

    @Test
    fun `video no celular sai em quadros crus no ritmo certo e o audio a parte`() {
        val sondagem = Sondagem(duracaoSegundos = 10.0, temVideo = true, temAudio = true, largura = 1920, altura = 1080, quadrosPorSegundo = 29.97)
        val opcoes = Opcoes(formato = "mp4", alturaDoVideo = 720)
        val quadros = Comandos.quadrosDoCelular(opcoes, sondagem)!!
        assertEquals(Quadros(1280, 720, 30), quadros)
        assertEquals(1280 * 720 * 3 / 2, quadros.bytes)
        val argumentos = Comandos.videoParaOCelular("e.mov", "/c/a.m4a", opcoes, sondagem, quadros)
        assertFalse("-progress" in argumentos)
        assertTrue(depois(argumentos, "-vf").endsWith("scale=1280:720,setsar=1,format=yuv420p"))
        assertEquals("rawvideo", depois(argumentos, "-f"))
        assertEquals("30", depois(argumentos, "-r"))
        assertEquals("pipe:1", argumentos[argumentos.indexOf("rawvideo") + 3])
        assertEquals("/c/a.m4a", argumentos.last())
        assertEquals("aac", depois(argumentos, "-c:a"))
        // Girado 90 graus: o quadro fica em pé.
        assertEquals(Quadros(1080, 1920, 30), Comandos.quadrosDoCelular(Opcoes(formato = "mp4", graus = 90), sondagem))
        assertEquals(null, Comandos.quadrosDoCelular(Opcoes(formato = "mp4"), Sondagem(temVideo = true)))
    }

    @Test
    fun `juntar copia sem recodificar`() {
        assertEquals(
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-i", "v.mp4", "-i", "a.m4a", "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-map_metadata", "-1", "s.mkv"),
            Comandos.juntar("v.mp4", "a.m4a", "s.mkv", "mkv"),
        )
        assertTrue("+faststart" in Comandos.juntar("v.mp4", null, "s.mp4", "mp4"))
    }

    @Test
    fun `webm e avi tem codificadores proprios e o audio e sempre refeito`() {
        assertEquals(listOf(Codificador.VP9), Codificador.para("webm"))
        assertEquals(listOf(Codificador.MPEG4), Codificador.para("avi"))
        assertEquals(listOf(Codificador.H264_CELULAR, Codificador.MPEG4), Codificador.para("mkv"))
        val sondagem = Sondagem(temVideo = true, temAudio = true, largura = 640, altura = 360)
        val webm = Comandos.video("e", "s.webm", Opcoes(formato = "webm"), sondagem, Codificador.VP9)
        assertEquals("libvpx-vp9", depois(webm, "-c:v"))
        assertEquals("libopus", depois(webm, "-c:a"))
        val avi = Comandos.video("e", "s.avi", Opcoes(formato = "avi"), sondagem, Codificador.MPEG4)
        assertEquals("libmp3lame", depois(avi, "-c:a"))
        assertEquals("XVID", depois(avi, "-tag:v"))
    }

    @Test
    fun `sem audio vira -an e velocidade acelera imagem e som`() {
        val sondagem = Sondagem(temVideo = true, temAudio = true, largura = 640, altura = 360)
        assertTrue("-an" in Comandos.video("e", "s.mp4", Opcoes(formato = "mp4", removerAudio = true), sondagem, Codificador.MPEG4))
        val rapido = Comandos.video("e", "s.mp4", Opcoes(formato = "mp4", velocidade = 4.0), sondagem, Codificador.MPEG4)
        assertTrue("setpts=0.25*PTS" in depois(rapido, "-vf"))
        assertEquals("atempo=2.0,atempo=2", depois(rapido, "-af"))
        assertEquals("atempo=0.5,atempo=0.5", Comandos.filtroDeVelocidadeDoAudio(0.25))
        assertNull(Comandos.filtroDeVelocidadeDoAudio(1.0))
    }

    @Test
    fun `proporcao nova com cada encaixe`() {
        val sondagem = Sondagem(temVideo = true, largura = 1920, altura = 1080)
        val quadrado = Opcoes(formato = "mp4", proporcao = Proporcao.QUADRADA)
        assertEquals(1080 to 1080, Comandos.tamanhoFinal(quadrado, sondagem))
        assertTrue("crop=1080:1080" in Comandos.filtrosDeVideo(quadrado, sondagem, Codificador.MPEG4)!!)
        assertTrue("pad=1080:1080" in Comandos.filtrosDeVideo(quadrado.copy(encaixe = Encaixe.AJUSTAR), sondagem, Codificador.MPEG4)!!)
        assertTrue("gblur" in Comandos.filtrosDeVideo(quadrado.copy(encaixe = Encaixe.DESFOCAR), sondagem, Codificador.MPEG4)!!)
        // Vertical a partir de horizontal, com altura escolhida: 9:16 em 720 de altura.
        assertEquals(404 to 720, Comandos.tamanhoFinal(Opcoes(formato = "mp4", proporcao = Proporcao.VERTICAL, alturaDoVideo = 720), sondagem))
    }

    @Test
    fun `girar 90 graus troca largura e altura antes da geometria`() {
        val sondagem = Sondagem(temVideo = true, largura = 1920, altura = 1080)
        val filtros = Comandos.filtrosDeVideo(Opcoes(formato = "mp4", graus = 90, proporcao = Proporcao.QUADRADA), sondagem, Codificador.MPEG4)!!
        assertTrue(filtros.startsWith("transpose=1"))
        assertTrue("crop=1080:1080" in filtros)
    }

    @Test
    fun `transcricao usa o filtro whisper com os caminhos escapados`() {
        val argumentos = Comandos.transcrever("/c/entrada.opus", "/c/saida.srt", "/m/ggml-small.bin", "pt", "srt")
        val filtro = depois(argumentos, "-af")
        assertTrue(filtro.startsWith("whisper=model='/m/ggml-small.bin':language=pt:destination='/c/saida.srt':format=srt"))
        assertTrue("queue=30" in filtro)
        assertEquals(listOf("-f", "null", "-"), argumentos.takeLast(3))
        // O log em "info" traz o aviso de cada bloco, de onde sai a porcentagem.
        assertEquals("info", depois(argumentos, "-v"))
        assertEquals("4", depois(argumentos, "-filter_threads"))
        assertEquals("2", depois(Comandos.transcrever("e", "s", "m", "pt", "srt", linhas = 2), "-filter_threads"))
        assertEquals("'C\\:\\\\x\\'y'", Comandos.caminhoNoFiltro("C:\\x'y"))
        assertThrows(ErroDeConversao::class.java) { Comandos.transcrever("e", "s", "m", "xx", "srt") }
    }

    @Test
    fun `gif sem corte usa os 10 primeiros segundos e quadros tem limite`() {
        val gif = Comandos.gif("e.mp4", "s.gif", Opcoes(formato = "gif"))
        assertEquals("10", depois(gif, "-t"))
        assertTrue("palettegen" in depois(gif, "-vf"))
        val quadros = Comandos.quadros("e.mp4", "/q/q%04d.png", Opcoes(formato = "png", maximoDeQuadros = 50))
        assertEquals("50", depois(quadros, "-frames:v"))
        assertEquals("fps=1", depois(quadros, "-vf"))
    }

    @Test
    fun `qualidade vira bitrate e escala do codificador`() {
        assertEquals(2, Comandos.escala(100, pior = 20, melhor = 2))
        assertEquals(20, Comandos.escala(0, pior = 20, melhor = 2))
        assertTrue(Comandos.bitrateDoVideo(1920, 1080, 30.0, 100) > Comandos.bitrateDoVideo(1920, 1080, 30.0, 20))
        assertEquals(300, Comandos.bitrateDoVideo(160, 120, 10.0, 0))
    }

    @Test
    fun `sondagem le duracao, trilhas, tamanho girado e ignora capa de mp3`() {
        val celular = """
            Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'video.mp4':
              Duration: 00:01:02.50, start: 0.000000, bitrate: 17000 kb/s
              Stream #0:0[0x1](eng): Video: h264 (High) (avc1 / 0x31637661), yuv420p(tv, bt709), 1920x1080, 16800 kb/s, 29.97 fps, 30 tbr, 90k tbn (default)
                  Side data:
                    displaymatrix: rotation of -90.00 degrees
              Stream #0:1[0x2](eng): Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 256 kb/s (default)
        """.trimIndent()
        val sondagem = Sondagem.ler(celular)
        assertEquals(62.5, sondagem.duracaoSegundos!!, 0.001)
        assertTrue(sondagem.temVideo)
        assertTrue(sondagem.temAudio)
        assertEquals(1080, sondagem.largura)
        assertEquals(1920, sondagem.altura)
        assertEquals(29.97, sondagem.quadrosPorSegundo!!, 0.001)

        val mp3 = """
            Input #0, mp3, from 'musica.mp3':
              Duration: 00:03:00.00, start: 0.025057, bitrate: 320 kb/s
              Stream #0:0: Audio: mp3 (mp3float), 44100 Hz, stereo, fltp, 320 kb/s
              Stream #0:1: Video: mjpeg (Baseline), yuvj420p(pc, bt470bg/unknown/unknown), 500x500 [SAR 1:1 DAR 1:1], 90k tbr, 90k tbn (attached pic)
        """.trimIndent()
        val musica = Sondagem.ler(mp3)
        assertFalse(musica.temVideo)
        assertTrue(musica.temAudio)
        assertEquals(180.0, musica.duracaoSegundos!!, 0.001)
    }

    @Test
    fun `imagem pelo ffmpeg`() {
        assertEquals("libaom-av1", depois(Comandos.imagem("e.png", "s.avif", "avif", 80), "-c:v"))
        assertEquals("tiff", depois(Comandos.imagem("e.png", "s.tiff", "tiff", 80), "-c:v"))
        assertEquals(1, Comandos.imagem("e.png", "s.gif", "gif", 80).count { it == "-frames:v" })
        assertThrows(ErroDeConversao::class.java) { Comandos.imagem("e", "s", "psd", 80) }
    }

    @Test
    fun `cada ferramenta abre com um formato que ela oferece`() {
        for (ferramenta in Ferramenta.entries) {
            val formato = ferramenta.opcoesPadrao().formato
            assertTrue("${ferramenta.name}: $formato", formato in ferramenta.formatos)
        }
    }

    @Test
    fun `tipo pela extensao ganha do mime generico do WhatsApp`() {
        assertEquals(Tipo.DOCUMENTO, Tipo.de("application/octet-stream", "relatorio.doc"))
        assertEquals(Tipo.AUDIO, Tipo.de("audio/ogg; codecs=opus", "PTT-20260930-WA0001.opus"))
        assertEquals(Tipo.LEGENDA, Tipo.de("text/plain", "filme.srt"))
        assertEquals(Tipo.TEXTO, Tipo.de("text/plain", "nota.txt"))
        assertEquals(Tipo.AUDIO, Tipo.de("audio/mpeg", "sem-extensao"))
        assertNull(Tipo.de("application/zip", "pacote.zip"))
    }
}
