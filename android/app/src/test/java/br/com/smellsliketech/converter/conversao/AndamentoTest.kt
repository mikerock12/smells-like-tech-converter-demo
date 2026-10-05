package br.com.smellsliketech.converter.conversao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndamentoTest {
    @Test
    fun `tempo que falta sai da porcentagem e do tempo que ja levou`() {
        var agora = 0L
        val andamento = Andamento { agora }
        agora = 30_000
        // A preparação (30 s sem porcentagem) não conta: o relógio começa quando a barra anda.
        assertNull(andamento.registrar(5))
        agora = 31_000
        assertNull("antes de 2 s, nada", andamento.registrar(10))
        agora = 40_000
        // De 5% a 25% em 10 s: faltam 75% nesse ritmo, 37,5 s.
        assertEquals(38, andamento.registrar(25))
        agora = 50_000
        // 45 pontos em 20 s daria 22 s; a suavização fica entre isso e o que a conta anterior previa (27,5 s).
        assertEquals(26, andamento.registrar(50))
        assertNull(andamento.registrar(100))
    }

    @Test
    fun `descreve o tempo em portugues`() {
        assertEquals("quase pronto", Andamento.descrever(3))
        assertEquals("faltam cerca de 25 s", Andamento.descrever(23))
        assertEquals("faltam cerca de 2 min", Andamento.descrever(61))
        assertEquals("faltam cerca de 1 h", Andamento.descrever(3_600))
        assertEquals("faltam cerca de 1 h 10 min", Andamento.descrever(4_150))
        assertEquals("Preparando…", textoDoAndamento(0, null))
        assertEquals("Preparando… · faltam cerca de 2 min", textoDoAndamento(0, 90))
        assertEquals("37%", textoDoAndamento(37, null))
        assertEquals("37% · faltam cerca de 40 s", textoDoAndamento(37, 40))
        assertEquals("80% · faltam cerca de 2 min", textoDoAndamento(80, 100))
    }

    @Test
    fun `transcricao anda pelo ritmo entre os blocos do Whisper`() {
        // 90 s de áudio, ritmo padrão 0,5 s por segundo de áudio, 2 s para carregar o modelo.
        val progresso = ProgressoDaTranscricao(totalSegundos = 90.0, ritmoInicial = 0.5, cargaSegundos = 2.0)
        assertEquals(0 to 47, progresso.estado(agoraMs = 0, comecoMs = 0))

        progresso.linha("[Parsed_whisper_0 @ 0x1] run transcription at 0 ms, 480000/480000 samples (30.00 seconds)...", 2_000)
        // 5 s depois, no ritmo 0,5: 10 s de áudio feitos de 90.
        assertEquals(11 to 40, progresso.estado(agoraMs = 7_000, comecoMs = 0))

        // O segundo bloco começou 12 s depois do primeiro: o ritmo medido é 12/30 = 0,4.
        progresso.linha("run transcription at 30000 ms, 480000/480000 samples (30.00 seconds)...", 14_000)
        assertEquals(0.4, progresso.ritmo, 0.001)
        assertEquals(33 to 24, progresso.estado(agoraMs = 14_000, comecoMs = 0))

        // Parado num bloco longo, a porcentagem não passa do fim dele.
        val (porcentagem, _) = progresso.estado(agoraMs = 600_000, comecoMs = 0)
        assertTrue(porcentagem in 33..66)
        // Terminou 36 s depois do primeiro bloco: 90 s de áudio no ritmo 0,4.
        assertEquals(0.4, progresso.ritmoMedido(38_000)!!, 0.001)
    }

    @Test
    fun `transcricao em paragrafos pelas pausas`() {
        val transcricao = Transcricao(
            "audio.opus",
            listOf(
                Legenda.Trecho(0, 2_000, "Bom dia."),
                Legenda.Trecho(2_100, 4_000, "Tudo bem?"),
                Legenda.Trecho(6_000, 8_000, "Outro assunto."),
            ),
        )
        assertEquals(listOf("Bom dia. Tudo bem?", "Outro assunto."), transcricao.paragrafos)
        assertEquals("Bom dia. Tudo bem?\n\nOutro assunto.", transcricao.texto)
        assertEquals(6, transcricao.palavras)
        assertEquals(2, transcricao.documento().blocos.size)
    }
}
