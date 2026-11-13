package br.com.smellsliketech.converter.musica

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Acesso ao núcleo por JNI. O callback de áudio permanece no C++. */
internal object NucleoNativo {
    const val ABI = (1 shl 16) or 1
    const val CAPACIDADES = 0x17f
    const val OBOE = 3

    val disponivel: Boolean by lazy {
        try {
            versao() ushr 16 == ABI ushr 16
        } catch (_: LinkageError) {
            false
        }
    }

    fun versao() = NucleoJni.versao()
    fun capacidades() = NucleoJni.capacidades()
    fun backends() = NucleoJni.backends()

    val temStftComplexa: Boolean get() = disponivel && (capacidades() and (1 shl 8)) != 0

    fun plano(quadros: Int): ByteBuffer =
        ByteBuffer.allocateDirect(Math.multiplyExact(quadros, Float.SIZE_BYTES)).order(ByteOrder.nativeOrder())

    private fun amostras(buffer: ByteBuffer, n: Int, gravar: Boolean = false): ByteBuffer {
        require(n > 0 && n <= buffer.remaining() / Float.SIZE_BYTES)
        require(buffer.isDirect && buffer.order() == ByteOrder.nativeOrder())
        require(!gravar || !buffer.isReadOnly)
        return buffer.slice().order(ByteOrder.nativeOrder())
    }

    private fun chave(id: String): ByteArray {
        require(id.isNotEmpty() && '\u0000' !in id)
        return (id + '\u0000').toByteArray(Charsets.UTF_8)
    }

    private fun validar(resultado: Int) {
        check(resultado == 0) { "O núcleo de áudio devolveu o erro $resultado." }
    }

    /** O controle é serializado. O mixer fecha primeiro o fluxo e então libera suas fontes. */
    class Mixer(taxa: Int) : AutoCloseable {
        private var handle = NucleoJni.criarMixer(taxa).also { check(it != 0L) }
        private var fluxo: Fluxo? = null
        // Um plano publicado ou em uso pode manter ponteiros de registros anteriores.
        // As vistas ficam vivas até o fechamento do mixer, inclusive após substituir a fonte.
        private val fontes = mutableListOf<ByteBuffer>()

        private fun aberto(): Long = handle.also { check(it != 0L) }

        @Synchronized
        fun fonte(id: String, esquerda: ByteBuffer, direita: ByteBuffer?, quadros: Int) {
            val e = amostras(esquerda, quadros)
            val d = direita?.let { amostras(it, quadros) }
            val mixer = aberto()
            val idUtf8 = chave(id)
            fontes.add(e)
            if (d != null) fontes.add(d)
            val resultado = NucleoJni.fonte(mixer, idUtf8, e, d, quadros)
            if (resultado != 0) {
                if (d != null) fontes.removeAt(fontes.lastIndex)
                fontes.removeAt(fontes.lastIndex)
                validar(resultado)
            }
        }

        @Synchronized
        fun medidores(id: String): FloatArray = checkNotNull(NucleoJni.medidores(aberto(), chave(id)))

        @Synchronized
        fun projeto(jsonUtf8: ByteArray) = validar(NucleoJni.projeto(aberto(), jsonUtf8))

        @Synchronized fun removerFonte(id: String) = validar(NucleoJni.removerFonte(aberto(), chave(id)))
        @Synchronized fun atualizar() = validar(NucleoJni.atualizarMixer(aberto()))

        @Synchronized
        fun renderizar(inicio: Long, quadros: Int, esquerda: ByteBuffer, direita: ByteBuffer) {
            check(fluxo == null) { "Feche o fluxo de áudio antes de renderizar." }
            validar(NucleoJni.renderizar(aberto(), inicio, quadros,
                amostras(esquerda, quadros, true), amostras(direita, quadros, true)))
        }

        @Synchronized fun tocar(posicao: Long) = validar(NucleoJni.tocar(aberto(), posicao))
        @Synchronized fun parar() = validar(NucleoJni.parar(aberto()))
        @Synchronized fun posicao() = NucleoJni.posicao(aberto())
        @Synchronized fun loop(inicio: Long, fim: Long) = validar(NucleoJni.loop(aberto(), inicio, fim))
        @Synchronized fun metronomo(ligado: Boolean) = validar(NucleoJni.metronomo(aberto(), ligado))

        @Synchronized
        fun abrirFluxo(saida: Int = 0, entrada: Int = 0, capturar: Boolean = false, bloco: Int = 0): Fluxo {
            check(fluxo == null)
            val novo = NucleoJni.abrirFluxo(aberto(), saida, entrada, capturar, bloco)
            check(novo != 0L) { "O Oboe não abriu a saída de áudio." }
            return Fluxo(novo).also { fluxo = it }
        }

        @Synchronized
        override fun close() {
            fluxo?.close()
            NucleoJni.destruirMixer(handle)
            handle = 0
            fontes.clear()
        }

