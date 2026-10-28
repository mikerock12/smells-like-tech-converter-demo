package br.com.smellsliketech.converter.estudio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * As guias do enquadramento no estúdio, espelho de packages/converter-core/guias.mjs e de
 * StudioGuides.cs (a mesma ideia do Guias.kt das figurinhas, sobre a [Transformacao] do estúdio):
 * o que fica a menos de [TOLERANCIA] de uma linha (centro, borda do quadro, centro ou borda de
 * outra camada) encaixa nela, e o giro encaixa no reto a menos de [TOLERANCIA_DO_GIRO] graus.
 * Os casos de guias-casos.json valem para os três clientes.
 */
object GuiasDoEstudio {
    const val TOLERANCIA = 0.015
    const val TOLERANCIA_DO_GIRO = 3.0
    private const val IGUAL = 0.002
    private val PRIORIDADE = mapOf("centro" to 0, "borda" to 1, "camada" to 2)

    /** A mídia e o quadro, em pixels. */
    data class Medidas(val largura: Double, val altura: Double, val quadroW: Double, val quadroH: Double) {
        val valida get() = largura > 0 && altura > 0 && quadroW > 0 && quadroH > 0
    }

    /** A caixa que a camada ocupa no quadro, em frações do lado (0..1). */
    data class Caixa(val esquerda: Double, val topo: Double, val direita: Double, val base: Double) {
        val verticais get() = listOf(esquerda, (esquerda + direita) / 2, direita)
        val horizontais get() = listOf(topo, (topo + base) / 2, base)
    }

    /** `tipo`: centro, borda ou camada. */
    data class Guia(val vertical: Boolean, val posicao: Double, val tipo: String)

    data class Alinhamento(val transformacao: Transformacao, val guias: List<Guia>, val reta: Boolean)

    /** Os quatro cantos projetados, em pixels do quadro. Igual a cantosDaCamada (camadas.mjs). */
    fun cantos(m: Medidas, t: Transformacao): List<Pair<Double, Double>> {
        val a = Math.toRadians(t.giro); val ax = Math.toRadians(t.giroX); val ay = Math.toRadians(t.giroY)
        val escala = min(m.quadroW / m.largura, m.quadroH / m.altura) * t.escala
        val camera = max(m.quadroW, m.quadroH) * 3
        return listOf(0.0 to 0.0, m.largura to 0.0, m.largura to m.altura, 0.0 to m.altura).map { (u, v) ->
            val x = (u - m.largura / 2) * escala; val y = (v - m.altura / 2) * escala
            val y1 = y * cos(ax); val z1 = y * sin(ax)
            val x2 = x * cos(ay) + z1 * sin(ay); val z2 = -x * sin(ay) + z1 * cos(ay)
            val fator = camera / max(camera * 0.1, camera - z2)
            (m.quadroW * t.x + (x2 * cos(a) - y1 * sin(a)) * fator * (if (t.espelharX) -1 else 1)) to
                (m.quadroH * t.y + (x2 * sin(a) + y1 * cos(a)) * fator * (if (t.espelharY) -1 else 1))
        }
    }

    fun caixa(m: Medidas?, t: Transformacao): Caixa? {
        if (m == null || !m.valida) return null
        val c = cantos(m, t)
        return Caixa(c.minOf { it.first } / m.quadroW, c.minOf { it.second } / m.quadroH, c.maxOf { it.first } / m.quadroW, c.maxOf { it.second } / m.quadroH)
    }

    private fun linhas(referencias: List<Caixa>, vertical: Boolean): List<Pair<Double, String>> =
        listOf(0.0 to "borda", 0.5 to "centro", 1.0 to "borda") +
            referencias.flatMap { r -> (if (vertical) r.verticais else r.horizontais).map { it to "camada" } }

