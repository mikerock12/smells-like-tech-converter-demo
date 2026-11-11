package br.com.smellsliketech.converter.musica

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Tempo e transposição por clipe, sem alterar a mídia original. */
object TransformacaoDaMusica {
    const val VELOCIDADE_MINIMA = 0.25
    const val VELOCIDADE_MAXIMA = 4.0

    fun temTransformacao(c: ClipeMusical) = (c.velocidade ?: 1.0) != 1.0 || (c.semitons ?: 0.0) != 0.0

    fun chaveDaFonte(c: ClipeMusical): String = if (!temTransformacao(c)) c.midia else
        "${c.midia}~${String.format(Locale.US, "%.6f", c.velocidade ?: 1.0)}~${String.format(Locale.US, "%.3f", c.semitons ?: 0.0)}~${if (c.preservarTom ?: true) 1 else 0}"

    fun comVelocidade(c: ClipeMusical, m: MidiaMusical, velocidade: Double): ClipeMusical {
        require(velocidade.isFinite() && velocidade in VELOCIDADE_MINIMA..VELOCIDADE_MAXIMA) { "A velocidade vai de 25% a 400%." }
        val anterior = c.velocidade ?: 1.0
        val disponivel = max(1.0, (m.quadros - c.inicioNaMidia).toDouble())
        val origem = min(disponivel, c.duracao * anterior)
        val duracao = max(1.0, floor(origem / velocidade)).toLong()
        return c.copy(velocidade = velocidade, duracao = duracao, entradaSuave = min(c.entradaSuave, duracao), saidaSuave = min(c.saidaSuave, duracao))
    }

    fun velocidadeParaIgualar(origem: Double, destino: Double): Double {
        require(origem > 0 && destino > 0) { "Identifique o BPM das duas faixas primeiro." }
        return (origem / destino).coerceIn(VELOCIDADE_MINIMA, VELOCIDADE_MAXIMA)
    }

    /** Filtros do FFmpeg para processar o WAV em fluxo. O ajuste de taxa muda o tom,
     * e o atempo compensa a duração sem mudar novamente a afinação. */
    internal fun filtroEmFluxo(taxa: Int, quadros: Long, velocidade: Double, semitons: Double, preservarTom: Boolean): String {
        require(velocidade in VELOCIDADE_MINIMA..VELOCIDADE_MAXIMA && semitons in -12.0..12.0)
        val tom = 2.0.pow(semitons / 12)
        val fator = if (preservarTom) tom else tom * velocidade
        val novaTaxa = Musica.arredondar(taxa * fator)
        var tempo = velocidade * taxa / novaTaxa
        val filtros = mutableListOf<String>()
        if (novaTaxa != taxa.toLong()) filtros += listOf("asetrate=$novaTaxa", "aresample=$taxa")
        while (tempo > 2.0) { filtros += "atempo=2"; tempo /= 2 }
        while (tempo < 0.5) { filtros += "atempo=0.5"; tempo *= 2 }
        if (abs(tempo - 1) > 1e-9) filtros += "atempo=$tempo"
        val saida = max(1L, Musica.arredondar(quadros / velocidade))
        filtros += listOf("apad=whole_len=$saida", "atrim=end_sample=$saida")
        return filtros.joinToString(",")
    }

    fun reamostrar(canais: Array<FloatArray>, fator: Double): Array<FloatArray> {
        if (fator == 1.0) return Array(canais.size) { canais[it].copyOf() }
        return Array(canais.size) { c ->
            val canal = canais[c]
            if (canal.isEmpty()) return@Array FloatArray(0)
            val tamanho = max(1.0, Musica.arredondar(canal.size / fator).toDouble()).toInt()
            val saida = FloatArray(tamanho)
            for (i in 0 until tamanho) {
                val posicao = min((canal.size - 1).toDouble(), i * fator)
                val a = max(0.0, floor(posicao)).toInt()
                val b = min(canal.size - 1, a + 1)
                val peso = max(0.0, posicao - a)
                saida[i] = (canal[a] + (canal[b] - canal[a]) * peso).toFloat()
            }
            saida
        }
    }

