package br.com.smellsliketech.converter.conversao

/** Seleção testável sem Android; nenhuma voz de rede ou ainda por baixar pode ser usada. */
data class VozLocal(val nome: String, val idioma: String, val pais: String, val exigeRede: Boolean, val faltaInstalar: Boolean)

object VozesLocais {
    fun escolher(vozes: List<VozLocal>, preferida: String?): String? = vozes
        .filter { it.idioma == "pt" && !it.exigeRede && !it.faltaInstalar }
        .sortedWith(compareByDescending<VozLocal> { it.pais == "BR" }
            .thenByDescending { it.nome == preferida }.thenBy { it.nome })
        .firstOrNull()?.nome
}
