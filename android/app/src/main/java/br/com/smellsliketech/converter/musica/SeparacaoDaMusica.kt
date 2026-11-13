package br.com.smellsliketech.converter.musica

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class FaixaDoModo(val id: String, val rotulo: String, val de: List<String>)

data class ModoDeSeparacao(val id: String, val rotulo: String, val faixas: List<FaixaDoModo>)

/** Lê `n` amostras do canal a partir de `inicio` (sempre dentro do sinal) em `destino`. */
fun interface LeitorDeAmostras { fun ler(canal: Int, inicio: Long, n: Int, destino: FloatArray) }

/** Recebe amostras prontas; o buffer é reaproveitado, então copia na hora. */
fun interface EmissorDeAmostras { fun emitir(canal: Int, inicio: Long, amostras: FloatArray, n: Int) }

/**
 * Separação de faixas e redução de ruído, espelho de musica-separacao.mjs: STFT, máscaras de
 * Wiener e reconstrução em trechos de 512 quadros, com memória limitada (o modelo roda fora).
 */
object SeparacaoDaMusica {
    const val MODELO_ID = "spleeter-4stems-v1"
    const val ARQUIVO = "spleeter-4stems-v1.onnx"
    const val URL = "https://converter.smellsliketech.com.br/modelos/spleeter-4stems-v1.onnx"
    const val SHA256 = "c5fae1215e5636a911ef4ced6eb056b129562d2661a186fddfa321afc2dc95a1"
    const val BYTES = 78614199L
    const val LICENCA = "MIT — github.com/deezer/spleeter (código e modelos pré-treinados)"
    const val TAXA = 44100
    const val JANELA = 4096
    const val PASSO = 1024
    const val QUADROS_POR_TRECHO = 512
    const val BANDAS = 1024
    val INSTRUMENTOS = listOf("vozes", "bateria", "baixo", "outros")

    val MODOS = listOf(
        ModoDeSeparacao("2", "Voz e instrumental", listOf(FaixaDoModo("vozes", "Voz", listOf("vozes")), FaixaDoModo("instrumental", "Instrumental", listOf("bateria", "baixo", "outros")))),
        ModoDeSeparacao("4", "Voz, bateria, baixo e outros", listOf(
            FaixaDoModo("vozes", "Voz", listOf("vozes")), FaixaDoModo("bateria", "Bateria", listOf("bateria")),
            FaixaDoModo("baixo", "Baixo", listOf("baixo")), FaixaDoModo("outros", "Outros", listOf("outros")),
        )),
    )

    const val AVISO = "A separação por IA nunca é perfeita: pode sobrar um pouco de um instrumento no outro, principalmente quando tocam juntos na mesma região. " +
        "Guitarra, piano, segunda voz ou backing vocal separados não estão disponíveis neste modelo: eles ficam em “outros” ou junto da voz principal. " +
        "A faixa original continua no projeto, para comparar."

    fun modo(id: String) = MODOS.firstOrNull { it.id == id }

    /** Math.hypot do JS. */
    fun hypot(a0: Double, b0: Double): Double {
        val a = abs(a0); val b = abs(b0)
        if (a == 0.0) return b
        if (b == 0.0) return a
        return sqrt(a * a + b * b)
    }

    fun mascaraDaFaixa(estimativas: FloatArray, instrumentos: Int, indices: IntArray, q: Int, T: Int, bandas: Int, B: Int, destino: Array<FloatArray>) {
        val eps = 1e-10
        val plano = 2 * T * bandas
        for (c in 0 until 2) {
            val m = destino[c]
            for (f in 0 until q) {
                var somaDaLinha = 0.0
                val base = (c * T + f) * bandas
                for (k in 0 until bandas) {
                    var total = eps
                    var parte = 0.0
                    for (i in 0 until instrumentos) { val e = estimativas[i * plano + base + k].toDouble(); total += e * e }
                    for (i in indices) { val e = estimativas[i * plano + base + k].toDouble(); parte += e * e + eps / instrumentos }
                    val valor = parte / total
                    m[f * B + k] = valor.toFloat()
                    somaDaLinha += valor
                }
                val media = (somaDaLinha / bandas).toFloat()
                for (k in bandas until B) m[f * B + k] = media
            }
        }
    }