        inner class Fluxo internal constructor(private var handle: Long) : AutoCloseable {
            private fun aberto(): Long = handle.also { check(it != 0L) }
            fun iniciar() = synchronized(this@Mixer) { validar(NucleoJni.iniciarFluxo(aberto())) }
            fun parar() = synchronized(this@Mixer) { validar(NucleoJni.pararFluxo(aberto())) }
            fun captura(ligar: Boolean) = synchronized(this@Mixer) { validar(NucleoJni.captura(aberto(), ligar)) }

            fun info(): InfoDoFluxo = synchronized(this@Mixer) {
                val v = checkNotNull(NucleoJni.info(aberto()))
                InfoDoFluxo(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3].toInt(), v[4].toInt(),
                    v[5].toInt(), v[6].toInt(), v[7] != 0L, v[8] != 0L, v[9] != 0L)
            }

            fun estatisticas(): Estatisticas = synchronized(this@Mixer) {
                val v = checkNotNull(NucleoJni.estatisticas(aberto()))
                Estatisticas(v[0], v[1], v[2], v[3], v[4], v[5])
            }

            fun ler(quadros: Int, esquerda: ByteBuffer, direita: ByteBuffer): Captura = synchronized(this@Mixer) {
                val v = checkNotNull(NucleoJni.ler(aberto(), quadros,
                    amostras(esquerda, quadros, true), amostras(direita, quadros, true)))
                Captura(v[1], v[0].toInt())
            }

            override fun close() = synchronized(this@Mixer) {
                if (handle != 0L) {
                    NucleoJni.fecharFluxo(handle)
                    handle = 0
                    fluxo = null
                }
            }
        }
    }

    data class InfoDoFluxo(
        val backend: Int, val taxa: Int, val bloco: Int, val canaisDaSaida: Int, val canaisDaEntrada: Int,
        val latenciaDaSaida: Int, val latenciaDaEntrada: Int, val rodando: Boolean,
        val perdido: Boolean, val baixaLatencia: Boolean,
    )

    data class Estatisticas(
        val callbacks: Long, val quadros: Long, val falhas: Long, val maximoUs: Long,
        val mediaUs: Long, val transbordos: Long,
    )

    data class Captura(val posicao: Long, val quadros: Int)

    class Restaurador(taxa: Int, perfil: Int, intensidade: Float) : AutoCloseable {
        private var handle = NucleoJni.criarRestaurador(taxa, perfil, intensidade).also { check(it != 0L) }
        private fun aberto() = handle.also { check(it != 0L) }

        fun processar(quadros: Int, esquerda: ByteBuffer, direita: ByteBuffer? = null) =
            validar(NucleoJni.restaurar(aberto(), quadros, amostras(esquerda, quadros, true),
                direita?.let { amostras(it, quadros, true) }))

        fun ganho() = NucleoJni.ganhoDaRestauracao(aberto())
        override fun close() {
            NucleoJni.destruirRestaurador(handle)
            handle = 0
        }
    }

    data class Janela(val inicio: Long, val amostras: Int, val quadros: Int)
    data class Trecho(val inicio: Long, val amostras: Int)

    /** Um bloco por vez. Espectro e máscara ficam em buffers diretos do chamador. */
    class StftComplexa(janela: Int, passo: Int, val comprimento: Int) : AutoCloseable {
        private var handle: Long
        val tamanho: Int

        init {
            check(temStftComplexa) { "STFT complexa nativa indisponível." }
            handle = NucleoJni.criarStftComplexa(janela, passo, comprimento)
            check(handle != 0L) { "Configuração de STFT complexa inválida." }
            tamanho = NucleoJni.tamanhoStftComplexa(handle)
        }

        private fun aberto() = handle.also { check(it != 0L) }

        fun analisar(esquerda: ByteBuffer, direita: ByteBuffer, espectro: ByteBuffer) =
            validar(NucleoJni.analisarStftComplexa(aberto(), comprimento, amostras(esquerda, comprimento),
                amostras(direita, comprimento), amostras(espectro, tamanho, true), tamanho))

        fun sintetizar(espectro: ByteBuffer, mascara: ByteBuffer?, esquerda: ByteBuffer, direita: ByteBuffer, zerarDc: Boolean = false) =
            validar(NucleoJni.sintetizarStftComplexa(aberto(), amostras(espectro, tamanho), mascara?.let { amostras(it, tamanho) },
                tamanho, zerarDc, amostras(esquerda, comprimento, true), amostras(direita, comprimento, true), comprimento))

        override fun close() {
            NucleoJni.destruirStftComplexa(handle)
            handle = 0
        }
    }

    class Separador(
        amostras: Long, fft: Int, passo: Int, porTrecho: Int,
        bandas: Int, instrumentos: Int, saidas: Int, refinada: Boolean,
    ) : AutoCloseable {
        private var handle = NucleoJni.criarSeparador(amostras, fft, passo, porTrecho, bandas,
            instrumentos, saidas, refinada).also { check(it != 0L) }
        private fun aberto() = handle.also { check(it != 0L) }

        fun trechos() = NucleoJni.trechos(aberto())

        fun janela(trecho: Int): Janela {
            val v = checkNotNull(NucleoJni.janela(aberto(), trecho))
            return Janela(v[0], v[1].toInt(), v[2].toInt())
        }

        fun analisar(trecho: Int, n: Int, esquerda: ByteBuffer, direita: ByteBuffer, magnitude: ByteBuffer, tamanho: Int) =
            validar(NucleoJni.analisar(aberto(), trecho, n, amostras(esquerda, n), amostras(direita, n),
                amostras(magnitude, tamanho, true), tamanho))

        fun sintetizar(trecho: Int, saida: Int, estimativas: ByteBuffer, tamanho: Int, instrumentos: IntArray,
                       esquerda: ByteBuffer, direita: ByteBuffer, capacidade: Int): Trecho {
            val v = checkNotNull(NucleoJni.sintetizar(aberto(), trecho, saida, amostras(estimativas, tamanho),
                tamanho, instrumentos, amostras(esquerda, capacidade, true), amostras(direita, capacidade, true), capacidade))
            return Trecho(v[0], v[1].toInt())
        }

        override fun close() {
            NucleoJni.destruirSeparador(handle)
            handle = 0
        }
    }
}

