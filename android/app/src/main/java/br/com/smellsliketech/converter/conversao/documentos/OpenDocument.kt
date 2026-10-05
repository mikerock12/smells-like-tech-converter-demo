package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import org.w3c.dom.Element
import org.w3c.dom.Text

/** LibreOffice e OpenOffice: texto (.odt), planilha (.ods) e apresentação (.odp). */
object OpenDocument {
    private fun conteudo(bytes: ByteArray): Element {
        val pacote = Pacote(bytes)
        val raiz = pacote.xml("content.xml") ?: throw ErroDeConversao("Não é um documento do LibreOffice.")
        return raiz.filho("body") ?: throw ErroDeConversao("O documento do LibreOffice não tem corpo.")
    }

    fun odt(bytes: ByteArray): Documento {
        val corpo = conteudo(bytes).let { it.filho("text") ?: it.filhos().firstOrNull() } ?: return Documento(emptyList())
        val montador = Montador()
        blocos(corpo, montador)
        return montador.documento()
    }

    private fun blocos(elemento: Element, montador: Montador) {
        for (filho in elemento.filhos()) {
            when (filho.nome) {
                "h" -> montador.titulo(filho.atributo("outline-level")?.toIntOrNull() ?: 1, texto(filho))
                "p" -> montador.paragrafo(texto(filho))
                "list" -> lista(filho, montador)
                "table" -> montador.tabela(linhasDaTabela(filho))
                "section", "index-body", "table-of-content", "illustration-index", "alphabetical-index", "change" -> blocos(filho, montador)
                "frame" -> filho.filho("text-box")?.let { blocos(it, montador) }
            }
        }
    }

    private fun lista(lista: Element, montador: Montador) {
        for (item in lista.filhos("list-item") + lista.filhos("list-header")) {
            for (filho in item.filhos()) when (filho.nome) {
                "p", "h" -> montador.item(texto(filho))
                "list" -> lista(filho, montador)
            }
        }
    }

    /** O texto de um parágrafo: text:s são espaços, text:tab e text:line-break viram \t e \n; notas saem. */
    fun texto(elemento: Element): String = buildString {
        fun visitar(no: Element) {
            var filho = no.firstChild
            while (filho != null) {
                when {
                    filho is Text -> append(filho.data)
                    filho is Element -> when (filho.nome) {
                        "s" -> repeat((filho.atributo("c")?.toIntOrNull() ?: 1).coerceIn(1, 1000)) { append(' ') }
                        "tab" -> append('\t')
                        "line-break" -> append('\n')
                        "note", "annotation", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "change-start", "change-end" -> Unit
                        "p", "h" -> { if (isNotEmpty()) append('\n'); visitar(filho) }
                        else -> visitar(filho)
                    }
                }
                filho = filho.nextSibling
            }
        }
        visitar(elemento)
    }

    /** As linhas de uma tabela, com as repetições (number-rows/columns-repeated) expandidas até um limite. */
    private fun linhasDaTabela(tabela: Element): List<List<String>> {
        val linhas = mutableListOf<List<String>>()
        var celulas = 0
        fun visitar(elemento: Element) {
            for (filho in elemento.filhos()) when (filho.nome) {
                "table-row" -> {
                    val linha = mutableListOf<String>()
                    for (celula in filho.filhos()) {
                        if (celula.nome != "table-cell" && celula.nome != "covered-table-cell") continue
                        val valor = valorDaCelula(celula)
                        val repeticoes = celula.atributo("number-columns-repeated")?.toIntOrNull() ?: 1
                        // Uma célula vazia repetida 16 mil vezes é só o "resto" da planilha.
                        repeat(repeticoes.coerceAtMost(if (valor.isEmpty()) 64 else 1024)) { linha += valor }
                    }
                    val repeticoes = filho.atributo("number-rows-repeated")?.toIntOrNull() ?: 1
                    val vazia = linha.all(String::isEmpty)
                    repeat(repeticoes.coerceAtMost(if (vazia) 64 else 10_000)) {
                        celulas += linha.size
                        if (celulas > Limites.CELULAS) throw ErroDeConversao("A planilha é grande demais para o celular.")
                        linhas += linha
                    }
                }
                "table-header-rows", "table-rows", "table-row-group" -> visitar(filho)
            }
        }
        visitar(tabela)
        return linhas
    }

    private fun valorDaCelula(celula: Element): String {
        val texto = celula.filhos().filter { it.nome == "p" || it.nome == "h" }.joinToString("\n") { texto(it) }
        if (texto.isNotEmpty()) return texto
        return when (celula.atributo("value-type")) {
            "float", "percentage", "currency" -> celula.atributo("value")?.toDoubleOrNull()?.let(Numeros::texto).orEmpty()
            "date" -> celula.atributo("date-value").orEmpty()
            "time" -> celula.atributo("time-value").orEmpty()
            "boolean" -> if (celula.atributo("boolean-value") == "true") "VERDADEIRO" else "FALSO"
            else -> ""
        }
    }

    fun ods(bytes: ByteArray): Documento {
        val planilhas = conteudo(bytes).let { it.filho("spreadsheet") ?: it.filhos().firstOrNull() } ?: return Documento(emptyList())
        val montador = Montador()
        planilhas.filhos("table").forEach { tabela ->
            montador.titulo(2, tabela.atributo("name").orEmpty())
            montador.tabela(linhasDaTabela(tabela))
        }
        return montador.documento()
    }

    fun odp(bytes: ByteArray): Documento {
        val apresentacao = conteudo(bytes).let { it.filho("presentation") ?: it.filhos().firstOrNull() } ?: return Documento(emptyList())
        val montador = Montador()
        apresentacao.filhos("page").forEachIndexed { indice, pagina ->
            if (indice > 0) montador.quebra()
            var titulo: String? = null
            val corpo = Montador()
            for (quadro in pagina.descendentes("frame")) {
                val classe = quadro.atributo("class")
                val caixa = quadro.filho("text-box")
                val tabela = quadro.filho("table")
                when {
                    caixa != null && classe == "title" && titulo == null -> titulo = texto(caixa).replace('\n', ' ')
                    caixa != null && classe != "page-number" && classe != "date-time" && classe != "footer" && classe != "header" -> blocos(caixa, corpo)
                    tabela != null -> corpo.tabela(linhasDaTabela(tabela))
                }
            }
            montador.titulo(2, titulo?.takeIf(String::isNotBlank)?.let { "${indice + 1}. $it" } ?: "Slide ${indice + 1}")
            corpo.documento().blocos.forEach { bloco ->
                when (bloco) {
                    is Bloco.Titulo -> montador.paragrafo(bloco.texto)
                    is Bloco.Paragrafo -> montador.paragrafo(bloco.texto)
                    is Bloco.Item -> montador.item(bloco.texto, bloco.numero)
                    is Bloco.Tabela -> montador.tabela(bloco.linhas)
                    Bloco.Quebra -> Unit
                }
            }
        }
        return montador.documento()
    }
}
