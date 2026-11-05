package br.com.smellsliketech.converter.estudio

import java.math.BigDecimal
import kotlin.math.*

/** O efeito num instante: frações do quadro, escala, radianos, alfa, estilhaços, partículas e matiz. */
data class EstadoDoEfeito(
    val dx: Double = 0.0, val dy: Double = 0.0, val sx: Double = 1.0, val sy: Double = 1.0, val giro: Double = 0.0,
    val alfa: Double = 1.0, val pedacos: Double = 0.0, val particulas: Double = 1.0, val matiz: Double = 0.0,
)

/**
 * Efeitos de entrada, saída e contínuos de clipes, camadas e textos. Espelho de
 * estudio-efeitos.mjs e StudioEffects.cs: a receita sai idêntica, conferida pelos casos do
 * estúdio. Entrada e saída rodam só na sua janela de tempo (split + trim + concat), com filtros LGPL.
 */
object Efeitos {
    val ENTRADAS = listOf(
        "nenhum" to "Nenhum", "fade" to "Surgir suave (fade in)", "cima" to "Surgir de cima", "baixo" to "Surgir de baixo",
        "esquerda" to "Surgir da esquerda", "direita" to "Surgir da direita", "zoom" to "Crescer do nada",
        "impacto" to "Impacto (vem de perto e recua)", "giro" to "Entrar girando", "quicar" to "Cair quicando",
        "estilhacos" to "Montar de estilhaços", "desintegrar" to "Formar de partículas", "maquina" to "Máquina de escrever",
    )
    val SAIDAS = listOf(
        "nenhum" to "Nenhum", "fade" to "Esmaecer (fade out)", "cima" to "Sair por cima", "baixo" to "Sair por baixo",
        "esquerda" to "Sair pela esquerda", "direita" to "Sair pela direita", "zoom" to "Encolher até sumir",
        "impacto" to "Crescer e sumir", "giro" to "Sair girando", "quicar" to "Pular para fora",
        "estilhacos" to "Estilhaçar", "desintegrar" to "Desintegrar",
    )
    val CONTINUOS = listOf(
        "nenhum" to "Nenhum", "girar" to "Girar sem parar", "balancar" to "Balançar", "pulsar" to "Pulsar",
        "coracao" to "Bater como coração", "tremer" to "Tremer", "flutuar" to "Flutuar", "gelatina" to "Gelatina", "arcoiris" to "Arco-íris",
    )
    const val DURACAO_PADRAO = .6
    private const val COLUNAS = 6
    private const val LINHAS = 6
    private const val PASSOS_MAXIMOS = 120

    private fun n(v: Double): String {
        val r = floor(v * 1e6 + .5) / 1e6
        return if (r == 0.0) "0" else BigDecimal.valueOf(r).stripTrailingZeros().toPlainString()
    }
    private fun par(v: Double) = max(2, floor(v / 2 + .5).toInt() * 2)
    private fun ativo(id: String) = id != "nenhum"

    fun tem(t: Transformacao?) = t != null && (ativo(t.efeitoEntrada) || ativo(t.efeitoSaida) || ativo(t.efeitoContinuo))

    fun problema(t: Transformacao): String? {
        if (ENTRADAS.none { it.first == t.efeitoEntrada }) return "Efeito de entrada desconhecido."
        if (SAIDAS.none { it.first == t.efeitoSaida }) return "Efeito de saída desconhecido."
        if (CONTINUOS.none { it.first == t.efeitoContinuo }) return "Efeito contínuo desconhecido."
        for (d in listOf(t.duracaoEntrada, t.duracaoSaida)) if (!d.isFinite() || d < .1 || d > 10) return "A duração do efeito vai de 0,1 a 10 segundos."
        return null
    }

    fun janelas(t: Transformacao, duracao: Double): Pair<Double, Double> {
        val entrada = if (ativo(t.efeitoEntrada) && t.efeitoEntrada != "maquina") min(t.duracaoEntrada, duracao) else 0.0
        val saida = if (ativo(t.efeitoSaida)) min(t.duracaoSaida, max(0.0, duracao - entrada)) else 0.0
        return entrada to saida
    }

    /** Sorteio fixo de cada estilhaço (0 a 1): o mesmo resultado em todos os clientes. */
    fun sorteio(k: Int, m: Int) = ((k * 73 + m * 37 + 11) % 101) / 100.0

