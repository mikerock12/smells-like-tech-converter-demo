package br.com.smellsliketech.converter.conversao

/** Páginas escolhidas: "1-3, 5, 8-" (de 1 em diante). Kotlin puro, testável. */
object Intervalos {
    fun paginas(texto: String, total: Int): List<Int> {
        if (total <= 0) return emptyList()
        val escolhidas = linkedSetOf<Int>()
        for (parte in texto.split(',', ';').map(String::trim).filter(String::isNotEmpty)) {
            val faixa = Regex("""^(\d*)\s*[-–]\s*(\d*)$""").matchEntire(parte)
            val (inicio, fim) = when {
                faixa != null -> {
                    val (de, ate) = faixa.destructured
                    (de.toIntOrNull() ?: 1) to (ate.toIntOrNull() ?: total)
                }
                parte.toIntOrNull() != null -> parte.toInt() to parte.toInt()
                else -> throw ErroDeConversao("Não entendi as páginas \"$parte\". Use, por exemplo: 1-3, 5, 8-")
            }
            if (inicio < 1 || inicio > total || fim < inicio) throw ErroDeConversao("As páginas \"$parte\" não existem (o PDF tem $total).")
            for (pagina in inicio..minOf(fim, total)) escolhidas += pagina
        }
        if (escolhidas.isEmpty()) throw ErroDeConversao("Informe as páginas, por exemplo: 1-3, 5")
        return escolhidas.toList()
    }
}