    /**
     * Separa: [executar] recebe a magnitude [1,2,512,1024] e devolve as estimativas
     * [1,4,2,512,1024]; [emitir] recebe (faixa, canal, início, amostras, n).
     */
    suspend fun separar(
        comprimento: Long,
        canais: Int,
        leitor: LeitorDeAmostras,
        executar: suspend (FloatArray) -> FloatArray,
        emitir: (Int, Int, Long, FloatArray, Int) -> Unit,
        modo: String = "4",
        progresso: (Double) -> Unit = {},
        cancelado: () -> Boolean = { false },
        nativo: Boolean = false,
        refinada: Boolean = false,
    ): List<FaixaDoModo> {
        val definicao = modo(modo) ?: error("Modo de separação desconhecido: $modo")
        if (refinada || nativo && NucleoNativo.disponivel) {
            check(NucleoNativo.disponivel) { "O núcleo nativo é necessário para refinar os agudos." }
            return separarNativo(comprimento, canais, leitor, executar, emitir, definicao, progresso, cancelado, refinada)
        }
        val p = ProcessadorEspectral(comprimento, JANELA, PASSO, QUADROS_POR_TRECHO, definicao.faixas.size)
        val entrada = FloatArray(2 * QUADROS_POR_TRECHO * BANDAS)
        val mascara = arrayOf(FloatArray(QUADROS_POR_TRECHO * p.B), FloatArray(QUADROS_POR_TRECHO * p.B))
        val indices = definicao.faixas.map { f -> f.de.map { INSTRUMENTOS.indexOf(it) }.toIntArray() }
        for (s in 0 until p.trechos) {
            if (cancelado()) throw kotlinx.coroutines.CancellationException("Separação cancelada.")
            val q = p.analisar(s, leitor, canais)
            p.magnitude(q, BANDAS, entrada)
            val estimativas = executar(entrada)
            for (f in definicao.faixas.indices) {
                mascaraDaFaixa(estimativas, INSTRUMENTOS.size, indices[f], q, QUADROS_POR_TRECHO, BANDAS, p.B, mascara)
                p.sintetizar(s, f, mascara) { c, inicio, amostras, n -> emitir(f, c, inicio, amostras, n) }
            }
            progresso((s + 1) / p.trechos.toDouble())
        }
        return definicao.faixas
    }

    private suspend fun separarNativo(
        comprimento: Long, canais: Int, leitor: LeitorDeAmostras, executar: suspend (FloatArray) -> FloatArray,
        emitir: (Int, Int, Long, FloatArray, Int) -> Unit, modo: ModoDeSeparacao,
        progresso: (Double) -> Unit, cancelado: () -> Boolean, refinada: Boolean,
    ): List<FaixaDoModo> {
        val tamanho = 2 * QUADROS_POR_TRECHO * BANDAS
        val capacidade = QUADROS_POR_TRECHO * PASSO + JANELA
        val magnitude = NucleoNativo.plano(tamanho)
        val estimativas = NucleoNativo.plano(tamanho * INSTRUMENTOS.size)
        val canaisDiretos = List(2) { NucleoNativo.plano(capacidade) }
        val fonte = FloatArray(capacidade)
        val copia = FloatArray(capacidade)
        val tensor = FloatArray(tamanho)
        val indices = modo.faixas.map { f -> f.de.map(INSTRUMENTOS::indexOf).toIntArray() }
        NucleoNativo.Separador(comprimento, JANELA, PASSO, QUADROS_POR_TRECHO, BANDAS,
            INSTRUMENTOS.size, indices.size, refinada).use { sep ->
            val trechos = sep.trechos()
            for (trecho in 0 until trechos) {
                if (cancelado()) throw kotlinx.coroutines.CancellationException("Separação cancelada.")
                val janela = sep.janela(trecho)
                val inicio = max(0, janela.inicio)
                val n = max(0, min(comprimento, janela.inicio + janela.amostras) - inicio).toInt()
                val deslocamento = (inicio - janela.inicio).toInt()
                for (c in 0..1) {
                    copia.fill(0f, 0, janela.amostras)
                    if (n > 0) {
                        leitor.ler(min(c, canais - 1), inicio, n, fonte)
                        fonte.copyInto(copia, deslocamento, 0, n)
                    }
                    canaisDiretos[c].asFloatBuffer().put(copia, 0, janela.amostras)
                }
                sep.analisar(trecho, janela.amostras, canaisDiretos[0], canaisDiretos[1], magnitude, tamanho)
                magnitude.asFloatBuffer().get(tensor)
                val resultado = executar(tensor)
                check(resultado.size == tamanho * INSTRUMENTOS.size) { "O modelo devolveu estimativas de tamanho inválido." }
                estimativas.asFloatBuffer().put(resultado)
                for ((f, ids) in indices.withIndex()) {
                    val pronto = sep.sintetizar(trecho, f, estimativas, resultado.size, ids,
                        canaisDiretos[0], canaisDiretos[1], capacidade)
                    if (pronto.amostras == 0) continue
                    for (c in 0..1) {
                        canaisDiretos[c].asFloatBuffer().get(copia, 0, pronto.amostras)
                        emitir(f, c, pronto.inicio, copia, pronto.amostras)
                    }
                }
                progresso((trecho + 1) / trechos.toDouble())
            }
        }
        return modo.faixas
    }

