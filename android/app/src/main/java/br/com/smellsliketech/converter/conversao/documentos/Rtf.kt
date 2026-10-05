package br.com.smellsliketech.converter.conversao.documentos

import java.nio.charset.Charset

/**
 * RTF: o texto, os parágrafos e as tabelas (\cell, \row). Fontes, cores, imagens,
 * cabeçalhos e códigos de campo ficam de fora.
 */
object Rtf {
    /** Grupos que não são texto do documento. */
    private val IGNORADOS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "headerl", "headerr", "headerf",
        "footer", "footerl", "footerr", "footerf", "footnote", "fldinst", "themedata", "colorschememapping",
        "latentstyles", "datastore", "xmlnstbl", "rsidtbl", "generator", "listtable", "listoverridetable",
        "pgdsctbl", "bkmkstart", "bkmkend", "filetbl", "revtbl", "mmathpr", "wgrffmtfilter", "nonshppict", "shppict", "sp",
    )

    private data class Estado(var ignorar: Boolean = false, var pular: Int = 1)

    fun ler(bytes: ByteArray): Documento {
        val fonte = String(bytes, Charsets.ISO_8859_1)
        val montador = Montador()
        val texto = StringBuilder()
        val linha = mutableListOf<String>()
        val linhas = mutableListOf<List<String>>()
        var emTabela = false
        var codificacao: Charset = Charset.forName("windows-1252")
        val pilha = ArrayDeque<Estado>().apply { addLast(Estado()) }
        val bytesPendentes = java.io.ByteArrayOutputStream()
        var pularCaracteres = 0

        fun estado() = pilha.last()
        fun descarregarBytes() {
            if (bytesPendentes.size() > 0) {
                texto.append(String(bytesPendentes.toByteArray(), codificacao))
                bytesPendentes.reset()
            }
        }
        fun escrever(caractere: Char) {
            if (estado().ignorar) return
            if (pularCaracteres > 0) { pularCaracteres--; return }
            descarregarBytes()
            texto.append(caractere)
        }
        fun fecharTabela() {
            if (linha.isNotEmpty()) { linhas += linha.toList(); linha.clear() }
            if (linhas.isNotEmpty()) { montador.tabela(linhas.toList()); linhas.clear() }
        }
        fun fimDeParagrafo() {
            descarregarBytes()
            if (emTabela) { texto.append('\n'); return }
            fecharTabela()
            montador.paragrafo(texto.toString())
            texto.clear()
        }

        var indice = 0
        while (indice < fonte.length) {
            val caractere = fonte[indice]
            when (caractere) {
                '{' -> { pilha.addLast(estado().copy()); indice++ }
                '}' -> { descarregarBytes(); if (pilha.size > 1) pilha.removeLast(); indice++ }
                '\r', '\n' -> indice++
                '\\' -> {
                    indice++
                    if (indice >= fonte.length) break
                    val seguinte = fonte[indice]
                    when {
                        seguinte == '\'' -> {
                            val hexa = fonte.substring(indice + 1, minOf(indice + 3, fonte.length))
                            indice += 3
                            if (!estado().ignorar) {
                                if (pularCaracteres > 0) pularCaracteres-- else hexa.toIntOrNull(16)?.let { bytesPendentes.write(it) }
                            }
                        }
                        seguinte == '*' -> { estado().ignorar = true; indice++ }
                        seguinte.isLetter() -> {
                            val inicio = indice
                            while (indice < fonte.length && fonte[indice].isLetter()) indice++
                            val palavra = fonte.substring(inicio, indice)
                            val inicioNumero = indice
                            if (indice < fonte.length && fonte[indice] == '-') indice++
                            while (indice < fonte.length && fonte[indice].isDigit()) indice++
                            val parametro = fonte.substring(inicioNumero, indice).toIntOrNull()
                            if (indice < fonte.length && fonte[indice] == ' ') indice++
                            when (palavra) {
                                in IGNORADOS -> estado().ignorar = true
                                "ansicpg" -> parametro?.let { runCatching { codificacao = Charset.forName("windows-$it") } }
                                "uc" -> estado().pular = parametro ?: 1
                                "u" -> parametro?.let {
                                    if (!estado().ignorar) {
                                        descarregarBytes()
                                        texto.append((if (it < 0) it + 65536 else it).toChar())
                                        pularCaracteres = estado().pular
                                    }
                                }
                                "par", "sect" -> if (!estado().ignorar) fimDeParagrafo()
                                "page" -> if (!estado().ignorar) { fimDeParagrafo(); montador.quebra() }
                                "line" -> escrever('\n')
                                "tab" -> escrever('\t')
                                "emdash" -> escrever('—')
                                "endash" -> escrever('–')
                                "bullet" -> escrever('•')
                                "lquote" -> escrever('‘')
                                "rquote" -> escrever('’')
                                "ldblquote" -> escrever('“')
                                "rdblquote" -> escrever('”')
                                "pard" -> emTabela = false
                                "intbl" -> emTabela = true
                                "cell" -> if (!estado().ignorar) {
                                    descarregarBytes()
                                    linha += texto.toString().trim()
                                    texto.clear()
                                }
                                "row" -> if (!estado().ignorar) {
                                    if (linha.isNotEmpty()) linhas += linha.toList()
                                    linha.clear()
                                    texto.clear()
                                }
                            }
                        }
                        else -> {
                            when (seguinte) {
                                '\\', '{', '}' -> escrever(seguinte)
                                '~' -> escrever(' ')
                                '_' -> escrever('-')
                                '\r', '\n' -> if (!estado().ignorar) fimDeParagrafo()
                            }
                            indice++
                        }
                    }
                }
                else -> { escrever(caractere); indice++ }
            }
        }
        descarregarBytes()
        emTabela = false
        fecharTabela()
        montador.paragrafo(texto.toString())
        return montador.documento()
    }
}
