package br.com.smellsliketech.converter.musica

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * A criação musical do Android tem de soar igual ao site e ao Windows: cada caso de
 * packages/converter-core/musica-casos traz o esperado calculado pela referência em JS
 * (node scripts/musica-casos.mjs), recalculado aqui em Kotlin.
 */
class MusicaCasosTest {
    private val pasta = File("../../packages/converter-core/musica-casos")

    private fun gerar(g: JSONObject, canais: Int, quadros: Long, taxa: Int): Array<FloatArray> = Array(canais) { c ->
        val x = FloatArray(quadros.toInt())
        val amplitude = g.optDouble("amplitude", 0.5)
        when (g.getString("tipo")) {
            "seno" -> {
                val f = if (c == 1 && g.has("frequencia2")) g.getDouble("frequencia2") else g.getDouble("frequencia")
                for (i in x.indices) x[i] = (amplitude * sin(2 * Math.PI * f * i / taxa)).toFloat()
            }
            "ruido" -> {
                val ruido = MasterDaMusica.Ruido(g.optInt("semente", 1) + c)
                for (i in x.indices) x[i] = (amplitude * (ruido.proximo() * 2 - 1)).toFloat()
            }
            "impulsos" -> {
                var i = g.optInt("inicio", 0)
                while (i < x.size) { x[i] = amplitude.toFloat(); i += g.getInt("periodo") }
            }
        }
        x
    }

    private fun fnv(bytes: ByteArray): Long {
        var h = 0x811c9dc5.toInt()
        for (b in bytes) { h = h xor (b.toInt() and 0xff); h *= 0x01000193 }
        return h.toLong() and 0xffffffffL
    }

    private fun perto(esperado: Any, real: Double, oque: String) {
        if (esperado is String) {
            assertTrue("$oque: esperado $esperado, veio $real", esperado == "-inf" && real == Double.NEGATIVE_INFINITY)
            return
        }
        val e = (esperado as Number).toDouble()
        assertTrue("$oque: esperado $e, veio $real", abs(e - real) <= 2e-6 + 1e-7 * abs(e))
    }

    private fun resumo(esperado: JSONObject, esq: FloatArray, dir: FloatArray, oque: String) {
        var soma = 0.0; var energia = 0.0; var pico = 0.0
        for (i in esq.indices) {
            soma += esq[i].toDouble() + dir[i]
            energia += esq[i].toDouble() * esq[i] + dir[i].toDouble() * dir[i]
            pico = max(pico, max(abs(esq[i].toDouble()), abs(dir[i].toDouble())))
        }
        perto(esperado.get("soma"), soma, "$oque soma")
        perto(esperado.get("energia"), energia, "$oque energia")
        perto(esperado.get("pico"), pico, "$oque pico")
        val amostras = esperado.getJSONArray("amostras")
        for (k in 0 until amostras.length()) {
            val a = amostras.getJSONArray(k)
            val i = a.getInt(0)
            perto(a.get(1), esq[i].toDouble(), "$oque L[$i]")
            perto(a.get(2), dir[i].toDouble(), "$oque R[$i]")
        }
    }

