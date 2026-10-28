package br.com.smellsliketech.converter.estudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * As guias do estúdio do Android encaixam igual ao site e ao Windows: os casos de
 * packages/converter-core/guias-casos.json saem da regra em JS (node scripts/gerar-casos-das-guias.mjs).
 * Os testes rodam com a pasta do módulo (android/app).
 */
class GuiasDoEstudioTest {
    private val casos = JSONObject(File("../../packages/converter-core/guias-casos.json").readText(Charsets.UTF_8))

    private fun medidas(m: JSONObject) = GuiasDoEstudio.Medidas(m.getDouble("largura"), m.getDouble("altura"), m.getDouble("quadroW"), m.getDouble("quadroH"))

    @Test
    fun `encaixa como nos casos compartilhados`() {
        val lista = casos.getJSONArray("alinhar")
        for (i in 0 until lista.length()) {
            val caso = lista.getJSONObject(i)
            val nome = caso.getString("nome")
            val b = caso.getJSONObject("bruta")
            val bruta = Transformacao(
                x = b.optDouble("x", .5), y = b.optDouble("y", .5), escala = b.optDouble("escala", 1.0),
                giro = b.optDouble("giro", 0.0), giroX = b.optDouble("giroX", 0.0), giroY = b.optDouble("giroY", 0.0),
            )
            val refs = caso.getJSONArray("referencias").let { a ->
                (0 until a.length()).map { j -> a.getJSONObject(j).let { GuiasDoEstudio.Caixa(it.getDouble("esquerda"), it.getDouble("topo"), it.getDouble("direita"), it.getDouble("base")) } }
            }
            val r = GuiasDoEstudio.alinhar(bruta, medidas(caso.getJSONObject("medidas")), refs)
            val esperado = caso.getJSONObject("esperado")
            assertEquals(nome, esperado.getDouble("x"), r.transformacao.x, 1e-5)
            assertEquals(nome, esperado.getDouble("y"), r.transformacao.y, 1e-5)
            assertEquals(nome, esperado.getDouble("giro"), r.transformacao.giro, 1e-9)
            assertEquals(nome, esperado.getBoolean("reta"), r.reta)
            val guias = r.guias.sortedWith(compareBy({ if (it.vertical) 0 else 1 }, { it.posicao }))
                .map { "${it.vertical}|${Math.round(it.posicao * 1e6) / 1e6}|${it.tipo}" }
            val desejadas = esperado.getJSONArray("guias").let { a ->
                (0 until a.length()).map { j -> a.getJSONObject(j).let { "${it.getBoolean("vertical")}|${Math.round(it.getDouble("posicao") * 1e6) / 1e6}|${it.getString("tipo")}" } }
            }
            assertEquals(nome, desejadas, guias)
        }
    }

    @Test
    fun `preencher cobre e caber e centralizar voltam ao meio`() {
        val lista = casos.getJSONArray("preencher")
        for (i in 0 until lista.length()) {
            val caso = lista.getJSONObject(i)
            val cheia = GuiasDoEstudio.preencher(Transformacao(giro = 30.0, x = .1, opacidade = .5), medidas(caso.getJSONObject("medidas")))
            assertEquals(caso.getString("nome"), caso.getDouble("esperado"), cheia.escala, 1e-5)
            assertEquals(listOf(.5, .5, 0.0, .5), listOf(cheia.x, cheia.y, cheia.giro, cheia.opacidade))
        }
        val inteira = GuiasDoEstudio.caberInteira(Transformacao(escala = 2.4, giroX = 20.0))
        assertEquals(listOf(1.0, 0.0), listOf(inteira.escala, inteira.giroX))
        val meio = GuiasDoEstudio.centralizar(Transformacao(x = .1, y = .9, escala = 2.0, giro = 45.0))
        assertEquals(listOf(.5, .5, 2.0, 45.0), listOf(meio.x, meio.y, meio.escala, meio.giro))
    }
}
