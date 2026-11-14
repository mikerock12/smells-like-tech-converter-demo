package br.com.smellsliketech.converter.musica

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer

/** Um modelo de separação do pacote e as faixas que ele entrega, na ordem. */
data class ModeloDoPacote(val id: String, val rotulo: String, val resumo: String, val modo: String, val faixas: List<String>)

/** Um arquivo do pacote: o nome e as faixas separadas que ele junta. */
data class GrupoDoPacote(val nome: String, val faixas: List<String>) {
    fun paraJson(): JSONObject = JSONObject().put("nome", nome).put("faixas", JSONArray(faixas))

    companion object {
        fun deJson(j: JSONObject) = GrupoDoPacote(j.optString("nome"), j.optJSONArray("faixas")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList())
    }
}

data class CombinacaoPronta(val id: String, val rotulo: String, val grupos: List<GrupoDoPacote>)

/** O pedido inteiro, no mesmo JSON do site e do Windows. */
data class PedidoDoPacote(
    val modelo: String,
    val grupos: List<GrupoDoPacote>,
    val configuracao: ProjetoMusical,
    val formato: String = "wav-24",
    val master: String = "sem-alvo",
    val manter: String? = null,
) {
    fun paraJson(): JSONObject = JSONObject().put("versao", PacoteDeFaixas.VERSAO).put("modelo", modelo)
        .put("grupos", JSONArray(grupos.map { it.paraJson() })).put("configuracao", configuracao.paraJson())
        .put("formato", formato).put("master", master).putOpt("manter", manter)
}

class ProblemaNoPacote(mensagem: String) : IllegalArgumentException(mensagem)

/**
 * Separar faixas no conversor (experimental): as regras, sem áudio. Espelho de
 * packages/converter-core/musica-pacote.mjs, conferido por musica-pacote-casos.json.
 */
object PacoteDeFaixas {
    const val VERSAO = 1
    const val MAXIMO_DE_ARQUIVOS = 12

    private val ROTULOS = mapOf("vozes" to "Voz", "bateria" to "Bateria", "baixo" to "Baixo", "guitarra" to "Guitarra", "piano" to "Piano", "outros" to "Outros")

    val MODELOS = listOf(
        ModeloDoPacote("spleeter", "Spleeter · 4 faixas", "Voz, bateria, baixo e outros. O mais rápido; guitarra e piano ficam em “outros”.", "4",
            listOf("vozes", "bateria", "baixo", "outros")),
        ModeloDoPacote("roformer", "RoFormer · 6 faixas (experimental)", "Voz, bateria, baixo, guitarra, piano e resíduo. Bem mais lento e pede mais memória.", "6",
            listOf("vozes", "bateria", "baixo", "guitarra", "piano", "outros")),
    )

    fun modelo(id: String?) = MODELOS.firstOrNull { it.id == id }

    fun rotulo(id: String) = ROTULOS[id] ?: id

    private fun menos(modelo: ModeloDoPacote, vararg tirar: String) = modelo.faixas.filterNot { it in tirar }

    fun combinacoes(modeloId: String): List<CombinacaoPronta> {
        val modelo = modelo(modeloId) ?: return emptyList()
        val lista = mutableListOf(
            Triple("separadas", "Cada faixa num arquivo", modelo.faixas.map { listOf(it) }),
            Triple("karaoke", "Sem a voz (playback)", listOf(menos(modelo, "vozes"))),
            Triple("voz", "Só a voz", listOf(listOf("vozes"))),
            Triple("voz-e-base", "Voz num arquivo, o resto no outro", listOf(listOf("vozes"), menos(modelo, "vozes"))),
            Triple("sem-bateria", "Sem a bateria (para tocar junto)", listOf(menos(modelo, "bateria"))),
            Triple("sem-baixo", "Sem o baixo (para tocar junto)", listOf(menos(modelo, "baixo"))),
            Triple("voz-baixo-e-bateria", "Voz e baixo juntos, bateria à parte", listOf(listOf("vozes", "baixo"), listOf("bateria"))),
        )
        if ("guitarra" in modelo.faixas) {
            lista += Triple("sem-guitarra", "Sem a guitarra (para tocar junto)", listOf(menos(modelo, "guitarra")))
            lista += Triple("sem-piano", "Sem o piano (para tocar junto)", listOf(menos(modelo, "piano")))
            lista += Triple("cordas-e-ritmo", "Guitarra e baixo juntos, voz e bateria no outro", listOf(listOf("guitarra", "baixo"), listOf("vozes", "bateria")))
        }
        return lista.map { (id, rotulo, grupos) -> CombinacaoPronta(id, rotulo, grupos.map { GrupoDoPacote(nomeDoGrupo(it, modelo), it) }) }
    }

