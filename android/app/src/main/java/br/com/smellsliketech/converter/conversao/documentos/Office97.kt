package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Word 97–2003 (.doc), Excel 97–2003 (.xls) e PowerPoint 97–2003 (.ppt): o texto, pelas
 * especificações abertas da Microsoft (MS-DOC, MS-XLS, MS-PPT). A formatação fica de fora.
 */
object Office97 {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    // ---------------------------------------------------------------- Word (.doc)

    fun doc(bytes: ByteArray): Documento {
        val arquivo = Cfb(bytes)
        val palavra = arquivo.fluxo("WordDocument") ?: throw ErroDeConversao("Não é um documento do Word 97–2003.")
        val fib = palavra.le()
        if (palavra.size < 0x1AA || fib.getShort(0).toInt() and 0xFFFF != 0xA5EC) throw ErroDeConversao("Documento do Word em formato desconhecido (anterior ao Word 97?).")
        val bandeiras = fib.getShort(0x0A).toInt()
        if (bandeiras and 0x0100 != 0) throw ErroDeConversao("O documento do Word está protegido por senha.")
        val tabela = arquivo.fluxo(if (bandeiras and 0x0200 != 0) "1Table" else "0Table") ?: throw ErroDeConversao("Documento do Word sem a tabela de texto.")

        // FibBase (32 bytes) → csw + fibRgW → cslw + fibRgLw → cbRgFcLcb + fibRgFcLcb.
        val csw = fib.getShort(32).toInt() and 0xFFFF
        val inicioLw = 34 + csw * 2
        val cslw = fib.getShort(inicioLw).toInt() and 0xFFFF
        val ccpText = fib.getInt(inicioLw + 2 + 3 * 4)
        val inicioFcLcb = inicioLw + 2 + cslw * 4 + 2
        val fcClx = fib.getInt(inicioFcLcb + 33 * 8)
        val lcbClx = fib.getInt(inicioFcLcb + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tabela.size) throw ErroDeConversao("Documento do Word corrompido (tabela de peças).")

        val texto = StringBuilder()
        val clx = tabela.le()
        var posicao = fcClx
        val fim = fcClx + lcbClx
        while (posicao < fim) {
            when (tabela[posicao].toInt()) {
                1 -> posicao += 3 + (clx.getShort(posicao + 1).toInt() and 0xFFFF) // Prc: formatação, pula
                2 -> {
                    val tamanho = clx.getInt(posicao + 1)
                    val inicio = posicao + 5
                    val pecas = (tamanho - 4) / 12
                    for (indice in 0 until pecas) {
                        val cpInicio = clx.getInt(inicio + indice * 4)
                        val cpFim = clx.getInt(inicio + (indice + 1) * 4)
                        val pcd = inicio + (pecas + 1) * 4 + indice * 8
                        val fc = clx.getInt(pcd + 2)
                        val comprimido = fc and 0x40000000 != 0
                        val quantos = (cpFim - cpInicio).coerceAtLeast(0)
                        if (comprimido) {
                            val de = (fc and 0x3FFFFFFF) / 2
                            if (de + quantos > palavra.size) throw ErroDeConversao("Documento do Word truncado.")
                            texto.append(String(palavra, de, quantos, WINDOWS_1252))
                        } else {
                            if (fc + quantos * 2 > palavra.size) throw ErroDeConversao("Documento do Word truncado.")
                            texto.append(String(palavra, fc, quantos * 2, Charsets.UTF_16LE))
                        }
                    }
                    posicao = fim
                }
                else -> throw ErroDeConversao("Documento do Word corrompido (tabela de peças).")
            }
        }
        // Só o corpo: rodapés, notas e cabeçalhos vêm depois de ccpText.
        val corpo = if (ccpText in 1..texto.length) texto.substring(0, ccpText) else texto.toString()
        return montarDoc(semCampos(corpo))
    }