    private fun esticar(canais: Array<FloatArray>, taxa: Int, velocidade: Double): Array<FloatArray> {
        if (velocidade == 1.0) return Array(canais.size) { canais[it].copyOf() }
        val origem = canais.firstOrNull()?.size ?: 0
        val tamanho = max(1L, Musica.arredondar(origem / velocidade)).toInt()
        val janela = max(64L, Musica.arredondar(taxa * 0.04 / 2) * 2).toInt()
        val passo = janela / 2
        val busca = Musica.arredondar(taxa * 0.008).toInt()
        val saida = Array(canais.size) { FloatArray(tamanho) }
        val pesos = DoubleArray(tamanho)
        val envelope = DoubleArray(janela) { 0.5 - 0.5 * cos(2 * Math.PI * (it + 0.5) / janela) }
        var anterior = 0
        var pos = 0
        while (pos < tamanho) {
            val esperado = Musica.arredondar(pos * velocidade).toInt()
            val maximo = max(0, origem - janela)
            var escolhido = min(esperado, maximo)
            if (pos > 0 && esperado < maximo) {
                var melhor = Double.NEGATIVE_INFINITY
                var candidato = max(0, esperado - busca)
                val ate = min(maximo, esperado + busca)
                while (candidato <= ate) {
                    var produto = 0.0
                    var a = 1e-9
                    var b = 1e-9
                    var i = 0
                    while (i < passo) {
                        val x = if (anterior + passo + i < origem) canais[0][anterior + passo + i].toDouble() else 0.0
                        val y = if (candidato + i < origem) canais[0][candidato + i].toDouble() else 0.0
                        produto += x * y; a += x * x; b += y * y
                        i += 8
                    }
                    val nota = produto / sqrt(a * b) - abs(candidato - esperado) * 1e-8
                    if (nota > melhor) { melhor = nota; escolhido = candidato }
                    candidato += 4
                }
            }
            var i = 0
            while (i < janela && pos + i < tamanho) {
                val peso = envelope[i]
                pesos[pos + i] += peso
                for (c in canais.indices) saida[c][pos + i] += (if (escolhido + i < origem) canais[c][escolhido + i] else 0f) * peso.toFloat()
                i++
            }
            anterior = escolhido
            pos += passo
        }
        for (i in 0 until tamanho) if (pesos[i] > 0) for (canal in saida) canal[i] = (canal[i] / pesos[i]).toFloat()
        return saida
    }

    fun transformar(canais: Array<FloatArray>, taxa: Int, velocidade: Double = 1.0, semitons: Double = 0.0, preservarTom: Boolean = true): Array<FloatArray> {
        require(velocidade in VELOCIDADE_MINIMA..VELOCIDADE_MAXIMA) { "A velocidade vai de 25% a 400%." }
        require(semitons in -12.0..12.0) { "A tonalidade vai de −12 a +12 semitons." }
        val tom = 2.0.pow(semitons / 12)
        if (preservarTom) return esticar(reamostrar(canais, tom), taxa, velocidade / tom)
        return esticar(reamostrar(canais, velocidade * tom), taxa, 1 / tom)
    }

    fun lerTudo(fonte: FonteDeAudio, limite: Long = fonte.quadros): Array<FloatArray> = Array(fonte.canais) { canal ->
        val quadros = min(limite, fonte.quadros)
        val dados = FloatArray(quadros.toInt())
        var inicio = 0L
        val passo = 65536
        val buffer = FloatArray(passo)
        while (inicio < quadros) {
            val n = min(passo.toLong(), quadros - inicio).toInt()
            fonte.copiar(canal, inicio, n, buffer)
            System.arraycopy(buffer, 0, dados, inicio.toInt(), n)
            inicio += n
        }
        dados
    }
}
