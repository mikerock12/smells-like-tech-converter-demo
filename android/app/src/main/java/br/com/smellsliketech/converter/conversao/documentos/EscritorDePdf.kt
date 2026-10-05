package br.com.smellsliketech.converter.conversao.documentos

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * O [Documento] em PDF A4, desenhado pelo próprio Android (PdfDocument): texto de verdade,
 * selecionável, com quebra de linha e de página. Tabelas com grade, colunas iguais.
 */
object EscritorDePdf {
    private const val LARGURA = 595 // A4 em pontos
    private const val ALTURA = 842
    private const val MARGEM = 56f
    private const val UTIL = LARGURA - 2 * MARGEM

    private class Pagina(val documento: PdfDocument) {
        private var numero = 0
        private var atual: PdfDocument.Page? = null
        var y = MARGEM
            private set
        val canvas: Canvas get() = atual!!.canvas

        fun nova() {
            atual?.let(documento::finishPage)
            numero++
            atual = documento.startPage(PdfDocument.PageInfo.Builder(LARGURA, ALTURA, numero).create())
            y = MARGEM
        }

        fun cabe(altura: Float) = y + altura <= ALTURA - MARGEM
        fun avancar(altura: Float) { y += altura }
        fun fechar() { atual?.let(documento::finishPage); atual = null }
        val vazia: Boolean get() = y == MARGEM
    }

    private fun pincel(tamanho: Float, negrito: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho
        color = Color.BLACK
        typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(texto: String, pincel: TextPaint, largura: Int): StaticLayout =
        StaticLayout.Builder.obtain(texto, 0, texto.length, pincel, largura.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

    fun escrever(documento: Documento, saida: OutputStream) {
        val pdf = PdfDocument()
        try {
            val pagina = Pagina(pdf)
            pagina.nova()
            val corpo = pincel(11f)
            documento.blocos.forEach { bloco ->
                when (bloco) {
                    is Bloco.Titulo -> {
                        val tamanho = when (bloco.nivel) { 1 -> 20f; 2 -> 16f; 3 -> 14f; else -> 12f }
                        // O título não fica sozinho no pé da página.
                        if (!pagina.cabe(tamanho * 4)) pagina.nova()
                        if (!pagina.vazia) pagina.avancar(tamanho * 0.6f)
                        texto(pagina, bloco.texto, pincel(tamanho, negrito = true), MARGEM, UTIL)
                        pagina.avancar(6f)
                    }
                    is Bloco.Paragrafo -> { texto(pagina, bloco.texto, corpo, MARGEM, UTIL); pagina.avancar(8f) }
                    is Bloco.Item -> {
                        val marca = bloco.numero?.let { "$it." } ?: "•"
                        if (!pagina.cabe(corpo.textSize * 1.5f)) pagina.nova()
                        pagina.canvas.drawText(marca, MARGEM + 6f, pagina.y - corpo.ascent(), corpo)
                        texto(pagina, bloco.texto, corpo, MARGEM + 24f, UTIL - 24f)
                        pagina.avancar(4f)
                    }
                    is Bloco.Tabela -> { tabela(pagina, bloco.linhas); pagina.avancar(10f) }
                    Bloco.Quebra -> if (!pagina.vazia) pagina.nova()
                }
            }
            pagina.fechar()
            pdf.writeTo(saida)
        } finally {
            pdf.close()
        }
    }

    /** Desenha o texto linha a linha, passando para a página seguinte quando acaba o espaço. */
    private fun texto(pagina: Pagina, texto: String, pincel: TextPaint, x: Float, largura: Float) {
        val desenho = layout(texto, pincel, largura.toInt())
        var linha = 0
        while (linha < desenho.lineCount) {
            val alturaDaLinha = (desenho.getLineBottom(linha) - desenho.getLineTop(linha)).toFloat()
            if (!pagina.cabe(alturaDaLinha)) pagina.nova()
            // As linhas que cabem nesta página, de uma vez.
            var ultima = linha
            var altura = alturaDaLinha
            while (ultima + 1 < desenho.lineCount) {
                val proxima = (desenho.getLineBottom(ultima + 1) - desenho.getLineTop(ultima + 1)).toFloat()
                if (!pagina.cabe(altura + proxima)) break
                altura += proxima
                ultima++
            }
            val topo = desenho.getLineTop(linha).toFloat()
            val canvas = pagina.canvas
            canvas.save()
            canvas.translate(x, pagina.y - topo)
            canvas.clipRect(0f, topo, largura, topo + altura)
            desenho.draw(canvas)
            canvas.restore()
            pagina.avancar(altura)
            linha = ultima + 1
        }
    }

    private fun tabela(pagina: Pagina, linhas: List<List<String>>) {
        val colunas = linhas.maxOf { it.size }.coerceAtLeast(1)
        // Muitas colunas: letra menor, para caber na largura da página.
        val tamanho = when { colunas > 10 -> 6f; colunas > 6 -> 7.5f; else -> 9.5f }
        val normal = pincel(tamanho)
        val cabecalho = pincel(tamanho, negrito = true)
        val largura = UTIL / colunas
        val folga = 3f
        val grade = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.5f; style = Paint.Style.STROKE }
        linhas.forEachIndexed { indice, linha ->
            val pincelDaLinha = if (indice == 0 && linhas.size > 1) cabecalho else normal
            val desenhos = List(colunas) { coluna -> layout(linha.getOrElse(coluna) { "" }, pincelDaLinha, (largura - 2 * folga).toInt()) }
            // Uma linha mais alta que a página é cortada: tabela não se divide no meio da célula.
            val altura = (desenhos.maxOf { it.height } + 2 * folga).coerceAtMost(ALTURA - 2 * MARGEM)
            if (!pagina.cabe(altura)) pagina.nova()
            val canvas = pagina.canvas
            desenhos.forEachIndexed { coluna, desenho ->
                val x = MARGEM + coluna * largura
                canvas.drawRect(x, pagina.y, x + largura, pagina.y + altura, grade)
                canvas.save()
                canvas.translate(x + folga, pagina.y + folga)
                canvas.clipRect(0f, 0f, largura - 2 * folga, altura - 2 * folga)
                desenho.draw(canvas)
                canvas.restore()
            }
            pagina.avancar(altura)
        }
    }
}
