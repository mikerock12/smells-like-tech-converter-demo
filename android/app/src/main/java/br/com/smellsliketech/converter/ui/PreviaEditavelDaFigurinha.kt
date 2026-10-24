package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.figurinhas.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** A janela de inspeção e o cursor ficam fora do projeto: só a máscara altera a saída. */
@Composable
fun PreviaEditavelDaFigurinha(modelo: FigurinhasViewModel, pincel: Boolean, apagar: Boolean, raio: Float, modifier: Modifier) {
    var janela by remember(modelo.origem) { mutableStateOf(JanelaDaPrevia()) }
    var caixas by remember(modelo.origem) { mutableStateOf(listOf<JanelaDaPrevia>()) }
    var selecionarZoom by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var inicioCaixa by remember { mutableStateOf<Offset?>(null) }
    var fimCaixa by remember { mutableStateOf<Offset?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier.clipToBounds().background(Color(0xFF292932)).pointerInput(pincel, apagar, raio, selecionarZoom, modelo.camadaSelecionada) {
            var ultimoToque = 0L
            var ultimoPonto = Offset.Unspecified
            val afastamento = 38.dp.toPx()
            awaitEachGesture {
                val primeiro = awaitFirstDown(requireUnconsumed = false)
                val segundoToque = primeiro.uptimeMillis - ultimoToque in 40..viewConfiguration.doubleTapTimeoutMillis &&
                    ultimoPonto.x.isFinite() && (primeiro.position - ultimoPonto).getDistance() < viewConfiguration.touchSlop * 4
                var pintar = pincel && segundoToque && !selecionarZoom
                var inclinar = !pincel && segundoToque && !selecionarZoom
                val de = primeiro.position
                var foiMultiplo = false
                var moveu = false
                var soltura = primeiro.uptimeMillis
                var bruta = modelo.edicao
                var pontoAnterior: Pair<Float, Float>? = null
                var giro = bruta.giro.toFloat()
                val giroInicial = giro
                fun pontoDoPincel(p: Offset): Offset = Offset(p.x.coerceIn(0f, size.width.toFloat()), (p.y - afastamento).coerceIn(0f, size.height.toFloat()))
                fun aplicar(p: Offset) {
                    val ponto = pontoDoPincel(p)
                    cursor = ponto
                    if (pintar) {
                        val imagem = janela.ponto(ponto.x / size.width, ponto.y / size.height)
                        val anterior = pontoAnterior ?: imagem
                        modelo.pincelarNaPrevia(imagem.first, imagem.second, raio * janela.lado, apagar, anterior.first, anterior.second)
                        pontoAnterior = imagem
                    }
                }
                modelo.iniciarGesto()
                if (selecionarZoom) { inicioCaixa = de; fimCaixa = de }
                if (pincel && !selecionarZoom) aplicar(de)
                try {
                    do {
                        val evento = awaitPointerEvent()
                        soltura = evento.changes.maxOf { it.uptimeMillis }
                        val dedos = evento.changes.count { it.pressed }
                        val pan = evento.calculatePan()
                        val zoom = evento.calculateZoom()
                        val rotacao = evento.calculateRotation()
                        if ((evento.changes.first().position - de).getDistance() > viewConfiguration.touchSlop) moveu = true
                        if (dedos > 1) { foiMultiplo = true; pintar = false; inclinar = false }
                        when {
                            selecionarZoom -> fimCaixa = evento.changes.first().position
                            pincel && dedos <= 1 && !foiMultiplo -> aplicar(evento.changes.first().position)
                            pincel -> {
                                janela = janela.ampliar(zoom)
                                janela = janela.copy(x = (janela.x - pan.x / size.width * janela.lado).coerceIn(0f, 1 - janela.lado),
                                    y = (janela.y - pan.y / size.height * janela.lado).coerceIn(0f, 1 - janela.lado))
                            }
                            else -> {
                                giro += rotacao
                                bruta = bruta.copy(zoom = (bruta.zoom * zoom).coerceIn(.3f, 6f), giro = (giro.roundToInt() + 540).mod(360) - 180)
                                bruta = if (inclinar) GestoDeInclinacao.aplicar(bruta, pan.x, pan.y, size.width, size.height)
                                else if (!foiMultiplo) bruta.copy(deslocamentoX = (bruta.deslocamentoX + pan.x / size.width).coerceIn(-1f, 1f),
                                    deslocamentoY = (bruta.deslocamentoY + pan.y / size.height).coerceIn(-1f, 1f))
                                else bruta
                                if (pan != Offset.Zero || zoom != 1f || rotacao != 0f) {
                                    if (inclinar || foiMultiplo) modelo.mudar(bruta)
                                    else modelo.moverPorGesto(bruta, abs(giro - giroInicial) > 1)
                                }
                            }
                        }
                        evento.changes.forEach { it.consume() }
                    } while (evento.changes.any { it.pressed })
                    if (selecionarZoom && moveu) {
                        val ate = fimCaixa ?: de
                        caixas = (caixas + janela).takeLast(16)
                        janela = janela.caixa(de.x / size.width, de.y / size.height, ate.x / size.width, ate.y / size.height)
                        selecionarZoom = false
                    }
                    ultimoToque = if (!moveu && !foiMultiplo && soltura - primeiro.uptimeMillis < viewConfiguration.longPressTimeoutMillis) soltura else 0L
                    ultimoPonto = primeiro.position
                } finally {
                    modelo.terminarGesto(); inicioCaixa = null; fimCaixa = null
                }
            }
        }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val celula = 12.dp.toPx()
                for (y in 0..(size.height / celula).toInt()) for (x in 0..(size.width / celula).toInt()) if ((x + y) % 2 == 0)
                    drawRect(Color(0xFF393941), Offset(x * celula, y * celula), Size(celula, celula))
            }
            modelo.previa?.let { bitmap ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1 / janela.lado; scaleY = 1 / janela.lado
                    translationX = (.5f - janela.x - janela.lado / 2) * size.width / janela.lado
                    translationY = (.5f - janela.y - janela.lado / 2) * size.height / janela.lado
                }) {
                    Image(bitmap.asImageBitmap(), "Prévia da figurinha", modifier = Modifier.fillMaxSize())
                    if (!pincel) GuiasDoEnquadramento(modelo)
                }
            } ?: Text("Preparando…")
            Canvas(Modifier.fillMaxSize()) {
                if (pincel && !selecionarZoom) cursor?.let { p ->
                    drawCircle(Color.Black, raio * size.width, p, style = Stroke(4.dp.toPx()))
                    drawCircle(if (apagar) Color.White else Color.Green, raio * size.width, p, style = Stroke(2.dp.toPx()))
                    drawLine(Color.White, p - Offset(7.dp.toPx(), 0f), p + Offset(7.dp.toPx(), 0f), 1.dp.toPx())
                    drawLine(Color.White, p - Offset(0f, 7.dp.toPx()), p + Offset(0f, 7.dp.toPx()), 1.dp.toPx())
                }
                inicioCaixa?.let { a -> fimCaixa?.let { b ->
                    drawRect(Color.Cyan, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(2.dp.toPx()))
                } }
            }
            if (janela.lado < 1f) {
                Canvas(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(64.dp).background(Color.Black)) {
                    modelo.previa?.let { drawImage(it.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) }
                    drawRect(Color.Cyan, Offset(janela.x * size.width, janela.y * size.height), Size(janela.lado * size.width, janela.lado * size.height), style = Stroke(2.dp.toPx()))
                }
            }
        }
        if (pincel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AcaoDoEditor(AcaoEditor.ZOOM, "Desenhar caixa de zoom", selecionada = selecionarZoom) { selecionarZoom = !selecionarZoom }
                AcaoDoEditor(AcaoEditor.DESFAZER, "Voltar à caixa de zoom anterior", habilitada = caixas.isNotEmpty()) { janela = caixas.last(); caixas = caixas.dropLast(1) }
                AcaoDoEditor(AcaoEditor.REDEFINIR, "Ver imagem inteira") { janela = JanelaDaPrevia(); caixas = emptyList(); cursor = null }
                Text("${(1 / janela.lado).roundToInt()}×", style = MaterialTheme.typography.labelSmall)
            }
        } else LaunchedEffect(Unit) { janela = JanelaDaPrevia(); caixas = emptyList(); cursor = null }
    }
}
