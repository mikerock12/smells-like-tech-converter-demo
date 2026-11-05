package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import br.com.smellsliketech.converter.estudio.EstadoDoEfeito
import java.util.UUID

data class CamadaDeFigurinha(
    val id: String = UUID.randomUUID().toString(),
    val nome: String,
    val origem: Origem? = null,
    val imagem: Bitmap,
    val mascara: Bitmap? = null,
    val mascaraManual: Boolean = false,
    val edicao: Edicao = Edicao(zoom = 0.5f),
    val visivel: Boolean = true,
    val ordem: Int = 1,
    val texto: Boolean = false,
    val inicio: Double = 0.0,
    val fim: Double? = null,
)

/** Cada camada é recortada/filtrada isoladamente, e só depois é mesclada na composição. */
class CompositorDeFigurinhas(contexto: Context, val renderizador: Renderizador = Renderizador(contexto)) {

    /** Sem `instante`, os efeitos ficam de fora: é a figura parada, como ela é editada. */
    fun desenhar(camadas: List<CamadaDeFigurinha>, fundo: Int = Color.TRANSPARENT, fundoFinal: Int? = null, instante: InstanteDosEfeitos? = null): Bitmap {
        val saida = Bitmap.createBitmap(Regras.LADO, Regras.LADO, Bitmap.Config.ARGB_8888)
        val tela = Canvas(saida)
        if (fundoFinal == null) tela.drawColor(fundo) else {
            tela.drawRect(0f, 0f, Regras.LADO.toFloat(), Regras.LADO.toFloat(), Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, Regras.LADO.toFloat(), Regras.LADO.toFloat(),
                    fundo, fundoFinal, android.graphics.Shader.TileMode.CLAMP)
            })
        }
        camadas.filter { it.visivel }.sortedBy { it.ordem }.forEach { camada ->
            val e = camada.edicao
            val momento = instante?.takeIf { EfeitosDaFigurinha.tem(e) }
            val duracao = momento?.duracaoDe(camada) ?: 0.0
            val estado = momento?.let { EfeitosDaFigurinha.estado(e, it.tempo, duracao) }
            val movimento = estado?.let { matrizDoEfeito(e, it) }
            // O texto é um objeto: uma textura justa (em cache, não reciclar), no tamanho natural
            // vezes o zoom, que se move, gira e inclina como a imagem. O recorte não vale para ele.
            val desenho = if (camada.texto) {
                val letras = momento?.let { EfeitosDaFigurinha.letrasVisiveis(renderizador.quantasLetras(e.texto), e, it.tempo, duracao, it.quadrosPorSegundo) }
                val textura = renderizador.texturaDoTexto(e, letras)
                renderizador.desenhar(textura, null, e.copy(zoom = renderizador.zoomDoTexto(textura, e),
                    corDeFundo = Color.TRANSPARENT, texto = "", recorte = null), movimento)
            } else renderizador.desenhar(camada.imagem, camada.mascara, e.copy(corDeFundo = Color.TRANSPARENT), movimento)
            val quebrado = estado?.let { quebrar(desenho, it) } ?: desenho
            val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (e.opacidade.coerceIn(0f, 1f) * (estado?.alfa ?: 1.0).coerceIn(0.0, 1.0) * 255).toInt()
                xfermode = PorterDuffXfermode(e.mescla.modo)
                if (estado != null && estado.matiz != 0.0) colorFilter = ColorMatrixColorFilter(EfeitosDaFigurinha.matrizDoMatiz(estado.matiz))
            }
            tela.drawBitmap(quebrado, 0f, 0f, pintura)
            quebrado.recycle()
        }
        return saida
    }

    /** Move, aproxima e gira a figura em torno do centro dela. */
    private fun matrizDoEfeito(e: Edicao, estado: EstadoDoEfeito): Matrix {
        val lado = Regras.LADO.toFloat()
        val centroX = (.5f + e.deslocamentoX) * lado
        val centroY = (.5f + e.deslocamentoY) * lado
        return Matrix().apply {
            postTranslate(-centroX, -centroY)
            postScale(estado.sx.toFloat(), estado.sy.toFloat())
            postRotate(Math.toDegrees(estado.giro).toFloat())
            postTranslate(centroX + estado.dx.toFloat() * lado, centroY + estado.dy.toFloat() * lado)
        }
    }

    /** Estilhaços e partículas: mexem nos pixels da camada já desenhada. Devolve o bitmap a usar. */
    private fun quebrar(desenho: Bitmap, estado: EstadoDoEfeito): Bitmap {
        if (estado.particulas >= 1 && estado.pedacos <= 0) return desenho
        val lado = Regras.LADO
        val pontos = IntArray(lado * lado)
        desenho.getPixels(pontos, 0, lado, 0, 0, lado, lado)
        if (estado.particulas < 1) {
            val grao = EfeitosDaFigurinha.GRAO
            val colunas = (lado + grao - 1) / grao
            for (linha in 0 until (lado + grao - 1) / grao) for (coluna in 0 until colunas) {
                if (EfeitosDaFigurinha.particulaVisivel(coluna, linha, colunas, estado.particulas)) continue
                for (y in linha * grao until minOf(lado, (linha + 1) * grao)) {
                    pontos.fill(0, y * lado + coluna * grao, y * lado + minOf(lado, (coluna + 1) * grao))
                }
            }
            desenho.setPixels(pontos, 0, lado, 0, 0, lado, lado)
        }
        if (estado.pedacos <= 0) return desenho
        // A caixa da figura: é ela que quebra, e não o quadro inteiro.
        var esquerda = lado; var topo = lado; var direita = 0; var base = 0
        for (y in 0 until lado) for (x in 0 until lado) if (pontos[y * lado + x] ushr 24 != 0) {
            if (x < esquerda) esquerda = x
            if (x >= direita) direita = x + 1
            if (y < topo) topo = y
            base = y + 1
        }
        if (direita <= esquerda) return desenho
        val saida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val tela = Canvas(saida)
        val pintura = Paint(Paint.FILTER_BITMAP_FLAG)
        EfeitosDaFigurinha.estilhacos(esquerda, topo, direita, base, estado.pedacos).forEach { p ->
            tela.save()
            tela.translate(p.centroX, p.centroY)
            tela.rotate(p.giro)
            tela.drawBitmap(desenho, Rect(p.x, p.y, p.x + p.largura, p.y + p.altura),
                RectF(-p.largura / 2f, -p.altura / 2f, p.largura / 2f, p.altura / 2f), pintura)
            tela.restore()
        }
        desenho.recycle()
        return saida
    }
}