    private fun textos(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun `casos compartilhados batem com a referencia`() {
        val casos = pasta.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        assertTrue("casos encontrados", casos.size >= 12)
        for (arquivo in casos) {
            val caso = JSONObject(arquivo.readText())
            val esperado = caso.getJSONObject("esperado")
            caso.optJSONObject("projeto")?.let { conferirProjeto(caso, ProjetoMusical.deJson(it), esperado, arquivo.name) }
            caso.optJSONObject("latencia")?.let { l ->
                val quadros = l.getInt("quadros"); val atraso = l.getInt("atraso")
                val pulsos = (0 until l.getJSONArray("pulsos").length()).map { l.getJSONArray("pulsos").getLong(it) }
                val gravado = FloatArray(quadros)
                for (p in pulsos) for (i in 0 until 48) if (p + atraso + i < quadros) gravado[(p + atraso + i).toInt()] = (0.6 * exp(-i / 8.0) * (if (i % 2 == 1) -1 else 1)).toFloat()
                assertEquals(arquivo.name, esperado.getLong("latencia"), Musica.medirLatencia(gravado, pulsos, l.getInt("taxa")))
            }
            caso.optJSONArray("arquivo")?.let { listas ->
                val resultados = esperado.getJSONArray("arquivo")
                for (i in 0 until listas.length()) {
                    val l = listas.getJSONArray(i)
                    val entradas = (0 until l.length()).map { val e = l.getJSONObject(it); EntradaDoArquivo(e.getString("nome"), e.getLong("tamanho"), e.getLong("comprimido")) }
                    assertEquals(if (resultados.isNull(i)) null else resultados.getString(i), ArquivoDaMusica.conferirEntradas(entradas))
                }
            }
            caso.optJSONObject("separacao")?.let { conferirSeparacao(it, esperado.getJSONObject("separacao")) }
            caso.optJSONObject("reducaoDeRuido")?.let { r ->
                val comprimento = r.getInt("comprimento")
                val g = r.getJSONObject("gerador")
                val original = gerar(g, g.optInt("canais", 1), comprimento.toLong(), 48000)
                val saida = arrayOf(FloatArray(comprimento), FloatArray(comprimento))
                SeparacaoDaMusica.reduzirRuido(comprimento.toLong(), original.size,
                    { c, inicio, n, d -> System.arraycopy(original[minOf(c, original.size - 1)], inicio.toInt(), d, 0, n) },
                    { c, inicio, a, n -> System.arraycopy(a, 0, saida[c], inicio.toInt(), n) })
                resumo(esperado.getJSONObject("reducaoDeRuido"), saida[0], saida[1], "ruído")
            }
        }
    }

    private fun conferirProjeto(caso: JSONObject, p: ProjetoMusical, esperado: JSONObject, nome: String) {
        val problemas = Musica.problemas(p)
        assertEquals(nome, textos(esperado.getJSONArray("problemas")), problemas)
        if (problemas.isNotEmpty()) return
        assertEquals(nome, esperado.getLong("duracao"), Musica.duracaoEmQuadros(p))
        assertEquals(nome, esperado.getLong("cauda"), MisturadorDaMusica.caudaEmQuadros(p))
        assertEquals(nome, textos(esperado.getJSONArray("audiveis")), Musica.faixasAudiveis(p))
        val ganhos = esperado.getJSONArray("ganhos")
        p.faixas.forEachIndexed { i, f ->
            val (e, d) = Musica.ganhosEstereo(f.ganhoDb, f.pan, f.ajusteEsquerdoDb, f.ajusteDireitoDb)
            perto(ganhos.getJSONArray(i).get(0), e, "ganho L ${f.id}")
            perto(ganhos.getJSONArray(i).get(1), d, "ganho R ${f.id}")
        }
        caso.optJSONArray("posicoes")?.let { pos ->
            assertEquals(textos(esperado.getJSONArray("posicoes")), (0 until pos.length()).map { Musica.posicao(p, pos.getLong(it)).texto })
        }
        caso.optJSONObject("metronomo")?.let { m ->
            val cliques = Musica.cliquesDoMetronomo(p, m.getLong("inicio"), m.getLong("fim"))
            val e = esperado.getJSONArray("metronomo")
            assertEquals((0 until e.length()).map { Clique(e.getJSONObject(it).getLong("quadro"), e.getJSONObject(it).getBoolean("forte")) }, cliques)
        }
        caso.optJSONObject("gravacao")?.let { g ->
            val plano = Musica.planoDaGravacao(p, g.getLong("cursor"))
            val e = esperado.getJSONObject("gravacao")
            val ep = e.getJSONObject("plano")
            assertEquals(ep.getLong("inicioDoTransporte"), plano.inicioDoTransporte)
            assertEquals(ep.getLong("inicioDaTomada"), plano.inicioDaTomada)
            assertEquals(if (ep.isNull("fimDaTomada")) null else ep.getLong("fimDaTomada"), plano.fimDaTomada)
            assertEquals(ep.getLong("preRolagem"), plano.preRolagem)
            val cc = ep.getJSONArray("cliquesDaContagem")
            assertEquals((0 until cc.length()).map { Clique(cc.getJSONObject(it).getLong("quadro"), cc.getJSONObject(it).getBoolean("forte")) }, plano.cliquesDaContagem)
            val clipe = Musica.clipeDaTomada(p, "tomada-1", "gravacao-1", g.getLong("quadroAlinhado"), g.getLong("quadrosCapturados"), plano)!!
            val ec = e.getJSONObject("clipe")
            assertEquals(ec.getLong("inicio"), clipe.inicio)
            assertEquals(ec.getLong("inicioNaMidia"), clipe.inicioNaMidia)
            assertEquals(ec.getLong("duracao"), clipe.duracao)
        }
        val renders = caso.optJSONArray("renders") ?: return
        val geradores = caso.getJSONObject("midias")
        val fontes = p.midias.filter { geradores.has(it.id) }.associate { it.id to (FonteNaMemoria(gerar(geradores.getJSONObject(it.id), it.canais, it.quadros, p.taxa)) as FonteDeAudio) }
        val esperados = esperado.getJSONArray("renders")
        for (i in 0 until renders.length()) {
            val pedido = renders.getJSONObject(i)
            val somente = if (pedido.has("somente") && !pedido.isNull("somente")) pedido.getString("somente") else null
            val (esq, dir) = MisturadorDaMusica.renderizar(p, fontes, pedido.optLong("inicio", 0), pedido.getInt("quadros"), somente, metronomo = pedido.optBoolean("metronomo", false))
            val er = esperados.getJSONObject(i)
            val oque = "$nome render $i"
            resumo(er, esq, dir, oque)
            er.optJSONObject("medidas")?.let { m ->
                val medidas = MasterDaMusica.medir(esq, dir, p.taxa)
                perto(m.get("lufs"), medidas.lufs, "$oque lufs")
                perto(m.get("amostraDb"), medidas.amostraDb, "$oque pico")
                perto(m.get("realDb"), medidas.realDb, "$oque pico real")
            }
            er.optJSONObject("master")?.let { m ->
                val r = MasterDaMusica.masterizar(esq, dir, p.taxa, pedido.getString("master"))
                perto(m.get("ganhoDb"), r.ganhoDb, "$oque ganho do master")
                perto(m.get("lufs"), r.depois.lufs, "$oque lufs do master")
                perto(m.get("realDb"), r.depois.realDb, "$oque pico do master")
                resumo(m, r.esq, r.dir, "$oque master")
            }
            er.optJSONArray("wav")?.let { w ->
                val bits = pedido.getJSONArray("wav")
                for (k in 0 until bits.length()) assertEquals("$oque wav ${bits.getInt(k)}", w.getLong(k), fnv(MasterDaMusica.codificarWav(listOf(esq, dir), p.taxa, bits.getInt(k))))
            }
        }
    }

    private fun falsas(entrada: FloatArray, t: Int, bandas: Int, instrumentos: Int): FloatArray {
        val saida = FloatArray(instrumentos * entrada.size)
        for (i in 0 until instrumentos) for (c in 0 until 2) for (f in 0 until t) for (k in 0 until bandas) {
            val j = (c * t + f) * bandas + k
            val peso = 1 + (i + 1) * ((k * 7 + f * 3 + c) % 11) / 10.0
            saida[i * entrada.size + j] = (entrada[j] * peso).toFloat()
        }
        return saida
    }

    private fun conferirSeparacao(s: JSONObject, esperado: JSONObject) {
        val comprimento = s.getInt("comprimento")
        val g = s.getJSONObject("gerador")
        val original = gerar(g, g.optInt("canais", 1), comprimento.toLong(), SeparacaoDaMusica.TAXA)
        val modo = SeparacaoDaMusica.modo(s.getString("modo"))!!
        val saidas = modo.faixas.map { arrayOf(FloatArray(comprimento), FloatArray(comprimento)) }
        runBlocking {
            SeparacaoDaMusica.separar(comprimento.toLong(), original.size,
                { c, inicio, n, d -> System.arraycopy(original[minOf(c, original.size - 1)], inicio.toInt(), d, 0, n) },
                { falsas(it, SeparacaoDaMusica.QUADROS_POR_TRECHO, SeparacaoDaMusica.BANDAS, 4) },
                { f, c, inicio, a, n -> System.arraycopy(a, 0, saidas[f][c], inicio.toInt(), n) }, modo.id)
        }
        assertEquals(esperado.getInt("trechos"), ProcessadorEspectral(comprimento.toLong(), 4096, 1024, 512, 1).trechos)
        val faixas = esperado.getJSONArray("faixas")
        for (i in 0 until faixas.length()) {
            assertEquals(faixas.getJSONObject(i).getString("id"), modo.faixas[i].id)
            resumo(faixas.getJSONObject(i), saidas[i][0], saidas[i][1], "separação ${modo.faixas[i].id}")
        }
    }

    @Test fun `o sltmusic salva e abre de volta, e recusa entrada fora da pasta`() {
        val pasta = Files.createTempDirectory("musica").toFile()
        try {
            val wav = File(pasta, "voz.wav").apply { writeBytes(MasterDaMusica.codificarWav(listOf(FloatArray(480) { 0.1f }), 48000, 32)) }
            var p = Musica.projetoDoModelo("violao-e-voz", "teste", "Teste")
            p = p.copy(midias = listOf(MidiaMusical("voz-1", "voz.wav", "wav", 48000, 1, 480)), faixas = p.faixas.mapIndexed { i, f -> if (i == 0) f.copy(clipes = listOf(ClipeMusical("c1", "voz-1", 0, 0, 480))) else f })
            val zip = ByteArrayOutputStream().also { ArquivoDaMusica.salvar(p, { wav }, it) }.toByteArray()
            val destino = File(pasta, "aberto")
            val aberto = ArquivoDaMusica.abrir(ByteArrayInputStream(zip), destino)
            assertEquals(p.paraJson().toString(), aberto.paraJson().toString())
            assertEquals(480, MasterDaMusica.lerWav(File(destino, "midias/voz-1.wav").readBytes()).canais[0].size)
            val ruim = ByteArrayOutputStream().also { out ->
                java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../fora.wav")); z.write(1); z.closeEntry() }
            }.toByteArray()
            val erro = runCatching { ArquivoDaMusica.abrir(ByteArrayInputStream(ruim), File(pasta, "ruim")) }.exceptionOrNull()
            assertTrue(erro?.message.orEmpty(), erro?.message.orEmpty().contains("não permitida"))
            assertNull(Musica.problemas(aberto).firstOrNull())
        } finally {
            pasta.deleteRecursively()
        }
    }
}
