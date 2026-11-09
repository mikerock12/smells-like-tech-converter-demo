package br.com.smellsliketech.converter.musica

import org.json.JSONArray
import org.json.JSONObject

/**
 * O projeto da criação musical (.sltmusic), no mesmo JSON dos quatro clientes
 * (packages/converter-core/musica.mjs). Imutável: a tela troca o projeto inteiro a cada
 * mudança e o mixer lê o novo entre dois blocos. Posições e durações em quadros.
 */
data class ProjetoMusical(
    val versao: Int = Musica.VERSAO,
    val id: String = "",
    val titulo: String = "Nova música",
    val taxa: Int = Musica.TAXA_PADRAO,
    val profundidade: Int = 24,
    val bpm: Double = 120.0,
    val compasso: List<Int> = listOf(4, 4),
    val faixas: List<FaixaMusical> = emptyList(),
    val barramentos: List<Barramento> = emptyList(),
    val master: Master = Master(),
    val marcadores: List<Marcador> = emptyList(),
    val midias: List<MidiaMusical> = emptyList(),
    val metronomo: Metronomo = Metronomo(),
    val contagem: Double = 1.0,
    val latencia: Latencia = Latencia(),
    val loop: Loop = Loop(),
    val punch: Punch = Punch(),
    val separacoes: List<RegistroDeSeparacao> = emptyList(),
) {
    fun paraJson(): JSONObject = JSONObject()
        .put("versao", versao).put("id", id).put("titulo", titulo).put("taxa", taxa).put("profundidade", profundidade)
        .put("bpm", bpm).put("compasso", JSONArray(compasso))
        .put("faixas", JSONArray(faixas.map { it.paraJson() }))
        .put("barramentos", JSONArray(barramentos.map { it.paraJson() }))
        .put("master", master.paraJson())
        .put("marcadores", JSONArray(marcadores.map { JSONObject().put("id", it.id).put("quadro", it.quadro).put("nome", it.nome) }))
        .put("midias", JSONArray(midias.map { it.paraJson() }))
        .put("metronomo", JSONObject().put("ligado", metronomo.ligado).put("volumeDb", metronomo.volumeDb).put("naExportacao", metronomo.naExportacao))
        .put("contagem", contagem)
        .put("latencia", JSONObject().put("entrada", latencia.entrada).put("saida", latencia.saida).put("medida", latencia.medida).putOpt("ajuste", latencia.ajuste))
        .put("loop", JSONObject().put("ligado", loop.ligado).put("inicio", loop.inicio).put("fim", loop.fim))
        .put("punch", JSONObject().put("ligado", punch.ligado).put("entrada", punch.entrada).put("saida", punch.saida))
        .put("separacoes", JSONArray(separacoes.map { JSONObject().put("id", it.id).put("midia", it.midia).put("modo", it.modo).put("modelo", it.modelo).put("faixas", JSONArray(it.faixas)) }))

    companion object {
        fun deJson(json: JSONObject): ProjetoMusical {
            val compasso = json.optJSONArray("compasso")
            val metro = json.optJSONObject("metronomo") ?: JSONObject()
            val lat = json.optJSONObject("latencia") ?: JSONObject()
            val loop = json.optJSONObject("loop") ?: JSONObject()
            val punch = json.optJSONObject("punch") ?: JSONObject()
            return ProjetoMusical(
                versao = json.optInt("versao", Musica.VERSAO),
                id = json.optString("id", ""),
                titulo = json.optString("titulo", "Nova música"),
                taxa = json.optInt("taxa", Musica.TAXA_PADRAO),
                profundidade = json.optInt("profundidade", 24),
                bpm = json.optDouble("bpm", 120.0),
                compasso = if (compasso == null) emptyList() else (0 until compasso.length()).map { compasso.optInt(it) },
                faixas = json.lista("faixas").map(FaixaMusical::deJson),
                barramentos = json.lista("barramentos").map(Barramento::deJson),
                master = json.optJSONObject("master")?.let(Master::deJson) ?: Master(),
                marcadores = json.lista("marcadores").map { Marcador(it.optString("id"), it.optLong("quadro"), it.optString("nome")) },
                midias = json.lista("midias").map(MidiaMusical::deJson),
                metronomo = Metronomo(metro.optBoolean("ligado", true), metro.optDouble("volumeDb", -6.0), metro.optBoolean("naExportacao", false)),
                contagem = json.optDouble("contagem", 1.0),
                latencia = Latencia(lat.optDouble("entrada", 0.0), lat.optDouble("saida", 0.0), lat.optBoolean("medida", false), if (lat.has("ajuste")) lat.optDouble("ajuste") else null),
                loop = Loop(loop.optBoolean("ligado", false), loop.optLong("inicio"), loop.optLong("fim")),
                punch = Punch(punch.optBoolean("ligado", false), punch.optLong("entrada"), punch.optLong("saida")),
                separacoes = json.lista("separacoes").map { s ->
                    val faixas = s.optJSONArray("faixas")
                    RegistroDeSeparacao(s.optString("id"), s.optString("midia"), s.optString("modo", "4"), s.optString("modelo"), if (faixas == null) emptyList() else (0 until faixas.length()).map { faixas.optString(it) })
                },
            )
        }

        fun deTexto(texto: String) = deJson(JSONObject(texto))
    }
}

