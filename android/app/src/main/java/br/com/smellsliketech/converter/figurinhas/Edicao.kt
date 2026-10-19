package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import kotlin.math.sin

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

/** Onde o texto fica na figurinha. */
enum class PosicaoDoTexto(val rotulo: String, val y: Float) { CIMA("Em cima", 0.06f), MEIO("No meio", 0.42f), BAIXO("Embaixo", 0.78f) }

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
    /** Giro da imagem em graus, no sentido do relógio: 0, 90, 180 ou 270. */
    val giro: Int = 0,
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
    val contorno: Boolean = false,
    val corDoContorno: Int = Color.WHITE,
    val larguraDoContorno: Float = 12f,
)

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
        // Girada de lado, a imagem ocupa o quadro com largura e altura trocadas.
        val deitada = edicao.giro % 180 != 0
        val destino = enquadramento(if (deitada) fonte.height else fonte.width, if (deitada) fonte.width else fonte.height, edicao, lado - 2 * margem)
            .apply { offset(margem, margem) }
        val pintura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filtroDeCor(edicao) }
        // A imagem e a máscara giram e espelham juntas, em torno do centro do enquadramento.
        val semGiro = if (deitada) RectF(-destino.height() / 2, -destino.width() / 2, destino.height() / 2, destino.width() / 2)
        else RectF(-destino.width() / 2, -destino.height() / 2, destino.width() / 2, destino.height() / 2)
        desenhoDaCamada.save()
        desenhoDaCamada.translate(destino.centerX(), destino.centerY())
        desenhoDaCamada.scale(if (edicao.espelharHorizontal) -1f else 1f, if (edicao.espelharVertical) -1f else 1f)
        desenhoDaCamada.rotate(edicao.giro.toFloat())
        desenhoDaCamada.drawBitmap(fonte, null, semGiro, pintura)
        if (mascara != null && edicao.removerFundo) {
            val recorte = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            desenhoDaCamada.drawBitmap(mascara, null, semGiro, recorte)
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
        val deitada = edicao.giro % 180 != 0
        val area = enquadramento(if (deitada) altura else largura, if (deitada) largura else altura, edicao, Regras.LADO - 2 * margem)
            .apply { offset(margem, margem) }
        // O caminho inverso do desenho: tira o deslocamento, desfaz o espelho e depois o giro.
        var dx = (x * Regras.LADO - area.centerX()).toDouble()
        var dy = (y * Regras.LADO - area.centerY()).toDouble()
        if (edicao.espelharHorizontal) dx = -dx
        if (edicao.espelharVertical) dy = -dy
        val angulo = Math.toRadians(-edicao.giro.toDouble())
        val rx = dx * cos(angulo) - dy * sin(angulo)
        val ry = dx * sin(angulo) + dy * cos(angulo)
        val larguraSemGiro = if (deitada) area.height() else area.width()
        val alturaSemGiro = if (deitada) area.width() else area.height()
        return Triple(
            ((rx + larguraSemGiro / 2) / larguraSemGiro).toFloat(),
            ((ry + alturaSemGiro / 2) / alturaSemGiro).toFloat(),
            raio * Regras.LADO / maxOf(area.width(), area.height()),
        )
    }

    /** Onde a imagem cai no quadrado: cabe inteira com zoom 1, aproxima e desloca com mais. */
    private fun enquadramento(largura: Int, altura: Int, edicao: Edicao, lado: Float): RectF {
        val escala = min(lado / largura, lado / altura) * edicao.zoom.coerceIn(0.3f, 6f)
        val w = largura * escala
        val h = altura * escala
        val cx = lado / 2 + edicao.deslocamentoX * lado
        val cy = lado / 2 + edicao.deslocamentoY * lado
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun filtroDeCor(edicao: Edicao): ColorMatrixColorFilter? {
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