    /** O menor deslocamento que leva uma das marcas da caixa a uma das linhas, ou 0. */
    private fun encaixe(marcas: List<Double>, linhas: List<Pair<Double, String>>, tolerancia: Double): Double {
        var melhor = 0.0
        var distancia = Double.MAX_VALUE
        for (marca in marcas) for ((linha, _) in linhas) {
            val d = linha - marca
            if (abs(d) <= tolerancia && abs(d) < distancia) { melhor = d; distancia = abs(d) }
        }
        return melhor
    }

    private fun guiasDe(caixa: Caixa, linhasX: List<Pair<Double, String>>, linhasY: List<Pair<Double, String>>): List<Guia> {
        val achadas = linhasX.filter { (x, _) -> caixa.verticais.any { abs(it - x) <= IGUAL } }.map { Guia(true, it.first, it.second) } +
            linhasY.filter { (y, _) -> caixa.horizontais.any { abs(it - y) <= IGUAL } }.map { Guia(false, it.first, it.second) }
        // A mesma posição só uma vez: o centro do quadro vale mais que o de outra camada.
        return achadas.groupBy { it.vertical to Math.round(it.posicao * 1000) }.map { (_, iguais) -> iguais.minBy { PRIORIDADE.getValue(it.tipo) } }
    }

    private fun normalizarGiro(graus: Double) = (((graus + 540) % 360) + 360) % 360 - 180

    /** Encaixa a transformação que o gesto propôs (`bruta`) e diz quais linhas ficaram encaixadas. */
    fun alinhar(
        bruta: Transformacao,
        medidas: Medidas?,
        referencias: List<Caixa> = emptyList(),
        tolerancia: Double = TOLERANCIA,
        toleranciaDoGiro: Double = TOLERANCIA_DO_GIRO,
    ): Alinhamento {
        var t = bruta
        val reto = Math.round(t.giro / 90) * 90.0
        if (abs(t.giro - reto) <= toleranciaDoGiro) t = t.copy(giro = normalizarGiro(reto))
        val reta = t.giro % 90 == 0.0
        val caixa = caixa(medidas, t) ?: return Alinhamento(t, emptyList(), reta)
        val linhasX = linhas(referencias, true); val linhasY = linhas(referencias, false)
        val dx = encaixe(caixa.verticais, linhasX, tolerancia); val dy = encaixe(caixa.horizontais, linhasY, tolerancia)
        if (dx != 0.0 || dy != 0.0) t = t.copy(x = t.x + dx, y = t.y + dy)
        val final = caixa(medidas, t) ?: return Alinhamento(t, emptyList(), reta)
        return Alinhamento(t, guiasDe(final, linhasX, linhasY), reta)
    }

    /** As linhas em que a camada já está, sem mexer nela: para quando a mudança vem dos controles. */
    fun guias(medidas: Medidas?, t: Transformacao, referencias: List<Caixa> = emptyList()): List<Guia> =
        caixa(medidas, t)?.let { guiasDe(it, linhas(referencias, true), linhas(referencias, false)) } ?: emptyList()

    private fun reta(t: Transformacao) = t.copy(x = .5, y = .5, giro = 0.0, giroX = 0.0, giroY = 0.0)

    /** Cobre o quadro inteiro, centralizada e reta. */
    fun preencher(t: Transformacao, medidas: Medidas?): Transformacao {
        if (medidas == null || !medidas.valida) return reta(t)
        val cobrir = max(medidas.quadroW / medidas.largura, medidas.quadroH / medidas.altura) /
            min(medidas.quadroW / medidas.largura, medidas.quadroH / medidas.altura)
        return reta(t).copy(escala = min(6.0, cobrir))
    }

    /** Inteira dentro do quadro, centralizada e reta. */
    fun caberInteira(t: Transformacao) = reta(t).copy(escala = 1.0)

    /** Só leva ao centro, sem mudar tamanho nem giro. */
    fun centralizar(t: Transformacao) = t.copy(x = .5, y = .5)
}
