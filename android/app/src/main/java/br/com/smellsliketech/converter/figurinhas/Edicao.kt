package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** O texto das camadas é desenhado no dobro do tamanho, para não borrar com zoom. */
const val RESOLUCAO_DO_TEXTO = 2f

/** Os filtros rápidos, como no sticker.ly: uma matriz de cor cada um. */
enum class Filtro(val rotulo: String) {
    NENHUM("Original"),
    VIVIDO("Vívido"),
    PRETO_E_BRANCO("P&B"),
    SEPIA("Sépia"),
    VINTAGE("Vintage"),
    FRIO("Frio"),
    QUENTE("Quente"),
    NEGATIVO("Negativo"),
}

/** As fontes do texto: as do sistema e quatro embutidas (licença OFL, em assets/fontes). */
enum class Fonte(val rotulo: String, val arquivo: String?) {
    PADRAO("Padrão", null),
    IMPACTO("Impacto", "fontes/Anton-Regular.ttf"),
    QUADRINHO("Quadrinho", "fontes/Bangers-Regular.ttf"),
    CURSIVA("Cursiva", "fontes/Pacifico-Regular.ttf"),
    MARCADOR("Marcador", "fontes/PermanentMarker-Regular.ttf"),
    SERIFADA("Serifada", null),
    MONO("Máquina", null),
}

/**
 * Onde o texto fica na figurinha. Nas camadas de texto é um atalho: posiciona o objeto (que
 * depois se move à vontade). `y` é o topo do texto antigo, desenhado direto na mídia principal.
 */
enum class PosicaoDoTexto(val rotulo: String, val y: Float) { CIMA("Em cima", 0.06f), MEIO("No meio", 0.42f), BAIXO("Embaixo", 0.78f) }

/** Como as letras se arrumam: em linha, uma embaixo da outra, ou em escada subindo/descendo. */
enum class DisposicaoDoTexto(val rotulo: String) {
    HORIZONTAL("Horizontal"),
    VERTICAL("Vertical"),
    SUBINDO("Escada subindo"),
    DESCENDO("Escada descendo"),
}

enum class FormaDoRecorte(val rotulo: String) { QUADRADO("Quadrado"), CIRCULO("Círculo"), LIVRE("Livre") }

/**
 * O pedaço da imagem (ou de cada quadro do vídeo) que fica, em frações da largura e da altura
 * da mídia original. Quadrado e círculo guardam a caixa (esquerda, topo, direita, base); o
 * livre guarda os pontos do traço (x0, y0, x1, y1, …), fechado no fim.
 */
data class Recorte(val forma: FormaDoRecorte, val pontos: List<Float>) {
    /** Esquerda, topo, direita e base, em frações (0..1). */
    fun caixa(): FloatArray {
        val xs = pontos.filterIndexed { i, _ -> i % 2 == 0 }.map { it.coerceIn(0f, 1f) }
        val ys = pontos.filterIndexed { i, _ -> i % 2 == 1 }.map { it.coerceIn(0f, 1f) }
        return floatArrayOf(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f, xs.maxOrNull() ?: 1f, ys.maxOrNull() ?: 1f)
    }

    /** Pequeno demais (ou um traço livre sem área) não recorta nada. */
    val valido: Boolean
        get() {
            val (l, t, r, b) = caixa().toList()
            return pontos.size % 2 == 0 && r - l > 0.02f && b - t > 0.02f &&
                (forma != FormaDoRecorte.LIVRE || pontos.size >= 6) && (forma == FormaDoRecorte.LIVRE || pontos.size == 4)
        }

    /** Largura e altura, em pixels, do pedaço recortado de uma mídia `largura` × `altura`. */
    fun medidas(largura: Int, altura: Int): Pair<Int, Int> {
        val (l, t, r, b) = caixa().toList()
        return maxOf(1, ((r - l) * largura).roundToInt()) to maxOf(1, ((b - t) * altura).roundToInt())
    }
}

/**
 * Tudo o que a pessoa escolheu no editor. É a mesma edição para a figurinha estática e para
 * cada quadro da animada: o [Renderizador] aplica igual.
 */