    /** Tira os códigos de campo (0x13 instrução 0x14 resultado 0x15), ficando com o resultado. */
    fun semCampos(texto: String): String = buildString {
        // Para cada campo aberto: true enquanto estamos na instrução (antes do 0x14).
        val campos = ArrayDeque<Boolean>()
        for (caractere in texto) {
            when (caractere) {
                '\u0013' -> campos.addLast(true)
                '\u0014' -> if (campos.isNotEmpty()) { campos.removeLast(); campos.addLast(false) }
                '\u0015' -> campos.removeLastOrNull()
                else -> if (campos.none { it }) append(caractere)
            }
        }
    }

    /** Parágrafos terminam em \r; células em 0x07, e a linha da tabela termina com outro 0x07. */
    fun montarDoc(texto: String): Documento {
        val montador = Montador()
        val linhas = mutableListOf<List<String>>()
        val celulas = mutableListOf<String>()
        val pendentes = mutableListOf<String>()
        var ultimaFoiCelula = false
        fun fecharTabela() {
            if (celulas.isNotEmpty()) { linhas += celulas.toList(); celulas.clear() }
            if (linhas.isNotEmpty()) { montador.tabela(linhas.toList()); linhas.clear() }
            ultimaFoiCelula = false
        }
        val atual = StringBuilder()
        for (caractere in texto) {
            when (caractere) {
                '\r', '\u000C' -> {
                    val paragrafo = atual.toString(); atual.clear()
                    if (celulas.isNotEmpty()) {
                        pendentes += paragrafo
                    } else {
                        fecharTabela()
                        montador.paragrafo(paragrafo)
                        if (caractere == '\u000C') montador.quebra()
                    }
                }
                '\u0007' -> {
                    val conteudo = atual.toString(); atual.clear()
                    if (conteudo.isEmpty() && pendentes.isEmpty() && ultimaFoiCelula) {
                        linhas += celulas.toList(); celulas.clear()
                        ultimaFoiCelula = false
                    } else {
                        celulas += (pendentes + conteudo).joinToString("\n").trim()
                        pendentes.clear()
                        ultimaFoiCelula = true
                    }
                }
                '\u000B' -> atual.append('\n')
                '\t' -> atual.append('\t')
                '\u001E' -> atual.append('-')
                ' ' -> atual.append(' ')
                '\u0001', '\u0002', '\u0005', '\u0008', '\u001F' -> Unit // imagem, nota, comentário, desenho, hífen opcional
                else -> if (caractere >= ' ') atual.append(caractere)
            }
        }
        if (pendentes.isNotEmpty()) pendentes.forEach(montador::paragrafo)
        fecharTabela()
        montador.paragrafo(atual.toString())
        return montador.documento()
    }

    // ---------------------------------------------------------------- Excel (.xls, BIFF8)

