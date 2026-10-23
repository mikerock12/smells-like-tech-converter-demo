package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val itens=Estudio.segmentos(p).map {Item(it.id,"Principal","clipes",it.inicio,it.fim)}+
        p.camadas.sortedBy {it.ordem}.map {Item(it.id,"Mídia · ${it.ordem}","camadas",it.inicio,it.fim)}+
        p.audios.map {Item(it.id,"Áudio","audio",it.entrada?:0.0,(it.entrada?:0.0)+it.fim-it.inicio)}
    Text("Faixas da composição",style=MaterialTheme.typography.titleSmall)
    itens.forEach {item ->
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text("${item.nome}: ${Estudio.numero(item.inicio)}–${Estudio.numero(item.fim)} s",style=MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {modelo.irPara(item.inicio);selecionar(item.id,item.tipo)}) {
                Box(Modifier.offset(x=maxWidth*(item.inicio/total).toFloat()).width(maxWidth*((item.fim-item.inicio)/total).toFloat()).fillMaxHeight()
                    .background(if(item.tipo=="audio") Color(0xFF27644B) else Color(0xFF95400D)))
                Box(Modifier.offset(x=maxWidth*(modelo.instante/total).toFloat()).width(2.dp).fillMaxHeight().background(Color.White))
            }
        }
    }
}
