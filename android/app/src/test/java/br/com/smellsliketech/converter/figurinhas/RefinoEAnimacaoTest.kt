package br.com.smellsliketech.converter.figurinhas

import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Repeticoes
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.estudio.Projeto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** A limpeza da máscara e as repetições: os mesmos casos do site e do Windows. */
class RefinoEAnimacaoTest {
    private val casos = JSONObject(File("../../packages/converter-core/recorte-casos.json").readText())

    @Test fun `a curva bate com a referencia`() {
        val curva = casos.getJSONArray("curva")
        for (i in 0 until curva.length()) {
            val par = curva.getJSONArray(i)
            assertEquals(par.getDouble(1).toFloat(), RefinoDaMascara.curva(par.getDouble(0).toFloat()), 1e-3f)
        }
    }

    @Test fun `as ilhas saem como na referencia`() {
        val caso = casos.getJSONObject("ilhas")
        val entrada = caso.getJSONArray("mascara")
        val mascara = FloatArray(entrada.length()) { entrada.getDouble(it).toFloat() }
        assertEquals(caso.getInt("tiradas"), RefinoDaMascara.limparIlhas(mascara, caso.getInt("largura"), caso.getInt("altura")))
        val esperado = caso.getJSONArray("esperado")
        assertEquals((0 until esperado.length()).map { esperado.getDouble(it).toFloat() }, mascara.toList())
    }

    @Test fun `mascara vazia nao quebra`() {
        assertEquals(0, RefinoDaMascara.limparIlhas(FloatArray(16), 4, 4))
    }

    @Test fun `o gif conta as repeticoes depois da primeira`() {
        assertEquals(listOf(0, 3, 5, 10), Repeticoes.TODAS)
        assertEquals(listOf(0, 2, 4, 9), Repeticoes.TODAS.map(Repeticoes::loopDoGif))
        assertEquals("Sem fim", Repeticoes.rotulo(0))
        assertEquals("3 vezes", Repeticoes.rotulo(3))
    }

    @Test fun `o comando do gif grava as repeticoes e recusa o que nao existe`() {
        val comando = Comandos.gif("a.mp4", "a.gif", Opcoes(formato = "gif", vezesDoGif = 5))
        assertEquals("4", comando[comando.indexOf("-loop") + 1])
        val padrao = Comandos.gif("a.mp4", "a.gif", Opcoes(formato = "gif"))
        assertEquals("0", padrao[padrao.indexOf("-loop") + 1])
        assertThrows(ErroDeConversao::class.java) { Comandos.gif("a.mp4", "a.gif", Opcoes(formato = "gif", vezesDoGif = 7)) }
    }

    @Test fun `as repeticoes do projeto do estudio voltam do json`() {
        assertEquals(10, Projeto.deJson(Projeto(repeticoes = 10).paraJson()).repeticoes)
    }

    /** WebP mínimo com ANIM e dois quadros. */
    private fun webp(): ByteArray {
        fun u(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        fun pedaco(nome: String, dados: ByteArray) = nome.toByteArray() + u(dados.size, 4) + dados + if (dados.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        val quadro = pedaco("ANMF", u(0, 3) + u(0, 3) + u(511, 3) + u(511, 3) + u(100, 3) + byteArrayOf(2) + ByteArray(2))
        val corpo = "WEBP".toByteArray() + pedaco("VP8X", byteArrayOf(2, 0, 0, 0) + u(511, 3) + u(511, 3)) + pedaco("ANIM", ByteArray(6)) + quadro + quadro
        return "RIFF".toByteArray() + u(corpo.size, 4) + corpo
    }

    @Test fun `as repeticoes da figurinha ficam no ANIM sem mexer nos quadros`() {
        val original = webp()
        assertEquals(0, WebpAnimado.ler(original).repeticoes)
        val tres = WebpAnimado.repetir(original, 3)
        assertEquals(3, WebpAnimado.ler(tres).repeticoes)
        assertEquals(original.size, tres.size)
        assertEquals(2, WebpAnimado.ler(tres).quadros.size)
        assertEquals(0, WebpAnimado.ler(WebpAnimado.repetir(tres, 0)).repeticoes)
    }

    @Test fun `as repeticoes atravessam o projeto da figurinha e valor estranho vira sem fim`() {
        val pasta = java.nio.file.Files.createTempDirectory("repeticoes").toFile()
        try {
            File(pasta, "principal.png").writeBytes(byteArrayOf(1))
            val principal = CamadaSalva("principal", "foto", TipoDaOrigem.IMAGEM, "principal.png", null, Edicao())
            Projetos.gravar(pasta, ProjetoSalvo(true, 10, 0.0, 0.0, 0, null, listOf("😀"), principal, emptyList(), repeticoes = 5))
            assertEquals(5, Projetos.ler(pasta)!!.repeticoes)
            val arquivo = File(pasta, Projetos.ARQUIVO)
            arquivo.writeText(arquivo.readText().replace("\"repeticoes\":5", "\"repeticoes\":7"))
            assertEquals(0, Projetos.ler(pasta)!!.repeticoes)
        } finally {
            pasta.deleteRecursively()
        }
    }
}
