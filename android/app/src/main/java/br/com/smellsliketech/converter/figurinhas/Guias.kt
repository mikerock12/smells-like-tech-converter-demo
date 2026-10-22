package br.com.smellsliketech.converter.figurinhas

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * As guias do enquadramento: onde a imagem está na figurinha 1×1 e o que ela encosta. Tudo
 * em frações do lado (0..1), a partir dos mesmos cantos que o [Renderizador] desenha.
 *
 * Ao arrastar, o que fica a menos de [TOLERANCIA] de uma linha (centro, borda da figurinha,
 * centro ou borda de outra camada) encaixa nela, e o giro encaixa no reto (0°, 90°, 180°)
 * a menos de [TOLERANCIA_DO_GIRO]. A tela mostra as linhas encaixadas piscando.
 */
object Guias {
    const val TOLERANCIA = 0.015f
    const val TOLERANCIA_DO_GIRO = 3
    private const val IGUAL = 0.002f

    /** A caixa que a imagem ocupa na figurinha (0..1), já com zoom, giro e perspectiva. */
    data class Caixa(val esquerda: Float, val topo: Float, val direita: Float, val base: Float) {
        val centroX get() = (esquerda + direita) / 2
        val centroY get() = (topo + base) / 2
        val verticais get() = listOf(esquerda, centroX, direita)
        val horizontais get() = listOf(topo, centroY, base)
    }

    enum class Tipo { CENTRO, BORDA, CAMADA }

    /** Uma linha encaixada: vertical (x fixo) ou horizontal (y fixo), na posição 0..1. */
    data class Guia(val vertical: Boolean, val posicao: Float, val tipo: Tipo)

    data class Alinhamento(val edicao: Edicao, val guias: List<Guia>, val reta: Boolean)

    /** Margem que o contorno tira do quadro, como no [Renderizador]. */
    fun margem(edicao: Edicao): Float = if (edicao.contorno) edicao.larguraDoContorno + 4f else 0f

    fun caixa(largura: Int, altura: Int, edicao: Edicao, lado: Int = Regras.LADO): Caixa? {
        if (largura <= 0 || altura <= 0) return null
        val efetiva = edicao.copy(zoom = edicao.zoom * (lado - 2 * margem(edicao)) / lado)
        val cantos = Perspectiva.cantos(largura, altura, lado, lado, efetiva) ?: return null
        val xs = floatArrayOf(cantos[0], cantos[2], cantos[4], cantos[6])
        val ys = floatArrayOf(cantos[1], cantos[3], cantos[5], cantos[7])
        return Caixa(xs.min() / lado, ys.min() / lado, xs.max() / lado, ys.max() / lado)
    }

    /**
     * Encaixa a edição que o dedo propôs (`bruta`) nas linhas mais próximas, se estiverem a
     * menos da tolerância, e diz quais linhas ficaram encaixadas. `referencias` são as caixas
     * das outras camadas visíveis (a imagem de trás, por exemplo).
     */
    fun alinhar(
        bruta: Edicao,
        largura: Int,
        altura: Int,
        referencias: List<Caixa> = emptyList(),
        tolerancia: Float = TOLERANCIA,
        toleranciaDoGiro: Int = TOLERANCIA_DO_GIRO,
    ): Alinhamento {
        var edicao = bruta
        val reto = ((edicao.giro / 90f).roundToInt() * 90)
        if (abs(edicao.giro - reto) <= toleranciaDoGiro) edicao = edicao.copy(giro = normalizarGiro(reto))
        val reta = edicao.giro % 90 == 0

        val caixa = caixa(largura, altura, edicao) ?: return Alinhamento(edicao, emptyList(), reta)
        val linhasX = linhas(referencias, vertical = true)
        val linhasY = linhas(referencias, vertical = false)
        val dx = encaixe(caixa.verticais, linhasX.map { it.first }, tolerancia)
        val dy = encaixe(caixa.horizontais, linhasY.map { it.first }, tolerancia)
        if (dx != 0f || dy != 0f) {
            edicao = edicao.copy(deslocamentoX = edicao.deslocamentoX + dx, deslocamentoY = edicao.deslocamentoY + dy)
        }
        val final = caixa(largura, altura, edicao) ?: return Alinhamento(edicao, emptyList(), reta)
        return Alinhamento(edicao, guiasDe(final, linhasX, linhasY), reta)
    }

    /** As linhas em que a caixa já está (sem mexer em nada): para quando a mudança vem dos controles. */
    fun guiasDe(caixa: Caixa, referencias: List<Caixa> = emptyList()): List<Guia> =
        guiasDe(caixa, linhas(referencias, vertical = true), linhas(referencias, vertical = false))

    private fun guiasDe(caixa: Caixa, linhasX: List<Pair<Float, Tipo>>, linhasY: List<Pair<Float, Tipo>>): List<Guia> {
        val verticais = linhasX.filter { (x, _) -> caixa.verticais.any { abs(it - x) <= IGUAL } }.map { Guia(true, it.first, it.second) }
        val horizontais = linhasY.filter { (y, _) -> caixa.horizontais.any { abs(it - y) <= IGUAL } }.map { Guia(false, it.first, it.second) }
        // A mesma posição só uma vez: o centro da figurinha vale mais que o de outra camada.
        return (verticais + horizontais).groupBy { it.vertical to (it.posicao * 1000).roundToInt() }.map { (_, iguais) -> iguais.minBy { it.tipo.ordinal } }
    }

    private fun linhas(referencias: List<Caixa>, vertical: Boolean): List<Pair<Float, Tipo>> =
        listOf(0f to Tipo.BORDA, 0.5f to Tipo.CENTRO, 1f to Tipo.BORDA) +
            referencias.flatMap { (if (vertical) it.verticais else it.horizontais).map { valor -> valor to Tipo.CAMADA } }

    /** O menor deslocamento que leva uma das marcas da caixa a uma das linhas, ou 0. */
    private fun encaixe(marcas: List<Float>, linhas: List<Float>, tolerancia: Float): Float {
        var melhor = 0f
        var distancia = Float.MAX_VALUE
        for (marca in marcas) for (linha in linhas) {
            val d = linha - marca
            if (abs(d) <= tolerancia && abs(d) < distancia) { melhor = d; distancia = abs(d) }
        }
        return melhor
    }

    private fun normalizarGiro(graus: Int): Int = ((graus + 540) % 360) - 180

    /**
     * Zoom que faz a imagem cobrir a figurinha inteira, centralizada e reta (como "preencher"
     * no Instagram): 1 é caber; o lado menor da imagem passa a encostar nas bordas.
     */
    fun zoomParaPreencher(largura: Int, altura: Int, edicao: Edicao, lado: Int = Regras.LADO): Float {
        val proporcao = maxOf(largura, altura).toFloat() / minOf(largura, altura).coerceAtLeast(1)
        return proporcao * lado / (lado - 2 * margem(edicao))
    }
}
