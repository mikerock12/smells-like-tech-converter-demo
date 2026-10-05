package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import org.w3c.dom.Element
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Word (.docx), Excel (.xlsx) e PowerPoint (.pptx): o XML de dentro do pacote. */
object Ooxml {
    /** O r:id que liga uma aba ou um slide ao seu arquivo (o slide também tem um "id" numérico). */
    private const val RELACOES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun Element.relacao(): String = getAttributeNS(RELACOES, "id")

    fun docx(bytes: ByteArray): Documento {
        val pacote = Pacote(bytes)
        val principal = pacote.relacoes("").values.firstOrNull { it.endsWith(".xml") && pacote.tem(it) && "document" in it.lowercase() }
            ?: "word/document.xml"
        val raiz = pacote.xml(principal) ?: throw ErroDeConversao("Não é um documento do Word.")
        val estilos = nomesDosEstilos(pacote.xml(principal.substringBeforeLast('/') + "/styles.xml"))
        val corpo = raiz.filho("body") ?: throw ErroDeConversao("O documento do Word não tem corpo.")
        val montador = Montador()
        fun visitar(elemento: Element) {
            for (filho in elemento.filhos()) {
                when (filho.nome) {
                    "p" -> paragrafoDoWord(filho, estilos, montador)
                    "tbl" -> montador.tabela(
                        filho.filhos("tr").map { linha ->
                            linha.filhos("tc").map { celula -> celula.descendentes("p").joinToString("\n") { textoDoWord(it) }.trim() }
                        },
                    )
                    "sdt" -> filho.filho("sdtContent")?.let(::visitar)
                    "customXml", "ins", "smartTag" -> visitar(filho)
                }
            }
        }
        visitar(corpo)
        return montador.documento()
    }

    /** styleId → nome ("Ttulo1" → "heading 1"): no Word em português o id muda, o nome não. */
    private fun nomesDosEstilos(raiz: Element?): Map<String, String> =
        raiz?.filhos("style")?.associate { estilo ->
            estilo.atributo("styleId").orEmpty() to (estilo.filho("name")?.atributo("val") ?: "").lowercase()
        }.orEmpty()

    private val TITULO = Regex("""^(?:heading|t[ií]tulo|ttulo)\s*(\d)$""", RegexOption.IGNORE_CASE)

    private fun paragrafoDoWord(paragrafo: Element, estilos: Map<String, String>, montador: Montador) {
        val propriedades = paragrafo.filho("pPr")
        val estilo = propriedades?.filho("pStyle")?.atributo("val").orEmpty()
        val nome = estilos[estilo] ?: estilo.lowercase()
        val texto = textoDoWord(paragrafo)
        val nivelDeContorno = propriedades?.filho("outlineLvl")?.atributo("val")?.toIntOrNull()
        val nivel = TITULO.find(nome)?.groupValues?.get(1)?.toIntOrNull()
            ?: TITULO.find(estilo)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (nome == "title" || estilo.equals("Title", true)) 1 else null
            ?: nivelDeContorno?.takeIf { it in 0..5 }?.plus(1)
        when {
            nivel != null -> montador.titulo(nivel, texto)
            propriedades?.filho("numPr") != null || "list" in nome -> montador.item(texto)
            else -> montador.paragrafo(texto)
        }
    }

    /** O texto das corridas: tabulação e quebras viram \t e \n; revisões apagadas e códigos de campo ficam de fora. */
    private fun textoDoWord(elemento: Element): String = buildString {
        fun visitar(no: Element) {
            for (filho in no.filhos()) {
                when (filho.nome) {
                    "t" -> append(filho.textContent)
                    "tab", "ptab" -> append('\t')
                    "br", "cr" -> if (filho.atributo("type") != "page") append('\n')
                    "noBreakHyphen" -> append('-')
                    "sym" -> filho.atributo("char")?.toIntOrNull(16)?.let { if (it < 0xF000) append(it.toChar()) }
                    "del", "delText", "instrText", "pPr", "rPr", "fldData", "footnoteReference", "endnoteReference", "commentReference" -> Unit
                    "p" -> { if (isNotEmpty()) append('\n'); visitar(filho) }
                    else -> visitar(filho)
                }
            }
        }
        visitar(elemento)
    }

