package br.com.smellsliketech.converter.conversao

import br.com.smellsliketech.converter.conversao.documentos.Bloco
import br.com.smellsliketech.converter.conversao.documentos.Documento

/**
 * O que o Whisper ouviu num arquivo: os trechos com os tempos (para SRT e VTT) e o texto
 * corrido, em parágrafos, para ler na tela, copiar e salvar. Kotlin puro, testável.
 */
data class Transcricao(val nomeOriginal: String, val trechos: List<Legenda.Trecho>) {
    /** Um parágrafo novo a cada pausa de 1,5 s ou mais; dentro dele, os trechos seguidos. */
    val paragrafos: List<String>
        get() {
            val lista = mutableListOf<String>()
            val atual = StringBuilder()
            var fimAnterior: Long? = null
            for (trecho in trechos) {
                val pausa = fimAnterior?.let { trecho.inicioMs - it } ?: 0
                if (atual.isNotEmpty() && pausa >= PAUSA_MS) {
                    lista += atual.toString()
                    atual.clear()
                }
                if (atual.isNotEmpty()) atual.append(' ')
                atual.append(trecho.texto.replace('\n', ' ').trim())
                fimAnterior = trecho.fimMs
            }
            if (atual.isNotEmpty()) lista += atual.toString()
            return lista
        }

    val texto: String get() = paragrafos.joinToString("\n\n")

    val palavras: Int get() = texto.split(Regex("""\s+""")).count { it.isNotBlank() }

    fun documento(): Documento = Documento(paragrafos.map { Bloco.Paragrafo(it) })

    companion object {
        const val PAUSA_MS = 1_500L

        /** Os formatos de "Salvar como", na ordem da tela. */
        val FORMATOS = listOf("txt", "pdf", "docx", "srt", "vtt", "md")
    }
}
