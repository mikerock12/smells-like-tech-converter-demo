package br.com.smellsliketech.converter.musica

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O pacote de faixas do conversor tem as mesmas regras no Android, no site e no Windows:
 * musica-pacote-casos.json traz o esperado calculado em JS (node scripts/musica-pacote-casos.mjs).
 */
class PacoteDeFaixasTest {
    private val caso = JSONObject(File("../../packages/converter-core/musica-pacote-casos.json").readText())
    private val entrada = caso.getJSONObject("entrada")
    private val esperado = caso.getJSONObject("esperado")

    private fun textos(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun grupos(a: JSONArray) = (0 until a.length()).map { GrupoDoPacote.deJson(a.getJSONObject(it)) }

    @Test
    fun combinacoesNomesEProblemasBatemComOJs() {
        for (modelo in textos(entrada.getJSONArray("modelos"))) {
            val esperadas = esperado.getJSONObject("combinacoes").getJSONArray(modelo)
            val combos = PacoteDeFaixas.combinacoes(modelo)
            assertEquals(esperadas.length(), combos.size)
            combos.forEachIndexed { i, c ->
                val e = esperadas.getJSONObject(i)
                assertEquals(e.getString("id"), c.id)
                assertEquals(grupos(e.getJSONArray("grupos")), c.grupos)
            }
            val video = esperado.getJSONObject("video").getJSONObject(modelo)
            for (manter in listOf("voz", "resto")) assertEquals(GrupoDoPacote.deJson(video.getJSONObject(manter)), PacoteDeFaixas.grupoDoVideo(modelo, manter))
        }
        val nomesDeGrupo = textos(esperado.getJSONArray("nomesDeGrupo"))
        val casosDeNome = entrada.getJSONArray("nomesDeGrupo")
        for (k in 0 until casosDeNome.length()) {
            val c = casosDeNome.getJSONObject(k)
            assertEquals(nomesDeGrupo[k], PacoteDeFaixas.nomeDoGrupo(textos(c.getJSONArray("faixas")), PacoteDeFaixas.modelo(c.getString("modelo"))))
        }
        val nomes = entrada.getJSONObject("nomes")
        assertEquals(textos(esperado.getJSONArray("nomes")), PacoteDeFaixas.nomesDosArquivos(nomes.getString("titulo"), grupos(nomes.getJSONArray("grupos")), nomes.getString("extensao")))
        val problemas = esperado.getJSONArray("problemas")
        val casos = entrada.getJSONArray("problemas")
        for (k in 0 until casos.length()) {
            val c = casos.getJSONObject(k)
            assertEquals(textos(problemas.getJSONArray(k)), PacoteDeFaixas.problemas(c.getString("modelo"), grupos(c.getJSONArray("grupos"))))
        }
    }

    @Test
    fun oArquivoMisturaIgualAoJs() {
        val a = entrada.getJSONObject("arquivo")
        val taxa = a.getInt("taxa")
        val quadros = a.getLong("quadros")
        val ajustes = a.getJSONObject("ajustes")
        val base = PacoteDeFaixas.configuracao(a.getString("modelo"), taxa)
        val master = a.getJSONArray("master")
        val configuracao = base.copy(
            faixas = base.faixas.map { f ->
                val ajuste = ajustes.optJSONObject(f.id) ?: return@map f
                val efeitos = ajuste.getJSONArray("efeitos")
                f.copy(ganhoDb = ajuste.getDouble("ganhoDb"), pan = ajuste.getDouble("pan"), efeitos = (0 until efeitos.length()).map { EfeitoMusical.deJson(efeitos.getJSONObject(it)) })
            },
            master = base.master.copy(efeitos = (0 until master.length()).map { EfeitoMusical.deJson(master.getJSONObject(it)) }),
        )
        val projeto = PacoteDeFaixas.projetoDoArquivo(configuracao, GrupoDoPacote.deJson(a.getJSONObject("grupo")), quadros)
        val resumo = esperado.getJSONObject("arquivo")
        val faixas = resumo.getJSONArray("faixas")
        assertEquals((0 until faixas.length()).map { faixas.getJSONObject(it).getString("id") }, projeto.faixas.map { it.id })
        projeto.faixas.forEachIndexed { i, f ->
            val e = faixas.getJSONObject(i)
            assertEquals(e.getString("nome"), f.nome)
            assertEquals(e.getDouble("ganhoDb"), f.ganhoDb, 0.0)
            assertEquals(textos(e.getJSONArray("efeitos")), f.efeitos.map { it.tipo })
            assertEquals(e.getJSONArray("clipes").getJSONArray(0).getLong(2), f.clipes[0].duracao)
        }
        assertEquals(textos(resumo.getJSONArray("master")), projeto.master.efeitos.map { it.tipo })
        val geradores = a.getJSONObject("geradores")
        val casos = MusicaCasosTest()
        val fontes = geradores.keys().asSequence().associateWith { id ->
            val g = geradores.getJSONObject(id)
            FonteNaMemoria(casos.gerar(g, g.getInt("canais"), quadros, taxa)) as FonteDeAudio
        }
        val (esq, dir) = MisturadorDaMusica.renderizar(projeto, fontes, 0, quadros.toInt(), null)
        casos.resumo(esperado.getJSONObject("render"), esq, dir, "pacote")
    }

    @Test
    fun oPedidoChegaNormalizado() {
        val configuracao = PacoteDeFaixas.configuracao("spleeter")
        val json = JSONObject().put("modelo", "spleeter").put("configuracao", configuracao.paraJson())
            .put("grupos", JSONArray().put(JSONObject().put("nome", " Base ").put("faixas", JSONArray(listOf("outros", "bateria"))))).toString()
        val pedido = PacoteDeFaixas.lerPedido(json)
        assertEquals(listOf(GrupoDoPacote("Base", listOf("bateria", "outros"))), pedido.grupos)
        assertEquals("wav-24", pedido.formato)
        assertEquals(listOf("vozes"), PacoteDeFaixas.lerPedido("""{"modelo":"roformer","manter":"voz"}""").grupos.single().faixas)
        assertTrue(assertThrows(ProblemaNoPacote::class.java) { PacoteDeFaixas.lerPedido("{") }.message!!.contains("corrompidas"))
        val outro = JSONObject().put("modelo", "roformer").put("configuracao", configuracao.paraJson()).toString()
        assertTrue(assertThrows(ProblemaNoPacote::class.java) { PacoteDeFaixas.lerPedido(outro) }.message!!.contains("não correspondem"))
        assertEquals(0.8, PacoteDeFaixas.fracao("mixando", 0.0), 1e-9)
    }
}
