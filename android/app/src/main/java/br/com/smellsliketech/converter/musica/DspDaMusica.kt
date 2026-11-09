package br.com.smellsliketech.converter.musica

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class ParametroDoEfeito(val id: String, val rotulo: String, val min: Double, val max: Double, val padrao: Double, val unidade: String)

data class DefinicaoDoEfeito(val tipo: String, val rotulo: String, val grupo: String, val parametros: List<ParametroDoEfeito>)

/** Um efeito pronto para tocar: processa no lugar, sem alocar. */
interface ProcessadorMusical {
    fun processar(esq: FloatArray, dir: FloatArray, n: Int)
    fun atualizar(efeito: EfeitoMusical)
}

/**
 * Efeitos da criação musical, espelho de musica-dsp.mjs: mesmo catálogo, mesmas contas em
 * Double (buffers Float) e mesmas mensagens.
 */
object DspDaMusica {
    private const val DOIS_PI = Math.PI * 2
    private fun p(id: String, rotulo: String, min: Double, max: Double, padrao: Double, unidade: String) = ParametroDoEfeito(id, rotulo, min, max, padrao, unidade)

    val CATALOGO = listOf(
        DefinicaoDoEfeito("passa-alta", "Filtro passa-alta", "Filtros", listOf(p("frequencia", "Corte", 20.0, 2000.0, 80.0, "Hz"))),
        DefinicaoDoEfeito("passa-baixa", "Filtro passa-baixa", "Filtros", listOf(p("frequencia", "Corte", 500.0, 20000.0, 12000.0, "Hz"))),
        DefinicaoDoEfeito("equalizador", "Equalizador", "Filtros", listOf(
            p("gravesDb", "Graves (100 Hz)", -15.0, 15.0, 0.0, "dB"),
            p("mediosFrequencia", "Médios: frequência", 150.0, 2000.0, 500.0, "Hz"),
            p("mediosDb", "Médios: ganho", -15.0, 15.0, 0.0, "dB"),
            p("mediosQ", "Médios: largura (Q)", 0.3, 8.0, 1.0, ""),
            p("presencaFrequencia", "Presença: frequência", 1500.0, 8000.0, 3000.0, "Hz"),
            p("presencaDb", "Presença: ganho", -15.0, 15.0, 0.0, "dB"),
            p("agudosDb", "Agudos (8 kHz)", -15.0, 15.0, 0.0, "dB"),
        )),
        DefinicaoDoEfeito("compressor", "Compressor", "Dinâmica", listOf(
            p("limiarDb", "Limiar", -60.0, 0.0, -18.0, "dB"),
            p("razao", "Razão (x:1)", 1.0, 20.0, 3.0, ""),
            p("ataqueMs", "Ataque", 0.1, 100.0, 10.0, "ms"),
            p("liberacaoMs", "Liberação", 10.0, 1000.0, 120.0, "ms"),
            p("joelhoDb", "Joelho", 0.0, 12.0, 6.0, "dB"),
            p("ganhoDb", "Ganho de compensação", 0.0, 24.0, 0.0, "dB"),
        )),
        DefinicaoDoEfeito("porta", "Gate / expansor", "Dinâmica", listOf(
            p("limiarDb", "Limiar", -80.0, 0.0, -50.0, "dB"),
            p("faixaDb", "Redução quando fechado", -80.0, 0.0, -60.0, "dB"),
            p("ataqueMs", "Abertura", 0.1, 50.0, 1.0, "ms"),
            p("liberacaoMs", "Fechamento", 10.0, 1000.0, 100.0, "ms"),
        )),
        DefinicaoDoEfeito("limitador", "Limitador", "Dinâmica", listOf(p("tetoDb", "Teto", -12.0, 0.0, -1.0, "dB"), p("liberacaoMs", "Liberação", 1.0, 500.0, 50.0, "ms"))),
        DefinicaoDoEfeito("desesser", "De-esser", "Dinâmica", listOf(p("frequencia", "Frequência", 3000.0, 10000.0, 6500.0, "Hz"), p("limiarDb", "Limiar", -60.0, 0.0, -30.0, "dB"), p("reducaoMaxDb", "Redução máxima", 0.0, 24.0, 10.0, "dB"))),
        DefinicaoDoEfeito("saturacao", "Saturação", "Cor", listOf(p("driveDb", "Drive", 0.0, 24.0, 6.0, "dB"), p("mistura", "Mistura", 0.0, 1.0, 1.0, ""), p("saidaDb", "Saída", -24.0, 6.0, 0.0, "dB"))),
        DefinicaoDoEfeito("atraso", "Delay", "Espaço", listOf(p("tempoMs", "Tempo", 1.0, 2000.0, 350.0, "ms"), p("retorno", "Realimentação", 0.0, 0.95, 0.35, ""), p("mistura", "Mistura", 0.0, 1.0, 0.25, ""))),
        DefinicaoDoEfeito("reverberacao", "Reverb", "Espaço", listOf(p("tamanho", "Tamanho da sala", 0.0, 1.0, 0.6, ""), p("amortecimento", "Amortecimento", 0.0, 1.0, 0.4, ""), p("largura", "Largura", 0.0, 1.0, 1.0, ""), p("mistura", "Mistura", 0.0, 1.0, 0.25, ""))),
        DefinicaoDoEfeito("coro", "Chorus", "Modulação", listOf(p("taxaHz", "Velocidade", 0.1, 5.0, 0.8, "Hz"), p("profundidadeMs", "Profundidade", 0.0, 10.0, 3.0, "ms"), p("mistura", "Mistura", 0.0, 1.0, 0.35, ""))),
        DefinicaoDoEfeito("tremolo", "Tremolo", "Modulação", listOf(p("taxaHz", "Velocidade", 0.5, 15.0, 5.0, "Hz"), p("profundidade", "Profundidade", 0.0, 1.0, 0.5, ""))),
        DefinicaoDoEfeito("largura", "Largura estéreo", "Espaço", listOf(p("largura", "Largura", 0.0, 2.0, 1.0, "×"))),
        DefinicaoDoEfeito("ganho", "Ganho", "Utilidades", listOf(p("ganhoDb", "Ganho", -24.0, 24.0, 0.0, "dB"))),
    )

