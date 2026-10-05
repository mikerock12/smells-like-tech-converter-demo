package br.com.smellsliketech.converter.conversao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LegendaTest {
    private val srt = """
        1
        00:00:00,000 --> 00:00:02,500
        Bom dia.

        2
        00:00:02,500 --> 00:00:04,000
        [Música]

        3
        00:00:04,000 --> 00:00:09,000
        <i>Vamos</i> começar
        agora.
    """.trimIndent()

    @Test
    fun `le srt, tira o que nao e fala e renumera`() {
        val trechos = Legenda.limpar(Legenda.ler(srt))
        assertEquals(2, trechos.size)
        assertEquals("Vamos começar\nagora.", trechos[1].texto)
        assertEquals(4_000L, trechos[1].inicioMs)
        assertEquals("1\n00:00:00,000 --> 00:00:02,500\nBom dia.\n\n2\n00:00:04,000 --> 00:00:09,000\nVamos começar\nagora.\n\n", Legenda.srt(trechos))
    }

    @Test
    fun `vtt usa ponto e tem cabecalho, e o texto sai corrido`() {
        val trechos = Legenda.limpar(Legenda.ler(srt))
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:02.500\nBom dia.\n\n00:00:04.000 --> 00:00:09.000\nVamos começar\nagora.\n\n", Legenda.vtt(trechos))
        assertEquals("Bom dia.\nVamos começar agora.\n", Legenda.texto(trechos))
    }

    @Test
    fun `le vtt sem horas e com configuracoes de posicao`() {
        val vtt = "WEBVTT\n\nNOTE comentário\n\n01:02.345 --> 01:05.000 align:start position:10%\nOlá\n"
        val trechos = Legenda.ler(vtt)
        assertEquals(1, trechos.size)
        assertEquals(62_345L, trechos[0].inicioMs)
        assertEquals("Olá", trechos[0].texto)
    }

    @Test
    fun `corta o que passa da duracao`() {
        val trechos = Legenda.limpar(Legenda.ler(srt), duracaoMs = 6_000)
        assertEquals(6_000L, trechos.last().fimMs)
        assertEquals(1, Legenda.limpar(Legenda.ler(srt), duracaoMs = 3_000).size)
    }

    @Test
    fun `fala corrida para narrar`() {
        assertEquals("Bom dia. Vamos começar agora.", Legenda.falaCorrida(srt))
    }

    @Test
    fun `paginas escolhidas`() {
        assertEquals(listOf(1, 2, 3, 5, 8, 9, 10), Intervalos.paginas("1-3, 5, 8-", 10))
        assertEquals(listOf(4, 5), Intervalos.paginas("4;5", 10))
        assertEquals(listOf(1, 2), Intervalos.paginas("-2", 10))
        assertThrows(ErroDeConversao::class.java) { Intervalos.paginas("11", 10) }
        assertThrows(ErroDeConversao::class.java) { Intervalos.paginas("a-b", 10) }
        assertThrows(ErroDeConversao::class.java) { Intervalos.paginas(" ", 10) }
    }

    @Test
    fun `ico com varias resolucoes em png`() {
        val png16 = ByteArray(10) { 1 }
        val png256 = ByteArray(20) { 2 }
        val ico = ByteBuffer.wrap(Ico.montar(listOf(16 to png16, 256 to png256))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, ico.getShort(2).toInt())
        assertEquals(2, ico.getShort(4).toInt())
        assertEquals(16, ico.get(6).toInt())
        assertEquals(0, ico.get(6 + 16).toInt()) // 256 é gravado como 0
        assertEquals(10, ico.getInt(6 + 8))
        assertEquals(6 + 32, ico.getInt(6 + 12))
        assertEquals(6 + 32 + 10, ico.getInt(6 + 16 + 12))
        assertEquals(6 + 32 + 30, ico.capacity())
    }
}