    fun nomeDoGrupo(faixas: Collection<String>, modelo: ModeloDoPacote?): String {
        val todas = modelo?.faixas ?: emptyList()
        val ordenadas = if (todas.isNotEmpty()) todas.filter { it in faixas } else faixas.toList()
        if (ordenadas.isEmpty()) return "Vazio"
        if (todas.isNotEmpty() && ordenadas.size == todas.size) return "Tudo"
        val faltam = todas.filterNot { it in faixas }
        if (todas.size > 2 && faltam.size == 1) return "Sem ${rotulo(faltam[0]).lowercase()}"
        val nomes = ordenadas.mapIndexed { i, f -> if (i == 0) rotulo(f) else rotulo(f).lowercase() }
        return if (nomes.size == 1) nomes[0] else "${nomes.dropLast(1).joinToString(", ")} e ${nomes.last()}"
    }

    /** O projeto dos ajustes: uma faixa por faixa separada, sem clipes; o master vale para todos os arquivos. */
    fun configuracao(modeloId: String, taxa: Int = 44100, titulo: String = "Faixas separadas"): ProjetoMusical {
        val modelo = modelo(modeloId) ?: throw ProblemaNoPacote("Modelo desconhecido: $modeloId")
        return Musica.novoProjeto("pacote", titulo, taxa).copy(
            metronomo = Metronomo(ligado = false, naExportacao = false),
            faixas = modelo.faixas.mapIndexed { i, f -> Musica.novaFaixa(f, rotulo(f), i) },
        )
    }

    fun problemas(modeloId: String?, grupos: List<GrupoDoPacote>?): List<String> {
        val modelo = modelo(modeloId) ?: return listOf("Escolha o modelo de separação.")
        if (grupos.isNullOrEmpty()) return listOf("Escolha pelo menos um arquivo para gerar.")
        if (grupos.size > MAXIMO_DE_ARQUIVOS) return listOf("No máximo $MAXIMO_DE_ARQUIVOS arquivos por pacote.")
        val problemas = mutableListOf<String>()
        val nomes = HashSet<String>()
        grupos.forEachIndexed { i, g ->
            val rotulo = "Arquivo ${i + 1}"
            if (g.faixas.isEmpty()) problemas += "$rotulo: escolha pelo menos uma faixa."
            else {
                val estranhas = g.faixas.filterNot { it in modelo.faixas }
                if (estranhas.isNotEmpty()) problemas += "$rotulo: ${estranhas.joinToString(", ")} não existe no ${modelo.rotulo}."
                if (g.faixas.toSet().size != g.faixas.size) problemas += "$rotulo: a mesma faixa aparece duas vezes."
            }
            val nome = g.nome.trim().lowercase()
            if (nome.isEmpty()) problemas += "$rotulo: dê um nome ao arquivo."
            else if (nome in nomes) problemas += "$rotulo: já existe outro arquivo chamado “${g.nome}”."
            nomes += nome
        }
        return problemas
    }

    /** O projeto que mistura um arquivo: as faixas do grupo, cada uma tocando a faixa separada inteira. */
    fun projetoDoArquivo(configuracao: ProjetoMusical, grupo: GrupoDoPacote, quadros: Long): ProjetoMusical {
        val faixas = configuracao.faixas.filter { it.id in grupo.faixas }.map {
            it.copy(mudo = false, solo = false, gravar = false, monitorar = false, saida = "master", envios = emptyList(),
                clipes = listOf(ClipeMusical("clipe-${it.id}", it.id, 0, 0, quadros)))
        }
        return configuracao.copy(
            faixas = faixas,
            barramentos = emptyList(),
            midias = faixas.map { MidiaMusical(it.id, "${it.id}.wav", "wav", configuracao.taxa, 2, quadros, 32) },
            metronomo = configuracao.metronomo.copy(ligado = false, naExportacao = false),
            loop = Loop(), punch = Punch(), marcadores = emptyList(), separacoes = emptyList(),
        )
    }

