package br.com.smellsliketech.converter.musica

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

data class PresetDeMaster(val id: String, val rotulo: String, val alvoLufs: Double?, val tetoDbtp: Double)

data class FormatoDeExportacao(val id: String, val rotulo: String, val extensao: String, val bits: Int)

data class Medidas(val lufs: Double, val amostraDb: Double, val realDb: Double)

class ResultadoDoMaster(val esq: FloatArray, val dir: FloatArray, val ganhoDb: Double, val antes: Medidas, val depois: Medidas)

/** Master e WAV, espelho de musica-master.mjs (BS.1770-4, pico real 4×, dither TPDF xorshift32). */
object MasterDaMusica {
    val PRESETS = listOf(
        PresetDeMaster("sem-alvo", "Sem alvo (só proteção contra clipping)", null, -1.0),
        PresetDeMaster("streaming", "Streaming (−14 LUFS)", -14.0, -1.0),
        PresetDeMaster("podcast", "Podcast e voz (−16 LUFS)", -16.0, -1.0),
        PresetDeMaster("video", "Vídeo para redes (−14 LUFS, teto −2 dBTP)", -14.0, -2.0),
        PresetDeMaster("alto", "Alto, estilo CD (−9 LUFS)", -9.0, -0.5),
    )

    val FORMATOS = listOf(
        FormatoDeExportacao("wav-24", "WAV 24 bits", "wav", 24),
        FormatoDeExportacao("wav-16", "WAV 16 bits (CD)", "wav", 16),
        FormatoDeExportacao("wav-32f", "WAV 32 bits float", "wav", 32),
        FormatoDeExportacao("flac", "FLAC 24 bits", "flac", 24),
        FormatoDeExportacao("mp3", "MP3 320 kbps", "mp3", 16),
        FormatoDeExportacao("aac", "AAC 256 kbps (M4A)", "m4a", 16),
        FormatoDeExportacao("opus", "Opus 192 kbps", "opus", 16),
    )

