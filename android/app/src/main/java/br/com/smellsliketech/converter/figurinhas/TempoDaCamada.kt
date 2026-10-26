package br.com.smellsliketech.converter.figurinhas

import kotlin.math.abs
import kotlin.math.roundToInt

/** Um vídeo acaba no corte ou no fim real do arquivo; GIFs conservam sua repetição. */
object TempoDaCamada {
    fun indice(segundos: Double, duracao: Double, fps: Int, total: Int,
        repetir: Boolean = false, ultimoNaBorda: Boolean = false, arredondar: Boolean = false,
        inspecionar: Boolean = false): Int? {
        if (!segundos.isFinite() || segundos < 0 || fps <= 0 || total <= 0) return null
        if (!duracao.isFinite() || duracao <= 0) return null
        // O fim digitado pode cair entre quadros. Na inspeção individual, mostrar o
        // último disponível mesmo quando o cursor antigo ficou além do novo corte.
        // A composição continua usando o relógio real e fica transparente no fim.
        if (inspecionar && !repetir) {
            val tempo = segundos.coerceAtMost(duracao)
            val n = if (arredondar) (tempo * fps).roundToInt() else (tempo * fps).toInt()
            return n.coerceIn(0, total - 1)
        }
        val limite = minOf(duracao, total.toDouble() / fps)
        if (limite <= 0) return null
        val n = if (arredondar) (segundos * fps).roundToInt() else (segundos * fps).toInt()
        if (repetir) return n % total
        if (segundos >= limite && !(ultimoNaBorda && abs(segundos - limite) < .0001)) return null
        return n.coerceAtMost(total - 1)
    }
}