    fun definicao(tipo: String) = CATALOGO.firstOrNull { it.tipo == tipo }

    fun novoEfeito(tipo: String, id: String): EfeitoMusical {
        val d = definicao(tipo) ?: error("Efeito desconhecido: $tipo")
        return EfeitoMusical(id, tipo, true, d.parametros.associate { it.id to it.padrao })
    }

    fun parametro(e: EfeitoMusical, id: String): Double = e.numero(id) ?: definicao(e.tipo)!!.parametros.first { it.id == id }.padrao

    fun validar(e: EfeitoMusical): String? {
        val d = definicao(e.tipo) ?: return "efeito desconhecido (${e.tipo})."
        if (e.ligado == null) return "${d.rotulo}: falta dizer se está ligado."
        for (item in d.parametros) {
            if (!e.valores.containsKey(item.id)) continue
            val valor = e.valores[item.id]
            val numero = (valor as? Number)?.toDouble()
            if (numero == null || !numero.isFinite() || numero < item.min || numero > item.max)
                return "${d.rotulo}: ${item.rotulo.lowercase()} vai de ${Musica.js(item.min)} a ${Musica.js(item.max)}${if (item.unidade.isNotEmpty()) " ${item.unidade}" else ""}."
        }
        return null
    }

    fun cauda(e: EfeitoMusical): Double {
        if (!e.estaLigado) return 0.0
        if (e.tipo == "reverberacao") return 1 + 5 * parametro(e, "tamanho")
        if (e.tipo == "atraso") {
            val retorno = parametro(e, "retorno")
            val tempo = parametro(e, "tempoMs") / 1000
            return if (retorno <= 0) tempo else min(10.0, tempo * (ln(0.001) / ln(retorno)) + tempo)
        }
        return if (e.tipo == "coro") 0.05 else 0.0
    }

