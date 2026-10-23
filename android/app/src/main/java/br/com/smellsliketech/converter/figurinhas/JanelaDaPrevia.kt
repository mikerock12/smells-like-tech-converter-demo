package br.com.smellsliketech.converter.figurinhas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Zoom de inspeção: não pertence à edição nem modifica os pixels exportados. */
data class JanelaDaPrevia(val x: Float = 0f, val y: Float = 0f, val lado: Float = 1f) {
    fun ponto(px: Float, py: Float) = (x + px * lado) to (y + py * lado)

    fun caixa(x1: Float, y1: Float, x2: Float, y2: Float): JanelaDaPrevia {
        val a = ponto(x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f))
        val b = ponto(x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f))
        val tamanho = max(abs(a.first - b.first), abs(a.second - b.second)).coerceIn(1f / 16, lado)
        val centroX = (a.first + b.first) / 2
        val centroY = (a.second + b.second) / 2
        return JanelaDaPrevia((centroX - tamanho / 2).coerceIn(0f, 1 - tamanho),
            (centroY - tamanho / 2).coerceIn(0f, 1 - tamanho), tamanho)
    }

    fun ampliar(fator: Float): JanelaDaPrevia {
        val novo = (lado / fator).coerceIn(1f / 16, 1f)
        return JanelaDaPrevia((x + (lado - novo) / 2).coerceIn(0f, 1 - novo),
            (y + (lado - novo) / 2).coerceIn(0f, 1 - novo), novo)
    }
}

/** Dois dedos paralelos inclinam; pinça e torção continuam controlando zoom e giro Z. */
object GestoDeInclinacao {
    fun aplicar(edicao: Edicao, dx: Float, dy: Float, largura: Int, altura: Int): Edicao = edicao.copy(
        inclinacaoX = (edicao.inclinacaoX + dy / max(1, altura) * 180).coerceIn(-180f, 180f),
        inclinacaoY = (edicao.inclinacaoY + dx / max(1, largura) * 180).coerceIn(-180f, 180f),
    )
}
