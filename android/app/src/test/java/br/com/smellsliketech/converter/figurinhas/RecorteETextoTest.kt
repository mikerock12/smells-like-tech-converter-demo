package br.com.smellsliketech.converter.figurinhas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** O recorte em frações da mídia e o texto antigo (quadro de 512) virando objeto. */
class RecorteETextoTest {
    @Test fun `o recorte guarda a caixa em fracoes e mede o pedaco em pixels`() {
        val quadrado = Recorte(FormaDoRecorte.QUADRADO, listOf(0.25f, 0.1f, 0.75f, 0.9f))
        assertArrayEquals(floatArrayOf(0.25f, 0.1f, 0.75f, 0.9f), quadrado.caixa(), 1e-6f)
        assertTrue(quadrado.valido)
        // 50% de 1000 × 80% de 500.
        assertEquals(500 to 400, quadrado.medidas(1000, 500))

        val livre = Recorte(FormaDoRecorte.LIVRE, listOf(0.2f, 0.3f, 0.8f, 0.25f, 0.6f, 0.9f, 0.1f, 0.7f))
        assertArrayEquals(floatArrayOf(0.1f, 0.25f, 0.8f, 0.9f), livre.caixa(), 1e-6f)
        assertTrue(livre.valido)
    }

    @Test fun `recorte sem area ou com pontos de menos nao vale`() {
        assertFalse(Recorte(FormaDoRecorte.CIRCULO, listOf(0.5f, 0.5f, 0.51f, 0.51f)).valido)
        assertFalse(Recorte(FormaDoRecorte.LIVRE, listOf(0.1f, 0.1f, 0.9f, 0.9f)).valido)
        assertFalse(Recorte(FormaDoRecorte.QUADRADO, listOf(0.1f, 0.1f, 0.9f)).valido)
        // Pontos fora da mídia ficam na borda.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), Recorte(FormaDoRecorte.QUADRADO, listOf(-0.5f, -1f, 2f, 1.5f)).caixa(), 1e-6f)
    }

    @Test fun `texto antigo vai para onde aparecia, com o zoom e o deslocamento que tinha`() {
        // Embaixo: topo em 78% de 512 (399,4 px), uma linha de 80 px; o centro fica a 0,358 abaixo do meio.
        val embaixo = Projetos.textoAntigoComoObjeto(Edicao(tamanhoDoTexto = 64f, posicaoDoTexto = PosicaoDoTexto.BAIXO))
        assertEquals((399.36f + 40f) / 512f - 0.5f, embaixo.deslocamentoY, 1e-4f)
        val meio = Projetos.textoAntigoComoObjeto(Edicao(tamanhoDoTexto = 64f, posicaoDoTexto = PosicaoDoTexto.MEIO, zoom = 2f, deslocamentoY = 0.1f))
        assertEquals(0.1f + ((215.04f + 40f) / 512f - 0.5f) * 2f, meio.deslocamentoY, 1e-4f)
    }

    @Test fun `o projeto guarda recorte e disposicao do texto, e le os antigos sem eles`() {
        val edicao = Edicao(disposicaoDoTexto = DisposicaoDoTexto.SUBINDO,
            recorte = Recorte(FormaDoRecorte.CIRCULO, listOf(0.1f, 0.2f, 0.6f, 0.7f)))
        val lida = Projetos.edicaoDeJson(Projetos.edicaoParaJson(edicao))
        assertEquals(DisposicaoDoTexto.SUBINDO, lida.disposicaoDoTexto)
        assertEquals(FormaDoRecorte.CIRCULO, lida.recorte?.forma)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.6f, 0.7f), lida.recorte!!.caixa(), 1e-6f)

        val antiga = Projetos.edicaoDeJson(org.json.JSONObject().put("zoom", 1.5))
        assertEquals(DisposicaoDoTexto.HORIZONTAL, antiga.disposicaoDoTexto)
        assertNull(antiga.recorte)
    }

    @Test fun `camada de texto de projeto antigo e convertida ao ler`() {
        val pasta = Files.createTempDirectory("projeto-antigo").toFile()
        try {
            File(pasta, "principal.png").writeBytes(byteArrayOf(1))
            val texto = Projetos.edicaoParaJson(Edicao(texto = "Oi", posicaoDoTexto = PosicaoDoTexto.CIMA))
            File(pasta, Projetos.ARQUIVO).writeText(org.json.JSONObject()
                .put("versao", 3).put("animada", false).put("quadrosPorSegundo", 10).put("inicio", 0.0).put("fim", 0.0)
                .put("corDeFundo", 0).put("corFinalDoFundo", org.json.JSONObject.NULL).put("emojis", org.json.JSONArray(listOf("😀")))
                .put("principal", org.json.JSONObject().put("id", "principal").put("nome", "foto").put("tipo", "IMAGEM")
                    .put("arquivo", "principal.png").put("mascara", org.json.JSONObject.NULL).put("edicao", org.json.JSONObject()))
                .put("camadas", org.json.JSONArray().put(org.json.JSONObject().put("id", "t1").put("nome", "Texto")
                    .put("tipo", org.json.JSONObject.NULL).put("arquivo", org.json.JSONObject.NULL).put("mascara", org.json.JSONObject.NULL)
                    .put("texto", true).put("edicao", texto)))
                .toString())
            val projeto = Projetos.ler(pasta)!!
            // Em cima: topo em 6% (30,72 px) + meia linha de 40 px, acima do meio.
            assertEquals((30.72f + 40f) / 512f - 0.5f, projeto.camadas.single().edicao.deslocamentoY, 1e-4f)
        } finally { pasta.deleteRecursively() }
    }
}