    /** Como o `normalize("NFD").replace(/[^\w\s-]/g, "")` do JS: sem acento e sem pontuação, com hífens. */
    private fun slug(texto: String): String {
        val limpo = Normalizer.normalize(texto, Normalizer.Form.NFD).filter { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '_' || it == '-' || it.isWhitespace() }
        return limpo.trim().replace(Regex("\\s+"), "-").lowercase()
    }

    fun nomesDosArquivos(titulo: String, grupos: List<GrupoDoPacote>, extensao: String): List<String> {
        val base = slug(titulo).take(40).ifEmpty { "musica" }
        val usados = HashSet<String>()
        return grupos.mapIndexed { i, g ->
            val parte = slug(g.nome).take(40).ifEmpty { "arquivo-${i + 1}" }
            var nome = "$base-$parte.$extensao"
            var n = 2
            while (nome in usados) nome = "$base-$parte-${n++}.$extensao"
            usados += nome
            nome
        }
    }

    fun grupoDoVideo(modeloId: String, manter: String): GrupoDoPacote {
        val modelo = modelo(modeloId) ?: throw ProblemaNoPacote("Modelo desconhecido: $modeloId")
        return when (manter) {
            "voz" -> GrupoDoPacote("Voz", listOf("vozes"))
            "resto" -> GrupoDoPacote("Sem voz", menos(modelo, "vozes"))
            else -> throw ProblemaNoPacote("Escolha o que manter no áudio: $manter")
        }
    }

    /** Lê e normaliza o pedido; lança [ProblemaNoPacote] com o problema, em texto para a tela. */
    fun lerPedido(texto: String?, modeloPadrao: String = "spleeter", manterPadrao: String? = null): PedidoDoPacote {
        val raiz = try {
            if (texto.isNullOrBlank()) JSONObject() else JSONObject(texto)
        } catch (_: JSONException) {
            throw ProblemaNoPacote("As opções da separação estão corrompidas. Escolha os arquivos de novo.")
        }
        val modelo = modelo(raiz.optString("modelo", modeloPadrao).ifEmpty { modeloPadrao }) ?: throw ProblemaNoPacote("Escolha o modelo de separação.")
        val manter = if (raiz.has("manter")) raiz.optString("manter") else manterPadrao
        val configuracao = raiz.optJSONObject("configuracao")?.takeIf { it.has("faixas") }?.let { ProjetoMusical.deJson(it) } ?: configuracao(modelo.id)
        if (modelo.faixas.any { f -> configuracao.faixas.none { it.id == f } }) throw ProblemaNoPacote("Os ajustes das faixas não correspondem ao modelo escolhido.")
        val grupos = raiz.optJSONArray("grupos")?.let { a -> List(a.length()) { GrupoDoPacote.deJson(a.getJSONObject(it)) } }
            ?: if (manter != null) listOf(grupoDoVideo(modelo.id, manter)) else combinacoes(modelo.id)[0].grupos
        problemas(modelo.id, grupos).firstOrNull()?.let { throw ProblemaNoPacote(it) }
        return PedidoDoPacote(
            modelo = modelo.id,
            grupos = grupos.map { g -> GrupoDoPacote(g.nome.trim(), modelo.faixas.filter { it in g.faixas }) },
            configuracao = configuracao,
            formato = raiz.optString("formato", "wav-24").ifEmpty { "wav-24" },
            master = raiz.optString("master", "sem-alvo").ifEmpty { "sem-alvo" },
            manter = manter,
        )
    }

    /** Quanto da barra cada etapa ocupa: a separação é quase tudo. */
    fun fracao(etapa: String, fracao: Double): Double {
        val f = fracao.coerceIn(0.0, 1.0)
        val (de, ate) = when (etapa) {
            "lendo" -> 0.0 to 0.03
            "modelo" -> 0.03 to 0.08
            "separando" -> 0.08 to 0.8
            "mixando" -> 0.8 to 0.96
            "juntando" -> 0.96 to 1.0
            else -> 0.0 to 1.0
        }
        return Math.round((de + (ate - de) * f) * 1e6) / 1e6
    }
}
