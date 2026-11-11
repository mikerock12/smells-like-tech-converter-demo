package br.com.smellsliketech.converter.musica

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException

/**
 * WAV float32 lido do disco em janelas reutilizáveis de 64 KiB. Um clipe longo ou
 * desacelerado não exige mapear todo o arquivo. Cada mixer usa sua própria instância.
 */
class FonteWav(private val arquivo: File, private val apagarAoFechar: Boolean = false) : FonteDeAudio, AutoCloseable {
    private val canal = RandomAccessFile(arquivo, "r").channel
    private val inicioDosDados: Long
    private var fechado = false
    private var janela: ByteBuffer? = null
    private var inicioDaJanela = -1L
    private var fimDaJanela = -1L
    override val canais: Int
    override val quadros: Long

    init {
        try {
            val cabecalho = ByteBuffer.allocate(minOf(4096L, canal.size()).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            canal.read(cabecalho, 0)
            val (c, inicio, tamanho) = ler(cabecalho.array(), canal.size())
            canais = c
            inicioDosDados = inicio
            quadros = tamanho / (4L * c)
        } catch (erro: Throwable) {
            canal.close()
            throw erro
        }
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
                require(tamanho >= 16 && p + 24 <= h.size) { "Cabeçalho WAV incompleto." }
                tipo = b.getShort(p + 8).toInt() and 0xffff
                if (tipo == 0xfffe && tamanho >= 26 && p + 34 <= h.size) tipo = b.getShort(p + 32).toInt() and 0xffff
                canais = b.getShort(p + 10).toInt() and 0xffff
                bits = b.getShort(p + 22).toInt() and 0xffff
            } else if (id == "data") {
                require(tipo == 3 && bits == 32 && canais in 1..2) { "As mídias do projeto são WAV float32 mono ou estéreo." }
                val disponivel = comprimento - (p + 8)
                if (tamanho == 0L || tamanho > disponivel) tamanho = disponivel
                return Triple(canais, (p + 8).toLong(), tamanho - tamanho % (4L * canais))
            }
            val proximo = p + 8L + tamanho + (tamanho and 1)
            if (proximo > h.size) break
            p = proximo.toInt()
        }
        error("WAV sem dados.")
    }

    @Synchronized
    override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
        // O mixer pode terminar um bloco com a fonte anterior após uma edição.
        if (fechado || inicio >= quadros || inicio <= -n.toLong()) {
            destino.fill(0f, 0, n)
            return
        }
        val c = canal.coerceIn(0, canais - 1)
        val bytesPorQuadro = 4 * canais
        var i = (-inicio).coerceAtLeast(0L).toInt()
        destino.fill(0f, 0, i)
        val fim = minOf(n.toLong(), quadros - inicio).toInt()
        val dados = janela ?: ByteBuffer.allocateDirect(65536).order(ByteOrder.LITTLE_ENDIAN).also { janela = it }
        while (i < fim) {
            val posicao = (inicio + i) * bytesPorQuadro
            if (posicao < inicioDaJanela || posicao >= fimDaJanela) {
                val base = posicao / dados.capacity() * dados.capacity()
                val tamanho = minOf(dados.capacity().toLong(), quadros * bytesPorQuadro - base).toInt()
                dados.clear().limit(tamanho)
                while (dados.hasRemaining()) {
                    if (this.canal.read(dados, inicioDosDados + base + dados.position()) <= 0) {
                        throw IOException("O arquivo de áudio foi interrompido durante a leitura.")
                    }
                }
                dados.flip()
                inicioDaJanela = base
                fimDaJanela = base + tamanho
            }
            val ate = minOf(fim, i + ((fimDaJanela - posicao) / bytesPorQuadro).toInt())
            var indice = (posicao - inicioDaJanela).toInt() + c * 4
            while (i < ate) {
                destino[i++] = dados.getFloat(indice)
                indice += bytesPorQuadro
            }
        }
        destino.fill(0f, i, n)
    }

    @Synchronized
    override fun close() {
        if (fechado) return
        fechado = true
        janela = null
        try { canal.close() } finally { if (apagarAoFechar) arquivo.delete() }
    }
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
