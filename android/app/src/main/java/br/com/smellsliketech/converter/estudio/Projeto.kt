package br.com.smellsliketech.converter.estudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * O projeto do estúdio, no mesmo JSON dos quatro apps (packages/converter-core/estudio.mjs e
 * docs/estudio.md). As regras ficam em [Estudio] e a receita do FFmpeg em [Estudio.receita].
 */
data class Projeto(
    val versao: Int = Estudio.VERSAO_DO_PROJETO,
    val destino: String = "personalizado",
    val largura: Int = 1080,
    val altura: Int = 1920,
    val fps: Int = 30,
    val saida: String = "video",
    val clipes: List<Clipe> = emptyList(),
    val textos: List<Texto> = emptyList(),
    val audios: List<AudioExtra> = emptyList(),
    val camadas: List<Camada> = emptyList(),
    val fundo: FundoDoProjeto? = null,
    /** Instante exportado quando a saída é imagem. */
    val quadro: Double = 0.0,
) {
    fun paraJson(): JSONObject = JSONObject()
        .put("versao", versao).put("destino", destino).put("largura", largura).put("altura", altura)
        .put("fps", fps).put("saida", saida)
        .put("clipes", JSONArray(clipes.map { it.paraJson() }))
        .put("textos", JSONArray(textos.map { it.paraJson() }))
        .put("audios", JSONArray(audios.map { it.paraJson() }))
        .put("camadas", JSONArray(camadas.map { it.paraJson() }))
        .putOpt("fundo", fundo?.paraJson())
        .put("quadro", quadro)

    companion object {
        fun deJson(json: JSONObject) = Projeto(
            versao = json.optInt("versao", Estudio.VERSAO_DO_PROJETO),
            destino = json.optString("destino", "personalizado"),
            largura = json.optInt("largura"),
            altura = json.optInt("altura"),
            fps = json.optInt("fps", 30),
            saida = json.optString("saida", "video"),
            clipes = json.lista("clipes").map(Clipe::deJson),
            textos = json.lista("textos").map(Texto::deJson),
            audios = json.lista("audios").map(AudioExtra::deJson),
            camadas = json.lista("camadas").map(Camada::deJson),
            fundo = json.optJSONObject("fundo")?.let(FundoDoProjeto::deJson),
            quadro = json.optDouble("quadro", 0.0),
        )
    }
}

/** Um vídeo ou imagem da faixa principal. `inicio`/`fim` contam no arquivo. */
data class Transicao(val tipo: String = "corte", val duracao: Double = .5) {
    fun paraJson() = JSONObject().put("tipo", tipo).put("duracao", duracao)
}

data class Clipe(
    val id: String,
    /** Referência do arquivo: o caminho da cópia local, no Android. */
    val arquivo: String,
    val tipo: String = "video",
    val inicio: Double? = null,
    val fim: Double? = null,
    val duracao: Double? = null,
    val ajuste: String = "caber-desfocado",
    val volume: Double? = null,
    val mudo: Boolean? = null,
    val velocidade: Double = 1.0,
    val transicao: Transicao? = null,
    val transformacao: Transformacao? = null,
) {
    val ehImagem get() = tipo == "imagem"

    fun paraJson(): JSONObject = JSONObject().put("id", id).put("arquivo", arquivo).put("tipo", tipo)
        .putOpt("inicio", inicio).putOpt("fim", fim).putOpt("duracao", duracao).put("ajuste", ajuste)
        .putOpt("volume", volume).putOpt("mudo", mudo)
        .put("velocidade", velocidade).putOpt("transicao", transicao?.paraJson())
        .putOpt("transformacao", transformacao?.paraJson())

    companion object {
        fun deJson(json: JSONObject) = Clipe(
            id = json.optString("id"),
            arquivo = json.optString("arquivo"),
            tipo = json.optString("tipo", "video"),
            inicio = json.numero("inicio"),
            fim = json.numero("fim"),
            duracao = json.numero("duracao"),
            ajuste = json.optString("ajuste", "caber"),
            volume = json.numero("volume"),
            velocidade = json.optDouble("velocidade", 1.0),
            transicao = json.optJSONObject("transicao")?.let { Transicao(it.optString("tipo", "corte"), it.optDouble("duracao", .5)) },
            mudo = if (json.has("mudo") && !json.isNull("mudo")) json.getBoolean("mudo") else null,
            transformacao = json.optJSONObject("transformacao")?.let(Transformacao::deJson),
        )
    }
}