    internal fun anti(v: Double) = if (abs(v) < 1e-25) 0.0 else v
    internal fun coeficiente(ms: Double, taxa: Int) = if (ms <= 0) 0.0 else exp(-1 / (taxa * ms / 1000))
    internal fun db(v: Double) = 10.0.pow(v / 20)

    fun biquad(tipo: String, frequencia: Double, taxa: Int, q: Double = 0.7071067811865476, ganhoDb: Double = 0.0): DoubleArray {
        val f = frequencia.coerceIn(10.0, taxa * 0.45)
        val w0 = DOIS_PI * f / taxa
        val cosw = cos(w0)
        val sinw = sin(w0)
        val a = 10.0.pow(ganhoDb / 40)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        if (tipo == "passa-baixa" || tipo == "passa-alta") {
            val alpha = sinw / (2 * q)
            val s = if (tipo == "passa-baixa") 1 - cosw else 1 + cosw
            b0 = s / 2; b1 = if (tipo == "passa-baixa") s else -s; b2 = s / 2
            a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha
        } else if (tipo == "pico") {
            val alpha = sinw / (2 * q)
            b0 = 1 + alpha * a; b1 = -2 * cosw; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * cosw; a2 = 1 - alpha / a
        } else {
            val alpha = sinw / 2 * sqrt(2.0)
            val raiz = 2 * sqrt(a) * alpha
            if (tipo == "prateleira-grave") {
                b0 = a * (a + 1 - (a - 1) * cosw + raiz)
                b1 = 2 * a * (a - 1 - (a + 1) * cosw)
                b2 = a * (a + 1 - (a - 1) * cosw - raiz)
                a0 = a + 1 + (a - 1) * cosw + raiz
                a1 = -2 * (a - 1 + (a + 1) * cosw)
                a2 = a + 1 + (a - 1) * cosw - raiz
            } else {
                b0 = a * (a + 1 + (a - 1) * cosw + raiz)
                b1 = -2 * a * (a - 1 + (a + 1) * cosw)
                b2 = a * (a + 1 + (a - 1) * cosw - raiz)
                a0 = a + 1 - (a - 1) * cosw + raiz
                a1 = 2 * (a - 1 - (a + 1) * cosw)
                a2 = a + 1 - (a - 1) * cosw - raiz
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    fun criar(e: EfeitoMusical, taxa: Int): ProcessadorMusical = when (e.tipo) {
        "passa-alta", "passa-baixa" -> Filtro(e, taxa)
        "equalizador" -> Equalizador(e, taxa)
        "compressor" -> Compressor(e, taxa)
        "porta" -> Porta(e, taxa)
        "limitador" -> Limitador(e, taxa)
        "desesser" -> Desesser(e, taxa)
        "saturacao" -> Saturacao(e)
        "atraso" -> Atraso(e, taxa)
        "reverberacao" -> Reverberacao(e, taxa)
        "coro" -> Coro(e, taxa)
        "tremolo" -> Tremolo(e, taxa)
        "largura" -> Largura(e)
        "ganho" -> Ganho(e)
        else -> error("Efeito desconhecido: ${e.tipo}")
    }
}

class BiquadMusical(c: DoubleArray) {
    private val e = DoubleArray(8)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0

    init { definir(c) }

    fun definir(c: DoubleArray) { b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4] }

    fun amostra(x: Double, canal: Int): Double {
        val k = canal * 4
        val y = DspDaMusica.anti(b0 * x + b1 * e[k] + b2 * e[k + 1] - a1 * e[k + 2] - a2 * e[k + 3])
        e[k + 1] = e[k]; e[k] = x; e[k + 3] = e[k + 2]; e[k + 2] = y
        return y
    }

    fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            esq[i] = amostra(esq[i].toDouble(), 0).toFloat()
            dir[i] = amostra(dir[i].toDouble(), 1).toFloat()
        }
    }
}

