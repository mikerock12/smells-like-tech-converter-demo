package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuffXfermode
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

    fun desenhar(camadas: List<CamadaDeFigurinha>, fundo: Int = Color.TRANSPARENT, fundoFinal: Int? = null): Bitmap {
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
            // O texto é um objeto: uma textura justa (em cache, não reciclar), no tamanho natural
            // vezes o zoom, que se move, gira e inclina como a imagem. O recorte não vale para ele.
            val desenho = if (camada.texto) {
                val textura = renderizador.texturaDoTexto(e)
                renderizador.desenhar(textura, null, e.copy(zoom = renderizador.zoomDoTexto(textura, e),
                    corDeFundo = Color.TRANSPARENT, texto = "", recorte = null))
            } else renderizador.desenhar(camada.imagem, camada.mascara, e.copy(corDeFundo = Color.TRANSPARENT))
            val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (e.opacidade.coerceIn(0f, 1f) * 255).toInt()
                xfermode = PorterDuffXfermode(e.mescla.modo)
            }
            tela.drawBitmap(desenho, 0f, 0f, pintura)
            desenho.recycle()
        }
        return saida
    }
}
