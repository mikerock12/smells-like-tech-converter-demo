package br.com.smellsliketech.converter.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.figurinhas.FormaDoRecorte
import br.com.smellsliketech.converter.figurinhas.Recorte
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Onde a pessoa marca o recorte, sobre a mídia original inteira (sem o enquadramento): o
 * quadrado e o círculo se arrastam e crescem pelo canto de baixo; o livre é o traço do dedo,
 * fechado ao soltar. O que fica de fora escurece. `mudar` recebe o rascunho; aplicar é com o
 * botão da aba.
 */
@Composable
fun EditorDeRecorte(imagem: Bitmap, recorte: Recorte?, forma: FormaDoRecorte, mudar: (Recorte) -> Unit, modifier: Modifier) {
    // Uma cópia: o quadro do vídeo pode ser trocado (e reciclado) enquanto a aba está aberta.
    val desenho = remember(imagem) { runCatching { imagem.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap() }.getOrNull() }
    var traco by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val atual by rememberUpdatedState(recorte)
    val aoMudar by rememberUpdatedState(mudar)
    val largura = imagem.width
    val altura = imagem.height

    Box(modifier.clipToBounds().background(Color(0xFF292932)).semantics { contentDescription = "Área do recorte" }
        .pointerInput(forma, largura, altura) {
            // Onde a mídia aparece dentro da caixa (cabendo inteira, centralizada).
            fun area(): Rect {
                val escala = min(size.width / largura.toFloat(), size.height / altura.toFloat())
                val w = largura * escala
                val h = altura * escala
                return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
            }
            fun fracao(p: Offset, a: Rect) = Offset(((p.x - a.left) / a.width).coerceIn(0f, 1f), ((p.y - a.top) / a.height).coerceIn(0f, 1f))
            awaitEachGesture {
                val inicio = awaitFirstDown()
                val a = area()
                if (forma == FormaDoRecorte.LIVRE) {
                    val pontos = mutableListOf(fracao(inicio.position, a))
                    traco = pontos.toList()
                    do {
                        val evento = awaitPointerEvent()
                        val mudanca = evento.changes.first()
                        val p = fracao(mudanca.position, a)
                        // Um ponto a cada ~1% do lado: o traço fica leve e liso.
                        if (hypot(p.x - pontos.last().x, p.y - pontos.last().y) > 0.01f) { pontos += p; traco = pontos.toList() }
                        mudanca.consume()
                    } while (evento.changes.any { it.pressed })
                    if (pontos.size >= 3) aoMudar(Recorte(FormaDoRecorte.LIVRE, pontos.flatMap { listOf(it.x, it.y) }))
                    traco = emptyList()
                    return@awaitEachGesture
                }
                // Quadrado e círculo: a caixa em pixels da mídia, sempre quadrada.
                val caixa = atual?.takeIf { it.forma == forma }?.caixa() ?: return@awaitEachGesture
                var l = caixa[0] * largura; var t = caixa[1] * altura
                var lado = min((caixa[2] - caixa[0]) * largura, (caixa[3] - caixa[1]) * altura)
                val escala = a.width / largura
                val toque = Offset((inicio.position.x - a.left) / escala, (inicio.position.y - a.top) / escala)
                // A alça: no canto do quadrado, ou na borda do círculo (embaixo à direita).
                val canto = if (forma == FormaDoRecorte.CIRCULO) Offset(l + lado / 2 * (1 + 0.7071f), t + lado / 2 * (1 + 0.7071f))
                    else Offset(l + lado, t + lado)
                val redimensionar = hypot(toque.x - canto.x, toque.y - canto.y) < 36.dp.toPx() / escala
                val dentro = toque.x in l..(l + lado) && toque.y in t..(t + lado)
                if (!redimensionar && !dentro) {
                    // Fora do quadrado: ele vai para onde o dedo tocou.
                    l = (toque.x - lado / 2).coerceIn(0f, largura - lado); t = (toque.y - lado / 2).coerceIn(0f, altura - lado)
                }
                var anterior = inicio.position
                fun publicar() = aoMudar(Recorte(forma, listOf(l / largura, t / altura, (l + lado) / largura, (t + lado) / altura)))
                publicar()
                do {
                    val evento = awaitPointerEvent()
                    val mudanca = evento.changes.first()
                    val dx = (mudanca.position.x - anterior.x) / escala
                    val dy = (mudanca.position.y - anterior.y) / escala
                    anterior = mudanca.position
                    if (redimensionar) {
                        // O canto de cima fica; o lado acompanha o maior movimento do dedo.
                        val passo = if (abs(dx) > abs(dy)) dx else dy
                        lado = (lado + passo).coerceIn(min(largura, altura) * 0.05f, min(largura - l, altura - t))
                    } else {
                        l = (l + dx).coerceIn(0f, largura - lado); t = (t + dy).coerceIn(0f, altura - lado)
                    }
                    publicar()
                    mudanca.consume()
                } while (evento.changes.any { it.pressed })
            }
        }) {
        Canvas(Modifier.matchParentSize()) {
            val escala = min(size.width / largura, size.height / altura)
            val a = Rect(Offset((size.width - largura * escala) / 2, (size.height - altura * escala) / 2), Size(largura * escala, altura * escala))
            desenho?.let { drawImage(it, dstOffset = IntOffset(a.left.toInt(), a.top.toInt()), dstSize = IntSize(a.width.toInt(), a.height.toInt())) }
            fun ponto(x: Float, y: Float) = Offset(a.left + x * a.width, a.top + y * a.height)
            val forma2 = if (traco.isNotEmpty()) null else recorte
            val caminho = Path().apply {
                when {
                    traco.isNotEmpty() -> traco.forEachIndexed { i, p -> ponto(p.x, p.y).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    forma2 == null -> Unit
                    forma2.forma == FormaDoRecorte.LIVRE -> {
                        forma2.pontos.chunked(2).forEachIndexed { i, (x, y) -> ponto(x, y).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                        close()
                    }
                    else -> {
                        val c = forma2.caixa()
                        val r = Rect(ponto(c[0], c[1]), ponto(c[2], c[3]))
                        if (forma2.forma == FormaDoRecorte.CIRCULO) addOval(r) else addRect(r)
                    }
                }
            }
            if (forma2 != null) {
                // Escurece o que vai ficar de fora: a mídia inteira menos o recorte (par/ímpar).
                val fora = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(a)
                    addPath(caminho)
                }
                drawPath(fora, Color.Black.copy(alpha = 0.6f))
            }
            drawPath(caminho, Color.White, style = Stroke(width = 2.dp.toPx()))
            if (forma2 != null && forma2.forma != FormaDoRecorte.LIVRE) {
                // A alça de tamanho: no canto do quadrado, ou na borda do círculo (embaixo à direita).
                val c = forma2.caixa()
                val canto = ponto(c[2], c[3])
                val alca = if (forma2.forma == FormaDoRecorte.QUADRADO) canto else {
                    val centro = ponto((c[0] + c[2]) / 2, (c[1] + c[3]) / 2)
                    centro + (canto - centro) * 0.7071f
                }
                drawCircle(Color(0xFFFF7A18), radius = 9.dp.toPx(), center = alca)
            }
        }
    }
}

/** O ponto de partida: um quadrado (ou círculo) central de 80% do lado menor. O livre começa vazio. */
fun recortePadrao(forma: FormaDoRecorte, largura: Int, altura: Int): Recorte? {
    if (forma == FormaDoRecorte.LIVRE || largura <= 0 || altura <= 0) return null
    val lado = min(largura, altura) * 0.8f
    val l = (largura - lado) / 2 / largura
    val t = (altura - lado) / 2 / altura
    return Recorte(forma, listOf(l, t, l + lado / largura, t + lado / altura))
}