data class Edicao(
    /** 1 = a imagem inteira cabe no quadrado; mais que 1 aproxima. */
    val zoom: Float = 1f,
    /** Deslocamento do centro, em frações do lado (−1..1). */
    val deslocamentoX: Float = 0f,
    val deslocamentoY: Float = 0f,
    /** Giro livre da imagem em graus, no sentido do relógio. */
    val giro: Int = 0,
    val inclinacaoX: Float = 0f,
    val inclinacaoY: Float = 0f,
    val opacidade: Float = 1f,
    val mescla: Mescla = Mescla.NORMAL,
    /** Espelha como se vê na figurinha: da esquerda para a direita, e de cima para baixo. */
    val espelharHorizontal: Boolean = false,
    val espelharVertical: Boolean = false,
    /** Cor do fundo onde a imagem não cobre; transparente por padrão. */
    val corDeFundo: Int = Color.TRANSPARENT,
    val removerFundo: Boolean = false,
    val filtro: Filtro = Filtro.NENHUM,
    /** −1..1 cada um; 0 = sem ajuste. */
    val brilho: Float = 0f,
    val contraste: Float = 0f,
    val saturacao: Float = 0f,
    val texto: String = "",
    val fonte: Fonte = Fonte.IMPACTO,
    val corDoTexto: Int = Color.WHITE,
    val corDoContornoDoTexto: Int = Color.BLACK,
    val tamanhoDoTexto: Float = 64f,
    val posicaoDoTexto: PosicaoDoTexto = PosicaoDoTexto.BAIXO,
    val disposicaoDoTexto: DisposicaoDoTexto = DisposicaoDoTexto.HORIZONTAL,
    val contorno: Boolean = false,
    val corDoContorno: Int = Color.WHITE,
    val larguraDoContorno: Float = 12f,
    /** Só o pedaço escolhido da mídia vira figura; nulo = a mídia inteira. */
    val recorte: Recorte? = null,
)

enum class Mescla(val rotulo: String, val modo: PorterDuff.Mode) {
    NORMAL("Normal", PorterDuff.Mode.SRC_OVER),
    MULTIPLICAR("Multiplicar", PorterDuff.Mode.MULTIPLY),
    CLAREAR("Clarear", PorterDuff.Mode.SCREEN),
    SOBREPOR("Sobrepor", PorterDuff.Mode.OVERLAY),
}

/**
 * Desenha uma figurinha de 512×512 a partir de uma imagem (ou de um quadro de vídeo) e de uma
 * [Edicao]. A `mascara`, quando existe, diz o que é a figura (alfa 255) e o que é fundo (0):
 * vem da remoção automática de fundo ou do pincel.
 */
class Renderizador(private val contexto: Context) {
    // Os quadros da animada são desenhados em paralelo.
    private val fontes = ConcurrentHashMap<Fonte, Typeface>()

    fun desenhar(fonte: Bitmap, mascara: Bitmap?, edicao: Edicao): Bitmap {
        val lado = Regras.LADO
        val saida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val tela = Canvas(saida)
        if (Color.alpha(edicao.corDeFundo) > 0 && !(edicao.removerFundo && edicao.contorno)) tela.drawColor(edicao.corDeFundo)

        // 1. A figura: a imagem com o fundo recortado e o filtro, já no enquadramento escolhido.
        val margem = if (edicao.contorno) edicao.larguraDoContorno + 4f else 0f
        val camada = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val desenhoDaCamada = Canvas(camada)
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filtroDeCor(edicao) }
        // Com recorte, o pedaço recortado faz o papel da mídia inteira: é ele que cabe, gira e
        // encaixa nas guias. O resto da mídia fica de fora do traçado.
        val pedaco = edicao.recorte?.takeIf { it.valido }
        val (largura, altura) = pedaco?.medidas(fonte.width, fonte.height) ?: (fonte.width to fonte.height)
        val transformacao = Perspectiva.matriz(largura, altura, lado, lado, edicao.copy(zoom = edicao.zoom * (lado - 2 * margem) / lado))
        desenhoDaCamada.save()
        if (transformacao != null) {
            desenhoDaCamada.concat(transformacao)
            if (pedaco != null) {
                val caixa = pedaco.caixa()
                desenhoDaCamada.translate(-caixa[0] * fonte.width, -caixa[1] * fonte.height)
                desenhoDaCamada.clipPath(caminhoDoRecorte(pedaco, fonte.width, fonte.height))
            }
            desenhoDaCamada.drawBitmap(fonte, 0f, 0f, pintura)
            if (mascara != null && edicao.removerFundo) {
                val recorte = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                desenhoDaCamada.drawBitmap(mascara, null, RectF(0f, 0f, fonte.width.toFloat(), fonte.height.toFloat()), recorte)
            }
        }
        desenhoDaCamada.restore()

