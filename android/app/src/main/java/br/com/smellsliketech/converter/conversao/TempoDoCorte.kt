package br.com.smellsliketech.converter.conversao

/** Campos precisos aceitam segundos decimais e h:mm:ss com vírgula ou ponto decimal. */
object TempoDoCorte {
    fun ler(texto: String): Double? {
        val limpo = texto.trim()
        val segundos = if (':' !in limpo && '-' !in limpo && ' ' !in limpo)
            limpo.replace(',', '.').toDoubleOrNull()
        else Relogio.ler(if (':' in limpo) limpo.replace('.', ',') else limpo)
        return segundos?.takeIf { it.isFinite() && it >= 0 }
    }
}
