package br.com.smellsliketech.converter.musica

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O restaurador e o tratamento do pacote têm as mesmas regras no Android, no site e no Windows:
 * restaurador-casos.json e musica-pacote-casos.json trazem o esperado calculado em JS.
 */
class RestauradorDeAudioTest {
    private fun textos(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test
    fun planoBateComOJs() {
        val casos = JSONArray(File("../../packages/converter-core/restaurador-casos.json").readText())
        for (i in 0 until casos.length()) {
            val caso = casos.getJSONObject(i)
            val nome = caso.getString("nome")
            val d = caso.getJSONObject("diagnostico").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }
            val plano = RestauradorDeAudio.planejar(d, RestauradorDeAudio.lerOpcoes(caso.getJSONObject("opcoes").toString()))
            val e = caso.getJSONObject("esperado")
            assertEquals(nome, e.getString("perfil"), plano.perfil)
            assertEquals(nome, textos(e.getJSONArray("etapas")), plano.etapas.map { it.first })
            assertEquals(nome, textos(e.getJSONArray("avisos")), plano.avisos)
            assertEquals(nome, e.getBoolean("semMudanca"), plano.semMudanca)
            assertEquals(nome, textos(e.getJSONArray("recomendacoes")), plano.avaliacao.recomendacoes)
            val p = e.getJSONObject("parametros")
            for (k in p.keys()) assertEquals("$nome: $k", p.getDouble(k), plano.parametros.getValue(k), 1e-4)
            if (e.isNull("ia")) assertEquals(nome, null, plano.ia)
            else {
                assertEquals(nome, e.getJSONObject("ia").getDouble("mistura"), plano.ia!!.mistura, 1e-4)
                assertEquals(nome, e.getJSONObject("ia").getDouble("corteHz"), plano.ia!!.corteHz, 1e-4)
            }
        }
    }

    @Test
    fun tratamentoDoPacoteBateComOJs() {
        val caso = JSONObject(File("../../packages/converter-core/musica-pacote-casos.json").readText())
        val entradas = caso.getJSONObject("entrada").getJSONArray("tratamentos")
        val esperados = caso.getJSONObject("esperado").getJSONArray("tratamentos")
        for (i in 0 until entradas.length()) {
            val t = TratamentoDoPacote.deJson(entradas.optJSONObject(i))
            val e = esperados.getJSONObject(i)
            assertEquals(e.getBoolean("refinarAgudos"), t.refinarAgudos)
            assertEquals(e.getBoolean("recuperarBateria"), t.recuperarBateria)
            assertEquals(e.getBoolean("reduzirRuido"), t.reduzirRuido)
            assertEquals(e.getInt("restauracao"), t.restauracao)
            assertEquals(e.getBoolean("aceleracao"), t.aceleracao)
        }
        val pedido = PacoteDeFaixas.lerPedido("""{"modelo":"roformer","tratamento":{"refinarAgudos":true,"restauracao":40}}""")
        assertTrue(pedido.tratamento.refinarAgudos)
        assertEquals(40, PacoteDeFaixas.lerPedido(pedido.paraJson().toString()).tratamento.restauracao)
    }

    @Test
    fun opcoesENomes() {
        val o = RestauradorDeAudio.lerOpcoes("""{"perfil":"live","intensidade":-3,"formato":"ogg","ia":true}""")
        assertEquals("live", o.perfil)
        assertEquals(0, o.intensidade)
        assertEquals("wav-24", o.formato)
        assertTrue(o.ia)
        assertEquals("show ao vivo-restaurado.flac", RestauradorDeAudio.nomeDoRestaurado("show ao vivo.mp3", "flac"))
    }
}