        // 2. O contorno: a silhueta da figura, em branco, repetida em volta dela.
        if (edicao.contorno) {
            val silhueta = camada.extractAlpha()
            val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(edicao.corDoContorno, PorterDuff.Mode.SRC_IN) }
            val passos = 32
            val raio = edicao.larguraDoContorno
            for (i in 0 until passos) {
                val angulo = 2 * Math.PI * i / passos
                tela.drawBitmap(silhueta, (cos(angulo) * raio).toFloat(), (sin(angulo) * raio).toFloat(), tinta)
            }
            tela.drawBitmap(silhueta, 0f, 0f, tinta)
            silhueta.recycle()
        }
        tela.drawBitmap(camada, 0f, 0f, null)
        camada.recycle()

        // 3. O texto, com contorno para ler em cima de qualquer imagem.
        if (edicao.texto.isNotBlank()) desenharTexto(tela, edicao, lado)
        return saida
    }

    /**
     * O ponto da imagem original (frações 0..1) que está sob o ponto (x, y) da figurinha
     * (também frações); e quanto da imagem cabe num raio de `raio` da figurinha. O pincel usa
     * isso para apagar exatamente onde o dedo passou.
     */
    fun pontoNaImagem(x: Float, y: Float, raio: Float, largura: Int, altura: Int, edicao: Edicao): Triple<Float, Float, Float> {
        val margem = if (edicao.contorno) edicao.larguraDoContorno + 4f else 0f
        val recorte = edicao.recorte?.takeIf { it.valido }
        val (w, h) = recorte?.medidas(largura, altura) ?: (largura to altura)
        val m = Perspectiva.matriz(w, h, Regras.LADO, Regras.LADO, edicao.copy(zoom = edicao.zoom * (Regras.LADO - 2 * margem) / Regras.LADO))
        val inversa = android.graphics.Matrix()
        if (m == null || !m.invert(inversa)) return Triple(-10f, -10f, 0f)
        val pontos = floatArrayOf(x * Regras.LADO, y * Regras.LADO, (x + raio) * Regras.LADO, y * Regras.LADO)
        inversa.mapPoints(pontos)
        // O recorte desloca a origem: a matriz leva ao pedaço, e o pedaço começa na caixa.
        val caixa = recorte?.caixa()
        val px = pontos[0] + (caixa?.get(0) ?: 0f) * largura
        val py = pontos[1] + (caixa?.get(1) ?: 0f) * altura
        val r = kotlin.math.hypot(pontos[2] - pontos[0], pontos[3] - pontos[1]) / maxOf(largura, altura)
        return Triple(px / largura, py / altura, r.coerceAtMost(1f))
    }

    /** O traçado do recorte em pixels da mídia original. */
    fun caminhoDoRecorte(recorte: Recorte, largura: Int, altura: Int): Path {
        val (l, t, r, b) = recorte.caixa().toList()
        return Path().apply {
            when (recorte.forma) {
                FormaDoRecorte.QUADRADO -> addRect(l * largura, t * altura, r * largura, b * altura, Path.Direction.CW)
                FormaDoRecorte.CIRCULO -> addOval(RectF(l * largura, t * altura, r * largura, b * altura), Path.Direction.CW)
                FormaDoRecorte.LIVRE -> {
                    recorte.pontos.chunked(2).forEachIndexed { i, (px, py) ->
                        if (i == 0) moveTo(px * largura, py * altura) else lineTo(px * largura, py * altura)
                    }
                    close()
                }
            }
        }
    }

    // ---- Texto como objeto ----

    private data class ChaveDoTexto(val texto: String, val fonte: Fonte, val tamanho: Float, val cor: Int, val corDoContorno: Int, val disposicao: DisposicaoDoTexto)
    private val texturas = ConcurrentHashMap<ChaveDoTexto, Bitmap>()

    /**
     * O texto de uma camada como uma imagem justa, só do tamanho das letras (com o contorno):
     * assim ele se move, aproxima, gira e inclina como qualquer mídia, e as guias encostam nele
     * de verdade. Desenhada em [RESOLUCAO_DO_TEXTO]× para aguentar o zoom; [zoomDoTexto] devolve
     * o tamanho natural. Fica em cache: a prévia redesenha muitas vezes por segundo.
     */
    fun texturaDoTexto(edicao: Edicao): Bitmap {
        val chave = ChaveDoTexto(edicao.texto.ifBlank { " " }, edicao.fonte, edicao.tamanhoDoTexto, edicao.corDoTexto,
            edicao.corDoContornoDoTexto, edicao.disposicaoDoTexto)
        texturas[chave]?.let { return it }
        if (texturas.size > 24) texturas.clear()
        return desenharTextura(chave).also { texturas[chave] = it }
    }

    /** O zoom que o [Renderizador.desenhar] precisa para a textura sair no tamanho natural vezes `edicao.zoom`. */
    fun zoomDoTexto(textura: Bitmap, edicao: Edicao): Float =
        edicao.zoom * maxOf(textura.width, textura.height) / (Regras.LADO * RESOLUCAO_DO_TEXTO)

    private fun desenharTextura(chave: ChaveDoTexto): Bitmap {
        val tamanho = chave.tamanho * RESOLUCAO_DO_TEXTO
        val traco = tamanho / 7f
        val folga = (traco + 4f).toInt()
        fun pintura(estilo: Paint.Style) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tipo(chave.fonte)
            textSize = tamanho
            style = estilo
            if (estilo == Paint.Style.STROKE) {
                color = chave.corDoContorno
                strokeWidth = traco
                strokeJoin = Paint.Join.ROUND
            } else {
                color = chave.cor
            }
        }
        val preenchimento = pintura(Paint.Style.FILL)
        val contorno = pintura(Paint.Style.STROKE).takeIf { Color.alpha(chave.corDoContorno) > 0 }

        if (chave.disposicao == DisposicaoDoTexto.HORIZONTAL) {
            val limite = (Regras.LADO * 0.92f * RESOLUCAO_DO_TEXTO).toInt()
            // Para cima: arredondar para baixo faria a última letra quebrar para a linha de baixo.
            val largura = (kotlin.math.ceil(chave.texto.lines().maxOf { preenchimento.measureText(it) }.toDouble()).toInt() + 2).coerceIn(1, limite)
            fun bloco(p: TextPaint) = StaticLayout.Builder.obtain(chave.texto, 0, chave.texto.length, p, largura)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
            val desenho = bloco(preenchimento)
            val saida = Bitmap.createBitmap(largura + 2 * folga, desenho.height + 2 * folga, Bitmap.Config.ARGB_8888)
            val tela = Canvas(saida)
            tela.translate(folga.toFloat(), folga.toFloat())
            contorno?.let { bloco(it).draw(tela) }
            desenho.draw(tela)
            return saida
        }

        // Letra a letra (emoji e acento contam como uma letra só).
        val letras = letras(chave.texto)
        val metricas = preenchimento.fontMetrics
        val alturaDaLinha = metricas.descent - metricas.ascent
        data class Posta(val letra: String, val x: Float, val linhaDeBase: Float)
        val postas = mutableListOf<Posta>()
        when (chave.disposicao) {
            DisposicaoDoTexto.VERTICAL -> {
                // Cada linha do texto vira uma coluna; as colunas seguem da esquerda para a direita.
                var x = 0f
                chave.texto.lines().forEach { linha ->
                    val daColuna = letras(linha)
                    val largura = daColuna.maxOfOrNull { preenchimento.measureText(it) } ?: 0f
                    daColuna.forEachIndexed { i, letra ->
                        postas += Posta(letra, x + (largura - preenchimento.measureText(letra)) / 2, -metricas.ascent + i * alturaDaLinha * 0.92f)
                    }
                    x += largura + tamanho * 0.3f
                }
            }
            else -> {
                // Escada: cada letra anda para a direita (com um respiro) e sobe (ou desce) 40% do corpo.
                val degrau = tamanho * 0.4f * if (chave.disposicao == DisposicaoDoTexto.SUBINDO) -1 else 1
                var x = 0f
                letras.filterNot { it == "\n" }.forEachIndexed { i, letra ->
                    postas += Posta(letra, x, -metricas.ascent + i * degrau)
                    x += preenchimento.measureText(letra) + tamanho * 0.08f
                }
            }
        }
        if (postas.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val esquerda = postas.minOf { it.x }
        val direita = postas.maxOf { it.x + preenchimento.measureText(it.letra) }
        val topo = postas.minOf { it.linhaDeBase + metricas.ascent }
        val base = postas.maxOf { it.linhaDeBase + metricas.descent }
        val saida = Bitmap.createBitmap((direita - esquerda).toInt().coerceAtLeast(1) + 2 * folga,
            (base - topo).toInt().coerceAtLeast(1) + 2 * folga, Bitmap.Config.ARGB_8888)
        val tela = Canvas(saida)
        tela.translate(folga - esquerda, folga - topo)
        contorno?.let { p -> postas.forEach { tela.drawText(it.letra, it.x, it.linhaDeBase, p) } }
        postas.forEach { tela.drawText(it.letra, it.x, it.linhaDeBase, preenchimento) }
        return saida
    }

    private fun letras(texto: String): List<String> {
        val iterador = java.text.BreakIterator.getCharacterInstance()
        iterador.setText(texto)
        val saida = mutableListOf<String>()
        var inicio = iterador.first()
        var fim = iterador.next()
        while (fim != java.text.BreakIterator.DONE) {
            saida += texto.substring(inicio, fim)
            inicio = fim
            fim = iterador.next()
        }
        return saida.filter { it.isNotBlank() || it == " " }
    }

    fun filtroDeCor(edicao: Edicao): ColorMatrixColorFilter? {
        val matriz = ColorMatrix()
        when (edicao.filtro) {
            Filtro.NENHUM -> Unit
            Filtro.VIVIDO -> matriz.setSaturation(1.6f)
            Filtro.PRETO_E_BRANCO -> matriz.setSaturation(0f)
            Filtro.SEPIA -> {
                matriz.setSaturation(0f)
                matriz.postConcat(ColorMatrix().apply { setScale(1.07f, 0.74f, 0.43f, 1f) })
            }
            Filtro.VINTAGE -> {
                matriz.setSaturation(0.6f)
                matriz.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 30f, 0f, 0.95f, 0f, 0f, 20f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            }
            Filtro.FRIO -> matriz.set(floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 15f, 0f, 0f, 0f, 1f, 0f))
            Filtro.QUENTE -> matriz.set(floatArrayOf(1.15f, 0f, 0f, 0f, 15f, 0f, 1.02f, 0f, 0f, 5f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            Filtro.NEGATIVO -> matriz.set(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        }
        if (edicao.saturacao != 0f) matriz.postConcat(ColorMatrix().apply { setSaturation(1f + edicao.saturacao) })
        if (edicao.contraste != 0f || edicao.brilho != 0f) {
            val c = 1f + edicao.contraste
            val deslocamento = 128f * (1f - c) + edicao.brilho * 100f
            matriz.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, deslocamento, 0f, c, 0f, 0f, deslocamento, 0f, 0f, c, 0f, deslocamento, 0f, 0f, 0f, 1f, 0f)))
        }
        return if (edicao.filtro == Filtro.NENHUM && edicao.saturacao == 0f && edicao.contraste == 0f && edicao.brilho == 0f) null
        else ColorMatrixColorFilter(matriz)
    }

    private fun desenharTexto(tela: Canvas, edicao: Edicao, lado: Int) {
        val largura = (lado * 0.92f).toInt()
        fun pintura(estilo: Paint.Style) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tipo(edicao.fonte)
            textSize = edicao.tamanhoDoTexto
            style = estilo
            if (estilo == Paint.Style.STROKE) {
                color = edicao.corDoContornoDoTexto
                strokeWidth = edicao.tamanhoDoTexto / 7f
                strokeJoin = Paint.Join.ROUND
            } else {
                color = edicao.corDoTexto
            }
        }
        fun bloco(pintura: TextPaint) = StaticLayout.Builder.obtain(edicao.texto, 0, edicao.texto.length, pintura, largura)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build()
        val contorno = bloco(pintura(Paint.Style.STROKE))
        val preenchimento = bloco(pintura(Paint.Style.FILL))
        val y = (lado * edicao.posicaoDoTexto.y).coerceAtMost(lado - preenchimento.height.toFloat() - 8f).coerceAtLeast(8f)
        tela.save()
        tela.translate((lado - largura) / 2f, y)
        if (Color.alpha(edicao.corDoContornoDoTexto) > 0) contorno.draw(tela)
        preenchimento.draw(tela)
        tela.restore()
    }

    private fun tipo(fonte: Fonte): Typeface = fontes.getOrPut(fonte) {
        when {
            fonte.arquivo != null -> runCatching { Typeface.createFromAsset(contexto.assets, fonte.arquivo) }.getOrDefault(Typeface.DEFAULT_BOLD)
            fonte == Fonte.SERIFADA -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            fonte == Fonte.MONO -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            else -> Typeface.DEFAULT_BOLD
        }
    }
}