private class Filtro(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var biquad: BiquadMusical? = null
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        val c = DspDaMusica.biquad(efeito.tipo, DspDaMusica.parametro(efeito, "frequencia"), taxa)
        biquad?.definir(c) ?: run { biquad = BiquadMusical(c) }
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) = biquad!!.processar(esq, dir, n)
}

private class Equalizador(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private val bandas = arrayOfNulls<BiquadMusical>(4)
    private val ativas = BooleanArray(4)
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        val p = { id: String -> DspDaMusica.parametro(efeito, id) }
        val c = listOf(
            DspDaMusica.biquad("prateleira-grave", 100.0, taxa, 0.7071067811865476, p("gravesDb")),
            DspDaMusica.biquad("pico", p("mediosFrequencia"), taxa, p("mediosQ"), p("mediosDb")),
            DspDaMusica.biquad("pico", p("presencaFrequencia"), taxa, 1.0, p("presencaDb")),
            DspDaMusica.biquad("prateleira-aguda", 8000.0, taxa, 0.7071067811865476, p("agudosDb")),
        )
        val ganhos = listOf(p("gravesDb"), p("mediosDb"), p("presencaDb"), p("agudosDb"))
        for (i in 0 until 4) {
            ativas[i] = ganhos[i] != 0.0
            bandas[i]?.definir(c[i]) ?: run { bandas[i] = BiquadMusical(c[i]) }
        }
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (b in 0 until 4) if (ativas[b]) bandas[b]!!.processar(esq, dir, n)
    }
}

private class Compressor(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var envelope = 0.0; private var limiar = 0.0; private var razao = 1.0; private var joelho = 0.0
    private var compensacao = 0.0; private var ataque = 0.0; private var liberacao = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        val p = { id: String -> DspDaMusica.parametro(efeito, id) }
        limiar = p("limiarDb"); razao = p("razao"); joelho = p("joelhoDb"); compensacao = p("ganhoDb")
        ataque = DspDaMusica.coeficiente(p("ataqueMs"), taxa); liberacao = DspDaMusica.coeficiente(p("liberacaoMs"), taxa)
    }
    private fun reducao(nivelDb: Double): Double {
        val acima = nivelDb - limiar
        val inclinacao = 1 / razao - 1
        val w = joelho
        if (w > 0 && 2 * abs(acima) <= w) return inclinacao * (acima + w / 2) * (acima + w / 2) / (2 * w)
        return if (acima > 0) inclinacao * acima else 0.0
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val nivel = max(abs(esq[i].toDouble()), abs(dir[i].toDouble()))
            val alvo = reducao(20 * log10(nivel + 1e-12))
            val c = if (alvo < envelope) ataque else liberacao
            envelope = c * envelope + (1 - c) * alvo
            val g = 10.0.pow((envelope + compensacao) / 20)
            esq[i] = (esq[i] * g).toFloat()
            dir[i] = (dir[i] * g).toFloat()
        }
    }
}

private class Porta(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var detector = 0.0; private var ganho = 1.0; private val queda = DspDaMusica.coeficiente(10.0, taxa)
    private var limiar = 0.0; private var fechado = 0.0; private var ataque = 0.0; private var liberacao = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        val p = { id: String -> DspDaMusica.parametro(efeito, id) }
        limiar = p("limiarDb"); fechado = DspDaMusica.db(p("faixaDb"))
        ataque = DspDaMusica.coeficiente(p("ataqueMs"), taxa); liberacao = DspDaMusica.coeficiente(p("liberacaoMs"), taxa)
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val nivel = max(abs(esq[i].toDouble()), abs(dir[i].toDouble()))
            detector = max(nivel, detector * queda)
            val alvo = if (20 * log10(detector + 1e-12) > limiar) 1.0 else fechado
            val c = if (alvo > ganho) ataque else liberacao
            ganho = DspDaMusica.anti(c * ganho + (1 - c) * alvo)
            esq[i] = (esq[i] * ganho).toFloat()
            dir[i] = (dir[i] * ganho).toFloat()
        }
    }
}