/** Um texto por cima do vídeo: tempo na linha do tempo, centro em fração do quadro. */
data class Texto(
    val id: String,
    val texto: String,
    val inicio: Double,
    val fim: Double,
    val x: Double = 0.5,
    val y: Double = 0.5,
    /** Altura da letra, em fração da altura do quadro. */
    val tamanho: Double = 0.06,
    /** Cores em CSS: "#RRGGBB" ou "#RRGGBBAA". */
    val cor: String = "#FFFFFF",
    val contorno: String? = "#000000",
    val caixa: String? = null,
    val fonte: String = "padrao",
    val transformacao: Transformacao? = null,
) {
    fun paraJson(): JSONObject = JSONObject().put("id", id).put("texto", texto).put("inicio", inicio).put("fim", fim)
        .put("x", x).put("y", y).put("tamanho", tamanho).put("cor", cor)
        .put("contorno", contorno ?: JSONObject.NULL).put("caixa", caixa ?: JSONObject.NULL).put("fonte", fonte)
        .putOpt("transformacao", transformacao?.paraJson())

    companion object {
        fun deJson(json: JSONObject) = Texto(
            id = json.optString("id"),
            texto = json.optString("texto"),
            inicio = json.optDouble("inicio", 0.0),
            fim = json.optDouble("fim", 0.0),
            x = json.optDouble("x", 0.5),
            y = json.optDouble("y", 0.5),
            tamanho = json.optDouble("tamanho", 0.06),
            cor = json.optString("cor", "#FFFFFF"),
            contorno = json.textoOuNulo("contorno"),
            caixa = json.textoOuNulo("caixa"),
            fonte = json.optString("fonte", "padrao"),
            transformacao = json.optJSONObject("transformacao")?.let(Transformacao::deJson),
        )
    }
}

/** Música ou áudio extra: o trecho do arquivo e onde ele entra na linha do tempo. */
data class AudioExtra(
    val id: String,
    val arquivo: String,
    val entrada: Double? = 0.0,
    val inicio: Double,
    val fim: Double,
    val volume: Double? = 1.0,
    val fadeEntrada: Double? = null,
    val fadeSaida: Double? = null,
) {
    fun paraJson(): JSONObject = JSONObject().put("id", id).put("arquivo", arquivo).putOpt("entrada", entrada)
        .put("inicio", inicio).put("fim", fim).putOpt("volume", volume)
        .putOpt("fadeEntrada", fadeEntrada).putOpt("fadeSaida", fadeSaida)

    companion object {
        fun deJson(json: JSONObject) = AudioExtra(
            id = json.optString("id"),
            arquivo = json.optString("arquivo"),
            entrada = json.numero("entrada"),
            inicio = json.optDouble("inicio", 0.0),
            fim = json.optDouble("fim", 0.0),
            volume = json.numero("volume"),
            fadeEntrada = json.numero("fadeEntrada"),
            fadeSaida = json.numero("fadeSaida"),
        )
    }
}

/** Destino pronto (rede e formato), lido de estudio-presets.json. */
data class Preset(
    val id: String,
    val rede: String,
    val nome: String,
    val largura: Int,
    val altura: Int,
    val fps: Int,
    /** Duração de referência da rede, em segundos; null quando não há limite prático. */
    val duracaoMaxima: Double?,
) {
    val titulo get() = "$rede · $nome"

    companion object {
        fun lista(json: String): List<Preset> = JSONObject(json).lista("presets").map {
            Preset(it.getString("id"), it.getString("rede"), it.getString("nome"), it.getInt("largura"), it.getInt("altura"),
                it.getInt("fps"), it.numero("duracaoMaxima"))
        }
    }
}

private fun JSONObject.lista(nome: String): List<JSONObject> {
    val array = optJSONArray(nome) ?: return emptyList()
    return (0 until array.length()).map { array.getJSONObject(it) }
}

private fun JSONObject.numero(nome: String): Double? = if (has(nome) && !isNull(nome)) getDouble(nome) else null

private fun JSONObject.textoOuNulo(nome: String): String? = if (has(nome) && !isNull(nome)) getString(nome) else null
