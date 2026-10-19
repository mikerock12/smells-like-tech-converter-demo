package br.com.smellsliketech.converter.estudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import br.com.smellsliketech.converter.figurinhas.Producao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Um quadro do projeto num instante, montado no próprio celular, para a prévia do editor: o
 * clipe daquele instante no enquadramento escolhido (com o fundo desfocado quando for o caso)
 * e os textos visíveis, com o [DesenhoDoTexto] da exportação. Os quadros de vídeo vêm do FFmpeg
 * e ficam guardados por instante, para arrastar a linha do tempo sem refazer tudo.
 */
class PreviaDoEstudio(private val contexto: Context) {
    private val desenho = DesenhoDoTexto(contexto)
    private val quadros = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(chave: String, valor: Bitmap) = valor.byteCount
    }

    /** O quadro do projeto em `t`, com o lado maior em `ladoMaximo` px. */
    suspend fun quadro(projeto: Projeto, t: Double, ladoMaximo: Int = 720): Bitmap = withContext(Dispatchers.Default) {
        val escala = minOf(1.0, ladoMaximo.toDouble() / max(projeto.largura, projeto.altura))
        val largura = max(2, (projeto.largura * escala).roundToInt())
        val altura = max(2, (projeto.altura * escala).roundToInt())
        val saida = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val tela = Canvas(saida)
        tela.drawColor(Color.BLACK)
        Estudio.clipeNoTempo(projeto, t)?.let { (_, clipe, tempoNoArquivo) ->
            fonte(clipe, tempoNoArquivo)?.let { imagem -> enquadrar(tela, imagem, clipe.ajuste, largura, altura) }
        }
        tela.save()
        tela.scale((largura.toDouble() / projeto.largura).toFloat(), (altura.toDouble() / projeto.altura).toFloat())
        Estudio.textosNoTempo(projeto, t).forEach { texto -> desenho.desenhar(tela, texto, projeto.largura, projeto.altura) }
        tela.restore()
        saida
    }

    /** A imagem do clipe naquele ponto do arquivo (quadros de vídeo arredondados a 0,1 s). */
    private suspend fun fonte(clipe: Clipe, tempo: Double): Bitmap? {
        val chave = if (clipe.ehImagem) clipe.arquivo else "${clipe.arquivo}@${(tempo * 10).roundToInt()}"
        quadros.get(chave)?.let { return it }
        val arquivo = File(clipe.arquivo)
        if (!arquivo.isFile) return null
        val imagem = runCatching {
            if (clipe.ehImagem) withContext(Dispatchers.IO) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(arquivo)) { decodificador, informacao, _ ->
                    decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maior = max(informacao.size.width, informacao.size.height)
                    if (maior > 1280) {
                        val fator = 1280.0 / maior
                        decodificador.setTargetSize((informacao.size.width * fator).roundToInt(), (informacao.size.height * fator).roundToInt())
                    }
                }
            } else Producao.quadro(contexto, arquivo, tempo)
        }.getOrNull() ?: return null
        quadros.put(chave, imagem)
        return imagem
    }

    private fun enquadrar(tela: Canvas, imagem: Bitmap, ajuste: String, largura: Int, altura: Int) {
        val pintura = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (ajuste == "caber-desfocado") {
            // Desfoque barato, como na exportação: a imagem bem pequena, esticada com filtro.
            val (x, y, w, h) = Estudio.retanguloDoAjuste("preencher", imagem.width.toDouble(), imagem.height.toDouble(), largura, altura)
            val fundo = Bitmap.createScaledBitmap(imagem, max(1, imagem.width / 24), max(1, imagem.height / 24), true)
            tela.drawBitmap(fundo, null, RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), pintura)
            fundo.recycle()
        }
        val (x, y, w, h) = Estudio.retanguloDoAjuste(if (ajuste == "preencher") "preencher" else "caber", imagem.width.toDouble(), imagem.height.toDouble(), largura, altura)
        tela.drawBitmap(imagem, null, RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), pintura)
    }

    fun esquecer() = quadros.evictAll()
}