private class Limitador(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var ganho = 1.0; private var teto = 1.0; private var liberacao = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        teto = DspDaMusica.db(DspDaMusica.parametro(efeito, "tetoDb"))
        liberacao = DspDaMusica.coeficiente(DspDaMusica.parametro(efeito, "liberacaoMs"), taxa)
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val pico = max(abs(esq[i].toDouble()), abs(dir[i].toDouble()))
            val alvo = if (pico > teto) teto / pico else 1.0
            ganho = if (alvo < ganho) alvo else liberacao * ganho + (1 - liberacao) * alvo
            esq[i] = (esq[i] * ganho).toFloat()
            dir[i] = (dir[i] * ganho).toFloat()
        }
    }
}

private class Desesser(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var envelope = 0.0
    private val ataque = DspDaMusica.coeficiente(1.0, taxa)
    private val liberacao = DspDaMusica.coeficiente(60.0, taxa)
    private var filtro: BiquadMusical? = null
    private var limiar = 0.0; private var maximo = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        val c = DspDaMusica.biquad("passa-alta", DspDaMusica.parametro(efeito, "frequencia"), taxa)
        filtro?.definir(c) ?: run { filtro = BiquadMusical(c) }
        limiar = DspDaMusica.parametro(efeito, "limiarDb"); maximo = DspDaMusica.parametro(efeito, "reducaoMaxDb")
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        val f = filtro!!
        for (i in 0 until n) {
            val he = f.amostra(esq[i].toDouble(), 0)
            val hd = f.amostra(dir[i].toDouble(), 1)
            val nivel = max(abs(he), abs(hd))
            val c = if (nivel > envelope) ataque else liberacao
            envelope = DspDaMusica.anti(c * envelope + (1 - c) * nivel)
            val reducao = min(maximo, max(0.0, 20 * log10(envelope + 1e-12) - limiar))
            val g = 10.0.pow(-reducao / 20)
            esq[i] = (esq[i] - he + he * g).toFloat()
            dir[i] = (dir[i] - hd + hd * g).toFloat()
        }
    }
}

private class Saturacao(e: EfeitoMusical) : ProcessadorMusical {
    private var drive = 1.0; private var normal = 1.0; private var mistura = 1.0; private var saida = 1.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        drive = DspDaMusica.db(DspDaMusica.parametro(efeito, "driveDb")); normal = tanh(drive)
        mistura = DspDaMusica.parametro(efeito, "mistura"); saida = DspDaMusica.db(DspDaMusica.parametro(efeito, "saidaDb"))
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val l = esq[i].toDouble(); val r = dir[i].toDouble()
            esq[i] = ((l * (1 - mistura) + tanh(drive * l) / normal * mistura) * saida).toFloat()
            dir[i] = ((r * (1 - mistura) + tanh(drive * r) / normal * mistura) * saida).toFloat()
        }
    }
}

private class Ganho(e: EfeitoMusical) : ProcessadorMusical {
    private var g = 1.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) { g = DspDaMusica.db(DspDaMusica.parametro(efeito, "ganhoDb")) }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) { esq[i] = (esq[i] * g).toFloat(); dir[i] = (dir[i] * g).toFloat() }
    }
}

