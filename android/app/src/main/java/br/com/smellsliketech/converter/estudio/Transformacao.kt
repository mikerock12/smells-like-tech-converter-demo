package br.com.smellsliketech.converter.estudio

import org.json.JSONObject

/** Contrato comum de camadas, espelho de camadas.mjs e StudioTransform. */
data class Transformacao(
    val x: Double = 0.5, val y: Double = 0.5, val escala: Double = 1.0,
    val giro: Double = 0.0, val giroX: Double = 0.0, val giroY: Double = 0.0,
    val espelharX: Boolean = false, val espelharY: Boolean = false,
    val opacidade: Double = 1.0, val mescla: String = "normal",
    val filtro: String = "original", val brilho: Double = 0.0, val contraste: Double = 0.0,
    val saturacao: Double = 0.0, val removerFundo: Boolean = false,
) {
    fun paraJson() = JSONObject().put("x", x).put("y", y).put("escala", escala)
        .put("giro", giro).put("giroX", giroX).put("giroY", giroY)
        .put("espelharX", espelharX).put("espelharY", espelharY)
        .put("opacidade", opacidade).put("mescla", mescla).put("filtro", filtro)
        .put("brilho", brilho).put("contraste", contraste).put("saturacao", saturacao).put("removerFundo", removerFundo)

    fun problema(): String? {
        val limites = listOf(
            Triple("x", x, -2.0..3.0), Triple("y", y, -2.0..3.0), Triple("escala", escala, 0.05..6.0),
            Triple("giro", giro, -360.0..360.0), Triple("giroX", giroX, -180.0..180.0), Triple("giroY", giroY, -180.0..180.0),
            Triple("opacidade", opacidade, 0.0..1.0), Triple("brilho", brilho, -1.0..1.0),
            Triple("contraste", contraste, -0.8..1.0), Triple("saturacao", saturacao, -1.0..1.0))
        for ((nome, valor, intervalo) in limites) if (!valor.isFinite() || valor !in intervalo) return "Ajuste inválido da camada: $nome."
        if (mescla !in listOf("normal", "multiplicar", "clarear", "sobrepor")) return "Modo de mesclagem desconhecido."
        if (filtro !in listOf("original", "vivido", "pb", "sepia", "vintage", "frio", "quente", "negativo")) return "Filtro desconhecido."
        return null
    }
    companion object {
        fun deJson(j: JSONObject) = Transformacao(j.optDouble("x", .5), j.optDouble("y", .5), j.optDouble("escala", 1.0),
            j.optDouble("giro", 0.0), j.optDouble("giroX", 0.0), j.optDouble("giroY", 0.0),
            j.optBoolean("espelharX"), j.optBoolean("espelharY"), j.optDouble("opacidade", 1.0),
            j.optString("mescla", "normal"), j.optString("filtro", "original"), j.optDouble("brilho", 0.0),
            j.optDouble("contraste", 0.0), j.optDouble("saturacao", 0.0), j.optBoolean("removerFundo"))
    }
}

data class Camada(
    val id: String, val arquivo: String, val tipo: String = "imagem",
    val inicio: Double = 0.0, val fim: Double = 3.0, val deslocamento: Double = 0.0,
    val repetir: Boolean = true, val visivel: Boolean = true, val ordem: Int = 1,
    val transformacao: Transformacao? = null,
) {
    fun paraJson() = JSONObject().put("id", id).put("arquivo", arquivo).put("tipo", tipo)
        .put("inicio", inicio).put("fim", fim).put("deslocamento", deslocamento)
        .put("repetir", repetir).put("visivel", visivel).put("ordem", ordem).putOpt("transformacao", transformacao?.paraJson())
    companion object {
        fun deJson(j: JSONObject) = Camada(j.optString("id"), j.optString("arquivo"), j.optString("tipo", "imagem"),
            j.optDouble("inicio", 0.0), j.optDouble("fim", 3.0), j.optDouble("deslocamento", 0.0),
            j.optBoolean("repetir", true), j.optBoolean("visivel", true), j.optInt("ordem", 1),
            j.optJSONObject("transformacao")?.let(Transformacao::deJson))
    }
}

data class FundoDoProjeto(val cor: String = "#00000000", val corFinal: String? = null) {
    fun paraJson() = JSONObject().put("cor", cor).putOpt("corFinal", corFinal)
    companion object {
        fun deJson(j: JSONObject) = FundoDoProjeto(j.optString("cor", "#00000000"),
            j.optString("corFinal").ifBlank { null })
    }
}