    fun xlsx(bytes: ByteArray): Documento {
        val pacote = Pacote(bytes)
        val livro = pacote.xml("xl/workbook.xml") ?: throw ErroDeConversao("Não é uma planilha do Excel.")
        val relacoes = pacote.relacoes("xl/workbook.xml")
        val compartilhadas = pacote.xml("xl/sharedStrings.xml")?.filhos("si")?.map(::textoDoExcel).orEmpty()
        val datas = formatosDeData(pacote.xml("xl/styles.xml"))
        val data1904 = livro.filho("workbookPr")?.atributo("date1904").let { it == "1" || it == "true" }
        val montador = Montador()
        var celulas = 0
        livro.filho("sheets")?.filhos("sheet").orEmpty().forEach { aba ->
            val caminho = relacoes[aba.relacao()] ?: return@forEach
            val planilha = pacote.xml(caminho) ?: return@forEach
            val linhas = sortedMapOf<Int, MutableMap<Int, String>>()
            var proximaLinha = 0
            planilha.filho("sheetData")?.filhos("row").orEmpty().forEach { linha ->
                val numero = linha.atributo("r")?.toIntOrNull()?.minus(1) ?: proximaLinha
                proximaLinha = numero + 1
                val valores = linhas.getOrPut(numero) { sortedMapOf() }
                var proximaColuna = 0
                linha.filhos("c").forEach { celula ->
                    val coluna = celula.atributo("r")?.let(::coluna) ?: proximaColuna
                    proximaColuna = coluna + 1
                    if (++celulas > Limites.CELULAS) throw ErroDeConversao("A planilha é grande demais para o celular.")
                    valores[coluna] = valorDoExcel(celula, compartilhadas, datas, data1904)
                }
            }
            montador.titulo(2, aba.atributo("name").orEmpty())
            val largura = (linhas.values.maxOfOrNull { it.keys.maxOrNull() ?: -1 } ?: -1) + 1
            val ultima = linhas.keys.maxOrNull() ?: -1
            montador.tabela((0..ultima).map { indice -> List(largura) { coluna -> linhas[indice]?.get(coluna).orEmpty() } })
        }
        return montador.documento()
    }

    /** "AB12" → 27 (a coluna, a partir de 0). */
    fun coluna(referencia: String): Int? {
        var valor = 0
        var letras = 0
        for (letra in referencia) {
            if (!letra.isLetter()) break
            valor = valor * 26 + (letra.uppercaseChar() - 'A' + 1)
            letras++
        }
        return if (letras == 0) null else valor - 1
    }

    private fun textoDoExcel(elemento: Element): String = buildString {
        fun visitar(no: Element) {
            for (filho in no.filhos()) when (filho.nome) {
                "t" -> append(filho.textContent)
                "rPh", "phoneticPr" -> Unit
                else -> visitar(filho)
            }
        }
        visitar(elemento)
    }

    private fun valorDoExcel(celula: Element, compartilhadas: List<String>, datas: Set<Int>, data1904: Boolean): String {
        val bruto = celula.filho("v")?.textContent
        return when (celula.atributo("t")) {
            "s" -> bruto?.trim()?.toIntOrNull()?.let { compartilhadas.getOrNull(it) }.orEmpty()
            "inlineStr" -> celula.filho("is")?.let(::textoDoExcel).orEmpty()
            "b" -> if (bruto?.trim() == "1") "VERDADEIRO" else "FALSO"
            "str", "e" -> bruto.orEmpty()
            else -> {
                val numero = bruto?.trim()?.toDoubleOrNull() ?: return bruto.orEmpty()
                val estilo = celula.atributo("s")?.toIntOrNull() ?: 0
                if (estilo in datas) Numeros.data(numero, data1904) else Numeros.texto(numero)
            }
        }
    }

    /** Os índices de estilo (cellXfs) cujo formato é de data ou hora. */
    private fun formatosDeData(estilos: Element?): Set<Int> {
        if (estilos == null) return emptySet()
        val proprios = estilos.filho("numFmts")?.filhos("numFmt").orEmpty()
            .associate { (it.atributo("numFmtId")?.toIntOrNull() ?: -1) to it.atributo("formatCode").orEmpty() }
        return estilos.filho("cellXfs")?.filhos("xf").orEmpty().mapIndexedNotNull { indice, xf ->
            val formato = xf.atributo("numFmtId")?.toIntOrNull() ?: 0
            indice.takeIf { Numeros.ehData(formato, proprios[formato]) }
        }.toSet()
    }

