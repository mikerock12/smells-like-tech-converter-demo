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
)

/** Cada camada é recortada/filtrada isoladamente, e só depois é mesclada na composição. */
class CompositorDeFigurinhas(contexto: Context) {
    private val renderizador = Renderizador(contexto)

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
            val textura = if (camada.texto) renderizador.desenhar(
                camada.imagem, null, e.copy(zoom = 1f, deslocamentoX = 0f, deslocamentoY = 0f, giro = 0,
                    inclinacaoX = 0f, inclinacaoY = 0f, corDeFundo = Color.TRANSPARENT, filtro = Filtro.NENHUM, contorno = false),
            ) else camada.imagem
            val desenho = renderizador.desenhar(textura, camada.mascara,
                e.copy(corDeFundo = Color.TRANSPARENT, texto = if (camada.texto) "" else e.texto))
            val pintura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (e.opacidade.coerceIn(0f, 1f) * 255).toInt()
                xfermode = PorterDuffXfermode(e.mescla.modo)
            }
            tela.drawBitmap(desenho, 0f, 0f, pintura)
            desenho.recycle()
            if (textura !== camada.imagem) textura.recycle()
        }
        return saida
    }
}