    private fun janela(e: EstadoDoEfeito, tipo: String, p: Double, entrada: Boolean): EstadoDoEfeito {
        val q = if (entrada) 1 - (1 - p).pow(3) else 1 - p.pow(3)
        val r = if (entrada) p else 1 - p
        val esmaecer = if (entrada) p else 1 - p
        val encolher = max(.02, q)
        return when (tipo) {
            "fade" -> e.copy(alfa = e.alfa * esmaecer)
            "cima" -> e.copy(dy = e.dy - (1 - q))
            "baixo" -> e.copy(dy = e.dy + (1 - q))
            "esquerda" -> e.copy(dx = e.dx - (1 - q))
            "direita" -> e.copy(dx = e.dx + (1 - q))
            "zoom" -> e.copy(sx = e.sx * encolher, sy = e.sy * encolher)
            "impacto" -> { val s = 1 + 2 * (1 - q); e.copy(sx = e.sx * s, sy = e.sy * s, alfa = e.alfa * esmaecer) }
            "giro" -> e.copy(giro = e.giro + 6.2832 * (1 - q), sx = e.sx * encolher, sy = e.sy * encolher)
            "quicar" -> e.copy(dy = e.dy - abs(cos(7.854 * r)) * (1 - r).pow(2))
            "estilhacos" -> e.copy(pedacos = 1 - q, alfa = e.alfa * esmaecer)
            "desintegrar" -> e.copy(particulas = q)
            else -> e
        }
    }

    fun estado(t: Transformacao, tempo: Double, duracao: Double): EstadoDoEfeito {
        if (!tem(t)) return EstadoDoEfeito()
        val e = when (t.efeitoContinuo) {
            "girar" -> EstadoDoEfeito(giro = PI * tempo)
            "balancar" -> EstadoDoEfeito(giro = .26 * sin(5.0265 * tempo))
            "pulsar" -> (1 + .1 * sin(7.5398 * tempo)).let { EstadoDoEfeito(sx = it, sy = it) }
            "coracao" -> (1 + .18 * max(0.0, sin(6.2832 * tempo)).pow(4)).let { EstadoDoEfeito(sx = it, sy = it) }
            "tremer" -> EstadoDoEfeito(dx = .012 * sin(56.5487 * tempo), dy = .012 * sin(69.115 * tempo + 1))
            "flutuar" -> EstadoDoEfeito(dy = .025 * sin(3.7699 * tempo))
            "gelatina" -> (.14 * sin(9.4248 * tempo)).let { EstadoDoEfeito(sx = 1 + it, sy = 1 - it) }
            "arcoiris" -> EstadoDoEfeito(matiz = 120 * tempo)
            else -> EstadoDoEfeito()
        }
        val (entrada, saida) = janelas(t, duracao)
        if (entrada > 0 && tempo < entrada) return janela(e, t.efeitoEntrada, max(0.0, tempo / entrada), true)
        if (saida > 0 && tempo >= duracao - saida) return janela(e, t.efeitoSaida, min(1.0, (tempo - duracao + saida) / saida), false)
        return e
    }

    /**
     * O efeito congelado num instante, como transformação comum: é o que o quadro parado da
     * prévia monta, sem renderizar a linha do tempo. Estilhaços e partículas viram transparência.
     */
    fun congelar(t: Transformacao, tempo: Double, duracao: Double, largura: Int, altura: Int, pivoX: Double = t.x, pivoY: Double = t.y): Transformacao {
        val e = estado(t, tempo, duracao)
        val escala = (e.sx + e.sy) / 2
        val px = pivoX * largura; val py = pivoY * altura
        val vx = t.x * largura - px; val vy = t.y * altura - py
        val x = px + e.dx * largura + escala * (vx * cos(e.giro) - vy * sin(e.giro))
        val y = py + e.dy * altura + escala * (vx * sin(e.giro) + vy * cos(e.giro))
        val graus = (t.giro + e.giro * 180 / PI).let { it - 360 * Math.rint(it / 360) }
        return t.semEfeitos().copy(
            x = (x / largura).coerceIn(-2.0, 3.0), y = (y / altura).coerceIn(-2.0, 3.0),
            escala = (t.escala * escala).coerceIn(.05, 6.0), giro = graus,
            opacidade = (t.opacidade * e.alfa * e.particulas * (1 - e.pedacos)).coerceIn(0.0, 1.0),
        )
    }

    fun usaMaquina(texto: Texto) = texto.transformacao?.efeitoEntrada == "maquina"

    data class Passos(val letras: Int, val passos: Int, val duracao: Double, val taxa: Double)

    /** Máquina de escrever: quantas imagens o texto tem e a taxa delas. */
    fun passosDaMaquina(texto: Texto, fps: Int): Passos {
        val conteudo = texto.texto.trim()
        val letras = conteudo.codePointCount(0, conteudo.length)
        val duracao = max(.1, min(texto.transformacao?.duracaoEntrada ?: DURACAO_PADRAO, texto.fim - texto.inicio))
        val passos = max(1, minOf(letras, floor(duracao * fps + .5).toInt(), PASSOS_MAXIMOS))
        return Passos(letras, passos, duracao, passos / duracao)
    }

    fun letrasNoPasso(letras: Int, passos: Int, passo: Int) = ceil(passo.toDouble() * letras / passos).toInt()