private class Largura(e: EfeitoMusical) : ProcessadorMusical {
    private var largura = 1.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) { largura = DspDaMusica.parametro(efeito, "largura") }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val meio = (esq[i].toDouble() + dir[i]) * 0.5
            val lado = (esq[i].toDouble() - dir[i]) * 0.5 * largura
            esq[i] = (meio + lado).toFloat(); dir[i] = (meio - lado).toFloat()
        }
    }
}

private class Tremolo(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private var fase = 0.0; private var passo = 0.0; private var profundidade = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        passo = Math.PI * 2 * DspDaMusica.parametro(efeito, "taxaHz") / taxa
        profundidade = DspDaMusica.parametro(efeito, "profundidade")
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val g = 1 - profundidade * 0.5 * (1 + sin(fase))
            esq[i] = (esq[i] * g).toFloat(); dir[i] = (dir[i] * g).toFloat()
            fase += passo
            if (fase >= Math.PI * 2) fase -= Math.PI * 2
        }
    }
}

private class Atraso(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private val tamanho = ceil(taxa * 2.0).toInt() + 1
    private val be = FloatArray(tamanho); private val bd = FloatArray(tamanho)
    private var escrita = 0; private var atraso = 1; private var retorno = 0.0; private var mistura = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        atraso = Musica.arredondar(DspDaMusica.parametro(efeito, "tempoMs") * taxa / 1000).coerceIn(1L, (tamanho - 1).toLong()).toInt()
        retorno = DspDaMusica.parametro(efeito, "retorno"); mistura = DspDaMusica.parametro(efeito, "mistura")
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            var leitura = escrita - atraso
            if (leitura < 0) leitura += tamanho
            val de = be[leitura].toDouble(); val dd = bd[leitura].toDouble()
            be[escrita] = DspDaMusica.anti(esq[i] + de * retorno).toFloat()
            bd[escrita] = DspDaMusica.anti(dir[i] + dd * retorno).toFloat()
            esq[i] = (esq[i] * (1 - mistura) + de * mistura).toFloat()
            dir[i] = (dir[i] * (1 - mistura) + dd * mistura).toFloat()
            escrita++
            if (escrita == tamanho) escrita = 0
        }
    }
}

private class Coro(e: EfeitoMusical, private val taxa: Int) : ProcessadorMusical {
    private val tamanho = ceil(taxa * 0.05).toInt() + 2
    private val be = FloatArray(tamanho); private val bd = FloatArray(tamanho)
    private val base = taxa * 0.015
    private var escrita = 0; private var fase = 0.0; private var passo = 0.0; private var profundidade = 0.0; private var mistura = 0.0
    init { atualizar(e) }
    override fun atualizar(efeito: EfeitoMusical) {
        passo = Math.PI * 2 * DspDaMusica.parametro(efeito, "taxaHz") / taxa
        profundidade = DspDaMusica.parametro(efeito, "profundidadeMs") * taxa / 1000
        mistura = DspDaMusica.parametro(efeito, "mistura")
    }
    private fun ler(b: FloatArray, atraso: Double): Double {
        var posicao = escrita - atraso
        while (posicao < 0) posicao += tamanho
        val i0 = floor(posicao).toInt()
        val fracao = posicao - i0
        val i1 = if (i0 + 1 == tamanho) 0 else i0 + 1
        return b[i0] + (b[i1] - b[i0].toDouble()) * fracao
    }
    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            be[escrita] = esq[i]; bd[escrita] = dir[i]
            val ae = base + profundidade * sin(fase)
            val ad = base + profundidade * sin(fase + Math.PI / 2)
            val we = ler(be, ae); val wd = ler(bd, ad)
            esq[i] = (esq[i] * (1 - mistura) + we * mistura).toFloat()
            dir[i] = (dir[i] * (1 - mistura) + wd * mistura).toFloat()
            escrita++
            if (escrita == tamanho) escrita = 0
            fase += passo
            if (fase >= Math.PI * 2) fase -= Math.PI * 2
        }
    }
}

