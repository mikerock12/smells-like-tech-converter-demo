package br.com.smellsliketech.converter.figurinhas

/**
 * Limpeza da máscara da remoção de fundo: tira as manchas soltas e aperta a borda, para não
 * sobrar névoa nem halo do fundo. Espelho de recorte-mascara.mjs e StudioMask.cs, conferido
 * por recorte-casos.json.
 */
object RefinoDaMascara {
    const val CORTE_DA_ILHA = .5f
    const val ILHA_MINIMA = .02f
    const val BAIXO = .45f
    const val ALTO = .85f

    /** Encurta a transição da borda e a leva para dentro da figura. */
    fun curva(valor: Float): Float {
        val t = ((valor - BAIXO) / (ALTO - BAIXO)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Tira as manchas soltas, no lugar. Devolve quantas saíram. */
    fun limparIlhas(mascara: FloatArray, largura: Int, altura: Int): Int {
        val rotulos = IntArray(largura * altura)
        val tamanhos = mutableListOf(0)
        val pilha = IntArray(largura * altura)
        for (inicio in mascara.indices) {
            if (rotulos[inicio] != 0 || mascara[inicio] < CORTE_DA_ILHA) continue
            val rotulo = tamanhos.size
            var topo = 0
            var tamanho = 0
            pilha[topo++] = inicio
            rotulos[inicio] = rotulo
            while (topo > 0) {
                val ponto = pilha[--topo]
                tamanho++
                val x = ponto % largura
                for (vizinho in intArrayOf(if (x > 0) ponto - 1 else -1, if (x < largura - 1) ponto + 1 else -1, ponto - largura, ponto + largura)) {
                    if (vizinho < 0 || vizinho >= mascara.size || rotulos[vizinho] != 0 || mascara[vizinho] < CORTE_DA_ILHA) continue
                    rotulos[vizinho] = rotulo
                    pilha[topo++] = vizinho
                }
            }
            tamanhos += tamanho
        }
        val limite = tamanhos.max() * ILHA_MINIMA
        for (i in mascara.indices) if (rotulos[i] != 0 && tamanhos[rotulos[i]] < limite) mascara[i] = 0f
        return tamanhos.drop(1).count { it < limite }
    }
}
