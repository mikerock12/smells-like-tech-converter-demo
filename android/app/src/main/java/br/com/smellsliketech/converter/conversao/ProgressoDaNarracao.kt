package br.com.smellsliketech.converter.conversao

import kotlin.math.ceil

/** Mede somente síntese, em caracteres, nunca o tempo de leitura/OCR/carga do modelo. */
class ProgressoDaNarracao(private val tamanhos: List<Int>, private val relogioMs: () -> Long) {
    init { require(tamanhos.isNotEmpty() && tamanhos.all { it > 0 }) }
    private val total = tamanhos.sum().coerceAtLeast(1)
    private var feitos = 0
    private var trechos = 0
    private var inicio = relogioMs()
    private var inicioDoTrecho = inicio
    private val medidas = ArrayDeque<Pair<Int, Long>>()

    fun concluirTrecho() {
        val agora = relogioMs()
        val tamanho = tamanhos[trechos]
        medidas.addLast(tamanho to (agora - inicioDoTrecho).coerceAtLeast(1))
        if (medidas.size > 8) medidas.removeFirst()
        feitos += tamanho
        trechos++
        inicioDoTrecho = agora
    }

    /** Progresso real: o relógio não inventa texto já narrado. */
    val porcentagem: Int get() = 10 + (feitos.toLong() * 75 / total).toInt()
    val etapa: String get() = "Gerando voz · trecho ${trechos.coerceAtMost(tamanhos.size - 1) + 1}/${tamanhos.size}"

    fun restante(): Int? {
        if (trechos == tamanhos.size) return null // A codificação/salvamento tem outro custo.
        val agora = relogioMs()
        if (trechos < 3 || agora - inicio < 5_000) return null
        val ritmo = medidas.sumOf { it.second }.toDouble() / medidas.sumOf { it.first }
        val atual = tamanhos[trechos]
        val depois = (total - feitos - atual).coerceAtLeast(0)
        // Se o trecho demora mais que a medida anterior, não contar até zero nem dizer "quase pronto".
        val decorrido = (agora - inicioDoTrecho).coerceAtLeast(0).toDouble()
        val previstoAtual = atual * ritmo
        // Uma pausa/outlier não autoriza extrapolar minutos crescentes a cada segundo.
        // Sem nova medida confiável, ocultar ETA e manter trecho/progresso reais.
        if (decorrido > maxOf(30_000.0, previstoAtual * 2)) return null
        val faltaAtual = (previstoAtual - decorrido).coerceAtLeast(previstoAtual * 0.2)
        return ceil((faltaAtual + depois * ritmo) / 1000.0).toInt().coerceAtLeast(1)
    }
}