/** Freeverb (Jezar, domínio público). */
private class Reverberacao(e: EfeitoMusical, taxa: Int) : ProcessadorMusical {
    private class Linha(tamanho: Int) { val b = FloatArray(max(1, tamanho)); var i = 0; var f = 0.0 }

    private val pentes: Array<Array<Linha>>
    private val passaTudo: Array<Array<Linha>>
    private var retorno = 0.0; private var amortecimento = 0.0; private var molhado1 = 0.0; private var molhado2 = 0.0; private var seco = 0.0

    init {
        val escala = taxa / 44100.0
        fun criar(tamanhos: IntArray, extra: Int) = Array(tamanhos.size) { Linha(Musica.arredondar((tamanhos[it] + extra) * escala).toInt()) }
        val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val allpass = intArrayOf(556, 441, 341, 225)
        pentes = arrayOf(criar(combs, 0), criar(combs, 23))
        passaTudo = arrayOf(criar(allpass, 0), criar(allpass, 23))
        atualizar(e)
    }

    override fun atualizar(efeito: EfeitoMusical) {
        retorno = DspDaMusica.parametro(efeito, "tamanho") * 0.28 + 0.7
        amortecimento = DspDaMusica.parametro(efeito, "amortecimento") * 0.4
        val mistura = DspDaMusica.parametro(efeito, "mistura")
        val largura = DspDaMusica.parametro(efeito, "largura")
        val molhado = mistura * 3
        molhado1 = molhado * (largura / 2 + 0.5)
        molhado2 = molhado * ((1 - largura) / 2)
        seco = 1 - mistura
    }

    private fun canal(entrada: Double, c: Int): Double {
        var saida = 0.0
        val d1 = amortecimento
        val d2 = 1 - d1
        for (pente in pentes[c]) {
            val valor = pente.b[pente.i].toDouble()
            pente.f = DspDaMusica.anti(valor * d2 + pente.f * d1)
            pente.b[pente.i] = (entrada + pente.f * retorno).toFloat()
            if (++pente.i == pente.b.size) pente.i = 0
            saida += valor
        }
        for (filtro in passaTudo[c]) {
            val valor = filtro.b[filtro.i].toDouble()
            filtro.b[filtro.i] = DspDaMusica.anti(saida + valor * 0.5).toFloat()
            saida = valor - saida
            if (++filtro.i == filtro.b.size) filtro.i = 0
        }
        return saida
    }

    override fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (i in 0 until n) {
            val entrada = (esq[i].toDouble() + dir[i]) * 0.015
            val oe = canal(entrada, 0)
            val od = canal(entrada, 1)
            val le = esq[i].toDouble(); val ld = dir[i].toDouble()
            esq[i] = (oe * molhado1 + od * molhado2 + le * seco).toFloat()
            dir[i] = (od * molhado1 + oe * molhado2 + ld * seco).toFloat()
        }
    }
}

/** Cadeia que reaproveita o processador de cada efeito pelo id (o reverb não corta a cauda). */
class CadeiaMusical(private val taxa: Int) {
    private class Item(val id: String, val tipo: String, @Volatile var ligado: Boolean, val processador: ProcessadorMusical)

    @Volatile private var itens: Array<Item> = emptyArray()

    fun sincronizar(efeitos: List<EfeitoMusical>) {
        val anteriores = itens.associateBy { it.id }
        itens = efeitos.map { e ->
            val anterior = anteriores[e.id]
            if (anterior != null && anterior.tipo == e.tipo) {
                anterior.processador.atualizar(e)
                anterior.ligado = e.estaLigado
                anterior
            } else Item(e.id, e.tipo, e.estaLigado, DspDaMusica.criar(e, taxa))
        }.toTypedArray()
    }

    fun processar(esq: FloatArray, dir: FloatArray, n: Int) {
        for (item in itens) if (item.ligado) item.processador.processar(esq, dir, n)
    }
}
