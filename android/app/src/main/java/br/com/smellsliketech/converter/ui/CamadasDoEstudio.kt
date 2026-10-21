package br.com.smellsliketech.converter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.estudio.*
import java.io.File
import java.util.Locale

/** Controles compartilhados por clipes, textos e mídias sobrepostas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlesDeTransformacao(valor: Transformacao?, permitirRecorte: Boolean = true, mudar: (Transformacao) -> Unit) {
    var aberto by rememberSaveable { mutableStateOf(false) }
    val t = valor ?: Transformacao()
    TextButton(onClick = { aberto = !aberto }) { Text(if (aberto) "▾ Transformar e filtrar" else "▸ Transformar e filtrar") }
    if (!aberto) return
    ControleDaCamada("Giro no plano (Z)", t.giro, -360.0..360.0, "°") { mudar(t.copy(giro = it)) }
    ControleDaCamada("Inclinar para frente/trás (X)", t.giroX, -180.0..180.0, "°") { mudar(t.copy(giroX = it)) }
    ControleDaCamada("Inclinar para esquerda/direita (Y)", t.giroY, -180.0..180.0, "°") { mudar(t.copy(giroY = it)) }
    ControleDaCamada("Posição horizontal", t.x * 100, -200.0..300.0, "%") { mudar(t.copy(x = it / 100)) }
    ControleDaCamada("Posição vertical", t.y * 100, -200.0..300.0, "%") { mudar(t.copy(y = it / 100)) }
    ControleDaCamada("Tamanho", t.escala * 100, 5.0..600.0, "%") { mudar(t.copy(escala = it / 100)) }
    ControleDaCamada("Opacidade", t.opacidade * 100, 0.0..100.0, "%") { mudar(t.copy(opacidade = it / 100)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(t.espelharX, { mudar(t.copy(espelharX = !t.espelharX)) }, label = { Text("Espelhar horizontal") })
        FilterChip(t.espelharY, { mudar(t.copy(espelharY = !t.espelharY)) }, label = { Text("Espelhar vertical") })
    }
    Text("Mesclagem")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("normal" to "Normal", "multiplicar" to "Multiplicar", "clarear" to "Clarear", "sobrepor" to "Sobrepor").forEach { (id, nome) ->
            FilterChip(t.mescla == id, { mudar(t.copy(mescla = id)) }, label = { Text(nome) })
        }
    }
    Text("Filtro desta camada")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("original" to "Original", "vivido" to "Vívido", "pb" to "Preto e branco", "sepia" to "Sépia", "vintage" to "Retrô", "frio" to "Frio", "quente" to "Quente", "negativo" to "Negativo").forEach { (id,nome) ->
            FilterChip(t.filtro == id, { mudar(t.copy(filtro = id)) }, label = { Text(nome) })
        }
    }
    ControleDaCamada("Brilho", t.brilho * 100, -100.0..100.0, "%") { mudar(t.copy(brilho = it / 100)) }
    ControleDaCamada("Contraste", t.contraste * 100, -80.0..100.0, "%") { mudar(t.copy(contraste = it / 100)) }
    ControleDaCamada("Saturação", t.saturacao * 100, -100.0..100.0, "%") { mudar(t.copy(saturacao = it / 100)) }
    if (permitirRecorte) {
        FilterChip(t.removerFundo, { mudar(t.copy(removerFundo = !t.removerFundo)) }, label = { Text("Remover o fundo desta mídia") })
        Text("Processado no celular. Vídeos são recortados quadro a quadro e podem demorar.", style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = { mudar(Transformacao()) }) { Text("Redefinir os ajustes desta camada") }
}

@Composable
private fun ControleDaCamada(nome: String, valor: Double, limites: ClosedFloatingPointRange<Double>, unidade: String, mudar: (Double) -> Unit) {
    Text("$nome: ${String.format(Locale.ROOT, "%.1f", valor)}$unidade")
    Slider(valor.toFloat().coerceIn(limites.start.toFloat(), limites.endInclusive.toFloat()),
        { mudar(Math.round(it * 10) / 10.0) }, valueRange = limites.start.toFloat()..limites.endInclusive.toFloat())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PainelDeCamadasDoEstudio(modelo: EstudioViewModel) {
    val p = modelo.projeto ?: return
    var catalogo by remember { mutableStateOf(false) }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) modelo.adicionarCamada(uri) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ importar.launch(arrayOf("image/*", "video/*")) }, enabled = p.clipes.isNotEmpty() && p.camadas.size < 12) { Text("Adicionar mídia por cima") }
        OutlinedButton({ catalogo = true }, enabled = p.clipes.isNotEmpty() && p.camadas.size < 12) { Text("Stickers e GIF stickers") }
        Text("Fundo da composição")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("#00000000" to "Transparente", "#000000" to "Preto", "#FFFFFF" to "Branco", "#FF7A18" to "Laranja", "#7536E8" to "Roxo").forEach { (cor, nome) ->
                FilterChip(p.fundo?.cor == cor && p.fundo.corFinal == null, { modelo.mudarProjeto { it.copy(fundo = FundoDoProjeto(cor)) } }, label = { Text(nome) })
            }
            FilterChip(p.fundo?.corFinal != null, { modelo.mudarProjeto { it.copy(fundo = FundoDoProjeto("#7536E8", "#FF7A18")) } }, label = { Text("Gradiente") })
        }
        p.camadas.sortedBy { it.ordem }.forEach { camada ->
            key(camada.id) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(modelo.infos[camada.arquivo]?.nome ?: File(camada.arquivo).name,
                            Modifier.fillMaxWidth().clickable { modelo.camadaAberta = if (modelo.camadaAberta == camada.id) null else camada.id })
                        Row {
                            Checkbox(camada.visivel, { valor -> modelo.mudarCamada(camada.id) { it.copy(visivel = valor) } })
                            TextButton({ modelo.moverCamada(camada.id, -1) }) { Text("↓") }
                            TextButton({ modelo.moverCamada(camada.id, 1) }) { Text("↑") }
                            TextButton({ modelo.removerCamada(camada.id) }) { Text("Excluir") }
                        }
                        if (modelo.camadaAberta == camada.id) {
                            TextButton({ modelo.mudarCamada(camada.id) { it.copy(ordem = if (it.ordem < 0) 1 else -1) } }) {
                                Text(if (camada.ordem < 0) "Trazer para frente do clipe" else "Usar como fundo")
                            }
                            val total = maxOf(.1, modelo.total)
                            ControleDaCamada("Aparece em", camada.inicio, 0.0..maxOf(0.0, total - .1), " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(inicio = valor, fim = maxOf(valor + .1, it.fim)) } }
                            ControleDaCamada("Termina em", camada.fim, .1..total, " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(fim = maxOf(it.inicio + .1, valor)) } }
                            if (camada.tipo != "imagem") {
                                FilterChip(camada.repetir, { modelo.mudarCamada(camada.id) { it.copy(repetir = !it.repetir) } }, label = { Text("Repetir animação") })
                                modelo.infos[camada.arquivo]?.duracao?.takeIf { it > .1 }?.let { duracao ->
                                    ControleDaCamada("Início no arquivo", camada.deslocamento, 0.0..maxOf(0.0, duracao - .1), " s") { valor -> modelo.mudarCamada(camada.id) { it.copy(deslocamento = valor) } }
                                }
                            }
                            ControlesDeTransformacao(camada.transformacao) { valor -> modelo.mudarCamada(camada.id) { it.copy(transformacao = valor) } }
                        }
                    }
                }
            }
        }
    }
    if (catalogo) SeletorDeStickers("editor", { catalogo = false }) { arquivo ->
        catalogo = false
        modelo.adicionarCamada(Uri.fromFile(arquivo), descartarAoAbrir = true)
    }
}
