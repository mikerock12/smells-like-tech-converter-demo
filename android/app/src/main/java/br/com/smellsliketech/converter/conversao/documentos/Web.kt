package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** HTML e EPUB (com o jsoup, que aguenta HTML malformado), Markdown e CSV. */
object Web {
    private val BLOCOS = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "blockquote", "pre", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "table", "hr", "address", "center", "body",
        "form", "fieldset", "details", "summary", "nav",
    )
    private val IGNORADOS = setOf("script", "style", "head", "noscript", "template", "svg", "math", "iframe", "object", "button", "select", "textarea")

    fun html(bytes: ByteArray): Documento {
        val documento = Jsoup.parse(Codificacao.decodificar(bytes))
        val montador = Montador()
        blocos(documento.body(), montador)
        return montador.documento()
    }

    private fun blocos(elemento: Element, montador: Montador) {
        val trecho = StringBuilder()
        fun descarregar() {
            montador.paragrafo(trecho.toString())
            trecho.clear()
        }
        for (no in elemento.childNodes()) {
            if (no is Element && no.normalName() in IGNORADOS) continue
            if (no !is Element || no.normalName() !in BLOCOS) {
                trecho.append(textoEmLinha(no))
                continue
            }
            descarregar()
            when (val nome = no.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> montador.titulo(nome[1].digitToInt(), texto(no))
                "p", "pre", "dt", "dd", "figcaption", "summary", "address" -> montador.paragrafo(texto(no))
                "ul", "ol" -> lista(no, montador)
                "table" -> montador.tabela(
                    no.select("tr").filter { it.closest("table") == no }.map { linha ->
                        linha.children().filter { it.normalName() == "td" || it.normalName() == "th" }.map { texto(it) }
                    },
                )
                "hr" -> Unit
                else -> blocos(no, montador)
            }
        }
        descarregar()
    }

    private fun lista(lista: Element, montador: Montador) {
        val numerada = lista.normalName() == "ol"
        var numero = lista.attr("start").toIntOrNull() ?: 1
        for (item in lista.children()) {
            if (item.normalName() != "li") continue
            val proprio = item.clone().apply { select("ul, ol").remove() }
            montador.item(texto(proprio), if (numerada) numero++ else null)
            item.children().filter { it.normalName() == "ul" || it.normalName() == "ol" }.forEach { lista(it, montador) }
        }
    }

    /** O texto de um elemento, com <br> e blocos internos como quebra de linha. */
    private fun texto(elemento: Element): String = buildString {
        if (elemento.normalName() == "pre") { append(elemento.wholeText()); return@buildString }
        for (no in elemento.childNodes()) {
            if (no is Element && no.normalName() in IGNORADOS) continue
            if (no is Element && no.normalName() in BLOCOS) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append(texto(no))
                append('\n')
            } else {
                append(textoEmLinha(no))
            }
        }
    }.replace(Regex("""[ \t]*\n[ \t]*"""), "\n").trim()

    private fun textoEmLinha(no: Node): String = when (no) {
        is TextNode -> no.text()
        is Element -> when (no.normalName()) {
            "br" -> "\n"
            "img" -> no.attr("alt")
            in IGNORADOS -> ""
            else -> no.childNodes().joinToString("") { textoEmLinha(it) }
        }
        else -> ""
    }

    /** EPUB: os capítulos na ordem de leitura (spine), cada um depois de uma quebra de página. */
    fun epub(bytes: ByteArray): Documento {
        val pacote = Pacote(bytes)
        val raiz = pacote.xml("META-INF/container.xml")
            ?.descendentes("rootfile")?.firstOrNull()?.atributo("full-path")
            ?: pacote.nomes.firstOrNull { it.endsWith(".opf") }
            ?: throw ErroDeConversao("Não é um livro EPUB.")
        val opf = pacote.xml(raiz) ?: throw ErroDeConversao("O EPUB não tem o índice do livro.")
        val pasta = raiz.substringBeforeLast('/', "")
        val itens = opf.filho("manifest")?.filhos("item").orEmpty().associate { (it.atributo("id") ?: "") to (it.atributo("href") ?: "") }
        val montador = Montador()
        opf.filho("spine")?.filhos("itemref").orEmpty().forEach { referencia ->
            val href = itens[referencia.atributo("idref")] ?: return@forEach
            val caminho = Pacote.resolver(pasta, java.net.URLDecoder.decode(href, "UTF-8"))
            val capitulo = pacote.bytes(caminho) ?: return@forEach
            montador.quebra()
            blocos(Jsoup.parse(Codificacao.decodificar(capitulo)).body(), montador)
        }
        return montador.documento()
    }

    private val TITULO_MD = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val ITEM_MD = Regex("""^\s*(?:[-*+]|(\d+)[.)])\s+(.*)$""")
    private val SEPARADOR_MD = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val REGUA_MD = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    fun markdown(bytes: ByteArray): Documento {
        val linhas = Codificacao.decodificar(bytes).replace("\r\n", "\n").replace('\r', '\n').lines()
        val montador = Montador()
        val paragrafo = mutableListOf<String>()
        fun descarregar() {
            if (paragrafo.isNotEmpty()) montador.paragrafo(emLinha(paragrafo.joinToString(" ")))
            paragrafo.clear()
        }
        var indice = 0
        while (indice < linhas.size) {
            val linha = linhas[indice]
            val limpa = linha.trim()
            when {
                limpa.startsWith("```") || limpa.startsWith("~~~") -> {
                    descarregar()
                    val cerca = limpa.take(3)
                    val codigo = mutableListOf<String>()
                    indice++
                    while (indice < linhas.size && !linhas[indice].trim().startsWith(cerca)) codigo += linhas[indice++]
                    montador.paragrafo(codigo.joinToString("\n"))
                }
                limpa.isEmpty() -> descarregar()
                TITULO_MD.matches(limpa) -> {
                    descarregar()
                    val (marcas, texto) = TITULO_MD.find(limpa)!!.destructured
                    montador.titulo(marcas.length, emLinha(texto))
                }
                paragrafo.isNotEmpty() && Regex("""^=+$""").matches(limpa) -> { montador.titulo(1, emLinha(paragrafo.joinToString(" "))); paragrafo.clear() }
                paragrafo.isNotEmpty() && Regex("""^-+$""").matches(limpa) -> { montador.titulo(2, emLinha(paragrafo.joinToString(" "))); paragrafo.clear() }
                REGUA_MD.matches(limpa) -> descarregar()
                '|' in limpa && indice + 1 < linhas.size && SEPARADOR_MD.matches(linhas[indice + 1]) -> {
                    descarregar()
                    val tabela = mutableListOf(celulasMd(limpa))
                    indice += 2
                    while (indice < linhas.size && '|' in linhas[indice] && linhas[indice].isNotBlank()) tabela += celulasMd(linhas[indice++].trim())
                    montador.tabela(tabela.map { linha -> linha.map(::emLinha) })
                    continue
                }
                ITEM_MD.matches(linha) -> {
                    descarregar()
                    val (numero, texto) = ITEM_MD.find(linha)!!.destructured
                    montador.item(emLinha(texto.removePrefix("[ ] ").removePrefix("[x] ")), numero.toIntOrNull())
                }
                limpa.startsWith(">") -> paragrafo += limpa.trimStart('>', ' ')
                else -> paragrafo += limpa
            }
            indice++
        }
        descarregar()
        return montador.documento()
    }

    private fun celulasMd(linha: String): List<String> =
        linha.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /** Tira as marcas de ênfase, links, imagens e código em linha. */
    fun emLinha(texto: String): String = texto
        .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
        .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)"""), "$1")
        .replace(Regex("""(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)"""), "$1")
        .replace(Regex("""~~(.+?)~~"""), "$1")
        .replace(Regex("""`([^`]*)`"""), "$1")
        .replace(Regex("""\\([\\`*_{}\[\]()#+\-.!|>])"""), "$1")

    /** CSV com vírgula, ponto e vírgula ou tabulação (o que aparecer mais na primeira linha). */
    fun csv(bytes: ByteArray): Documento {
        val texto = Codificacao.decodificar(bytes)
        val primeira = texto.lineSequence().firstOrNull().orEmpty()
        val separador = listOf(',', ';', '\t').maxBy { candidato -> primeira.count { it == candidato } }
        val linhas = mutableListOf<List<String>>()
        var linha = mutableListOf<String>()
        val celula = StringBuilder()
        var aspas = false
        var indice = 0
        var celulas = 0
        while (indice < texto.length) {
            val caractere = texto[indice]
            when {
                aspas && caractere == '"' && indice + 1 < texto.length && texto[indice + 1] == '"' -> { celula.append('"'); indice++ }
                caractere == '"' && (aspas || celula.isEmpty()) -> aspas = !aspas
                !aspas && caractere == separador -> { linha += celula.toString(); celula.clear() }
                !aspas && (caractere == '\n' || caractere == '\r') -> {
                    if (caractere == '\r' && indice + 1 < texto.length && texto[indice + 1] == '\n') indice++
                    linha += celula.toString(); celula.clear()
                    celulas += linha.size
                    if (celulas > Limites.CELULAS) throw ErroDeConversao("A planilha é grande demais para o celular.")
                    linhas += linha; linha = mutableListOf()
                }
                else -> celula.append(caractere)
            }
            indice++
        }
        if (celula.isNotEmpty() || linha.isNotEmpty()) { linha += celula.toString(); linhas += linha }
        val montador = Montador()
        val largura = linhas.maxOfOrNull { it.size } ?: 0
        montador.tabela(linhas.map { atual -> List(largura) { atual.getOrElse(it) { "" } } })
        return montador.documento()
    }
}
