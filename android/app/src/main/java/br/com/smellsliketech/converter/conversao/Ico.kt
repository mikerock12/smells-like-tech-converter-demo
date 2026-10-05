package br.com.smellsliketech.converter.conversao

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Um .ico com várias resoluções, cada uma um PNG dentro do arquivo (o formato que o Windows
 * aceita desde o Vista). Kotlin puro: os testes rodam no computador.
 */
object Ico {
    /** Os tamanhos de um ícone do Windows, do menor ao maior. */
    val TAMANHOS = listOf(16, 24, 32, 48, 64, 128, 256)

    fun montar(imagens: List<Pair<Int, ByteArray>>): ByteArray {
        require(imagens.isNotEmpty() && imagens.all { it.first in 1..256 }) { "Ícone precisa de imagens de 1 a 256 px." }
        val cabecalho = 6 + 16 * imagens.size
        val total = cabecalho + imagens.sumOf { it.second.size }
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0).putShort(1).putShort(imagens.size.toShort())
        var posicao = cabecalho
        imagens.forEach { (lado, png) ->
            val medida = if (lado == 256) 0 else lado // 0 significa 256
            buffer.put(medida.toByte()).put(medida.toByte()).put(0).put(0)
            buffer.putShort(1).putShort(32).putInt(png.size).putInt(posicao)
            posicao += png.size
        }
        imagens.forEach { buffer.put(it.second) }
        return buffer.array()
    }
}