internal fun JSONObject.lista(nome: String): List<JSONObject> {
    val array = optJSONArray(nome) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

data class PontoDeAutomacao(val quadro: Long, val valor: Double)

data class Automacao(val volume: List<PontoDeAutomacao> = emptyList(), val pan: List<PontoDeAutomacao> = emptyList())

data class Envio(val barramento: String, val nivelDb: Double, val preFader: Boolean = false)

data class ClipeMusical(
    val id: String,
    val midia: String,
    val inicio: Long,
    val inicioNaMidia: Long = 0,
    val duracao: Long,
    val ganhoDb: Double = 0.0,
    val entradaSuave: Long = 0,
    val saidaSuave: Long = 0,
    val mudo: Boolean = false,
    val nome: String? = null,
) {
    val fim get() = inicio + duracao

    fun paraJson(): JSONObject = JSONObject().put("id", id).put("midia", midia).put("inicio", inicio).put("inicioNaMidia", inicioNaMidia)
        .put("duracao", duracao).put("ganhoDb", ganhoDb).put("entradaSuave", entradaSuave).put("saidaSuave", saidaSuave).put("mudo", mudo).putOpt("nome", nome)

    companion object {
        fun deJson(j: JSONObject) = ClipeMusical(
            j.optString("id"), j.optString("midia"), j.optLong("inicio"), j.optLong("inicioNaMidia"), j.optLong("duracao"),
            j.optDouble("ganhoDb", 0.0), j.optLong("entradaSuave"), j.optLong("saidaSuave"), j.optBoolean("mudo", false),
            if (j.has("nome")) j.optString("nome") else null,
        )
    }
}

/**
 * Efeito: tipo, ligado e os parâmetros. `valores` guarda o que veio no JSON como veio (para a
 * validação recusar um parâmetro que não é número, como os outros núcleos).
 */
data class EfeitoMusical(val id: String, val tipo: String, val ligado: Boolean?, val valores: Map<String, Any?> = emptyMap()) {
    val estaLigado get() = ligado == true

    fun numero(nome: String): Double? = (valores[nome] as? Number)?.toDouble()

    fun com(nome: String, valor: Double) = copy(valores = valores + (nome to valor))

    fun paraJson(): JSONObject {
        val j = JSONObject().put("id", id).put("tipo", tipo)
        if (ligado != null) j.put("ligado", ligado)
        for ((k, v) in valores) j.put(k, v)
        return j
    }

    companion object {
        fun deJson(j: JSONObject): EfeitoMusical {
            val valores = mutableMapOf<String, Any?>()
            for (k in j.keys()) if (k !in setOf("id", "tipo", "ligado")) valores[k] = j.opt(k)
            return EfeitoMusical(j.optString("id"), j.optString("tipo"), if (j.opt("ligado") is Boolean) j.optBoolean("ligado") else null, valores)
        }
    }
}

data class FaixaMusical(
    val id: String,
    val nome: String,
    val cor: String? = null,
    val ganhoDb: Double = 0.0,
    val pan: Double = 0.0,
    val ajusteEsquerdoDb: Double = 0.0,
    val ajusteDireitoDb: Double = 0.0,
    val gravar: Boolean = false,
    val monitorar: Boolean = false,
    val mudo: Boolean = false,
    val solo: Boolean = false,
    val entrada: String? = null,
    val canaisDeEntrada: Int = 1,
    val saida: String = "master",
    val efeitos: List<EfeitoMusical> = emptyList(),
    val envios: List<Envio> = emptyList(),
    val automacao: Automacao = Automacao(),
    val clipes: List<ClipeMusical> = emptyList(),
    val oculta: Boolean? = null,
    val congelada: Boolean? = null,
) {
    fun paraJson(): JSONObject = JSONObject().put("id", id).put("nome", nome).putOpt("cor", cor).put("ganhoDb", ganhoDb).put("pan", pan)
        .put("ajusteEsquerdoDb", ajusteEsquerdoDb).put("ajusteDireitoDb", ajusteDireitoDb).put("gravar", gravar).put("monitorar", monitorar)
        .put("mudo", mudo).put("solo", solo).put("entrada", entrada ?: JSONObject.NULL).put("canaisDeEntrada", canaisDeEntrada).put("saida", saida)
        .put("efeitos", JSONArray(efeitos.map { it.paraJson() }))
        .put("envios", JSONArray(envios.map { JSONObject().put("barramento", it.barramento).put("nivelDb", it.nivelDb).put("preFader", it.preFader) }))
        .put("automacao", JSONObject()
            .put("volume", JSONArray(automacao.volume.map { JSONObject().put("quadro", it.quadro).put("valor", it.valor) }))
            .put("pan", JSONArray(automacao.pan.map { JSONObject().put("quadro", it.quadro).put("valor", it.valor) })))
        .put("clipes", JSONArray(clipes.map { it.paraJson() }))
        .putOpt("oculta", oculta).putOpt("congelada", congelada)

    companion object {
        private fun pontos(j: JSONObject?, nome: String) = j?.lista(nome)?.map { PontoDeAutomacao(it.optLong("quadro"), it.optDouble("valor")) } ?: emptyList()

        fun deJson(j: JSONObject) = FaixaMusical(
            id = j.optString("id"),
            nome = j.optString("nome"),
            cor = if (j.has("cor")) j.optString("cor") else null,
            ganhoDb = j.optDouble("ganhoDb", 0.0),
            pan = j.optDouble("pan", 0.0),
            ajusteEsquerdoDb = j.optDouble("ajusteEsquerdoDb", 0.0),
            ajusteDireitoDb = j.optDouble("ajusteDireitoDb", 0.0),
            gravar = j.optBoolean("gravar", false),
            monitorar = j.optBoolean("monitorar", false),
            mudo = j.optBoolean("mudo", false),
            solo = j.optBoolean("solo", false),
            entrada = if (j.isNull("entrada")) null else j.optString("entrada"),
            canaisDeEntrada = j.optInt("canaisDeEntrada", 1),
            saida = j.optString("saida", "master"),
            efeitos = j.lista("efeitos").map(EfeitoMusical::deJson),
            envios = j.lista("envios").map { Envio(it.optString("barramento"), it.optDouble("nivelDb", 0.0), it.optBoolean("preFader", false)) },
            automacao = j.optJSONObject("automacao").let { Automacao(pontos(it, "volume"), pontos(it, "pan")) },
            clipes = j.lista("clipes").map(ClipeMusical::deJson),
            oculta = if (j.has("oculta")) j.optBoolean("oculta") else null,
            congelada = if (j.has("congelada")) j.optBoolean("congelada") else null,
        )
    }
}

data class Barramento(val id: String, val nome: String, val ganhoDb: Double = 0.0, val pan: Double = 0.0, val mudo: Boolean = false, val efeitos: List<EfeitoMusical> = emptyList()) {
    fun paraJson(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("ganhoDb", ganhoDb).put("pan", pan).put("mudo", mudo)
        .put("efeitos", JSONArray(efeitos.map { it.paraJson() }))

    companion object {
        fun deJson(j: JSONObject) = Barramento(j.optString("id"), j.optString("nome"), j.optDouble("ganhoDb", 0.0), j.optDouble("pan", 0.0), j.optBoolean("mudo", false), j.lista("efeitos").map(EfeitoMusical::deJson))
    }
}

data class Master(val ganhoDb: Double = 0.0, val efeitos: List<EfeitoMusical> = emptyList()) {
    fun paraJson(): JSONObject = JSONObject().put("ganhoDb", ganhoDb).put("efeitos", JSONArray(efeitos.map { it.paraJson() }))

    companion object {
        fun deJson(j: JSONObject) = Master(j.optDouble("ganhoDb", 0.0), j.lista("efeitos").map(EfeitoMusical::deJson))
    }
}

data class Marcador(val id: String, val quadro: Long, val nome: String)

data class MidiaMusical(
    val id: String,
    val nome: String = "",
    val formato: String = "wav",
    val taxa: Int,
    val canais: Int,
    val quadros: Long,
    val bits: Int = 32,
    val sha256: String? = null,
    val origem: String? = null,
    val instrumento: String? = null,
    val modelo: String? = null,
    val versaoDoModelo: String? = null,
) {
    fun paraJson(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("formato", formato).put("taxa", taxa).put("canais", canais)
        .put("quadros", quadros).put("bits", bits).putOpt("sha256", sha256).putOpt("origem", origem).putOpt("instrumento", instrumento)
        .putOpt("modelo", modelo).putOpt("versaoDoModelo", versaoDoModelo)

    companion object {
        private fun JSONObject.texto(nome: String) = if (has(nome) && !isNull(nome)) optString(nome) else null

        fun deJson(j: JSONObject) = MidiaMusical(
            j.optString("id"), j.optString("nome"), j.optString("formato", "wav"), j.optInt("taxa"), j.optInt("canais"), j.optLong("quadros"), j.optInt("bits", 32),
            j.texto("sha256"), j.texto("origem"), j.texto("instrumento"), j.texto("modelo"), j.texto("versaoDoModelo"),
        )
    }
}

data class Metronomo(val ligado: Boolean = true, val volumeDb: Double = -6.0, val naExportacao: Boolean = false)

data class Latencia(val entrada: Double = 0.0, val saida: Double = 0.0, val medida: Boolean = false, val ajuste: Double? = null)

data class Loop(val ligado: Boolean = false, val inicio: Long = 0, val fim: Long = 0)

data class Punch(val ligado: Boolean = false, val entrada: Long = 0, val saida: Long = 0)

data class RegistroDeSeparacao(val id: String, val midia: String, val modo: String, val modelo: String, val faixas: List<String>)
