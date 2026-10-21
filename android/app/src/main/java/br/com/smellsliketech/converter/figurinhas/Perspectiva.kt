package br.com.smellsliketech.converter.figurinhas

import android.graphics.Matrix
import kotlin.math.cos
import kotlin.math.sin

/** Mesma projeção de packages/converter-core/camadas.mjs. */
object Perspectiva {
    fun matriz(largura: Int, altura: Int, quadroW: Int, quadroH: Int, edicao: Edicao): Matrix? {
        val escala = minOf(quadroW.toDouble() / largura, quadroH.toDouble() / altura) * edicao.zoom
        val camera = maxOf(quadroW, quadroH) * 3.0
        val a = Math.toRadians(edicao.giro.toDouble())
        val ax = Math.toRadians(edicao.inclinacaoX.toDouble())
        val ay = Math.toRadians(edicao.inclinacaoY.toDouble())
        val origem = floatArrayOf(0f, 0f, largura.toFloat(), 0f, largura.toFloat(), altura.toFloat(), 0f, altura.toFloat())
        val destino = FloatArray(8)
        for (i in 0..3) {
            val x = (origem[i * 2] - largura / 2.0) * escala
            val y = (origem[i * 2 + 1] - altura / 2.0) * escala
            val y1 = y * cos(ax)
            val z1 = y * sin(ax)
            val x2 = x * cos(ay) + z1 * sin(ay)
            val z2 = -x * sin(ay) + z1 * cos(ay)
            val fator = camera / maxOf(camera * 0.1, camera - z2)
            destino[i * 2] = (quadroW * (0.5 + edicao.deslocamentoX) + (x2 * cos(a) - y1 * sin(a)) * fator * if (edicao.espelharHorizontal) -1 else 1).toFloat()
            destino[i * 2 + 1] = (quadroH * (0.5 + edicao.deslocamentoY) + (x2 * sin(a) + y1 * cos(a)) * fator * if (edicao.espelharVertical) -1 else 1).toFloat()
        }
        val area = (destino[2] - destino[0]) * (destino[7] - destino[1]) - (destino[6] - destino[0]) * (destino[3] - destino[1])
        if (kotlin.math.abs(area) < 0.001f) return null
        return Matrix().takeIf { it.setPolyToPoly(origem, 0, destino, 0, 4) }
    }
}
