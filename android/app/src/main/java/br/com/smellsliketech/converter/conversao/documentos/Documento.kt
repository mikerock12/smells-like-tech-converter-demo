package br.com.smellsliketech.converter.conversao.documentos

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Um documento reduzido ao que todo formato entende: títulos, parágrafos, itens de lista e
 * tabelas. Os leitores (Word, OpenDocument, RTF, HTML, EPUB, planilhas, apresentações)
 * produzem isto, e os escritores gravam isto. Kotlin puro: os testes rodam no computador.
 */
sealed interface Bloco {
    data class Titulo(val nivel: Int, val texto: String) : Bloco
    data class Paragrafo(val texto: String) : Bloco
    data class Item(val texto: String, val numero: Int? = null) : Bloco
    data class Tabela(val linhas: List<List<String>>) : Bloco
    data object Quebra : Bloco
}

data class Documento(val blocos: List<Bloco>) {
    val vazio: Boolean
        get() = blocos.none {
            when (it) {
                is Bloco.Titulo -> it.texto.isNotBlank()
                is Bloco.Paragrafo -> it.texto.isNotBlank()
                is Bloco.Item -> it.texto.isNotBlank()
                is Bloco.Tabela -> it.linhas.any { linha -> linha.any(String::isNotBlank) }
                Bloco.Quebra -> false
            }
        }

    companion object {
        /** Texto corrido: parágrafos separados por linha em branco; as quebras simples ficam. */
        fun deTexto(texto: String): Documento = Documento(
            texto.replace("\r\n", "\n").replace('\r', '\n').replace("\u000C", "\n\n")
                .split(Regex("""\n\s*\n"""))
                .map { it.trim('\n') }
                .filter(String::isNotBlank)
                .map { Bloco.Paragrafo(it) },
        )
    }
}

/** Monta um documento limpando espaços e juntando o que os leitores entregam aos pedaços. */
class Montador {
    private val blocos = mutableListOf<Bloco>()

    fun titulo(nivel: Int, texto: String) = limpo(texto)?.let { blocos += Bloco.Titulo(nivel.coerceIn(1, 6), it) }
    fun paragrafo(texto: String) = limpo(texto)?.let { blocos += Bloco.Paragrafo(it) }
    fun item(texto: String, numero: Int? = null) = limpo(texto)?.let { blocos += Bloco.Item(it, numero) }
    fun tabela(linhas: List<List<String>>) {
        val cheias = linhas.map { linha -> linha.map { it.replace(Regex("""[ \t]+"""), " ").trim() } }
            .dropLastWhile { linha -> linha.all(String::isBlank) }
        if (cheias.isEmpty()) return
        // Colunas vazias à direita (comuns em planilha) saem.
        val largura = cheias.maxOf { linha -> linha.indexOfLast(String::isNotBlank) + 1 }
        if (largura == 0) return
        blocos += Bloco.Tabela(cheias.map { linha -> List(largura) { linha.getOrElse(it) { "" } } })
    }
    fun quebra() { if (blocos.isNotEmpty() && blocos.last() != Bloco.Quebra) blocos += Bloco.Quebra }

    fun documento() = Documento(blocos.dropLastWhile { it == Bloco.Quebra })

    private fun limpo(texto: String): String? =
        texto.lines().joinToString("\n") { it.replace(Regex("""[ \t\u00A0]+"""), " ").trim() }
            .replace(Regex("""\n{3,}"""), "\n\n").trim().ifEmpty { null }
}

/** Grava o [Documento] em cada formato de texto. O PDF é do Android (EscritorDePdf). */
object Escritores {
    fun texto(documento: Documento): String = buildString {
        documento.blocos.forEach { bloco ->
            when (bloco) {
                is Bloco.Titulo -> append(bloco.texto).append("\n\n")
                is Bloco.Paragrafo -> append(bloco.texto).append("\n\n")
                is Bloco.Item -> append(bloco.numero?.let { "$it. " } ?: "• ").append(bloco.texto.replace("\n", "\n  ")).append('\n')
                is Bloco.Tabela -> {
                    bloco.linhas.forEach { linha -> append(linha.joinToString("\t") { it.replace('\n', ' ') }).append('\n') }
                    append('\n')
                }
                Bloco.Quebra -> append("\n")
            }
        }
    }.replace(Regex("""\n{3,}"""), "\n\n").trim() + "\n"