    fun pptx(bytes: ByteArray): Documento {
        val pacote = Pacote(bytes)
        val apresentacao = pacote.xml("ppt/presentation.xml") ?: throw ErroDeConversao("Não é uma apresentação do PowerPoint.")
        val relacoes = pacote.relacoes("ppt/presentation.xml")
        val montador = Montador()
        apresentacao.filho("sldIdLst")?.filhos("sldId").orEmpty().forEachIndexed { indice, id ->
            val caminho = relacoes[id.relacao()] ?: return@forEachIndexed
            val slide = pacote.xml(caminho) ?: return@forEachIndexed
            if (indice > 0) montador.quebra()
            var titulo: String? = null
            val corpo = mutableListOf<() -> Unit>()
            slide.descendentes("sp").forEach { forma ->
                val tipo = forma.filho("nvSpPr")?.filho("nvPr")?.filho("ph")?.atributo("type")
                val paragrafos = forma.filho("txBody")?.filhos("p").orEmpty().map(::textoDoSlide).filter(String::isNotBlank)
                if ((tipo == "title" || tipo == "ctrTitle") && titulo == null) {
                    titulo = paragrafos.joinToString(" ")
                } else {
                    paragrafos.forEach { texto -> corpo += { montador.paragrafo(texto) } }
                }
            }
            slide.descendentes("tbl").forEach { tabela ->
                val linhas = tabela.filhos("tr").map { linha -> linha.filhos("tc").map { celula -> celula.descendentes("p").joinToString("\n", transform = ::textoDoSlide) } }
                corpo += { montador.tabela(linhas) }
            }
            montador.titulo(2, titulo?.takeIf(String::isNotBlank)?.let { "${indice + 1}. $it" } ?: "Slide ${indice + 1}")
            corpo.forEach { it() }
        }
        return montador.documento()
    }

    private fun textoDoSlide(paragrafo: Element): String = buildString {
        for (filho in paragrafo.filhos()) when (filho.nome) {
            "r", "fld" -> append(filho.filho("t")?.textContent.orEmpty())
            "br" -> append('\n')
        }
    }
}

/** Números e datas de planilha, iguais nos leitores do Excel e do OpenDocument. */
object Numeros {
    private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** 15 algarismos, como o Excel mostra: 0.30000000000000004 vira 0.3. */
    fun texto(numero: Double): String {
        if (numero.isNaN() || numero.isInfinite()) return numero.toString()
        if (numero == floor(numero) && kotlin.math.abs(numero) < 1e15) return numero.toLong().toString()
        return BigDecimal(numero).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** Número de série do Excel → data (a partir de 30/12/1899, ou 1904 nos arquivos antigos do Mac). */
    fun data(serie: Double, sistema1904: Boolean = false): String {
        if (serie < 0 || serie > 2_958_465) return texto(serie)
        val base = if (sistema1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        val dias = floor(serie).toLong()
        val segundos = ((serie - dias) * 86_400).roundToLong()
        val momento = base.plusDays(dias).plusSeconds(segundos)
        return when {
            segundos == 0L -> momento.toLocalDate().format(DATA)
            dias == 0L && !sistema1904 -> String.format(Locale.ROOT, "%02d:%02d:%02d", segundos / 3600, segundos / 60 % 60, segundos % 60)
            else -> momento.format(DATA_E_HORA)
        }
    }

    fun ehData(formato: Int, codigo: String?): Boolean {
        if (formato in 14..22 || formato in 45..47 || formato in 27..36 || formato in 50..58) return true
        if (codigo == null) return false
        // Tira o que está entre aspas, colchetes e escapes; sobra o padrão de data se houver d, m, y ou h.
        val limpo = codigo.replace(Regex("\"[^\"]*\""), "").replace(Regex("""\[[^]]*]"""), "").replace(Regex("""\\."""), "").lowercase()
        return limpo.any { it == 'd' || it == 'y' || it == 'h' || it == 's' } && !limpo.contains('0') && !limpo.contains('#')
    }
}
