package br.com.smellsliketech.converter.conversao

import android.content.Context
import java.io.File

/**
 * Quantas threads dar à inferência (separação de faixas, ONNX Runtime). Os celulares têm
 * núcleos rápidos e núcleos econômicos (big.LITTLE, Snapdragon e MediaTek): dividir o modelo
 * igualmente entre todos faz os rápidos esperarem pelos lentos. Por isso, só os núcleos com a
 * frequência máxima perto da do mais rápido entram; sem essa informação, todos menos um.
 */
object Processadores {
    /** Núcleos rápidos a partir das frequências máximas (kHz) de cada núcleo. Kotlin puro, testável. */
    fun rapidos(frequencias: List<Long>, total: Int = frequencias.size): Int {
        val validas = frequencias.filter { it > 0 }
        if (validas.size < 2) return maxOf(1, total - 1)
        val maior = validas.max()
        val menor = validas.min()
        // Todos iguais (sem big.LITTLE): deixa um núcleo para a interface e o sistema.
        if (maior - menor < maior / 10) return maxOf(1, validas.size - 1)
        return validas.count { it >= maior * 85 / 100 }.coerceIn(1, validas.size)
    }

    fun frequenciasDoAparelho(): List<Long> = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
        try {
            File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        } catch (_: Exception) {
            0L
        }
    }

    fun paraInferencia(): Int = rapidos(frequenciasDoAparelho(), Runtime.getRuntime().availableProcessors())

    // ==================== acelerador do aparelho (NNAPI) ====================

    private const val PREFERENCIAS = "aceleracao"
    private const val ACELERADOR = "nnapi"

    /**
     * Experimental, desligado de fábrica: entrega partes do modelo ao acelerador de IA do
     * aparelho (NNAPI). Em float32, sem perder precisão; se o driver recusar, volta à CPU.
     */
    fun aceleradorLigado(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).getBoolean(ACELERADOR, false)

    fun ligarAcelerador(context: Context, ligado: Boolean) {
        context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).edit().putBoolean(ACELERADOR, ligado).apply()
    }
}