    /** Redução de ruído por porta espectral (perfil dos 10% de quadros mais quietos). */
    fun reduzirRuido(comprimento: Long, canais: Int, leitor: LeitorDeAmostras, emissor: EmissorDeAmostras, intensidade: Double = 1.5, piso: Double = 0.1) {
        val janela = 2048; val passo = 512; val t = 256
        val analise = ProcessadorEspectral(comprimento, janela, passo, t, 1)
        val b = analise.B
        val energias = ArrayList<Double>()
        for (s in 0 until analise.trechos) {
            val q = analise.analisar(s, leitor, canais)
            for (f in 0 until q) {
                var e = 0.0
                for (c in 0 until 2) for (k in 0 until b) {
                    val re = analise.re[c][f * b + k].toDouble(); val im = analise.im[c][f * b + k].toDouble()
                    e += re * re + im * im
                }
                energias += e
            }
        }
        val ordenadas = energias.sorted()
        val limiar = if (ordenadas.isEmpty()) 0.0 else ordenadas[max(0, floor(ordenadas.size * 0.1).toInt() - 1)]
        val perfil = arrayOf(DoubleArray(b), DoubleArray(b))
        var contados = 0
        for (s in 0 until analise.trechos) {
            val q = analise.analisar(s, leitor, canais)
            for (f in 0 until q) {
                if (energias[s * t + f] > limiar) continue
                contados++
                for (c in 0 until 2) for (k in 0 until b) perfil[c][k] += hypot(analise.re[c][f * b + k].toDouble(), analise.im[c][f * b + k].toDouble())
            }
        }
        for (c in 0 until 2) for (k in 0 until b) perfil[c][k] /= max(1, contados).toDouble()
        val sintese = ProcessadorEspectral(comprimento, janela, passo, t, 1)
        val mascara = arrayOf(FloatArray(t * b), FloatArray(t * b))
        for (s in 0 until sintese.trechos) {
            val q = sintese.analisar(s, leitor, canais)
            for (c in 0 until 2) for (f in 0 until q) for (k in 0 until b) {
                val i = f * b + k
                val magnitude = hypot(sintese.re[c][i].toDouble(), sintese.im[c][i].toDouble())
                mascara[c][i] = max(piso, if (magnitude > 0) 1 - intensidade * perfil[c][k] / magnitude else 0.0).toFloat()
            }
            sintese.sintetizar(s, 0, mascara, emissor)
        }
    }
}