    fun letrasVisiveis(texto: Texto, tempo: Double, fps: Int): Int {
        val p = passosDaMaquina(texto, fps)
        return letrasNoPasso(p.letras, p.passos, floor(tempo * p.taxa).toInt().plus(1).coerceIn(1, p.passos))
    }

    private fun movimento(dx: String = "0", dy: String = "0", sx: String? = null, sy: String? = null, giro: String? = null, w: Int, h: Int, fps: Int, px: Double, py: Double, r: String): String {
        val cadeia = mutableListOf<String>()
        if (giro != null) {
            val d = par(ceil(hypot(w.toDouble(), h.toDouble())))
            cadeia += "rotate=a='$giro':ow=$d:oh=$d:fillcolor=black@0"
        }
        if (sx != null) cadeia += "scale=w='max(2,trunc(iw*($sx)/2)*2)':h='max(2,trunc(ih*($sy)/2)*2)':eval=frame"
        if (cadeia.isEmpty()) cadeia += "setsar=1"
        var x = dx; var y = dy
        if (giro != null || sx != null) {
            val cx = n(w / 2.0 - px); val cy = n(h / 2.0 - py)
            val vx = if (giro != null) "($cx*cos($giro)-($cy)*sin($giro))" else cx
            val vy = if (giro != null) "($cx*sin($giro)+($cy)*cos($giro))" else cy
            x = "${n(px)}+($dx)+(${sx ?: "1"})*($vx)-w/2"
            y = "${n(py)}+($dy)+(${sy ?: "1"})*($vy)-h/2"
        }
        return "${cadeia.joinToString(",")}[${r}z];color=c=black@0:s=${w}x$h:r=$fps,format=rgba[${r}b];" +
            "[${r}b][${r}z]overlay=x='$x':y='$y':eval=frame:format=auto:shortest=1,format=rgba"
    }

