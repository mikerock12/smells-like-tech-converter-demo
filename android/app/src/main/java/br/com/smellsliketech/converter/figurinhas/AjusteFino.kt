package br.com.smellsliketech.converter.figurinhas

import kotlin.math.round

object AjusteFino {
    fun aplicar(valor: Float, direcao: Int, passo: Float, limites: ClosedFloatingPointRange<Float>): Float =
        (round((valor + direcao * passo) * 10000f) / 10000f).coerceIn(limites.start, limites.endInclusive)
}
