package br.com.smellsliketech.converter.musica

/**
 * Sessão ONNX do modelo de separação, pela API C do ONNX Runtime (src/main/cpp/musica_onnx.c),
 * sobre o libonnxruntime.so que já vem com o sherpa-onnx: um runtime só no app.
 */
class InferenciaOnnx(caminho: String, threads: Int, economizarMemoria: Boolean = false, acelerar: Boolean = false) : AutoCloseable {
    private var sessao = abrir(caminho, threads, economizarMemoria, acelerar)

    /** Se o acelerador do aparelho (NNAPI) aceitou o modelo; falso quando roda só na CPU. */
    val usandoAcelerador: Boolean = comAcelerador()

    fun executar(entrada: FloatArray, forma: LongArray, nomeDaEntrada: String, nomeDaSaida: String): FloatArray =
        executar(sessao, entrada, forma, nomeDaEntrada, nomeDaSaida) ?: error("O modelo não devolveu resultado.")

    /** Com entradas escalares de forma [1] além da principal (o corte e a mistura do Apollo). */
    fun executar(entrada: FloatArray, forma: LongArray, nomeDaEntrada: String, escalares: Map<String, Float>, nomeDaSaida: String): FloatArray =
        executarComEscalares(sessao, entrada, forma, nomeDaEntrada, escalares.values.toFloatArray(), escalares.keys.toTypedArray(), nomeDaSaida)
            ?: error("O modelo não devolveu resultado.")

    override fun close() {
        fechar(sessao)
        sessao = 0
    }

    private companion object {
        init {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("musica-onnx")
        }

        @JvmStatic external fun abrir(caminho: String, threads: Int, economizarMemoria: Boolean, acelerar: Boolean): Long
        @JvmStatic external fun comAcelerador(): Boolean
        @JvmStatic external fun executar(sessao: Long, entrada: FloatArray, forma: LongArray, nomeDaEntrada: String, nomeDaSaida: String): FloatArray?
        @JvmStatic external fun executarComEscalares(sessao: Long, entrada: FloatArray, forma: LongArray, nomeDaEntrada: String,
                                                     escalares: FloatArray, nomesDosEscalares: Array<String>, nomeDaSaida: String): FloatArray?
        @JvmStatic external fun fechar(sessao: Long)
    }
}
