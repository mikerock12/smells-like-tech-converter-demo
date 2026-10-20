package br.com.smellsliketech.converter.figurinhas

/**
 * Tempos reais do WebP, depois da otimização do libwebp. O WhatsApp exige mais de um
 * quadro, pelo menos 8 ms por quadro e no máximo 10 s no total (WhatsApp/stickers).
 * Pular quadros pode arredondar 10 s para 10,2 s; limitar só o vídeo de entrada não basta.
 */
object WebpAnimado {
    const val QUADRO_MIN_MS = 8
    const val DURACAO_MAX_MS = 10_000

    data class Quadro(val posicaoDoTempo: Int, val duracaoMs: Int, val largura: Int, val altura: Int)
    data class Informacao(val largura: Int, val altura: Int, val quadros: List<Quadro>) {
        val duracaoMs: Long get() = quadros.sumOf { it.duracaoMs.toLong() }
        val temImagem: Boolean get() = quadros.any { it.largura > 1 || it.altura > 1 }
    }

    fun ler(webp: ByteArray): Informacao {
        fun texto(i: Int, n: Int) = String(webp, i, n, Charsets.US_ASCII)
        fun numero(i: Int, n: Int): Long = (0 until n).fold(0L) { acc, k ->
            acc or ((webp[i + k].toLong() and 0xff) shl (8 * k))
        }
        require(webp.size >= 12 && texto(0, 4) == "RIFF" && texto(8, 4) == "WEBP") { "A figurinha não é um WebP válido." }
        require(numero(4, 4) == webp.size.toLong() - 8) { "O WebP da figurinha está incompleto." }
        var largura = 0
        var altura = 0
        var animacao = false
        val quadros = mutableListOf<Quadro>()
        var i = 12
        while (i < webp.size) {
            require(i <= webp.size - 8) { "O WebP da figurinha está incompleto." }
            val tamanho = numero(i + 4, 4)
            val fim = i.toLong() + 8 + tamanho + (tamanho and 1)
            require(fim <= webp.size) { "O WebP da figurinha está incompleto." }
            when (texto(i, 4)) {
                "VP8X" -> {
                    require(i == 12 && tamanho == 10L && numero(i + 8, 1).toInt() and 2 != 0) { "A figurinha precisa ser animada." }
                    largura = numero(i + 12, 3).toInt() + 1
                    altura = numero(i + 15, 3).toInt() + 1
                }
                "ANIM" -> {
                    require(tamanho == 6L && !animacao && quadros.isEmpty()) { "A animação da figurinha é inválida." }
                    animacao = true
                }
                "ANMF" -> {
                    require(animacao && tamanho > 16) { "O quadro da figurinha está incompleto." }
                    val x = numero(i + 8, 3) * 2
                    val y = numero(i + 11, 3) * 2
                    val w = numero(i + 14, 3).toInt() + 1
                    val h = numero(i + 17, 3).toInt() + 1
                    require(x + w <= largura && y + h <= altura) { "O quadro está fora da figurinha." }
                    quadros += Quadro(i + 20, numero(i + 20, 3).toInt(), w, h)
                }
            }
            i = fim.toInt()
        }
        require(largura == Regras.LADO && altura == Regras.LADO) { "A figurinha precisa ter 512 × 512 pixels." }
        require(quadros.size > 1) { "A figurinha animada precisa ter pelo menos dois quadros. Escolha um trecho com movimento." }
        require(webp.size <= Regras.ANIMADA_MAX_BYTES) { "A figurinha passou de 500 KB." }
        return Informacao(largura, altura, quadros)
    }

    /** Ajusta somente os três bytes de duração de cada ANMF; não recodifica nem perde qualidade. */
    fun normalizar(webp: ByteArray, duracaoMaximaMs: Int = DURACAO_MAX_MS): ByteArray {
        val info = ler(webp)
        val minimo = info.quadros.size * QUADRO_MIN_MS
        require(duracaoMaximaMs in minimo..DURACAO_MAX_MS) { "Há quadros demais para a duração da figurinha." }
        val tempos = info.quadros.map { maxOf(QUADRO_MIN_MS, it.duracaoMs) }
        val total = tempos.sumOf { it.toLong() }
        val alvo = minOf(total, duracaoMaximaMs.toLong())
        if (total == info.duracaoMs && total == alvo) return webp

        val resultado = webp.copyOf()
        val pesoTotal = total - minimo
        var pesoAcumulado = 0L
        var distribuido = 0L
        info.quadros.forEachIndexed { indice, quadro ->
            pesoAcumulado += tempos[indice] - QUADRO_MIN_MS
            val acumulado = if (pesoTotal == 0L) 0L else (alvo - minimo) * pesoAcumulado / pesoTotal
            val tempo = QUADRO_MIN_MS + (acumulado - distribuido).toInt()
            repeat(3) { k -> resultado[quadro.posicaoDoTempo + k] = (tempo ushr (8 * k)).toByte() }
            distribuido = acumulado
        }
        return resultado
    }
}
