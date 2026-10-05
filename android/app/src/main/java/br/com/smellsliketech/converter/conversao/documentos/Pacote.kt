package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Limites contra arquivos que se expandem demais (zip bomb). */
internal object Limites {
    const val BYTES_DESCOMPACTADOS = 256L * 1024 * 1024
    const val CELULAS = 2_000_000
}

/**
 * Os arquivos XML de um pacote zip (DOCX, XLSX, PPTX, ODT, ODS, ODP, EPUB). Só as partes de
 * texto: as imagens não interessam e ficam de fora da memória.
 */
internal class Pacote(bytes: ByteArray) {
    private val partes: Map<String, ByteArray>

    init {
        val lidas = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entrada = zip.nextEntry ?: break
                    val nome = entrada.name.trimStart('/')
                    if (entrada.isDirectory || !textual(nome)) continue
                    val saida = ByteArrayOutputStream()
                    val bloco = ByteArray(1 shl 16)
                    while (true) {
                        val lidos = zip.read(bloco)
                        if (lidos < 0) break
                        total += lidos
                        if (total > Limites.BYTES_DESCOMPACTADOS) throw ErroDeConversao("O documento é grande demais para abrir no celular.")
                        saida.write(bloco, 0, lidos)
                    }
                    lidas[nome] = saida.toByteArray()
                }
            }
        } catch (erro: ErroDeConversao) {
            throw erro
        } catch (erro: Exception) {
            throw ErroDeConversao("O documento está corrompido (não é um pacote zip válido).", erro)
        }
        if (lidas.isEmpty()) throw ErroDeConversao("O documento está vazio ou corrompido.")
        partes = lidas
    }

    private fun textual(nome: String): Boolean {
        val minusculo = nome.lowercase()
        return listOf(".xml", ".rels", ".opf", ".xhtml", ".html", ".htm", ".ncx").any(minusculo::endsWith) || minusculo == "mimetype"
    }

    fun tem(nome: String) = nome in partes
    fun bytes(nome: String): ByteArray? = partes[nome.trimStart('/')]
    fun xml(nome: String): Element? = bytes(nome)?.let { Xml.ler(it) }
    val nomes: Set<String> get() = partes.keys

    /** Os relacionamentos (_rels) de uma parte: Id → caminho completo no pacote. */
    fun relacoes(parte: String): Map<String, String> {
        val pasta = parte.substringBeforeLast('/', "")
        val arquivo = parte.substringAfterLast('/')
        val rels = xml(if (pasta.isEmpty()) "_rels/$arquivo.rels" else "$pasta/_rels/$arquivo.rels") ?: return emptyMap()
        return rels.filhos("Relationship").associate { relacao ->
            val alvo = relacao.getAttribute("Target")
            relacao.getAttribute("Id") to resolver(pasta, alvo)
        }
    }

    companion object {
        fun resolver(pasta: String, alvo: String): String {
            if (alvo.startsWith("/")) return alvo.trimStart('/')
            val partes = (if (pasta.isEmpty()) emptyList() else pasta.split('/')).toMutableList()
            alvo.substringBefore('#').split('/').forEach { pedaco ->
                when (pedaco) {
                    "", "." -> Unit
                    ".." -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
                    else -> partes += pedaco
                }
            }
            return partes.joinToString("/")
        }
    }
}

internal object Xml {
    fun ler(bytes: ByteArray): Element {
        val fabrica = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Sem DTD e sem entidades externas (XXE). O Android não conhece todas as opções.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return try {
            fabrica.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        } catch (erro: Exception) {
            throw ErroDeConversao("O documento tem uma parte ilegível.", erro)
        }
    }
}

internal val Node.nome: String get() = localName ?: nodeName.substringAfter(':')

internal fun Element.filhos(): List<Element> {
    val lista = ArrayList<Element>()
    var no = firstChild
    while (no != null) {
        if (no is Element) lista += no
        no = no.nextSibling
    }
    return lista
}

internal fun Element.filhos(nome: String): List<Element> = filhos().filter { it.nome == nome }
internal fun Element.filho(nome: String): Element? = filhos().firstOrNull { it.nome == nome }

/** Todos os descendentes com esse nome, em ordem. */
internal fun Element.descendentes(nome: String): List<Element> {
    val lista = ArrayList<Element>()
    fun visitar(elemento: Element) {
        for (filho in elemento.filhos()) {
            if (filho.nome == nome) lista += filho
            visitar(filho)
        }
    }
    visitar(this)
    return lista
}

/** Um atributo pelo nome local, qualquer que seja o prefixo (w:val, text:outline-level…). */
internal fun Element.atributo(nome: String): String? {
    val atributos = attributes
    for (indice in 0 until atributos.length) {
        val atributo = atributos.item(indice)
        if (atributo.nome == nome) return atributo.nodeValue
    }
    return null
}

/** Texto de arquivos simples (TXT, MD, CSV, SRT): UTF-8 ou UTF-16 com BOM, UTF-8, ou Windows-1252. */
object Codificacao {
    fun decodificar(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
