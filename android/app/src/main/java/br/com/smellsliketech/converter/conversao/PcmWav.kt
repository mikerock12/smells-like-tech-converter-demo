package br.com.smellsliketech.converter.conversao

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Grava blocos em fluxo: narrações longas não acumulam todo o áudio na memória. */
class PcmWav(destino: File, private val taxa: Int) : AutoCloseable {
    private val arquivo = RandomAccessFile(destino, "rw").apply { setLength(0); write(ByteArray(44)) }
    fun adicionar(amostras: FloatArray) {
        val buffer = ByteBuffer.allocate(amostras.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        amostras.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort()) }
        arquivo.write(buffer.array())
    }
    override fun close() {
        try {
            val tamanho = arquivo.length() - 44
            require(tamanho <= Int.MAX_VALUE - 36) { "Narração excede o limite de WAV." }
            val cabecalho = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(tamanho.toInt() + 36); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(taxa); putInt(taxa * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(tamanho.toInt())
            }
            arquivo.seek(0); arquivo.write(cabecalho.array())
        } finally { arquivo.close() }
    }
}
