package br.com.smellsliketech.converter.estudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O estúdio do Android tem de chegar ao mesmo resultado do site e do Windows: cada caso de
 * packages/converter-core/estudio-casos traz o projeto e o esperado calculado pela referência
 * em JS (node scripts/estudio-casos.mjs). Os testes rodam com a pasta do módulo (android/app).
 */
class EstudioTest {
    private val raiz = File("../../packages/converter-core")
    private val presets = Preset.lista(File(raiz, "estudio-presets.json").readText())

    @Test fun `casos compartilhados batem com a referencia`() {
        val casos = File(raiz, "estudio-casos").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue("casos encontrados", casos.size >= 7)
        for (arquivo in casos) {
            val caso = JSONObject(arquivo.readText())
            val projeto = Projeto.deJson(caso.getJSONObject("projeto"))
            val esperado = caso.getJSONObject("esperado")
            val nome = arquivo.name
            assertEquals(nome, esperado.getDouble("duracao"), Estudio.duracaoTotal(projeto), 0.0)
            val segmentos = esperado.getJSONArray("segmentos")
            assertEquals(nome, segmentos.length(), Estudio.segmentos(projeto).size)
            Estudio.segmentos(projeto).forEachIndexed { i, s ->
                val e = segmentos.getJSONObject(i)
                assertEquals(nome, Segmento(e.getInt("indice"), e.getString("id"), e.getDouble("inicio"), e.getDouble("fim"), e.getDouble("duracao")), s)
            }
            assertEquals(nome, textos(esperado.getJSONArray("problemas")), Estudio.problemas(projeto))
            assertEquals(nome, textos(esperado.getJSONArray("avisos")), Estudio.avisos(projeto, presets))
            val gif = esperado.getJSONObject("gif")
            assertEquals(nome, gif.getInt("largura") to gif.getInt("altura"), Estudio.tamanhoDoGif(projeto.largura, projeto.altura))
            if (esperado.isNull("receita")) {
                assertTrue(nome, Estudio.problemas(projeto).isNotEmpty())
                continue
            }
            val receita = esperado.getJSONObject("receita")
            val entradas = caso.getJSONObject("entradas")
            val clipes = entradas.getJSONArray("clipes")
            val calculada = Estudio.receita(
                projeto,
                EntradasDaReceita(
                    (0 until clipes.length()).map { EntradaDeClipe(clipes.getJSONObject(it).getString("caminho"), clipes.getJSONObject(it).getBoolean("temAudio")) },
                    textos(entradas.getJSONArray("textos")),
                    textos(entradas.getJSONArray("audios")),
                ),
            )
            assertEquals(nome, textos(receita.getJSONArray("args")), calculada.args)
            assertEquals(nome, receita.getString("filtro"), calculada.filtro)
            assertEquals(nome, textos(receita.getJSONArray("mapas")), calculada.mapas)
            assertEquals(nome, receita.getDouble("duracao"), calculada.duracao, 0.0)
        }
    }

    @Test fun `presets vem do json compartilhado e a copia dos assets e identica`() {
        assertEquals(17, presets.size)
        assertEquals(180.0, Estudio.preset(presets, "instagram-reels")!!.duracaoMaxima!!, 0.0)
        assertNull(Estudio.preset(presets, "youtube")!!.duracaoMaxima)
        val copia = File("src/main/assets/estudio/estudio-presets.json").readText().replace("\r\n", "\n")
        assertEquals("copie packages/converter-core/estudio-presets.json para os assets", File(raiz, "estudio-presets.json").readText().replace("\r\n", "\n"), copia)
    }

    @Test fun `numeros retangulos e projeto novo como no js`() {
        assertEquals("10", Estudio.numero(10.0))
        assertEquals("2.5", Estudio.numero(2.5))
        assertEquals("0.333", Estudio.numero(1.0 / 3))
        assertEquals("7.79", Estudio.numero(8.123 - 0.333))
        assertEquals("2 min 20 s", Estudio.duracaoLegivel(140.0))
        assertEquals(listOf(0.0, 656.0, 1080.0, 608.0), Estudio.retanguloDoAjuste("caber", 1280.0, 720.0, 1080, 1920))
        assertEquals(listOf(-1167.0, 0.0, 3414.0, 1920.0), Estudio.retanguloDoAjuste("preencher", 1280.0, 720.0, 1080, 1920))
        val proprio = Estudio.novoProjeto(presets, "personalizado", "video", 721, 405)
        assertEquals(722 to 406, proprio.largura to proprio.altura)
    }

    @Test fun `projeto volta igual do json`() {
        val caso = JSONObject(File(raiz, "estudio-casos/01-reels-completo.json").readText())
        val projeto = Projeto.deJson(caso.getJSONObject("projeto"))
        assertEquals(projeto, Projeto.deJson(JSONObject(projeto.paraJson().toString())))
    }

    private fun textos(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
}