private object NucleoJni {
    init { System.loadLibrary("slt-jni") }

    external fun versao(): Int
    external fun capacidades(): Int
    external fun backends(): Int
    external fun criarStftComplexa(janela: Int, passo: Int, n: Int): Long
    external fun destruirStftComplexa(stft: Long)
    external fun tamanhoStftComplexa(stft: Long): Int
    external fun analisarStftComplexa(stft: Long, n: Int, esquerda: ByteBuffer, direita: ByteBuffer,
                                      espectro: ByteBuffer, tamanho: Int): Int
    external fun sintetizarStftComplexa(stft: Long, espectro: ByteBuffer, mascara: ByteBuffer?, tamanho: Int,
                                        zerarDc: Boolean, esquerda: ByteBuffer, direita: ByteBuffer, n: Int): Int
    external fun criarMixer(taxa: Int): Long
    external fun destruirMixer(mixer: Long)
    external fun fonte(mixer: Long, id: ByteArray, esquerda: ByteBuffer, direita: ByteBuffer?, quadros: Int): Int
    external fun projeto(mixer: Long, json: ByteArray): Int
    external fun removerFonte(mixer: Long, id: ByteArray): Int
    external fun atualizarMixer(mixer: Long): Int
    external fun renderizar(mixer: Long, inicio: Long, quadros: Int, esquerda: ByteBuffer, direita: ByteBuffer): Int
    external fun tocar(mixer: Long, posicao: Long): Int
    external fun parar(mixer: Long): Int
    external fun posicao(mixer: Long): Long
    external fun loop(mixer: Long, inicio: Long, fim: Long): Int
    external fun metronomo(mixer: Long, ligado: Boolean): Int
    external fun medidores(mixer: Long, id: ByteArray): FloatArray?
    external fun abrirFluxo(mixer: Long, saida: Int, entrada: Int, capturar: Boolean, bloco: Int): Long
    external fun fecharFluxo(fluxo: Long)
    external fun iniciarFluxo(fluxo: Long): Int
    external fun pararFluxo(fluxo: Long): Int
    external fun info(fluxo: Long): LongArray?
    external fun estatisticas(fluxo: Long): LongArray?
    external fun captura(fluxo: Long, ligar: Boolean): Int
    external fun ler(fluxo: Long, capacidade: Int, esquerda: ByteBuffer, direita: ByteBuffer): LongArray?
    external fun criarRestaurador(taxa: Int, perfil: Int, intensidade: Float): Long
    external fun destruirRestaurador(restaurador: Long)
    external fun restaurar(restaurador: Long, n: Int, esquerda: ByteBuffer, direita: ByteBuffer?): Int
    external fun ganhoDaRestauracao(restaurador: Long): Float
    external fun criarSeparador(amostras: Long, fft: Int, passo: Int, porTrecho: Int, bandas: Int,
                               instrumentos: Int, saidas: Int, refinada: Boolean): Long
    external fun destruirSeparador(separador: Long)
    external fun trechos(separador: Long): Int
    external fun janela(separador: Long, trecho: Int): LongArray?
    external fun analisar(separador: Long, trecho: Int, n: Int, esquerda: ByteBuffer, direita: ByteBuffer,
                          magnitude: ByteBuffer, tamanho: Int): Int
    external fun sintetizar(separador: Long, trecho: Int, saida: Int, estimativas: ByteBuffer, tamanho: Int,
                            instrumentos: IntArray, esquerda: ByteBuffer, direita: ByteBuffer, capacidade: Int): LongArray?
}
