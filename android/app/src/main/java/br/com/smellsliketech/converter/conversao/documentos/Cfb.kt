package br.com.smellsliketech.converter.conversao.documentos

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * O "Compound File Binary" do Office 97–2003 (.doc, .xls, .ppt): um sistema de arquivos
 * dentro do arquivo, com setores e uma tabela de alocação (MS-CFB). Só leitura.
 */
class Cfb(private val bytes: ByteArray) {
    private val dados = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val tamanhoDoSetor: Int
    private val tamanhoDoMiniSetor: Int
    private val limiteDoMini: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniFluxo: ByteArray
    private val entradas: List<Entrada>

    private class Entrada(val nome: String, val tipo: Int, val inicio: Int, val tamanho: Long)

    init {
        if (bytes.size < 512 || dados.getLong(0) != ASSINATURA) throw ErroDeConversao("Não é um arquivo do Office 97–2003.")
        tamanhoDoSetor = 1 shl dados.getShort(0x1E).toInt()
        tamanhoDoMiniSetor = 1 shl dados.getShort(0x20).toInt()
        if (tamanhoDoSetor !in listOf(512, 4096) || tamanhoDoMiniSetor != 64) throw ErroDeConversao("Arquivo do Office corrompido.")
        limiteDoMini = dados.getInt(0x38)

        // Os setores da FAT: 109 no cabeçalho, o resto numa corrente de setores DIFAT.
        val setoresDaFat = mutableListOf<Int>()
        for (indice in 0 until 109) dados.getInt(0x4C + indice * 4).takeIf { it >= 0 }?.let { setoresDaFat += it }
        var difat = dados.getInt(0x44)
        var voltas = 0
        val porSetor = tamanhoDoSetor / 4
        while (difat >= 0 && voltas++ < 10_000) {
            val inicio = deslocamento(difat)
            for (indice in 0 until porSetor - 1) dados.getInt(inicio + indice * 4).takeIf { it >= 0 }?.let { setoresDaFat += it }
            difat = dados.getInt(inicio + (porSetor - 1) * 4)
        }
        fat = IntArray(setoresDaFat.size * porSetor)
        setoresDaFat.forEachIndexed { ordem, setor ->
            val inicio = deslocamento(setor)
            for (indice in 0 until porSetor) fat[ordem * porSetor + indice] = dados.getInt(inicio + indice * 4)
        }

        val diretorio = corrente(dados.getInt(0x30), Long.MAX_VALUE)
        val lidas = mutableListOf<Entrada>()
        val buffer = ByteBuffer.wrap(diretorio).order(ByteOrder.LITTLE_ENDIAN)
        for (posicao in 0 until diretorio.size / 128) {
            val base = posicao * 128
            val comprimento = buffer.getShort(base + 0x40).toInt().coerceIn(0, 64)
            val nome = String(diretorio, base, (comprimento - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val tamanho = buffer.getInt(base + 0x78).toLong() and 0xFFFFFFFFL
            lidas += Entrada(nome, diretorio[base + 0x42].toInt(), buffer.getInt(base + 0x74), tamanho)
        }
        entradas = lidas
        val raiz = lidas.firstOrNull { it.tipo == 5 } ?: throw ErroDeConversao("Arquivo do Office sem diretório raiz.")
        miniFluxo = if (raiz.inicio >= 0) corrente(raiz.inicio, raiz.tamanho) else ByteArray(0)
        val miniFatBytes = dados.getInt(0x3C).takeIf { it >= 0 }?.let { corrente(it, Long.MAX_VALUE) } ?: ByteArray(0)
        val miniFatBuffer = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
        miniFat = IntArray(miniFatBytes.size / 4) { miniFatBuffer.getInt(it * 4) }
    }

    private fun deslocamento(setor: Int): Int {
        val inicio = (setor.toLong() + 1) * tamanhoDoSetor
        if (setor < 0 || inicio + tamanhoDoSetor > bytes.size) throw ErroDeConversao("Arquivo do Office truncado ou corrompido.")
        return inicio.toInt()
    }

    /** Lê uma corrente de setores da FAT, até `tamanho` bytes. */
    private fun corrente(primeiro: Int, tamanho: Long): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        var setor = primeiro
        var voltas = 0
        while (setor >= 0 && saida.size() < tamanho) {
            if (voltas++ > bytes.size / tamanhoDoSetor + 1) throw ErroDeConversao("Arquivo do Office com setores em ciclo.")
            saida.write(bytes, deslocamento(setor), tamanhoDoSetor)
            setor = fat.getOrElse(setor) { FIM }
        }
        val lidos = saida.toByteArray()
        return if (tamanho < lidos.size) lidos.copyOf(tamanho.toInt()) else lidos
    }

    private fun miniCorrente(primeiro: Int, tamanho: Long): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        var setor = primeiro
        var voltas = 0
        while (setor >= 0 && saida.size() < tamanho) {
            if (voltas++ > miniFat.size + 1) throw ErroDeConversao("Arquivo do Office com setores em ciclo.")
            val inicio = setor * tamanhoDoMiniSetor
            if (inicio + tamanhoDoMiniSetor > miniFluxo.size) throw ErroDeConversao("Arquivo do Office truncado ou corrompido.")
            saida.write(miniFluxo, inicio, tamanhoDoMiniSetor)
            setor = miniFat.getOrElse(setor) { FIM }
        }
        return saida.toByteArray().copyOf(tamanho.toInt())
    }

    fun tem(nome: String) = entradas.any { it.tipo == 2 && it.nome.equals(nome, ignoreCase = true) }

    /** Um fluxo (stream) pelo nome, onde quer que esteja na árvore. */
    fun fluxo(nome: String): ByteArray? {
        val entrada = entradas.firstOrNull { it.tipo == 2 && it.nome.equals(nome, ignoreCase = true) } ?: return null
        if (entrada.tamanho > Limites.BYTES_DESCOMPACTADOS) throw ErroDeConversao("O documento é grande demais para abrir no celular.")
        if (entrada.tamanho == 0L) return ByteArray(0)
        return if (entrada.tamanho < limiteDoMini) miniCorrente(entrada.inicio, entrada.tamanho) else corrente(entrada.inicio, entrada.tamanho)
    }

    companion object {
        private const val ASSINATURA = -0x1ee54e5e1fee3030L // D0 CF 11 E0 A1 B1 1A E1, lido em little-endian
        private const val FIM = -2

        fun eh(bytes: ByteArray) = bytes.size >= 8 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(0) == ASSINATURA
    }
}