    fun xls(bytes: ByteArray): Documento {
        val arquivo = Cfb(bytes)
        val livro = arquivo.fluxo("Workbook") ?: arquivo.fluxo("Book") ?: throw ErroDeConversao("Não é uma planilha do Excel 97–2003.")
        val registros = registros(livro)
        if (registros.any { it.tipo == 0x002F }) throw ErroDeConversao("A planilha do Excel está protegida por senha.")

        // Globais: strings compartilhadas, formatos e abas.
        val compartilhadas = mutableListOf<String>()
        val formatos = mutableMapOf<Int, String>()
        val estilos = mutableListOf<Int>()
        data class Aba(val nome: String, val posicao: Int)
        val abas = mutableListOf<Aba>()
        var data1904 = false
        var indice = 0
        while (indice < registros.size) {
            val registro = registros[indice]
            when (registro.tipo) {
                0x00FC -> { // SST, com os CONTINUE seguintes
                    val partes = mutableListOf(registro.dados)
                    while (indice + 1 < registros.size && registros[indice + 1].tipo == 0x003C) partes += registros[++indice].dados
                    compartilhadas += tabelaDeStrings(partes)
                }
                0x041E -> { // FORMAT
                    val buffer = registro.dados.le()
                    formatos[buffer.getShort(0).toInt() and 0xFFFF] = stringXl(registro.dados, 2, (buffer.getShort(2).toInt() and 0xFFFF), 4).first
                }
                0x00E0 -> estilos += registro.dados.le().getShort(2).toInt() and 0xFFFF // XF: o formato numérico
                0x0022 -> data1904 = registro.dados.le().getShort(0).toInt() == 1
                0x0085 -> { // BOUNDSHEET
                    val buffer = registro.dados.le()
                    val tipo = registro.dados[5].toInt()
                    if (tipo == 0) abas += Aba(stringXl(registro.dados, 6, registro.dados[6].toInt() and 0xFF, 7).first, buffer.getInt(0))
                }
            }
            indice++
        }
        val datas = estilos.mapIndexedNotNull { xf, formato -> xf.takeIf { Numeros.ehData(formato, formatos[formato]) } }.toSet()

        val montador = Montador()
        var celulasLidas = 0
        for (aba in abas) {
            val inicio = registros.indexOfFirst { it.posicao == aba.posicao }
            if (inicio < 0) continue
            val grade = sortedMapOf<Int, MutableMap<Int, String>>()
            fun por(linha: Int, coluna: Int, valor: String) {
                if (++celulasLidas > Limites.CELULAS) throw ErroDeConversao("A planilha é grande demais para o celular.")
                grade.getOrPut(linha) { sortedMapOf() }[coluna] = valor
            }
            fun numero(valor: Double, xf: Int) = if (xf in datas) Numeros.data(valor, data1904) else Numeros.texto(valor)
            var formulaPendente: Pair<Int, Int>? = null
            var posicao = inicio + 1
            while (posicao < registros.size && registros[posicao].tipo != 0x000A) {
                val registro = registros[posicao]
                val dados = registro.dados
                if (dados.size >= 6) {
                    val buffer = dados.le()
                    val linha = buffer.getShort(0).toInt() and 0xFFFF
                    val coluna = buffer.getShort(2).toInt() and 0xFFFF
                    val xf = buffer.getShort(4).toInt() and 0xFFFF
                    when (registro.tipo) {
                        0x00FD -> por(linha, coluna, compartilhadas.getOrElse(buffer.getInt(6)) { "" }) // LABELSST
                        0x0204 -> por(linha, coluna, stringXl(dados, 6, buffer.getShort(6).toInt() and 0xFFFF, 8).first) // LABEL
                        0x0203 -> por(linha, coluna, numero(buffer.getDouble(6), xf)) // NUMBER
                        0x027E -> por(linha, coluna, numero(rk(buffer.getInt(6)), xf)) // RK
                        0x00BD -> { // MULRK
                            val ultima = buffer.getShort(dados.size - 2).toInt() and 0xFFFF
                            for (atual in coluna..ultima) {
                                val base = 4 + (atual - coluna) * 6
                                if (base + 6 > dados.size - 2) break
                                por(linha, atual, numero(rk(buffer.getInt(base + 2)), buffer.getShort(base).toInt() and 0xFFFF))
                            }
                        }
                        0x0205 -> por(linha, coluna, if (dados[7].toInt() == 0) (if (dados[6].toInt() != 0) "VERDADEIRO" else "FALSO") else "#ERRO") // BOOLERR
                        0x0006 -> { // FORMULA: o resultado guardado
                            if ((buffer.getShort(12).toInt() and 0xFFFF) == 0xFFFF) {
                                when (dados[6].toInt()) {
                                    0 -> formulaPendente = linha to coluna
                                    1 -> por(linha, coluna, if (dados[8].toInt() != 0) "VERDADEIRO" else "FALSO")
                                    2 -> por(linha, coluna, "#ERRO")
                                }
                            } else {
                                por(linha, coluna, numero(buffer.getDouble(6), xf))
                            }
                        }
                    }
                }
                if (registro.tipo == 0x0207 && dados.size >= 3) { // STRING: o texto da fórmula anterior
                    formulaPendente?.let { (linha, coluna) -> por(linha, coluna, stringXl(dados, 0, dados.le().getShort(0).toInt() and 0xFFFF, 2).first) }
                    formulaPendente = null
                }
                posicao++
            }
            montador.titulo(2, aba.nome)
            val largura = (grade.values.maxOfOrNull { it.keys.maxOrNull() ?: -1 } ?: -1) + 1
            val ultima = grade.keys.maxOrNull() ?: -1
            montador.tabela((0..ultima).map { linha -> List(largura) { coluna -> grade[linha]?.get(coluna).orEmpty() } })
        }
        return montador.documento()
    }