/** FFT complexa radix-2 no lugar. */
class Fft(private val n: Int) {
    private val cosseno = DoubleArray(n / 2) { cos(2 * Math.PI * it / n) }
    private val seno = DoubleArray(n / 2) { sin(2 * Math.PI * it / n) }
    private val inverso = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) { var v = 0; for (b in 0 until bits) v = v or (((i shr b) and 1) shl (bits - 1 - b)); r[i] = v }
    }

    fun transformar(re: DoubleArray, im: DoubleArray, sinal: Int) {
        for (i in 0 until n) {
            val j = inverso[i]
            if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var tamanho = 2
        while (tamanho <= n) {
            val metade = tamanho / 2
            val passo = n / tamanho
            var inicio = 0
            while (inicio < n) {
                for (k in 0 until metade) {
                    val wr = cosseno[k * passo]
                    val wi = sinal * seno[k * passo]
                    val a = inicio + k
                    val b = a + metade
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                inicio += tamanho
            }
            tamanho *= 2
        }
    }
}

/** Análise/síntese STFT em trechos, espelho de ProcessadorEspectral (JS). */
class ProcessadorEspectral(private val comprimento: Long, private val N: Int, private val H: Int, private val T: Int, saidas: Int) {
    val B = N / 2 + 1
    val quadros: Long = (comprimento + N) / H + 1
    val trechos: Int = ((quadros + T - 1) / T).toInt()
    private val fft = Fft(N)
    private val w = DoubleArray(N) { 0.5 - 0.5 * cos(2 * Math.PI * it / N) }
    private val normal: Double = run { var e = 0.0; for (v in w) e += v * v; e / H }
    val re = arrayOf(FloatArray(T * B), FloatArray(T * B))
    val im = arrayOf(FloatArray(T * B), FloatArray(T * B))
    private val amostras = FloatArray((T - 1) * H + N)
    private val br = DoubleArray(N)
    private val bi = DoubleArray(N)
    private val acumulado = Array(saidas) { arrayOf(DoubleArray(T * H + N), DoubleArray(T * H + N)) }
    private val emitidos = LongArray(saidas)
    private val base = LongArray(saidas)

    fun quadrosDoTrecho(s: Int): Int = min(T.toLong(), quadros - s.toLong() * T).toInt()

    private fun lerComZeros(leitor: LeitorDeAmostras, canal: Int, inicio: Long, n: Int) {
        amostras.fill(0f, 0, n)
        val a = max(0L, inicio)
        val b = min(comprimento, inicio + n)
        if (b <= a) return
        val k = (b - a).toInt()
        val temp = FloatArray(k)
        leitor.ler(canal, a, k, temp)
        System.arraycopy(temp, 0, amostras, (a - inicio).toInt(), k)
    }

    fun analisar(s: Int, leitor: LeitorDeAmostras, canais: Int): Int {
        val q = quadrosDoTrecho(s)
        val f0 = s.toLong() * T
        val inicio = f0 * H - N
        val n = (q - 1) * H + N
        for (c in 0 until 2) {
            lerComZeros(leitor, min(c, canais - 1), inicio, n)
            for (f in 0 until q) {
                for (i in 0 until N) { br[i] = amostras[f * H + i] * w[i]; bi[i] = 0.0 }
                fft.transformar(br, bi, -1)
                for (k in 0 until B) { re[c][f * B + k] = br[k].toFloat(); im[c][f * B + k] = bi[k].toFloat() }
            }
        }
        return q
    }

    fun magnitude(q: Int, bandas: Int, destino: FloatArray): FloatArray {
        destino.fill(0f)
        for (c in 0 until 2) for (f in 0 until q) for (k in 0 until bandas) {
            val i = f * B + k
            destino[(c * T + f) * bandas + k] = SeparacaoDaMusica.hypot(re[c][i].toDouble(), im[c][i].toDouble()).toFloat()
        }
        return destino
    }

    fun sintetizar(s: Int, saida: Int, mascara: Array<FloatArray>, emissor: EmissorDeAmostras) {
        val q = quadrosDoTrecho(s)
        val f0 = s.toLong() * T
        val ultimo = s == trechos - 1
        for (c in 0 until 2) {
            val acc = acumulado[saida][c]
            val m = mascara[c]
            for (f in 0 until q) {
                for (k in 0 until B) { val i = f * B + k; br[k] = re[c][i] * m[i].toDouble(); bi[k] = im[c][i] * m[i].toDouble() }
                for (k in 1 until N / 2) { br[N - k] = br[k]; bi[N - k] = -bi[k] }
                fft.transformar(br, bi, 1)
                val posicao = ((f0 + f) * H - base[saida]).toInt()
                for (i in 0 until N) acc[posicao + i] += br[i] / N * w[i] / normal
            }
        }
        val prontoAte = min(comprimento, if (ultimo) comprimento else (f0 + q) * H - N)
        val de = emitidos[saida]
        if (prontoAte > de) {
            val n = (prontoAte - de).toInt()
            for (c in 0 until 2) {
                val acc = acumulado[saida][c]
                val desloc = (de + N - base[saida]).toInt()
                for (i in 0 until n) amostras[i] = acc[desloc + i].toFloat()
                emissor.emitir(c, de, amostras, n)
            }
            emitidos[saida] = prontoAte
        }
        val novaBase = (f0 + q) * H
        val desloc = (novaBase - base[saida]).toInt()
        for (acc in acumulado[saida]) {
            System.arraycopy(acc, desloc, acc, 0, N)
            acc.fill(0.0, N, acc.size)
        }
        base[saida] = novaBase
    }
}