    fun markdown(documento: Documento): String = buildString {
        var anterior: Bloco? = null
        documento.blocos.forEach { bloco ->
            if (anterior is Bloco.Item && bloco !is Bloco.Item) append('\n')
            when (bloco) {
                is Bloco.Titulo -> append("#".repeat(bloco.nivel)).append(' ').append(bloco.texto.replace('\n', ' ')).append("\n\n")
                is Bloco.Paragrafo -> append(bloco.texto.replace("\n", "  \n")).append("\n\n")
                is Bloco.Item -> append(bloco.numero?.let { "$it. " } ?: "- ").append(bloco.texto.replace("\n", "  \n  ")).append('\n')
                is Bloco.Tabela -> {
                    fun celula(texto: String) = texto.replace("|", "\\|").replace("\n", "<br>")
                    val cabecalho = bloco.linhas.first()
                    append("| ").append(cabecalho.joinToString(" | ", transform = ::celula)).append(" |\n")
                    append("|").append(cabecalho.joinToString("|") { " --- " }).append("|\n")
                    bloco.linhas.drop(1).forEach { linha -> append("| ").append(linha.joinToString(" | ", transform = ::celula)).append(" |\n") }
                    append('\n')
                }
                Bloco.Quebra -> append("---\n\n")
            }
            anterior = bloco
        }
    }.trim() + "\n"