    private fun filtroContinuo(tipo: String, w: Int, h: Int, fps: Int, px: Double, py: Double, r: String): String = when (tipo) {
        "girar" -> movimento(giro = "PI*t", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "balancar" -> movimento(giro = "0.26*sin(5.0265*t)", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "pulsar" -> movimento(sx = "(1+0.1*sin(7.5398*t))", sy = "(1+0.1*sin(7.5398*t))", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "coracao" -> movimento(sx = "(1+0.18*pow(max(0,sin(6.2832*t)),4))", sy = "(1+0.18*pow(max(0,sin(6.2832*t)),4))", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "tremer" -> movimento(dx = "${n(.012 * w)}*sin(56.5487*t)", dy = "${n(.012 * h)}*sin(69.115*t+1)", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "flutuar" -> movimento(dy = "${n(.025 * h)}*sin(3.7699*t)", w = w, h = h, fps = fps, px = px, py = py, r = r)
        "gelatina" -> movimento(sx = "(1+0.14*sin(9.4248*t))", sy = "(1-0.14*sin(9.4248*t))", w = w, h = h, fps = fps, px = px, py = py, r = r)
        else -> "format=gbrap,hue=h='120*t',format=rgba"
    }

    private fun estilhacos(q: String, w: Int, h: Int, fps: Int, px: Double, py: Double, r: String): String {
        val tw = par(ceil(w.toDouble() / COLUNAS)); val th = par(ceil(h.toDouble() / LINHAS)); val total = COLUNAS * LINHAS
        val lado = par(ceil(hypot(tw.toDouble(), th.toDouble()))); val alcance = 1.2 * max(w, h)
        val partes = mutableListOf("split=$total${(0 until total).joinToString("") { "[${r}p$it]" }}", "color=c=black@0:s=${w}x$h:r=$fps,format=rgba[${r}b0]")
        for (k in 0 until total) {
            val x0 = k % COLUNAS * tw; val y0 = k / COLUNAS * th
            val cw = min(tw, w - x0); val ch = min(th, h - y0)
            if (cw <= 0 || ch <= 0) { partes += "[${r}p$k]nullsink"; continue }
            val cx = x0 + cw / 2.0; val cy = y0 + ch / 2.0; val ax = cx - px; val ay = cy - py; val d = max(1.0, hypot(ax, ay))
            val distancia = alcance * (.5 + sorteio(k, 1))
            val vx = n(ax / d * distancia); val vy = n(ay / d * distancia); val volta = n((sorteio(k, 2) - .5) * 12.5664)
            val seguinte = if (k == total - 1) "" else "[${r}b${k + 1}]"
            partes += "[${r}p$k]crop=$cw:$ch:$x0:$y0,rotate=a='$volta*(1-$q)':ow=$lado:oh=$lado:fillcolor=black@0[${r}t$k]"
            partes += "[${r}b$k][${r}t$k]overlay=x='${n(cx)}+($vx)*(1-$q)-w/2':y='${n(cy)}+($vy)*(1-$q)-h/2':eval=frame:format=auto:shortest=1$seguinte"
        }
        return partes.joinToString(";")
    }

    private fun particulas(q: String, w: Int, h: Int, fps: Int, d: Double, r: String): String {
        val b = max(2, floor(min(w, h) / 90.0 + .5).toInt())
        val mw = ceil(w.toDouble() / b).toInt(); val mh = ceil(h.toDouble() / b).toInt()
        return "split[${r}a][${r}c];[${r}a]alphaextract[${r}l];" +
            "color=c=black:s=${mw}x$mh:r=$fps:d=${n(d)},format=gray,geq=lum='255*lt(mod(sin(X*12.9898+Y*78.233)*43758.5453,1)*0.7+X/W*0.3,$q)',scale=$w:$h:flags=neighbor,format=gray[${r}m];" +
            "[${r}l][${r}m]blend=all_mode=multiply:shortest=1[${r}n];[${r}c][${r}n]alphamerge"
    }

    private fun filtroDaJanela(tipo: String, entrada: Boolean, d: Double, w: Int, h: Int, fps: Int, px: Double, py: Double, r: String): String {
        val p = "clip(t/${n(d)},0,1)"
        val q = if (entrada) "(1-pow(1-$p,3))" else "(1-pow($p,3))"
        val volta = if (entrada) p else "(1-$p)"
        val esmaecer = "fade=t=${if (entrada) "in" else "out"}:st=0:d=${n(d)}:alpha=1"
        return when (tipo) {
            "fade" -> esmaecer
            "cima" -> movimento(dy = "-$h*(1-$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "baixo" -> movimento(dy = "$h*(1-$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "esquerda" -> movimento(dx = "-$w*(1-$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "direita" -> movimento(dx = "$w*(1-$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "zoom" -> movimento(sx = "max(0.02,$q)", sy = "max(0.02,$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "impacto" -> "${movimento(sx = "(1+2*(1-$q))", sy = "(1+2*(1-$q))", w = w, h = h, fps = fps, px = px, py = py, r = r)},$esmaecer"
            "giro" -> movimento(giro = "6.2832*(1-$q)", sx = "max(0.02,$q)", sy = "max(0.02,$q)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "quicar" -> movimento(dy = "-$h*abs(cos(7.854*$volta))*pow(1-$volta,2)", w = w, h = h, fps = fps, px = px, py = py, r = r)
            "estilhacos" -> "${estilhacos(q, w, h, fps, px, py, r)},format=rgba,$esmaecer"
            else -> {
                val g = "clip(T/${n(d)},0,1)"
                "${particulas(if (entrada) "(1-pow(1-$g,3))" else "(1-pow($g,3))", w, h, fps, d, r)},format=rgba"
            }
        }
    }

    /** A cadeia dos efeitos, para vir logo depois da transformação; começa com vírgula, ou é vazia. */
    fun filtro(valor: Transformacao?, w: Int, h: Int, fps: Int, duracao: Double, rotulo: String, pivoX: Double? = null, pivoY: Double? = null): String {
        val t = valor ?: return ""
        if (!tem(t)) return ""
        val px = (pivoX ?: t.x) * w; val py = (pivoY ?: t.y) * h
        var s = ""
        if (ativo(t.efeitoContinuo)) s += ",setpts=PTS-STARTPTS,${filtroContinuo(t.efeitoContinuo, w, h, fps, px, py, "${rotulo}c")}"
        val (entrada, saida) = janelas(t, duracao)
        if (entrada <= 0 && saida <= 0) return s
        val meio = duracao - entrada - saida > 1e-6
        val fim = duracao - saida
        val partes = mutableListOf<Triple<String, String, String>>()
        if (entrada > 0) partes += Triple("e", "end=${n(entrada)}", ",${filtroDaJanela(t.efeitoEntrada, true, entrada, w, h, fps, px, py, "${rotulo}e")}")
        if (meio) partes += Triple("m", "start=${n(entrada)}:end=${n(fim)}", "")
        if (saida > 0) partes += Triple("s", "start=${n(fim)}", ",${filtroDaJanela(t.efeitoSaida, false, saida, w, h, fps, px, py, "${rotulo}s")}")
        s += ",setpts=PTS-STARTPTS"
        if (partes.size == 1) return s + partes[0].third
        s += ",split=${partes.size}${partes.joinToString("") { "[${rotulo}w${it.first}]" }}"
        for ((r, trim, cadeia) in partes) s += ";[${rotulo}w$r]trim=$trim,setpts=PTS-STARTPTS$cadeia,setsar=1[${rotulo}x$r]"
        return "$s;${partes.joinToString("") { "[${rotulo}x${it.first}]" }}concat=n=${partes.size}:v=1:a=0"
    }
}
