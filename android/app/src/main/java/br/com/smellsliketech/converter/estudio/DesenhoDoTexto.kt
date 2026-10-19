package br.com.smellsliketech.converter.estudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.concurrent.ConcurrentHashMap

/**
 * Desenha um texto do estúdio com o layout comum aos quatro apps (LAYOUT_DO_TEXTO em
 * estudio.mjs): letra de `tamanho` × altura, quebra em 90% da largura, linhas centralizadas em
 * (x, y), contorno atrás do preenchimento e caixa de fundo arredondada. Serve para a prévia
 * (num Canvas qualquer) e para a exportação (PNG transparente do tamanho do quadro).
 */
class DesenhoDoTexto(private val contexto: Context) {
    private val fontes = ConcurrentHashMap<String, Typeface>()

    /** Um PNG transparente do tamanho do quadro, só com o texto. */
    fun imagem(texto: Texto, largura: Int, altura: Int): Bitmap =
        Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888).also { desenhar(Canvas(it), texto, largura, altura) }

    /** Desenha em `tela`, que representa um quadro de `largura` × `altura`. */
    fun desenhar(tela: Canvas, texto: Texto, largura: Int, altura: Int) {
        val conteudo = texto.texto.trim()
        if (conteudo.isEmpty()) return
        val tamanho = maxOf(4.0, texto.tamanho * altura).toFloat()
        val tipo = tipo(texto.fonte)
        val maxima = (largura * Estudio.LARGURA_MAXIMA_DO_TEXTO).toInt().coerceAtLeast(1)

        fun pintura(estilo: Paint.Style, cor: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tipo
            textSize = tamanho
            style = estilo
            color = cor
            if (estilo == Paint.Style.STROKE) {
                strokeWidth = (tamanho * Estudio.CONTORNO_DO_TEXTO * 2).toFloat()
                strokeJoin = Paint.Join.ROUND
            }
        }

        val preenchimento = pintura(Paint.Style.FILL, cor(texto.cor) ?: Color.WHITE)
        // Entrelinha fixa de 1,15 × letra, a mesma do Windows e do site.
        val metricas = preenchimento.fontMetrics
        val extra = (tamanho * Estudio.ENTRELINHA - (metricas.descent - metricas.ascent)).toFloat()
        fun bloco(pintura: TextPaint) = StaticLayout.Builder.obtain(conteudo, 0, conteudo.length, pintura, maxima)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(extra, 1f)
            .setIncludePad(false)
            .build()

        val layout = bloco(preenchimento)
        val alturaDoBloco = (tamanho * Estudio.ENTRELINHA * layout.lineCount).toFloat()
        val larguraDoBloco = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        val centroX = (texto.x * largura).toFloat()
        val topo = (texto.y * altura).toFloat() - alturaDoBloco / 2

        cor(texto.caixa)?.let { corDaCaixa ->
            val margem = (tamanho * Estudio.MARGEM_DA_CAIXA).toFloat()
            val raio = (tamanho * Estudio.RAIO_DA_CAIXA).toFloat()
            val caixa = RectF(centroX - larguraDoBloco / 2 - margem, topo - margem, centroX + larguraDoBloco / 2 + margem, topo + alturaDoBloco + margem)
            tela.drawRoundRect(caixa, raio, raio, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = corDaCaixa })
        }

        tela.save()
        // O StaticLayout ocupa `maxima` de largura e centraliza cada linha dentro dela; o
        // primeiro deslocamento é metade da sobra da entrelinha, para o texto ficar no meio.
        tela.translate(centroX - maxima / 2f, topo + extra / 2)
        cor(texto.contorno)?.let { corDoContorno -> bloco(pintura(Paint.Style.STROKE, corDoContorno)).draw(tela) }
        layout.draw(tela)
        tela.restore()
    }

    private fun tipo(fonte: String): Typeface = fontes.getOrPut(fonte) {
        val arquivo = Estudio.FONTES.firstOrNull { it.id == fonte }?.arquivo
        if (arquivo == null) Typeface.DEFAULT_BOLD
        else runCatching { Typeface.createFromAsset(contexto.assets, "fontes/$arquivo") }.getOrDefault(Typeface.DEFAULT_BOLD)
    }

    companion object {
        /** "#RRGGBB" ou "#RRGGBBAA" (CSS, como no JSON do projeto) para o ARGB do Android. */
        fun cor(css: String?): Int? {
            if (css == null || !css.startsWith('#') || (css.length != 7 && css.length != 9)) return null
            val valor = css.substring(1).toLongOrNull(16) ?: return null
            return if (css.length == 7) (0xFF000000 or valor).toInt()
            else (((valor and 0xFF) shl 24) or (valor ushr 8)).toInt()
        }
    }
}