    private class Registro(val tipo: Int, val dados: ByteArray, val posicao: Int)

    private fun registros(fluxo: ByteArray): List<Registro> {
        val buffer = fluxo.le()
        val lista = mutableListOf<Registro>()
        var posicao = 0
        while (posicao + 4 <= fluxo.size) {
            val tipo = buffer.getShort(posicao).toInt() and 0xFFFF
            val tamanho = buffer.getShort(posicao + 2).toInt() and 0xFFFF
            if (posicao + 4 + tamanho > fluxo.size) break
            lista += Registro(tipo, fluxo.copyOfRange(posicao + 4, posicao + 4 + tamanho), posicao)
            posicao += 4 + tamanho
        }
        return lista
    }

    /** Um número RK: inteiro ou os 30 bits altos de um double, talvez dividido por 100. */
    fun rk(valor: Int): Double {
        val numero = if (valor and 2 != 0) (valor shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((valor.toLong() and 0xFFFFFFFCL) shl 32)
        return if (valor and 1 != 0) numero / 100 else numero
    }

    /**
     * Uma XLUnicodeString: `caracteres` já lidos; em `posicao` vem o byte de opções.
     * Devolve o texto e onde a string termina.
     */
    private fun stringXl(dados: ByteArray, @Suppress("UNUSED_PARAMETER") inicioDoTamanho: Int, caracteres: Int, posicao: Int): Pair<String, Int> {
        if (posicao >= dados.size) return "" to posicao
        val opcoes = dados[posicao].toInt()
        var cursor = posicao + 1
        val buffer = dados.le()
        val ricos = if (opcoes and 0x08 != 0) (buffer.getShort(cursor).toInt() and 0xFFFF).also { cursor += 2 } else 0
        val extras = if (opcoes and 0x04 != 0) buffer.getInt(cursor).also { cursor += 4 } else 0
        val largos = opcoes and 0x01 != 0
        val bytesDoTexto = if (largos) caracteres * 2 else caracteres
        val fim = minOf(dados.size, cursor + bytesDoTexto)
        val texto = if (largos) String(dados, cursor, fim - cursor, Charsets.UTF_16LE) else String(dados, cursor, fim - cursor, Charsets.ISO_8859_1)
        return texto to (fim + ricos * 4 + extras)
    }

    /** A SST, cujas strings podem atravessar registros CONTINUE (com um novo byte de opções). */
    private fun tabelaDeStrings(partes: List<ByteArray>): List<String> {
        val strings = mutableListOf<String>()
        var parte = 0
        var dados = partes[0]
        var cursor = 8
        val total = dados.le().getInt(4)
        fun garantir(): Boolean {
            while (cursor >= dados.size) {
                if (++parte >= partes.size) return false
                dados = partes[parte]
                cursor = 0
            }
            return true
        }
        while (strings.size < total && garantir()) {
            if (cursor + 3 > dados.size) break
            val buffer = dados.le()
            val caracteres = buffer.getShort(cursor).toInt() and 0xFFFF
            var opcoes = dados[cursor + 2].toInt()
            cursor += 3
            val ricos = if (opcoes and 0x08 != 0) (buffer.getShort(cursor).toInt() and 0xFFFF).also { cursor += 2 } else 0
            val extras = if (opcoes and 0x04 != 0) buffer.getInt(cursor).also { cursor += 4 } else 0
            val texto = StringBuilder()
            var faltam = caracteres
            while (faltam > 0) {
                if (cursor >= dados.size) {
                    // Os caracteres continuam no próximo CONTINUE, que começa com um novo byte de opções.
                    if (++parte >= partes.size || partes[parte].isEmpty()) break
                    dados = partes[parte]
                    opcoes = dados[0].toInt()
                    cursor = 1
                }
                val largos = opcoes and 0x01 != 0
                val cabem = if (largos) (dados.size - cursor) / 2 else dados.size - cursor
                val agora = minOf(faltam, cabem)
                texto.append(if (largos) String(dados, cursor, agora * 2, Charsets.UTF_16LE) else String(dados, cursor, agora, Charsets.ISO_8859_1))
                cursor += if (largos) agora * 2 else agora
                faltam -= agora
                if (faltam > 0) { cursor = dados.size } // a string continua no próximo CONTINUE
            }
            strings += texto.toString()
            // Formatação rica e dados fonéticos: pulados, mesmo atravessando registros.
            var pular = ricos * 4 + extras
            while (pular > 0 && garantir()) {
                val aqui = minOf(pular, dados.size - cursor)
                cursor += aqui
                pular -= aqui
            }
        }
        return strings
    }

    // ---------------------------------------------------------------- PowerPoint (.ppt)

    fun ppt(bytes: ByteArray): Documento {
        val arquivo = Cfb(bytes)
        val fluxo = arquivo.fluxo("PowerPoint Document") ?: throw ErroDeConversao("Não é uma apresentação do PowerPoint 97–2003.")
        val slides = mutableListOf<MutableList<Pair<Int, String>>>()
        val listaDeTexto = mutableListOf<MutableList<Pair<Int, String>>>()
        val buffer = fluxo.le()

        // Registros: 2 bytes versão/instância, 2 tipo, 4 tamanho; versão 0xF = contêiner.
        fun visitar(inicio: Int, fim: Int, destino: MutableList<Pair<Int, String>>?, emListaDeSlides: Boolean) {
            var posicao = inicio
            var tipoDoTexto = -1
            var alvo = destino
            while (posicao + 8 <= fim) {
                val versao = buffer.getShort(posicao).toInt() and 0x000F
                val instancia = (buffer.getShort(posicao).toInt() and 0xFFFF) ushr 4
                val tipo = buffer.getShort(posicao + 2).toInt() and 0xFFFF
                val tamanho = buffer.getInt(posicao + 4)
                val dados = posicao + 8
                if (tamanho < 0 || dados + tamanho > fim) break
                when {
                    tipo == 0x03EE -> { // Slide
                        val slide = mutableListOf<Pair<Int, String>>()
                        slides += slide
                        visitar(dados, dados + tamanho, slide, false)
                    }
                    tipo == 0x03F8 || tipo == 0x03F0 -> Unit // mestres e anotações: fora
                    tipo == 0x0FF0 -> if (instancia == 0) visitar(dados, dados + tamanho, null, true) // SlideListWithText dos slides
                    tipo == 0x03F3 && emListaDeSlides -> { alvo = mutableListOf<Pair<Int, String>>().also { listaDeTexto += it } } // SlidePersistAtom
                    tipo == 0x0F9F -> tipoDoTexto = buffer.getInt(dados) // TextHeaderAtom
                    tipo == 0x0FA0 -> alvo?.add(tipoDoTexto to String(fluxo, dados, tamanho, Charsets.UTF_16LE)) // TextCharsAtom
                    tipo == 0x0FA8 -> alvo?.add(tipoDoTexto to String(fluxo, dados, tamanho, Charsets.ISO_8859_1)) // TextBytesAtom
                    versao == 0x0F -> visitar(dados, dados + tamanho, alvo, emListaDeSlides)
                }
                posicao = dados + tamanho
            }
        }
        visitar(0, fluxo.size, null, false)

        // O texto mora nos desenhos do slide (PowerPoint novo) ou na lista de texto (antigo).
        val fonte = if (slides.any { it.isNotEmpty() }) slides else listaDeTexto
        val montador = Montador()
        fonte.forEachIndexed { indice, textos ->
            if (indice > 0) montador.quebra()
            val titulo = textos.firstOrNull { it.first == 0 || it.first == 6 }?.second?.replace('\r', ' ')?.replace('\u000B', ' ')
            montador.titulo(2, titulo?.takeIf(String::isNotBlank)?.let { "${indice + 1}. ${it.trim()}" } ?: "Slide ${indice + 1}")
            textos.filter { it.first != 0 && it.first != 6 }.forEach { (_, texto) ->
                texto.split('\r').forEach { montador.paragrafo(it.replace('\u000B', '\n')) }
            }
        }
        return montador.documento()
    }
}
