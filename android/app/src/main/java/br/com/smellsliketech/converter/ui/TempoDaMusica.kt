package br.com.smellsliketech.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.musica.ClipeMusical
import br.com.smellsliketech.converter.musica.FaixaMusical
import br.com.smellsliketech.converter.musica.Musica
import br.com.smellsliketech.converter.musica.MusicaViewModel
import br.com.smellsliketech.converter.musica.ProjetoMusical
import br.com.smellsliketech.converter.musica.TransformacaoDaMusica

@Composable
fun AbaDeTempoETom(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    val clipes = projeto.faixas.flatMap { f -> f.clipes.filter { !it.mudo }.map { f to it } }
    if (clipes.isEmpty()) {
        Column(Modifier.padding(16.dp)) { Text("Importe ou grave um áudio para ajustar tempo e tom.") }
        return
    }
    var escolhido by rememberSaveable { mutableStateOf<String?>(null) }
    var origem by rememberSaveable { mutableStateOf<String?>(null) }
    var destino by rememberSaveable { mutableStateOf<String?>(null) }
    val atual = clipes.firstOrNull { it.second.id == escolhido } ?: clipes.first()
    val referencia = clipes.firstOrNull { it.second.id == origem } ?: clipes.first()
    val alvo = clipes.firstOrNull { it.second.id == destino } ?: clipes.getOrElse(1) { clipes.first() }
    val (faixa, clipe) = atual
    val midia = projeto.midias.first { it.id == clipe.midia }
    fun mudarClipe(juntar: String? = null, f: (ClipeMusical) -> ClipeMusical) = modelo.mudarFaixa(faixa.id, juntar) { t ->
        t.copy(clipes = t.clipes.map { if (it.id == clipe.id) f(it) else it })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tempo e tom por clipe", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        SeletorDeClipe("Clipe para editar ou analisar", clipes, atual.second.id) { escolhido = it }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Identificar o áudio", fontWeight = FontWeight.Bold)
                Button(onClick = { modelo.analisar(clipe.id) }, enabled = modelo.andamento == null, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Identificar BPM e tonalidade" }) { Text("Identificar BPM e tonalidade") }
                modelo.analises[clipe.id]?.let { r ->
                    Text("${r.bpm?.let { "$it BPM" } ?: "BPM não identificado"} · ${r.tonalidade ?: "Tonalidade não identificada"}", fontWeight = FontWeight.Bold)
                }
                Text("São estimativas feitas no celular. Em músicas com tempo livre ou muitos instrumentos, confira de ouvido.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Mais rápida ou mais lenta", fontWeight = FontWeight.Bold)
                Text("${Musica.arredondar((clipe.velocidade ?: 1.0) * 100)}% · ${"%.2f".format(clipe.duracao.toDouble() / projeto.taxa)} s", fontWeight = FontWeight.Bold)
                AjusteDaMusica("Velocidade de ${clipe.nome ?: faixa.nome}", (clipe.velocidade ?: 1.0) * 100, 25.0, 400.0, 1.0, valorTexto = "${Musica.arredondar((clipe.velocidade ?: 1.0) * 100)}%") { v ->
                    mudarClipe("velocidade-${clipe.id}") { TransformacaoDaMusica.comVelocidade(it, midia, v / 100) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preservar o tom", fontWeight = FontWeight.Bold)
                        Text("Ligado: muda a duração sem mudar a afinação. Desligado: velocidade e tom mudam juntos, como numa fita.", fontSize = 12.sp)
                    }
                    Switch(clipe.preservarTom ?: true, { ligado -> mudarClipe { it.copy(preservarTom = ligado) } }, Modifier.semantics { contentDescription = "Preservar o tom ao mudar a velocidade" })
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. Subir ou descer a tonalidade", fontWeight = FontWeight.Bold)
                val tom = clipe.semitons ?: 0.0
                Text("${if (tom > 0) "+" else ""}${Musica.arredondar(tom)} semitons", fontWeight = FontWeight.Bold)
                AjusteDaMusica("Transposição de ${clipe.nome ?: faixa.nome}", tom, -12.0, 12.0, 1.0, valorTexto = "${if (tom > 0) "+" else ""}${Musica.arredondar(tom)}") { v ->
                    mudarClipe("tom-${clipe.id}") { it.copy(semitons = v) }
                }
                Text("Uma oitava tem 12 semitons. A duração não muda ao transpor.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4. Igualar o ritmo de outra faixa", fontWeight = FontWeight.Bold)
                SeletorDeClipe("Ritmo de referência", clipes, referencia.second.id) { origem = it }
                SeletorDeClipe("Clipe que vai mudar", clipes, alvo.second.id) { destino = it }
                OutlinedButton(onClick = { modelo.analisar(referencia.second.id); modelo.analisar(alvo.second.id) }, enabled = modelo.andamento == null, modifier = Modifier.fillMaxWidth()) { Text("Identificar o BPM dos dois") }
                val bpmOrigem = modelo.analises[referencia.second.id]?.bpm
                val bpmDestino = modelo.analises[alvo.second.id]?.bpm
                Text("Referência: ${bpmOrigem?.let { "$it BPM" } ?: "—"} · destino: ${bpmDestino?.let { "$it BPM" } ?: "—"}", fontSize = 13.sp)
                Button(onClick = { modelo.igualarRitmo(referencia.second.id, alvo.second.id) }, enabled = referencia.second.id != alvo.second.id && bpmOrigem != null && bpmDestino != null, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Aplicar o ritmo da referência" }) { Text("Aplicar o ritmo da referência") }
                Text("O destino é acelerado ou desacelerado com o tom preservado. Você pode escolher qualquer clipe como origem e qualquer outro como destino.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SeletorDeClipe(rotulo: String, clipes: List<Pair<FaixaMusical, ClipeMusical>>, selecionado: String, mudar: (String) -> Unit) {
    var aberto by rememberSaveable { mutableStateOf(false) }
    val atual = clipes.first { it.second.id == selecionado }
    Text(rotulo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { aberto = true }, Modifier.fillMaxWidth().semantics { contentDescription = rotulo }) {
            Text("${atual.first.nome} · ${atual.second.nome ?: "Clipe"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(aberto, { aberto = false }) {
            clipes.forEach { (faixa, clipe) -> DropdownMenuItem(text = { Text("${faixa.nome} · ${clipe.nome ?: "Clipe"}", maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { mudar(clipe.id); aberto = false }) }
        }
    }
}
