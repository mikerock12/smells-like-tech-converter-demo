package br.com.smellsliketech.converter.musica

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * WAV float32 lido direto do disco por memória mapeada: a música longa não precisa caber na
 * RAM do celular, e o sistema guarda em cache só o que toca. Cada mixer usa a sua instância.
 */
class FonteMapeada(arquivo: File) : FonteDeAudio, AutoCloseable {
    private val canal = RandomAccessFile(arquivo, "r").channel
    private val dados: FloatBuffer
    override val canais: Int
    override val quadros: Long

    init {
        val cabecalho = ByteBuffer.allocate(minOf(4096L, canal.size()).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        canal.read(cabecalho, 0)
        val (c, inicio, tamanho) = ler(cabecalho.array(), canal.size())
        canais = c
        quadros = tamanho / (4L * c)
        dados = canal.map(FileChannel.MapMode.READ_ONLY, inicio, quadros * 4 * c).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    }

    private fun ler(h: ByteArray, comprimento: Long): Triple<Int, Long, Long> {
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        require(h.size >= 44 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF") { "Mídia do projeto não é um WAV." }
        var p = 12
        var canais = 0; var bits = 0; var tipo = 0
        while (p + 8 <= h.size) {
            val id = String(h, p, 4, Charsets.US_ASCII)
            var tamanho = b.getInt(p + 4).toLong() and 0xffffffffL
            if (id == "fmt ") {
                tipo = b.getShort(p + 8).toInt() and 0xffff
                if (tipo == 0xfffe && tamanho >= 26) tipo = b.getShort(p + 32).toInt() and 0xffff
                canais = b.getShort(p + 10).toInt() and 0xffff
                bits = b.getShort(p + 22).toInt() and 0xffff
            } else if (id == "data") {
                require(tipo == 3 && bits == 32) { "As mídias do projeto são WAV float32." }
                val disponivel = comprimento - (p + 8)
                if (tamanho == 0L || tamanho > disponivel) tamanho = disponivel
                return Triple(canais, (p + 8).toLong(), tamanho - tamanho % (4L * canais))
            }
            p += 8 + tamanho.toInt() + (tamanho and 1).toInt()
        }
        error("WAV sem dados.")
    }

    override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
        val c = minOf(canal, canais - 1)
        val disponivel = (quadros - inicio).coerceIn(0, n.toLong()).toInt()
        // Leitura absoluta: nada de alocação na thread de áudio.
        val base = (inicio * canais).toInt() + c
        for (i in 0 until disponivel) destino[i] = dados.get(base + i * canais)
        for (i in maxOf(0, disponivel) until n) destino[i] = 0f
    }

    override fun close() = canal.close()
}

/**
 * Escreve WAV float32 aos pedaços, com sync no disco a cada segundo: se o app cair no meio da
 * gravação, o arquivo abre (o leitor recupera o tamanho que faltou no cabeçalho).
 */
class EscritorDeWav(arquivo: File, private val canais: Int, private val taxa: Int) : AutoCloseable {
    private val saida = RandomAccessFile(arquivo.apply { parentFile?.mkdirs() }, "rw")
    private var ultimoSync = System.nanoTime()
    var quadros = 0L
        private set
    private var bytes = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)

    init {
        saida.setLength(0)
        saida.write(MasterDaMusica.cabecalhoWav(canais, taxa, 32, 0))
    }

    /** Intercalado (canais × quadros). */
    fun escrever(intercalado: FloatArray, n: Int = intercalado.size / canais) {
        val precisa = n * canais * 4
        if (bytes.capacity() < precisa) bytes = ByteBuffer.allocate(precisa).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asFloatBuffer().put(intercalado, 0, n * canais)
        saida.write(bytes.array(), 0, precisa)
        quadros += n
        if (System.nanoTime() - ultimoSync > 1_000_000_000L) {
            saida.fd.sync()
            ultimoSync = System.nanoTime()
        }
    }

    /** Canais separados. */
    fun escreverCanais(dados: List<FloatArray>, n: Int) {
        val intercalado = FloatArray(n * canais)
        for (i in 0 until n) for (c in 0 until canais) intercalado[i * canais + c] = dados[minOf(c, dados.size - 1)][i]
        escrever(intercalado, n)
    }

    override fun close() {
        saida.seek(0)
        saida.write(MasterDaMusica.cabecalhoWav(canais, taxa, 32, quadros))
        saida.fd.sync()
        saida.close()
    }
}
