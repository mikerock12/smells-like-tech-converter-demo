package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.estudio.*

@Composable
fun FaixasDoEstudio(modelo: EstudioViewModel,selecionar: (String,String)->Unit) {
    val p=modelo.projeto?:return;val total=modelo.total;if(total<=0)return
    data class Item(val id: String,val nome: String,val tipo: String,val inicio: Double,val fim: Double)
    val itens=Estudio.segmentos(p).map {Item(it.id,"${it.indice+1}. ${modelo.infos[p.clipes[it.indice].arquivo]?.nome ?: File(p.clipes[it.indice].arquivo).name}","clipes",it.inicio,it.fim)}+
        p.camadas.sortedBy {it.ordem}.map {Item(it.id,"Camada · ${modelo.infos[it.arquivo]?.nome ?: File(it.arquivo).name}","camadas",it.inicio,it.fim)}+
        p.audios.map {Item(it.id,"Áudio · ${it.openverse?.optString("titulo") ?: modelo.infos[it.arquivo]?.nome ?: "Trilha"}","audio",it.entrada?:0.0,(it.entrada?:0.0)+it.fim-it.inicio)}
    Text("Faixas da composição",style=MaterialTheme.typography.titleSmall)
    Text("Toque para selecionar a mídia e posicionar o cursor. Arraste uma faixa de áudio para movê-la.",style=MaterialTheme.typography.bodySmall)
    itens.forEach {item ->
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text("${item.nome}: ${Estudio.numero(item.inicio)}–${Estudio.numero(item.fim)} s",style=MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (item.tipo == "audio") Modifier.pointerInput(item.id, item.inicio, total) {
                    var deslocamento = 0f
                    var naFaixa = false
                    detectHorizontalDragGestures(
                        onDragStart = { ponto ->
                            deslocamento = 0f
                            naFaixa = ponto.x / size.width * total in item.inicio..item.fim
                        },
                        onDragEnd = {
                            if (naFaixa && size.width > 0) {
                                val entrada = (item.inicio + deslocamento / size.width * total).coerceIn(0.0, maxOf(0.0, total - 1.0 / p.fps))
                                modelo.mudarAudio(item.id) { it.copy(entrada = entrada) }
                                selecionar(item.id, item.tipo)
                            }
                        },
                        onHorizontalDrag = { mudanca, delta -> if (naFaixa) { mudanca.consume(); deslocamento += delta } },
                    )
                } else Modifier)
                .pointerInput(item.id,total) { detectTapGestures { ponto ->
                    modelo.irPara((ponto.x / size.width * total).coerceIn(0.0,total)); selecionar(item.id,item.tipo)
                } }.semantics { onClick("Selecionar ${item.nome}") { modelo.irPara(item.inicio); selecionar(item.id,item.tipo); true } }) {
                Box(Modifier.offset(x=maxWidth*(item.inicio/total).toFloat()).width(maxWidth*((item.fim-item.inicio)/total).toFloat()).fillMaxHeight()
                    .background(if(item.tipo=="audio") Color(0xFF27644B) else Color(0xFF95400D)))
                Box(Modifier.offset(x=maxWidth*(modelo.instante/total).toFloat()).width(2.dp).fillMaxHeight().background(Color.White))
            }
        }
    }
}
