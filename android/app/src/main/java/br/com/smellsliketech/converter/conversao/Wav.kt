package br.com.smellsliketech.converter.conversao

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Juntar WAVs do mesmo formato (as partes de uma narração longa). Kotlin puro, testável. */
object Wav {
    class Partes(val formato: ByteArray, val dados: ByteArray)

    /** Separa o bloco "fmt " e o áudio. Aceita o tamanho de "data" zerado (motores que gravam em fluxo). */
    fun ler(bytes: ByteArray): Partes {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "Não é um WAV."
        }
        var posicao = 12
        var formato: ByteArray? = null
        while (posicao + 8 <= bytes.size) {
            val id = String(bytes, posicao, 4, Charsets.US_ASCII)
            val tamanho = ByteBuffer.wrap(bytes, posicao + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val inicio = posicao + 8
            if (id == "fmt ") formato = bytes.copyOfRange(inicio, inicio + tamanho)
            if (id == "data") {
                val fim = if (tamanho <= 0 || inicio + tamanho > bytes.size) bytes.size else inicio + tamanho
                return Partes(formato ?: error("WAV sem bloco de formato."), bytes.copyOfRange(inicio, fim))
            }
            posicao = inicio + tamanho + (tamanho and 1)
        }
        error("WAV sem áudio.")
    }

    fun juntar(partes: List<ByteArray>): ByteArray {
        val lidas = partes.map(::ler)
        val formato = lidas.first().formato
        require(lidas.all { it.formato.contentEquals(formato) }) { "As partes não têm o mesmo formato." }
        val dados = lidas.sumOf { it.dados.size }
        val cabecalho = ByteBuffer.allocate(12 + 8 + formato.size + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(4 + 8 + formato.size + 8 + dados)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(formato.size); put(formato)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dados)
        }.array()
        return lidas.fold(cabecalho) { acumulado, parte -> acumulado + parte.dados }
    }

    fun juntarArquivos(arquivos: List<File>, destino: File) {
        destino.writeBytes(juntar(arquivos.map { it.readBytes() }))
    }

    /** Corta o texto em partes de até `limite` caracteres, preferindo o fim de uma frase. */
    fun dividirTexto(texto: String, limite: Int): List<String> {
        val partes = mutableListOf<String>()
        var resto = texto.trim()
        while (resto.length > limite) {
            val janela = resto.substring(0, limite)
            val corte = listOf(". ", "! ", "? ", "\n").maxOf { janela.lastIndexOf(it) }.takeIf { it > limite / 3 }?.plus(1)
                ?: janela.lastIndexOf(' ').takeIf { it > 0 }
                ?: limite
            partes += resto.substring(0, corte).trim()
            resto = resto.substring(corte).trim()
        }
        if (resto.isNotEmpty()) partes += resto
        return partes
    }
}
