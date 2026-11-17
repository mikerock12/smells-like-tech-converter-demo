package br.com.smellsliketech.converter.musica

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Reparo de compressão por IA: Apollo (JusperLee/Apollo, CC BY-SA 4.0, código e pesos),
 * convertido para ONNX com a STFT dentro do grafo. Blocos estéreo de 5 s a 44,1 kHz,
 * sobrepostos pela metade com transição linear de 0,5 s; abaixo do corte o grafo mantém o
 * original e acima mistura o Apollo. Mesma conta de packages/converter-core/apollo.mjs.
 */
object Apollo {
    const val ID = "apollo-jusperlee-v1-onnx-fp32"
    const val ARQUIVO = "apollo-restauracao-fp32-v1.onnx"
    const val BYTES = 73_947_536L
    const val SHA256 = "bd0e9aeb69c84056715e7bfc6386cbb8cf17e7e980add13b87177afceec2d893"
    const val URL = "https://converter.smellsliketech.com.br/modelos/$ARQUIVO"
    const val TAXA = 44100
    val disponivel get() = android.os.Process.is64Bit()
    const val ATRIBUICAO = "Apollo, de Kai Li e Yi Luo (JusperLee/Apollo), CC BY-SA 4.0. Convertido para ONNX pela Smells Like Tech, sob a mesma licença."
    private const val BLOCO = 220_500
    private const val PASSO = BLOCO / 2
    private const val TRANSICAO = BLOCO / 10

    /** Onde começa cada bloco (o último termina no fim do áudio, ou é o único). */
    fun inicios(quadros: Long): List<Long> {
        val lista = mutableListOf<Long>()
        var inicio = 0L
        while (inicio < maxOf(1L, quadros - PASSO)) { lista += inicio; inicio += PASSO }
        return lista
    }

    /** Restaura [fonte] (estéreo ou mono, 44,1 kHz) em [saida] (estéreo, 44,1 kHz), na mesma duração. */
    suspend fun restaurar(fonte: FonteDeAudio, saida: EscritorDeWav, executar: (FloatArray) -> FloatArray, progresso: (Double) -> Unit) {
        val quadros = fonte.quadros
        require(quadros > 0) { "O áudio está vazio." }
        val bloco = FloatArray(2 * BLOCO)
        val canal = FloatArray(BLOCO)
        val somas = List(2) { FloatArray(BLOCO) }
        val pesos = FloatArray(BLOCO)
        val sobra = List(2) { FloatArray(BLOCO) }
        val pesosDaSobra = FloatArray(BLOCO)
        val inicios = inicios(quadros)
        var escritos = 0L
        for ((b, inicio) in inicios.withIndex()) {
            coroutineContext.ensureActive()
            val fim = min(quadros, inicio + BLOCO)
            val validos = (fim - inicio).toInt()
            bloco.fill(0f)
            fonte.copiar(0, inicio, validos, canal)
            System.arraycopy(canal, 0, bloco, 0, validos)
            fonte.copiar(if (fonte.canais > 1) 1 else 0, inicio, validos, canal)
            System.arraycopy(canal, 0, bloco, BLOCO, validos)
            val y = executar(bloco)
            check(y.size == 2 * BLOCO) { "O formato de saída do Apollo não confere." }
            for (i in 0 until validos) {
                var p = 1f
                if (inicio > 0 && i < TRANSICAO) p = i.toFloat() / (TRANSICAO - 1)
                if (fim < quadros && i >= validos - TRANSICAO) p = minOf(p, (validos - 1 - i).toFloat() / (TRANSICAO - 1))
                somas[0][i] = y[i] * p
                somas[1][i] = y[BLOCO + i] * p
                pesos[i] = p
            }
            if (b > 0) for (i in 0 until min(BLOCO - PASSO, validos)) {
                somas[0][i] += sobra[0][i]; somas[1][i] += sobra[1][i]; pesos[i] += pesosDaSobra[i]
            }
            val prontos = if (b + 1 < inicios.size) (inicios[b + 1] - inicio).toInt() else validos
            val e = FloatArray(prontos)
            val d = FloatArray(prontos)
            for (i in 0 until prontos) {
                val w = if (pesos[i] > 1e-9f) pesos[i] else 1f
                e[i] = somas[0][i] / w
                d[i] = somas[1][i] / w
                check(e[i].isFinite() && d[i].isFinite()) { "O Apollo devolveu uma amostra inválida; o original não foi alterado." }
            }
            saida.escreverCanais(listOf(e, d), prontos)
            escritos += prontos
            sobra[0].fill(0f); sobra[1].fill(0f); pesosDaSobra.fill(0f)
            for (i in prontos until validos) {
                sobra[0][i - prontos] = somas[0][i]; sobra[1][i - prontos] = somas[1][i]; pesosDaSobra[i - prontos] = pesos[i]
            }
            progresso((b + 1).toDouble() / inicios.size)
        }
        check(escritos == quadros) { "A duração do áudio restaurado pelo Apollo não confere." }
    }

    /** Com a sessão ONNX do aparelho: entrada "audio" [1, 2, 220500], corte e mistura escalares. */
    fun executor(sessao: InferenciaOnnx, corteHz: Double, mistura: Double): (FloatArray) -> FloatArray {
        val forma = longArrayOf(1, 2, BLOCO.toLong())
        val escalares = linkedMapOf("corte" to corteHz.toFloat(), "mistura" to mistura.coerceIn(0.0, 1.0).toFloat())
        return { bloco -> sessao.executar(bloco, forma, "audio", escalares, "restaurado") }
    }
}