    fun coeficientesK(taxa: Int): Array<DoubleArray> {
        val prateleira = run {
            val g = 3.999843853973347; val q = 0.7071752369554196; val fc = 1681.974450955533
            val k = tan(Math.PI * fc / taxa)
            val vh = 10.0.pow(g / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            doubleArrayOf((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        val passaAlta = run {
            val q = 0.5003270373238773; val fc = 38.13547087602444
            val k = tan(Math.PI * fc / taxa)
            val a0 = 1 + k / q + k * k
            doubleArrayOf(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
        return arrayOf(prateleira, passaAlta)
    }

    fun loudnessIntegrado(esq: FloatArray, dir: FloatArray, taxa: Int): Double =
        loudnessIntegrado(FonteNaMemoria(arrayOf(esq, dir)), taxa) {}

    private fun loudnessIntegrado(fonte: FonteDeAudio, taxa: Int, verificar: () -> Unit): Double {
        val c = coeficientesK(taxa)
        val f1 = BiquadMusical(c[0]); val f2 = BiquadMusical(c[1])
        val n = fonte.quadros
        val tamanho = Musica.arredondar(taxa * 0.4).toInt()
        val passo = Musica.arredondar(taxa * 0.1).toInt()
        if (n < tamanho) return Double.NEGATIVE_INFINITY
        val partes = (n / passo).toInt()
        val energia = DoubleArray(partes)
        val esq = FloatArray(passo)
        val dir = FloatArray(passo)
        for (parte in 0 until partes) {
            verificar()
            val inicio = parte.toLong() * passo
            fonte.copiar(0, inicio, passo, esq)
            fonte.copiar(1, inicio, passo, dir)
            for (i in 0 until passo) {
                val e = f2.amostra(f1.amostra(esq[i].toDouble(), 0), 0)
                val d = f2.amostra(f1.amostra(dir[i].toDouble(), 1), 1)
                energia[parte] += e * e + d * d
            }
        }
        val blocos = ArrayList<Double>()
        var b = 0
        while (b + 4 <= partes) { blocos += (energia[b] + energia[b + 1] + energia[b + 2] + energia[b + 3]) / tamanho; b++ }
        fun lufs(z: Double) = -0.691 + 10 * log10(z)
        val absolutos = blocos.filter { it > 0 && lufs(it) > -70 }
        if (absolutos.isEmpty()) return Double.NEGATIVE_INFINITY
        fun media(lista: List<Double>): Double { var s = 0.0; for (z in lista) s += z; return s / lista.size }
        val relativo = lufs(media(absolutos)) - 10
        val finais = absolutos.filter { lufs(it) > relativo }
        return if (finais.isNotEmpty()) lufs(media(finais)) else Double.NEGATIVE_INFINITY
    }

    fun fasesDoPicoReal(): Array<DoubleArray> = Array(4) { f ->
        DoubleArray(12) { k ->
            val x = k - 6 + 1 - f / 4.0
            val sinc = if (x == 0.0) 1.0 else sin(Math.PI * x) / (Math.PI * x)
            val janela = 0.5 + 0.5 * cos(Math.PI * x / (6 + 0.5))
            sinc * janela
        }
    }

    fun picos(esq: FloatArray, dir: FloatArray): Pair<Double, Double> =
        picos(FonteNaMemoria(arrayOf(esq, dir))) {}

    private fun picos(fonte: FonteDeAudio, verificar: () -> Unit): Pair<Double, Double> {
        val fases = fasesDoPicoReal()
        var amostra = 0.0
        var real = 0.0
        val bloco = 8192
        val dados = FloatArray(bloco + 11)
        for (canal in 0 until fonte.canais) {
            var inicio = 0L
            while (inicio < fonte.quadros) {
                verificar()
                val n = min(bloco.toLong(), fonte.quadros - inicio).toInt()
                val anterior = min(5L, inicio).toInt()
                fonte.copiar(canal, inicio - anterior, n + anterior + 6, dados)
                for (i in anterior until anterior + n) {
                    amostra = max(amostra, abs(dados[i].toDouble()))
                    for (f in 1 until 4) {
                        val c = fases[f]
                        var soma = 0.0
                        for (k in 0 until 12) {
                            val j = i + k - 5
                            if (j >= 0) soma += dados[j] * c[k]
                        }
                        real = max(real, abs(soma))
                    }
                }
                inicio += n
            }
        }
        real = max(real, amostra)
        fun dbfs(v: Double) = if (v > 0) 20 * log10(v) else Double.NEGATIVE_INFINITY
        return Pair(dbfs(amostra), dbfs(real))
    }

    fun medir(esq: FloatArray, dir: FloatArray, taxa: Int): Medidas =
        medir(FonteNaMemoria(arrayOf(esq, dir)), taxa) {}

    internal fun medir(fonte: FonteDeAudio, taxa: Int, verificar: () -> Unit): Medidas {
        val (a, r) = picos(fonte, verificar)
        return Medidas(loudnessIntegrado(fonte, taxa, verificar), a, r)
    }

    /** Limita o sinal por blocos, mantendo o envelope de liberação entre blocos. */
    internal fun limitar(fonte: FonteDeAudio, destino: EscritorDeWav, taxa: Int, preset: PresetDeMaster, verificar: () -> Unit): Double {
        val lufs = if (preset.alvoLufs == null) Double.NEGATIVE_INFINITY else loudnessIntegrado(fonte, taxa, verificar)
        val ganhoDb = if (preset.alvoLufs == null || !lufs.isFinite()) 0.0 else min(24.0, preset.alvoLufs - lufs)
        val g = 10.0.pow(ganhoDb / 20)
        val teto = 10.0.pow((preset.tetoDbtp - 0.3) / 20)
        val liberacao = exp(-1 / (taxa * 0.05))
        val esq = FloatArray(8192)
        val dir = FloatArray(8192)
        val intercalado = FloatArray(16384)
        var ganho = 1.0
        var inicio = 0L
        while (inicio < fonte.quadros) {
            verificar()
            val n = min(esq.size.toLong(), fonte.quadros - inicio).toInt()
            fonte.copiar(0, inicio, n, esq)
            fonte.copiar(1, inicio, n, dir)
            for (i in 0 until n) {
                val a = esq[i] * g
                val b = dir[i] * g
                val pico = max(abs(a), abs(b))
                val alvo = if (pico > teto) teto / pico else 1.0
                ganho = if (alvo < ganho) alvo else liberacao * ganho + (1 - liberacao) * alvo
                intercalado[i * 2] = (a * ganho).toFloat()
                intercalado[i * 2 + 1] = (b * ganho).toFloat()
            }
            destino.escrever(intercalado, n)
            inicio += n
        }
        return ganhoDb
    }

    fun masterizar(esq: FloatArray, dir: FloatArray, taxa: Int, presetId: String): ResultadoDoMaster {
        val preset = PRESETS.firstOrNull { it.id == presetId } ?: PRESETS[0]
        val antes = medir(esq, dir, taxa)
        val ganhoDb = if (preset.alvoLufs == null || !antes.lufs.isFinite()) 0.0 else min(24.0, preset.alvoLufs - antes.lufs)
        val g = 10.0.pow(ganhoDb / 20)
        val teto = 10.0.pow((preset.tetoDbtp - 0.3) / 20)
        val liberacao = exp(-1 / (taxa * 0.05))
        val e = FloatArray(esq.size); val d = FloatArray(dir.size)
        var ganho = 1.0
        for (i in esq.indices) {
            val a = esq[i] * g
            val b = dir[i] * g
            val pico = max(abs(a), abs(b))
            val alvo = if (pico > teto) teto / pico else 1.0
            ganho = if (alvo < ganho) alvo else liberacao * ganho + (1 - liberacao) * alvo
            e[i] = (a * ganho).toFloat(); d[i] = (b * ganho).toFloat()
        }
        // O limitador olha a amostra; se o pico real (4×) passar do teto, tudo desce o que falta.
        var depois = medir(e, d, taxa)
        if (depois.realDb > preset.tetoDbtp) {
            val corte = 10.0.pow((preset.tetoDbtp - depois.realDb - 0.05) / 20)
            for (i in e.indices) { e[i] = (e[i] * corte).toFloat(); d[i] = (d[i] * corte).toFloat() }
            depois = medir(e, d, taxa)
        }
        return ResultadoDoMaster(e, d, ganhoDb, antes, depois)
    }

    /** xorshift32: a mesma sequência do JS e do C#. */
    class Ruido(semente: Int = 0x12345678) {
        private var estado = if (semente == 0) 1 else semente
        fun proximo(): Double {
            estado = estado xor (estado shl 13)
            estado = estado xor (estado ushr 17)
            estado = estado xor (estado shl 5)
            return (estado.toLong() and 0xffffffffL) / 4294967296.0
        }
    }

    fun quantizar(valor: Double, bits: Int, ruido: Ruido?): Int {
        val escala = if (bits == 16) 32767 else 8388607
        val dither = if (ruido == null) 0.0 else ruido.proximo() - ruido.proximo()
        val v = floor(valor * escala + dither + 0.5).toLong()
        return max((-escala - 1).toLong(), min(escala.toLong(), v)).toInt()
    }

    fun cabecalhoWav(canais: Int, taxa: Int, bits: Int, quadros: Long): ByteArray {
        val bytesPorAmostra = bits / 8
        val dados = quadros * canais * bytesPorAmostra
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(min(0xffffffffL, 36 + dados).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII)).put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        b.putShort((if (bits == 32) 3 else 1).toShort()).putShort(canais.toShort()).putInt(taxa)
        b.putInt(taxa * canais * bytesPorAmostra).putShort((canais * bytesPorAmostra).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(min(0xffffffffL, dados).toInt())
        return b.array()
    }

    fun escreverWav(saida: OutputStream, canais: List<FloatArray>, taxa: Int, bits: Int = 24, dither: Boolean = true, semente: Int = 0x12345678) {
        escreverWav(saida, FonteNaMemoria(canais.toTypedArray()), taxa, bits, dither, semente) {}
    }

    internal fun escreverWav(saida: OutputStream, fonte: FonteDeAudio, taxa: Int, bits: Int = 24, dither: Boolean = true, semente: Int = 0x12345678, verificar: () -> Unit) {
        val c = fonte.canais
        saida.write(cabecalhoWav(c, taxa, bits, fonte.quadros))
        val ruido = if (bits != 32 && dither) Ruido(semente) else null
        val bpa = bits / 8
        val bloco = 8192
        val buffer = ByteBuffer.allocate(bloco * c * bpa).order(ByteOrder.LITTLE_ENDIAN)
        val canais = Array(c) { FloatArray(bloco) }
        var inicio = 0L
        while (inicio < fonte.quadros) {
            verificar()
            val n = min(bloco.toLong(), fonte.quadros - inicio).toInt()
            for (k in 0 until c) fonte.copiar(k, inicio, n, canais[k])
            for (i in 0 until n) for (k in 0 until c) {
                val x = canais[k][i]
                if (bits == 32) buffer.putFloat(x)
                else {
                    val q = quantizar(x.toDouble(), bits, ruido)
                    buffer.put((q and 0xff).toByte()).put(((q shr 8) and 0xff).toByte())
                    if (bits == 24) buffer.put(((q shr 16) and 0xff).toByte())
                }
            }
            saida.write(buffer.array(), 0, buffer.position())
            buffer.clear()
            inicio += n
        }
    }

    fun codificarWav(canais: List<FloatArray>, taxa: Int, bits: Int = 24, dither: Boolean = true): ByteArray =
        ByteArrayOutputStream().also { escreverWav(it, canais, taxa, bits, dither) }.toByteArray()

    class Wav(val taxa: Int, val canais: Array<FloatArray>, val bits: Int)

    /** Lê WAV PCM 16/24/32 ou float32; recupera "data" zerado ou cortado (gravação interrompida). */
    fun lerWav(bytes: ByteArray): Wav {
        val v = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun texto(p: Int) = String(bytes, p, 4, Charsets.US_ASCII)
        require(bytes.size >= 12 && texto(0) == "RIFF" && texto(8) == "WAVE") { "Não é um WAV." }
        var p = 12
        var tipo = 0; var canais = 0; var taxa = 0; var bits = 0; var temFormato = false
        while (p + 8 <= bytes.size) {
            val id = texto(p)
            var tamanho = v.getInt(p + 4).toLong() and 0xffffffffL
            if (id == "fmt ") {
                tipo = v.getShort(p + 8).toInt() and 0xffff
                if (tipo == 0xfffe && tamanho >= 26) tipo = v.getShort(p + 32).toInt() and 0xffff
                canais = v.getShort(p + 10).toInt() and 0xffff
                taxa = v.getInt(p + 12)
                bits = v.getShort(p + 22).toInt() and 0xffff
                temFormato = true
            } else if (id == "data") {
                require(temFormato) { "WAV sem formato." }
                val disponivel = (bytes.size - (p + 8)).toLong()
                if (tamanho == 0L || tamanho > disponivel) tamanho = disponivel
                val bpa = bits / 8
                val n = (tamanho / (bpa * canais)).toInt()
                val dados = Array(canais) { FloatArray(n) }
                var q = p + 8
                for (i in 0 until n) for (c in 0 until canais) {
                    dados[c][i] = when {
                        tipo == 3 -> v.getFloat(q)
                        bits == 16 -> (v.getShort(q) / 32768.0).toFloat()
                        bits == 24 -> ((((bytes[q].toInt() and 0xff) or ((bytes[q + 1].toInt() and 0xff) shl 8) or ((bytes[q + 2].toInt() and 0xff) shl 16)) shl 8 shr 8) / 8388608.0).toFloat()
                        bits == 32 -> (v.getInt(q) / 2147483648.0).toFloat()
                        else -> error("Profundidade não suportada.")
                    }
                    q += bpa
                }
                return Wav(taxa, dados, bits)
            }
            p += 8 + tamanho.toInt() + (tamanho and 1).toInt()
        }
        error("WAV sem dados.")
    }
}