    fun html(documento: Documento, titulo: String): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"pt-BR\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escapar(titulo)).append("</title>\n")
        append("<style>body{font-family:system-ui,sans-serif;max-width:48rem;margin:2rem auto;padding:0 1rem;line-height:1.5}")
        append("table{border-collapse:collapse;margin:1rem 0}td,th{border:1px solid #999;padding:.25rem .5rem;text-align:left;vertical-align:top}")
        append("hr{border:0;border-top:1px dashed #999;margin:2rem 0}</style>\n</head>\n<body>\n")
        var lista: String? = null
        fun fecharLista() { lista?.let { append("</").append(it).append(">\n") }; lista = null }
        documento.blocos.forEach { bloco ->
            val tipoDeLista = (bloco as? Bloco.Item)?.let { if (it.numero != null) "ol" else "ul" }
            if (tipoDeLista != lista) {
                fecharLista()
                if (tipoDeLista != null) { append('<').append(tipoDeLista).append(">\n"); lista = tipoDeLista }
            }
            when (bloco) {
                is Bloco.Titulo -> append("<h${bloco.nivel}>").append(comQuebras(bloco.texto)).append("</h${bloco.nivel}>\n")
                is Bloco.Paragrafo -> append("<p>").append(comQuebras(bloco.texto)).append("</p>\n")
                is Bloco.Item -> append("<li>").append(comQuebras(bloco.texto)).append("</li>\n")
                is Bloco.Tabela -> {
                    append("<table>\n")
                    bloco.linhas.forEachIndexed { indice, linha ->
                        val celula = if (indice == 0 && bloco.linhas.size > 1) "th" else "td"
                        append("<tr>")
                        linha.forEach { append('<').append(celula).append('>').append(comQuebras(it)).append("</").append(celula).append('>') }
                        append("</tr>\n")
                    }
                    append("</table>\n")
                }
                Bloco.Quebra -> append("<hr>\n")
            }
        }
        fecharLista()
        append("</body>\n</html>\n")
    }

    /** As tabelas do documento (as abas de uma planilha, uma depois da outra); sem tabela, uma linha por parágrafo. */
    fun csv(documento: Documento): String {
        val tabelas = documento.blocos.filterIsInstance<Bloco.Tabela>()
        val linhas = if (tabelas.isNotEmpty()) {
            tabelas.flatMapIndexed { indice, tabela -> (if (indice > 0) listOf(emptyList<String>()) else emptyList()) + tabela.linhas }
        } else {
            documento.blocos.mapNotNull {
                when (it) {
                    is Bloco.Titulo -> listOf(it.texto)
                    is Bloco.Paragrafo -> listOf(it.texto)
                    is Bloco.Item -> listOf(it.texto)
                    else -> null
                }
            }
        }
        return linhas.joinToString("\r\n", postfix = "\r\n") { linha ->
            linha.joinToString(",") { celula ->
                if (celula.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || celula.startsWith(' ') || celula.endsWith(' ')) {
                    "\"" + celula.replace("\"", "\"\"") + "\""
                } else {
                    celula
                }
            }
        }
    }

    /** Um .docx mínimo e válido (Word, LibreOffice, Google Docs), com estilos de título e tabela com bordas. */
    fun docx(documento: Documento): ByteArray {
        val corpo = StringBuilder()
        fun paragrafo(texto: String, estilo: String? = null, prefixo: String = "") {
            corpo.append("<w:p>")
            if (estilo != null) corpo.append("<w:pPr><w:pStyle w:val=\"").append(estilo).append("\"/></w:pPr>")
            corpo.append(corridas(prefixo + texto))
            corpo.append("</w:p>")
        }
        documento.blocos.forEach { bloco ->
            when (bloco) {
                is Bloco.Titulo -> paragrafo(bloco.texto, "Heading${bloco.nivel.coerceIn(1, 6)}")
                is Bloco.Paragrafo -> paragrafo(bloco.texto)
                is Bloco.Item -> paragrafo(bloco.texto, "ListParagraph", bloco.numero?.let { "$it.\t" } ?: "•\t")
                is Bloco.Tabela -> {
                    corpo.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>")
                    repeat(bloco.linhas.first().size) { corpo.append("<w:gridCol/>") }
                    corpo.append("</w:tblGrid>")
                    bloco.linhas.forEach { linha ->
                        corpo.append("<w:tr>")
                        linha.forEach { celula -> corpo.append("<w:tc><w:p>").append(corridas(celula)).append("</w:p></w:tc>") }
                        corpo.append("</w:tr>")
                    }
                    corpo.append("</w:tbl><w:p/>")
                }
                Bloco.Quebra -> corpo.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        val documentoXml = XML + "<w:document xmlns:w=\"$W\"><w:body>$corpo" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        return zip(
            "[Content_Types].xml" to XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "</Types>",
            "_rels/.rels" to XML + "<Relationships xmlns=\"$RELS\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>",
            "word/_rels/document.xml.rels" to XML + "<Relationships xmlns=\"$RELS\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                "</Relationships>",
            "word/styles.xml" to estilos(),
            "word/document.xml" to documentoXml,
        )
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** O texto em corridas do Word: tabulação e quebra de linha viram os elementos próprios. */
    private fun corridas(texto: String): String = buildString {
        append("<w:r>")
        texto.split('\n').forEachIndexed { linha, trecho ->
            if (linha > 0) append("<w:br/>")
            trecho.split('\t').forEachIndexed { coluna, pedaco ->
                if (coluna > 0) append("<w:tab/>")
                if (pedaco.isNotEmpty()) append("<w:t xml:space=\"preserve\">").append(escapar(semControle(pedaco))).append("</w:t>")
            }
        }
        append("</w:r>")
    }

    private fun estilos(): String = buildString {
        append(XML).append("<w:styles xmlns:w=\"$W\">")
        append("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:lang w:val=\"pt-BR\"/></w:rPr></w:rPrDefault>")
        append("<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>")
        append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>")
        val tamanhos = listOf(36, 30, 26, 24, 22, 22)
        tamanhos.forEachIndexed { indice, tamanho ->
            val nivel = indice + 1
            append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$nivel\"><w:name w:val=\"heading $nivel\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
            append("<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"$indice\"/></w:pPr>")
            append("<w:rPr><w:b/><w:sz w:val=\"$tamanho\"/></w:rPr></w:style>")
        }
        append("<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>")
        append("<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:style>")
        append("<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>") }
        append("</w:tblBorders><w:tblCellMar><w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>")
        append("</w:styles>")
    }

    private fun zip(vararg partes: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            partes.forEach { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry(nome))
                zip.write(conteudo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    fun escapar(texto: String): String = buildString(texto.length) {
        texto.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(it)
            }
        }
    }

    private fun comQuebras(texto: String) = escapar(texto).replace("\n", "<br>")

    /** O XML 1.0 não aceita caracteres de controle; o Word recusaria o arquivo. */
    private fun semControle(texto: String) = texto.filter { it == '\t' || it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }
}
