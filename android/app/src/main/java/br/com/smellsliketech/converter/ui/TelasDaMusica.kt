@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package br.com.smellsliketech.converter.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.musica.ClipeMusical
import br.com.smellsliketech.converter.musica.DspDaMusica
import br.com.smellsliketech.converter.musica.EfeitoMusical
import br.com.smellsliketech.converter.musica.Envio
import br.com.smellsliketech.converter.musica.FaixaMusical
import br.com.smellsliketech.converter.musica.MasterDaMusica
import br.com.smellsliketech.converter.musica.Musica
import br.com.smellsliketech.converter.musica.MusicaViewModel
import br.com.smellsliketech.converter.musica.NucleoNativo
import br.com.smellsliketech.converter.musica.ProjetoMusical
import br.com.smellsliketech.converter.musica.SeparacaoDaMusica
import java.util.UUID
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.roundToLong

private fun nivel(pico: Float) = ((20 * log10(pico + 1e-9f) + 60) / 60).coerceIn(0f, 1f)

private fun cor(hex: String?): Color = runCatching { Color(android.graphics.Color.parseColor(hex ?: "#FF7A18")) }.getOrDefault(Color(0xFFFF7A18))

private fun novoId(prefixo: String) = "$prefixo-${UUID.randomUUID().toString().replace("-", "").take(14)}"

/**
 * Criação musical no Android: gravar faixas ouvindo as outras, mixer, efeitos, separação por IA
 * e exportação. A licença vale como no resto do app (teste de 7 dias, depois a compra); nada
 * de limite de projetos ou faixas.
 */
@Composable
fun TelaDaMusica(modelo: MusicaViewModel, podeCriar: Boolean, abrirLicenca: () -> Unit, voltar: () -> Unit) {
    val projeto = modelo.projeto
    LaunchedEffect(projeto == null) { if (projeto == null) modelo.recarregar() }
    if (projeto == null) {
        BackHandler(onBack = voltar)
        ListaDeProjetosMusicais(modelo, podeCriar, abrirLicenca, voltar)
        return
    }
    BackHandler { if (modelo.gravando != null) modelo.pararGravacao() else modelo.fechar() }
    var aba by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { modelo.fechar() }, enabled = modelo.gravando == null) { Text("← Projetos") }
                OutlinedTextField(projeto.titulo, { t -> modelo.mudar("titulo") { it.copy(titulo = t.take(80)) } }, Modifier.weight(1f), singleLine = true, label = { Text("Projeto") })
                TextButton(onClick = modelo::desfazer, enabled = modelo.podeDesfazer) { Text("↶") }
                TextButton(onClick = modelo::refazer, enabled = modelo.podeRefazer) { Text("↷") }
            }
            Transporte(modelo, projeto, podeCriar, abrirLicenca)
            modelo.mensagem?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp).pointerInput(Unit) { detectTapGestures { modelo.mensagem = null } }, fontSize = 13.sp) }
            if (modelo.soltas.isNotEmpty()) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${modelo.soltas.size} tomada(s) ficaram gravadas sem entrar no projeto.", Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = modelo::recuperarSoltas) { Text("Recuperar") }
            }
            modelo.andamento?.let { (etapa, fracao) ->
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(etapa, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { fracao.toFloat() }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = modelo::cancelar) { Text("Cancelar") }
                }
            }
            val abas = listOf("Faixas", "Gravar", "Mixer", "Tempo e tom", "FX", "Separar", "Exportar")
            ScrollableTabRow(selectedTabIndex = aba, edgePadding = 8.dp) {
                abas.forEachIndexed { i, rotulo -> Tab(selected = aba == i, onClick = { aba = i }, text = { Text(rotulo) }) }
            }
            Box(Modifier.weight(1f)) {
                when (aba) {
                    0 -> AbaDasFaixas(modelo, projeto) { id -> modelo.alvoDeEfeitos = id; aba = 4 }
                    1 -> AbaDeGravar(modelo, projeto)
                    2 -> AbaDoMixer(modelo, projeto)
                    3 -> AbaDeTempoETom(modelo, projeto)
                    4 -> AbaDeEfeitos(modelo, projeto)
                    5 -> AbaDeSeparar(modelo, projeto)
                    else -> AbaDeExportar(modelo, projeto, podeCriar, abrirLicenca)
                }
            }
        }
    }
    if (modelo.perguntarSaida) AlertDialog(
        onDismissRequest = modelo::cancelarSaida,
        title = { Text("Salvar o projeto?") },
        text = { Text("Suas últimas alterações ainda não foram salvas neste celular.") },
        confirmButton = { TextButton(onClick = modelo::salvarESair) { Text("Salvar e sair") } },
        dismissButton = {
            Row {
                TextButton(onClick = modelo::sairSemSalvar) { Text("Sair sem salvar") }
                TextButton(onClick = modelo::cancelarSaida) { Text("Continuar editando") }
            }
        },
    )
}

@Composable
private fun ListaDeProjetosMusicais(modelo: MusicaViewModel, podeCriar: Boolean, abrirLicenca: () -> Unit, voltar: () -> Unit) {
    val abrirArquivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(modelo::abrirArquivo) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TextButton(onClick = voltar) { Text("← Início") } }
            item {
                Text("CRIAÇÃO MUSICAL", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
                Text("Grave, mixe e separe faixas", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Grave uma faixa ouvindo as outras, importe áudios, separe voz, bateria e baixo por IA, aplique efeitos e exporte. Tudo fica no celular. Use fone com fio ou interface USB: com Bluetooth o atraso varia.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!podeCriar) OutlinedButton(onClick = abrirLicenca) { Text("O teste acabou: ativar a licença") }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Salvar automaticamente", fontWeight = FontWeight.Bold)
                        Text("Desligado: pergunta o que fazer ao sair do projeto.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(modelo.salvarAutomaticamente, modelo::definirSalvamentoAutomatico, Modifier.semantics { contentDescription = "Salvar projetos automaticamente" })
                }
            }
            item { Text("COMEÇAR UM PROJETO", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, letterSpacing = 2.sp) }
            items(Musica.MODELOS) { m ->
                Card(onClick = { if (podeCriar) modelo.criar(m.id) else abrirLicenca() }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Modelo ${m.rotulo}" }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(m.rotulo, fontWeight = FontWeight.Bold)
                        Text(m.faixas.joinToString(", ") + if (m.barramentos.isNotEmpty()) " · barramentos: ${m.barramentos.joinToString(", ")}" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { OutlinedButton(onClick = { abrirArquivo.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Abrir um .sltmusic") } }
            if (modelo.carregandoLista) item { Text("Carregando os projetos salvos…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (modelo.lista.isNotEmpty()) {
                item { Text("PROJETOS NESTE CELULAR", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp, letterSpacing = 2.sp) }
                items(modelo.lista, key = { it.id }) { r ->
                    var menu by remember { mutableStateOf(false) }
                    Card(onClick = { modelo.abrirSalvo(r.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.titulo, fontWeight = FontWeight.Bold)
                                Text("${r.faixas} ${if (r.faixas == 1) "faixa" else "faixas"} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(r.atualizado))}", fontSize = 12.sp)
                            }
                            Box {
                                TextButton(onClick = { menu = true }) { Text("⋯") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Apagar do celular") }, onClick = { menu = false; modelo.biblioteca.apagar(r.id); modelo.recarregar() })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Transporte(modelo: MusicaViewModel, projeto: ProjetoMusical, podeCriar: Boolean, abrirLicenca: () -> Unit) {
    val posicao = Musica.posicao(projeto, modelo.posicao)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { modelo.irPara(0) }, modifier = Modifier.semantics { contentDescription = "Voltar ao início" }) { Text("⏮") }
        Button(onClick = modelo::tocarOuParar, modifier = Modifier.semantics { contentDescription = if (modelo.tocando) "Parar" else "Tocar" }) { Text(if (modelo.tocando) "⏹" else "▶") }
        Button(
            onClick = { if (modelo.gravando != null) modelo.pararGravacao() else if (podeCriar) modelo.gravar() else abrirLicenca() },
            modifier = Modifier.semantics { contentDescription = if (modelo.gravando != null) "Parar a gravação" else "Gravar agora (REC)" },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
        ) { Text(if (modelo.gravando != null) "■ ${"%.1f".format(modelo.segundosGravados)} s" else "● REC") }
        Column {
            Text(posicao.texto, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(Musica.tempo(projeto, modelo.posicao), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        FilterChip(projeto.loop.ligado, { modelo.mudar { p -> p.copy(loop = if (p.loop.fim > p.loop.inicio) p.loop.copy(ligado = !p.loop.ligado) else p.loop.copy(ligado = true, inicio = 0, fim = maxOf(Musica.arredondar(Musica.quadrosPorCompasso(p) * 4), Musica.duracaoEmQuadros(p)))) } }, { Text("⟲ Loop") })
        FilterChip(projeto.metronomo.ligado, { modelo.mudar { p -> p.copy(metronomo = p.metronomo.copy(ligado = !p.metronomo.ligado)) } }, { Text("♩ Metrônomo") })
        OutlinedButton(onClick = { modelo.mudar("bpm") { p -> p.copy(bpm = (p.bpm - 1).coerceAtLeast(Musica.BPM_MINIMO)) } }) { Text("−") }
        Text("${projeto.bpm.roundToLong()} BPM")
        OutlinedButton(onClick = { modelo.mudar("bpm") { p -> p.copy(bpm = (p.bpm + 1).coerceAtMost(Musica.BPM_MAXIMO)) } }) { Text("+") }
    }
}

@Composable
private fun AbaDasFaixas(modelo: MusicaViewModel, projeto: ProjetoMusical, efeitos: (String) -> Unit) {
    var zoom by rememberSaveable { mutableStateOf(40f) }
    var selecionado by rememberSaveable { mutableStateOf<String?>(null) }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(modelo::importar) }
    val rolagem = rememberScrollState()
    val densidade = LocalDensity.current
    val total = maxOf(Musica.duracaoEmQuadros(projeto) + projeto.taxa * 30L, projeto.taxa * 60L)
    val larguraDp = with(densidade) { (total.toFloat() / projeto.taxa * zoom).toDp() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { modelo.mudar { p -> p.copy(faixas = p.faixas + Musica.novaFaixa(novoId("faixa"), "Faixa ${p.faixas.size + 1}", p.faixas.size)) } }) { Text("+ Faixa") }
                OutlinedButton(onClick = { importar.launch(arrayOf("audio/*")) }) { Text("Importar áudio") }
                OutlinedButton(onClick = modelo::salvarArquivo) { Text("Salvar") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (modelo.salvarAutomaticamente) "Salvar automaticamente" else if (modelo.alteracoesPendentes) "Alterações não salvas" else "Perguntar ao sair", Modifier.weight(1f), fontSize = 12.sp)
                Switch(modelo.salvarAutomaticamente, modelo::definirSalvamentoAutomatico, Modifier.semantics { contentDescription = "Salvar este projeto automaticamente" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", fontSize = 12.sp)
                Slider(zoom, { zoom = it }, Modifier.weight(1f), valueRange = 8f..300f)
            }
        }
        items(projeto.faixas.filter { it.oculta != true }, key = { it.id }) { faixa ->
            CartaoDaFaixa(modelo, projeto, faixa, zoom, larguraDp, rolagem, selecionado, efeitos) { selecionado = it }
        }
        projeto.faixas.filter { it.oculta == true }.takeIf { it.isNotEmpty() }?.let { ocultas ->
            item { FlowRow { ocultas.forEach { f -> TextButton(onClick = { modelo.mudarFaixa(f.id) { it.copy(oculta = false) } }) { Text("Mostrar ${f.nome}") } } } }
        }
        selecionado?.let { id ->
            val par = projeto.faixas.firstNotNullOfOrNull { f -> f.clipes.firstOrNull { it.id == id }?.let { f to it } }
            if (par != null) item { EditorDoClipe(modelo, projeto, par.first, par.second) { selecionado = null } }
        }
    }
}

@Composable
private fun CartaoDaFaixa(modelo: MusicaViewModel, projeto: ProjetoMusical, faixa: FaixaMusical, zoom: Float, larguraDp: androidx.compose.ui.unit.Dp, rolagem: androidx.compose.foundation.ScrollState, selecionado: String?, efeitos: (String) -> Unit, selecionar: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var renomear by remember { mutableStateOf(false) }
    if (renomear) {
        var nome by remember { mutableStateOf(faixa.nome) }
        AlertDialog(onDismissRequest = { renomear = false }, title = { Text("Renomear faixa") },
            text = { OutlinedTextField(nome, { nome = it.take(60) }, singleLine = true, label = { Text("Nome da faixa") }) },
            confirmButton = { TextButton(onClick = { modelo.mudarFaixa(faixa.id) { it.copy(nome = nome.trim()) }; renomear = false }, enabled = nome.isNotBlank()) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renomear = false }) { Text("Cancelar") } })
    }
    val alternativas = faixa.clipes.mapNotNull { clipe ->
        val atual = projeto.midias.firstOrNull { it.id == clipe.midia }
        val restaurada = atual?.restauracao != null
        val destino = if (restaurada) atual?.origem else projeto.midias.firstOrNull { it.origem == atual?.id && it.restauracao != null }?.id
        destino?.let { Triple(clipe, it, restaurada) }
    }
    val corDaFaixa = cor(faixa.cor)
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Faixa ${faixa.nome}" }) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(6.dp).height(28.dp).background(corDaFaixa))
                OutlinedTextField(faixa.nome, { t -> modelo.mudarFaixa(faixa.id, "nome-${faixa.id}") { it.copy(nome = t.take(60)) } }, Modifier.weight(1f).padding(start = 6.dp), singleLine = true)
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Mais ações de ${faixa.nome}" }) { Text("⋯") }
                    DropdownMenu(menu, { menu = false }) {
                        for ((clipe, destino, restaurada) in alternativas) {
                            val sufixo = if (alternativas.size > 1) " · ${clipe.nome ?: "Clipe"}" else ""
                            DropdownMenuItem(text = { Text("Alternar para versão ${if (restaurada) "bruta" else "restaurada"}$sufixo") }, onClick = {
                                menu = false
                                modelo.mudarFaixa(faixa.id) { f -> f.copy(clipes = f.clipes.map { if (it.id == clipe.id) it.copy(midia = destino) else it }) }
                            })
                        }
                        DropdownMenuItem(text = { Text(if (faixa.mudo) "Desmutar faixa" else "Mutar faixa") }, onClick = { menu = false; modelo.mudarFaixa(faixa.id) { it.copy(mudo = !it.mudo) } })
                        DropdownMenuItem(text = { Text(if (faixa.solo) "Desativar solo" else "Ouvir em solo") }, onClick = { menu = false; modelo.mudarFaixa(faixa.id) { it.copy(solo = !it.solo) } })
                        DropdownMenuItem(text = { Text("Renomear faixa") }, onClick = { menu = false; renomear = true })
                        DropdownMenuItem(text = { Text("Filtros e efeitos") }, onClick = { menu = false; efeitos(faixa.id) })
                        DropdownMenuItem(text = { Text("Propriedades do clipe") }, enabled = faixa.clipes.isNotEmpty(), onClick = {
                            menu = false
                            selecionar(faixa.clipes.firstOrNull { it.id == selecionado }?.id ?: faixa.clipes.firstOrNull()?.id)
                        })
                        DropdownMenuItem(text = { Text("Duplicar") }, onClick = { menu = false; modelo.mudar { p -> val i = p.faixas.indexOf(faixa); p.copy(faixas = p.faixas.toMutableList().apply { add(i + 1, faixa.copy(id = novoId("faixa"), nome = "${faixa.nome} (cópia)", gravar = false, solo = false, clipes = faixa.clipes.map { it.copy(id = novoId("clipe")) })) }) } })
                        DropdownMenuItem(text = { Text("Mover para cima") }, onClick = { menu = false; modelo.mudar { p -> val i = p.faixas.indexOf(faixa); if (i <= 0) p else p.copy(faixas = p.faixas.toMutableList().apply { add(i - 1, removeAt(i)) }) } })
                        DropdownMenuItem(text = { Text("Mover para baixo") }, onClick = { menu = false; modelo.mudar { p -> val i = p.faixas.indexOf(faixa); if (i >= p.faixas.size - 1) p else p.copy(faixas = p.faixas.toMutableList().apply { add(i + 1, removeAt(i)) }) } })
                        DropdownMenuItem(text = { Text("Criar barramento para ela") }, onClick = { menu = false; modelo.mudar { p -> val bus = br.com.smellsliketech.converter.musica.Barramento(novoId("bus"), "Bus ${faixa.nome}"); p.copy(barramentos = p.barramentos + bus, faixas = p.faixas.map { if (it.id == faixa.id) it.copy(saida = bus.id) else it }) } })
                        DropdownMenuItem(text = { Text("Ocultar") }, onClick = { menu = false; modelo.mudarFaixa(faixa.id) { it.copy(oculta = true) } })
                        DropdownMenuItem(text = { Text("Remover (Desfazer volta)") }, onClick = { menu = false; modelo.mudar { p -> p.copy(faixas = p.faixas.filter { it.id != faixa.id }) } })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(faixa.gravar, { modelo.mudar { p -> p.copy(faixas = p.faixas.map { it.copy(gravar = if (it.id == faixa.id) !it.gravar else false) }) } }, { Text("REC") }, Modifier.semantics { contentDescription = "Armar ${faixa.nome}" })
                FilterChip(faixa.monitorar, { modelo.mudarFaixa(faixa.id) { it.copy(monitorar = !it.monitorar) } }, { Text("MON") })
                FilterChip(faixa.solo, { modelo.mudarFaixa(faixa.id) { it.copy(solo = !it.solo) } }, { Text("S") })
                FilterChip(faixa.mudo, { modelo.mudarFaixa(faixa.id) { it.copy(mudo = !it.mudo) } }, { Text("M") })
                val m = modelo.medidores[faixa.id]
                LinearProgressIndicator(progress = { if (m == null) 0f else nivel(maxOf(m[0], m[1])) }, modifier = Modifier.weight(1f))
            }
            AjusteDaMusica("Volume de ${faixa.nome}", Musica.volumeParaPercentual(faixa.ganhoDb).toDouble(), 0.0, 100.0, 1.0, rotulo = "Vol", valorTexto = "${Musica.volumeParaPercentual(faixa.ganhoDb)}") { v -> modelo.mudarFaixa(faixa.id, "vol-${faixa.id}") { it.copy(ganhoDb = Musica.percentualParaVolume(v)) } }
            AjusteDaMusica("Pan de ${faixa.nome}", faixa.pan, -100.0, 100.0, Musica.PASSO_PAN, rotulo = "Pan", valorTexto = Musica.textoDoPan(faixa.pan)) { v -> modelo.mudarFaixa(faixa.id, "pan-${faixa.id}") { it.copy(pan = v) } }
            Box(Modifier.fillMaxWidth().height(64.dp).horizontalScroll(rolagem)) {
                Pista(modelo, projeto, faixa, zoom, larguraDp, corDaFaixa, selecionado, selecionar)
            }
        }
    }
}

@Composable
private fun Pista(modelo: MusicaViewModel, projeto: ProjetoMusical, faixa: FaixaMusical, zoom: Float, larguraDp: androidx.compose.ui.unit.Dp, corDaFaixa: Color, selecionado: String?, selecionar: (String?) -> Unit) {
    val taxa = projeto.taxa
    val picos = modelo.picos
    Canvas(
        Modifier.width(larguraDp).height(64.dp).background(Color(0x14FFFFFF), RoundedCornerShape(6.dp))
            .pointerInput(faixa.clipes, zoom) {
                detectTapGestures { ponto ->
                    val quadro = (ponto.x / zoom * taxa).roundToLong()
                    val clipe = faixa.clipes.lastOrNull { quadro in it.inicio until it.fim }
                    if (clipe != null) selecionar(clipe.id) else modelo.irPara(quadro)
                }
            }
            .pointerInput(faixa.clipes, zoom, selecionado) {
                var arrasto = 0f
                detectHorizontalDragGestures(onDragStart = { arrasto = 0f }, onDragEnd = {
                    val id = selecionado ?: return@detectHorizontalDragGestures
                    val delta = (arrasto / zoom * taxa).roundToLong()
                    modelo.mudarFaixa(faixa.id, "mover-$id") { f -> f.copy(clipes = f.clipes.map { if (it.id == id) it.copy(inicio = maxOf(0L, it.inicio + delta)) else it }) }
                }) { _, d -> arrasto += d }
            },
    ) {
        for (clipe in faixa.clipes) {
            val x = clipe.inicio.toFloat() / taxa * zoom
            val w = maxOf(2f, clipe.duracao.toFloat() / taxa * zoom)
            drawRect(corDaFaixa.copy(alpha = if (clipe.mudo) 0.12f else 0.25f), Offset(x, 0f), Size(w, size.height))
            val p = picos[clipe.midia]
            if (p != null) {
                val colunas = p.size / 2
                var px = 0f
                while (px < w) {
                    val k = ((clipe.inicioNaMidia + px / w * clipe.duracao * (clipe.velocidade ?: 1.0)) / 1024).toInt().coerceIn(0, colunas - 1)
                    val y0 = (1 - p[k * 2 + 1]) / 2 * size.height
                    val y1 = (1 - p[k * 2]) / 2 * size.height
                    drawLine(corDaFaixa.copy(alpha = if (clipe.mudo) 0.4f else 1f), Offset(x + px, y0), Offset(x + px, y1), 1.5f)
                    px += 2f
                }
            }
            if (clipe.id == selecionado) drawRect(Color.White, Offset(x, 0f), Size(w, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        val cursor = modelo.posicao.toFloat() / taxa * zoom
        drawLine(Color(0xFFFF7A18), Offset(cursor, 0f), Offset(cursor, size.height), 3f)
    }
}

@Composable
private fun EditorDoClipe(modelo: MusicaViewModel, projeto: ProjetoMusical, faixa: FaixaMusical, clipe: ClipeMusical, fechar: () -> Unit) {
    fun mudarClipe(juntar: String? = null, f: (ClipeMusical) -> ClipeMusical) = modelo.mudarFaixa(faixa.id, juntar) { t -> t.copy(clipes = t.clipes.map { if (it.id == clipe.id) f(it) else it }) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${clipe.nome ?: "Clipe"} em ${"%.3f".format(clipe.inicio.toDouble() / projeto.taxa)} s", fontWeight = FontWeight.Bold)
            // Ajuste fino da posição: para encaixar de ouvido uma tomada já gravada.
            Text("Posição fina (adiantar ou atrasar o clipe):", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(-10, -1, 1, 10).forEach { ms ->
                    OutlinedButton(onClick = {
                        val d = Musica.arredondar(ms * projeto.taxa / 1000.0)
                        mudarClipe("posicao-fina") { c ->
                            val novo = c.inicio + d
                            if (novo >= 0) c.copy(inicio = novo)
                            else { val corte = minOf(-novo, c.duracao - 1); c.copy(inicio = 0, inicioNaMidia = c.inicioNaMidia + Musica.arredondar(corte * (c.velocidade ?: 1.0)), duracao = c.duracao - corte) }
                        }
                    }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (ms > 0) "+$ms ms" else "$ms ms") }
                }
            }
            Text("Ganho: ${"%.1f".format(clipe.ganhoDb)} dB", fontSize = 12.sp)
            AjusteDaMusica("Ganho do clipe", clipe.ganhoDb, -24.0, 12.0, Musica.PASSO_GANHO_DB) { v -> mudarClipe("ganho") { it.copy(ganhoDb = v) } }
            Text("Entrada suave: ${clipe.entradaSuave * 1000 / projeto.taxa} ms · saída suave: ${clipe.saidaSuave * 1000 / projeto.taxa} ms", fontSize = 12.sp)
            val passoSuave = projeto.taxa * Musica.PASSO_SUAVE_SEGUNDOS
            val maximoSuave = minOf(clipe.duracao.toDouble(), projeto.taxa * 5.0)
            AjusteDaMusica("Entrada suave", clipe.entradaSuave.toDouble(), 0.0, maximoSuave, passoSuave) { v -> mudarClipe("fadein") { it.copy(entradaSuave = minOf(v.roundToLong(), it.duracao - it.saidaSuave)) } }
            AjusteDaMusica("Saída suave", clipe.saidaSuave.toDouble(), 0.0, maximoSuave, passoSuave) { v -> mudarClipe("fadeout") { it.copy(saidaSuave = minOf(v.roundToLong(), it.duracao - it.entradaSuave)) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(!clipe.mudo, { a -> mudarClipe { it.copy(mudo = !a) } }); Text("Tomada ativa") }
            OutlinedButton(onClick = { modelo.reduzirRuido(faixa.id, clipe.id) }, enabled = modelo.andamento == null) { Text("Reduzir ruído deste clipe") }
            Text("Para gravações com chiado, ventilador ou ar-condicionado ao fundo: aprende o ruído nos trechos mais silenciosos e o tira do clipe todo. Numa música já mixada quase nunca ajuda. A original fica guardada (Desfazer volta).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val corte = modelo.posicao
                    if (corte > clipe.inicio && corte < clipe.fim) modelo.mudarFaixa(faixa.id) { t ->
                        val esq = corte - clipe.inicio
                        t.copy(clipes = t.clipes.flatMap { if (it.id != clipe.id) listOf(it) else listOf(it.copy(duracao = esq, saidaSuave = 0, entradaSuave = minOf(it.entradaSuave, esq)), it.copy(id = novoId("clipe"), inicio = corte, inicioNaMidia = it.inicioNaMidia + Musica.arredondar(esq * (it.velocidade ?: 1.0)), duracao = it.duracao - esq, entradaSuave = 0, saidaSuave = minOf(it.saidaSuave, it.duracao - esq))) })
                    }
                }) { Text("Dividir no cursor") }
                OutlinedButton(onClick = { modelo.mudarFaixa(faixa.id) { t -> t.copy(clipes = t.clipes.filter { it.id != clipe.id }) }; fechar() }) { Text("Remover clipe") }
            }
            Text("A mídia original nunca muda: cortar, mover e remover mexem só no clipe.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AbaDeGravar(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    val contexto = LocalContext.current
    var canais by rememberSaveable { mutableIntStateOf(1) }
    val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) modelo.abrirEntrada(canais) else modelo.mensagem = "Sem a permissão do microfone não dá para gravar. Ela pode ser dada em Configurações → Apps."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Entrada", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(canais == 1, { canais = 1 }, { Text("Mono") })
            FilterChip(canais == 2, { canais = 2 }, { Text("Estéreo (interface USB)") })
        }
        Button(onClick = {
            if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) modelo.abrirEntrada(canais) else permissao.launch(Manifest.permission.RECORD_AUDIO)
        }, modifier = Modifier.semantics { contentDescription = "Usar o microfone" }) { Text(if (modelo.entrada == null) "Usar o microfone" else "Trocar a entrada") }
        modelo.entrada?.let { e ->
            Text("${e.nome} · ${if (e.canais == 1) "mono" else "${e.canais} canais"} · ${if (e.semProcessamento) "sem processamento de voz" else "com o processamento padrão do Android"}", fontSize = 13.sp)
            e.avisos.forEach { Text("⚠ $it", fontSize = 12.sp) }
            OutlinedButton(onClick = modelo::fecharEntrada) { Text("Fechar o microfone") }
        }
        Text("Use fone com fio ou interface de áudio USB. Com Bluetooth o atraso varia. Sem fone, deixe o MON desligado para não dar microfonia. A gravação é sempre seca e continua com a tela apagada (notificação de microfone em uso).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Alinhamento", fontWeight = FontWeight.Bold)
        // A saída muda quando a pessoa conecta ou tira o fone: lê de novo enquanto a aba está aberta.
        LaunchedEffect(Unit) { while (true) { if (modelo.gravando == null && !modelo.calibrando) modelo.atualizarRota(); delay(2000) } }
        val rota = modelo.rota
        Text("Saída agora: ${rota?.nome ?: "—"}", fontSize = 13.sp)
        Text(modelo.atrasoDaRotaMs?.let { "Calibrada: as tomadas por esta saída voltam ${"%.1f".format(it)} ms para ficar no tempo do que você ouve." }
            ?: "Ainda não calibrada. Os relógios do Android alinham quase tudo, mas o fone (principalmente Bluetooth) e o próprio aparelho deixam alguns milissegundos de atraso: calibre uma vez para cada fone.", fontSize = 13.sp)
        Text("Calibrar: toque em Calibrar esta saída e, com fone, bata palmas forte junto com cada um dos 12 cliques. Sem fone, deixe o celular tocar os cliques perto do microfone, em silêncio.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = modelo::calibrar, enabled = modelo.entrada != null && !modelo.calibrando && modelo.gravando == null) { Text(if (modelo.calibrando) "Calibrando…" else "Calibrar esta saída") }
            if (modelo.atrasoDaRotaMs != null) TextButton(onClick = modelo::esquecerCalibracao) { Text("Esquecer") }
        }
        Text("Correção manual deste projeto (soma à calibração):", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { modelo.mudar("ajuste") { p -> p.copy(latencia = p.latencia.copy(ajuste = (p.latencia.ajuste ?: 0.0) - p.taxa / 1000.0)) } }) { Text("−1 ms") }
            OutlinedButton(onClick = { modelo.mudar("ajuste") { p -> p.copy(latencia = p.latencia.copy(ajuste = (p.latencia.ajuste ?: 0.0) + p.taxa / 1000.0)) } }) { Text("+1 ms") }
        }
        Text("Correção manual: ${"%.0f".format((projeto.latencia.ajuste ?: 0.0) * 1000 / projeto.taxa)} ms", fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            var monitor by remember { mutableStateOf(false) }
            Checkbox(monitor, { monitor = it; modelo.monitorarParado(it) }); Text("Ouvir a entrada com o transporte parado")
        }
        Text("Contagem e punch", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 1, 2).forEach { c -> FilterChip(projeto.contagem.toInt() == c, { modelo.mudar { p -> p.copy(contagem = c.toDouble()) } }, { Text(if (c == 0) "Sem contagem" else "$c compasso${if (c > 1) "s" else ""}") }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(projeto.punch.ligado, { l -> modelo.mudar { p -> p.copy(punch = p.punch.copy(ligado = l && p.loop.fim > p.loop.inicio, entrada = p.loop.inicio, saida = p.loop.fim)) } }, enabled = projeto.loop.fim > projeto.loop.inicio)
            Text("Gravar só dentro da região do loop (punch)")
        }
        Text("Volume do metrônomo: ${projeto.metronomo.volumeDb.roundToLong()} dB", fontSize = 12.sp)
        AjusteDaMusica("Volume do metrônomo", projeto.metronomo.volumeDb, -40.0, 6.0, Musica.PASSO_METRONOMO_DB) { v -> modelo.mudar("metro") { p -> p.copy(metronomo = p.metronomo.copy(volumeDb = v)) } }
    }
}

@Composable
private fun AbaDoMixer(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    LazyRow(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(projeto.faixas.filter { it.oculta != true }, key = { it.id }) { f ->
            Canal(f.nome, cor(f.cor), f.ganhoDb, f.pan, modelo.medidores[f.id], { v -> modelo.mudarFaixa(f.id, "vol-${f.id}") { it.copy(ganhoDb = v) } }, { v -> modelo.mudarFaixa(f.id, "pan-${f.id}") { it.copy(pan = v) } }) {
                Row { FilterChip(f.solo, { modelo.mudarFaixa(f.id) { it.copy(solo = !it.solo) } }, { Text("S") }); FilterChip(f.mudo, { modelo.mudarFaixa(f.id) { it.copy(mudo = !it.mudo) } }, { Text("M") }) }
                Text("L ${"%.1f".format(f.ajusteEsquerdoDb)} dB", fontSize = 11.sp)
                AjusteDaMusica("Ajuste do canal esquerdo de ${f.nome}", f.ajusteEsquerdoDb, Musica.AJUSTE_MINIMO_DB, Musica.AJUSTE_MAXIMO_DB, Musica.PASSO_AJUSTE_DB) { v -> modelo.mudarFaixa(f.id, "l-${f.id}") { it.copy(ajusteEsquerdoDb = v) } }
                Text("R ${"%.1f".format(f.ajusteDireitoDb)} dB", fontSize = 11.sp)
                AjusteDaMusica("Ajuste do canal direito de ${f.nome}", f.ajusteDireitoDb, Musica.AJUSTE_MINIMO_DB, Musica.AJUSTE_MAXIMO_DB, Musica.PASSO_AJUSTE_DB) { v -> modelo.mudarFaixa(f.id, "r-${f.id}") { it.copy(ajusteDireitoDb = v) } }
                projeto.barramentos.forEach { b ->
                    val envio = f.envios.firstOrNull { it.barramento == b.id }
                    Text("→ ${b.nome}: ${envio?.let { "${it.nivelDb.roundToLong()} dB" } ?: "desligado"}", fontSize = 11.sp)
                    AjusteDaMusica("Envio de ${f.nome} para ${b.nome}", envio?.nivelDb ?: -61.0, -61.0, 6.0, Musica.PASSO_ENVIO_DB) { v ->
                        modelo.mudarFaixa(f.id, "envio-${f.id}-${b.id}") { t ->
                            t.copy(envios = if (v <= -61.0) t.envios.filter { it.barramento != b.id } else if (envio == null) t.envios + Envio(b.id, v) else t.envios.map { if (it.barramento == b.id) it.copy(nivelDb = v) else it })
                        }
                    }
                }
            }
        }
        items(projeto.barramentos, key = { it.id }) { b ->
            Canal(b.nome, Color(0xFF00ACC1), b.ganhoDb, b.pan, modelo.medidores[b.id], { v -> modelo.mudar("vol-${b.id}") { p -> p.copy(barramentos = p.barramentos.map { if (it.id == b.id) it.copy(ganhoDb = v) else it }) } }, { v -> modelo.mudar("pan-${b.id}") { p -> p.copy(barramentos = p.barramentos.map { if (it.id == b.id) it.copy(pan = v) else it }) } }) {
                FilterChip(b.mudo, { modelo.mudar { p -> p.copy(barramentos = p.barramentos.map { if (it.id == b.id) it.copy(mudo = !it.mudo) else it }) } }, { Text("M") })
            }
        }
        item {
            Canal("Master", Color(0xFFFFC107), projeto.master.ganhoDb, 0.0, modelo.medidores["master"], { v -> modelo.mudar("vol-master") { p -> p.copy(master = p.master.copy(ganhoDb = v)) } }, null) {
                OutlinedButton(onClick = { modelo.mudar { p -> p.copy(barramentos = p.barramentos + br.com.smellsliketech.converter.musica.Barramento(novoId("bus"), "Bus ${p.barramentos.size + 1}")) } }) { Text("+ Barramento") }
            }
        }
    }
}

@Composable
private fun Canal(nome: String, corDoCanal: Color, ganhoDb: Double, pan: Double, medidor: FloatArray?, volume: (Double) -> Unit, mudarPan: ((Double) -> Unit)?, extra: @Composable () -> Unit) {
    Card(Modifier.width(210.dp).border(2.dp, corDoCanal, RoundedCornerShape(12.dp)).semantics { contentDescription = "Canal $nome" }) {
        Column(Modifier.padding(8.dp)) {
            Text(nome, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.height(8.dp).fillMaxWidth()) {
                Box(Modifier.weight(nivel(medidor?.get(0) ?: 0f).coerceAtLeast(0.001f)).height(8.dp).background(Color(0xFF43A047)))
                Spacer(Modifier.weight((1 - nivel(medidor?.get(0) ?: 0f)).coerceAtLeast(0.001f)))
            }
            Text("Volume ${Musica.volumeParaPercentual(ganhoDb)}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            AjusteDaMusica("Volume de $nome", Musica.volumeParaPercentual(ganhoDb).toDouble(), 0.0, 100.0, 1.0) { volume(Musica.percentualParaVolume(it)) }
            if (mudarPan != null) {
                Text("Pan ${Musica.textoDoPan(pan)}", fontSize = 12.sp)
                AjusteDaMusica("Pan de $nome", pan, -100.0, 100.0, Musica.PASSO_PAN) { mudarPan(it) }
            }
            extra()
        }
    }
}

@Composable
private fun AbaDeEfeitos(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    var alvo by modelo::alvoDeEfeitos
    val alvos = projeto.faixas.map { it.id to "Faixa: ${it.nome}" } + projeto.barramentos.map { it.id to "Barramento: ${it.nome}" } + ("master" to "Master")
    fun cadeia(p: ProjetoMusical) = if (alvo == "master") p.master.efeitos else p.faixas.firstOrNull { it.id == alvo }?.efeitos ?: p.barramentos.firstOrNull { it.id == alvo }?.efeitos ?: emptyList()
    fun trocar(juntar: String? = null, f: (List<EfeitoMusical>) -> List<EfeitoMusical>) = modelo.mudar(juntar) { p ->
        if (alvo == "master") p.copy(master = p.master.copy(efeitos = f(p.master.efeitos)))
        else p.copy(faixas = p.faixas.map { if (it.id == alvo) it.copy(efeitos = f(it.efeitos)) else it }, barramentos = p.barramentos.map { if (it.id == alvo) it.copy(efeitos = f(it.efeitos)) else it })
    }
    var menuAlvo by remember { mutableStateOf(false) }
    var menuNovo by remember { mutableStateOf(false) }
    var verCadeias by rememberSaveable { mutableStateOf(false) }
    var verPlugins by rememberSaveable { mutableStateOf(false) }
    val contexto = LocalContext.current
    val navegador = LocalUriHandler.current
    val catalogo = remember(contexto) {
        contexto.assets.open("efeitos.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // O alvo ocupa a linha toda e corta nomes longos; o botão de novo efeito fica sempre visível embaixo.
        Text("Efeitos de", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { menuAlvo = true }, Modifier.fillMaxWidth().semantics { contentDescription = "Escolher onde ficam os efeitos" }) {
                Text(alvos.firstOrNull { it.first == alvo }?.second ?: "Master", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(menuAlvo, { menuAlvo = false }) { alvos.forEach { (id, rotulo) -> DropdownMenuItem(text = { Text(rotulo, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { alvo = id; menuAlvo = false }) } }
        }
        Box(Modifier.fillMaxWidth()) {
            Button(onClick = { menuNovo = true }, Modifier.fillMaxWidth()) { Text("+ Efeito") }
            DropdownMenu(menuNovo, { menuNovo = false }) {
                DspDaMusica.CATALOGO.forEach { d -> DropdownMenuItem(text = { Text("${d.grupo}: ${d.rotulo}") }, onClick = { menuNovo = false; trocar { it + DspDaMusica.novoEfeito(d.tipo, novoId("fx")) } }) }
            }
        }
        OutlinedButton(onClick = { verCadeias = !verCadeias }) { Text("Cadeias prontas") }
        if (verCadeias) {
            val cadeias = catalogo.getJSONArray("cadeias")
            var grupoAnterior = ""
            repeat(cadeias.length()) { i ->
                val preset = cadeias.getJSONObject(i)
                val grupo = preset.getString("grupo")
                if (grupoAnterior != grupo) {
                    grupoAnterior = grupo
                    Text(grupo, fontWeight = FontWeight.SemiBold)
                    if (grupo == "Texturas de instrumento") {
                        Text("Use em solos de guitarra. Ajuste a transposição no clipe para escolher o registro.", fontSize = 13.sp)
                    }
                }
                TextButton(onClick = {
                    val etapas = preset.getJSONArray("efeitos")
                    trocar { lista -> lista + List(etapas.length()) { j ->
                        val etapa = etapas.getJSONObject(j)
                        val tipo = etapa.getString("tipo")
                        val efeito = DspDaMusica.novoEfeito(tipo, novoId("fx"))
                        DspDaMusica.definicao(tipo)!!.parametros.fold(efeito) { atual, parametro ->
                            if (etapa.has(parametro.id)) atual.com(parametro.id, etapa.getDouble(parametro.id)) else atual
                        }
                    } }
                }) { Text(preset.getString("nome")) }
            }
        }
        OutlinedButton(onClick = { verPlugins = !verPlugins }) { Text("Plugins VST3 para Windows") }
        if (verPlugins) {
            Text("Plugins externos exigem um host VST3 no Windows. As cadeias prontas funcionam neste estúdio.", fontSize = 13.sp)
            val plugins = catalogo.getJSONArray("plugins")
            repeat(plugins.length()) { i ->
                val plugin = plugins.getJSONObject(i)
                TextButton(onClick = { navegador.openUri(plugin.getString("url")) }) {
                    Text(plugin.getString("nome"))
                }
                Text("${plugin.getString("grupo")}. ${plugin.getString("uso")}", fontSize = 13.sp)
            }
        }
        val efeitos = cadeia(projeto)
        if (efeitos.isEmpty()) Text("Nenhum efeito aqui. A ordem conta: o sinal passa de cima para baixo. Os efeitos não têm atraso.", fontSize = 13.sp)
        efeitos.forEachIndexed { i, e ->
            val d = DspDaMusica.definicao(e.tipo) ?: return@forEachIndexed
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(e.estaLigado, { l -> trocar { lista -> lista.map { if (it.id == e.id) it.copy(ligado = l) else it } } })
                        Text(d.rotulo, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { trocar { l -> l.toMutableList().apply { if (i > 0) add(i - 1, removeAt(i)) } } }, enabled = i > 0) { Text("↑") }
                        TextButton(onClick = { trocar { l -> l.toMutableList().apply { if (i < size - 1) add(i + 1, removeAt(i)) } } }, enabled = i < efeitos.size - 1) { Text("↓") }
                        TextButton(onClick = { trocar { l -> l.filter { it.id != e.id } } }) { Text("✕") }
                    }
                    d.parametros.forEach { par ->
                        val v = DspDaMusica.parametro(e, par.id)
                        Text("${par.rotulo}: ${"%.2f".format(v)} ${par.unidade}", fontSize = 12.sp)
                        val passo = Musica.passoDoParametro(par.min, par.max, par.unidade)
                        AjusteDaMusica("${d.rotulo}: ${par.rotulo}", v, par.min, par.max, passo.passo, passo.relativo) { nv -> trocar("${e.id}-${par.id}") { l -> l.map { if (it.id == e.id) it.com(par.id, nv) else it } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AbaDeSeparar(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    val clipes = projeto.faixas.flatMap { f -> f.clipes.filter { !it.mudo }.map { f to it } }
    var escolhido by rememberSaveable { mutableStateOf<String?>(null) }
    var modo by rememberSaveable { mutableStateOf("4") }
    var limpar by rememberSaveable { mutableStateOf(false) }
    var refinada by rememberSaveable { mutableStateOf(false) }
    var intensidade by rememberSaveable { mutableIntStateOf(0) }
    var roformer by rememberSaveable { mutableStateOf(false) }
    val temRoFormer = br.com.smellsliketech.converter.musica.RoFormer.disponivel
    val premium = roformer && temRoFormer
    val baixado = remember(premium, modelo.revisaoDosModelos) { modelo.biblioteca.modeloPronto(premium) }
    var confirmarExclusao by remember { mutableStateOf(false) }
    if (confirmarExclusao) AlertDialog(
        onDismissRequest = { confirmarExclusao = false },
        title = { Text("Excluir modelo baixado?") },
        text = { Text("As faixas já criadas ficam no projeto. Você poderá baixar o modelo de novo.") },
        confirmButton = { TextButton(onClick = { confirmarExclusao = false; modelo.alterarModelo(premium, true) }) { Text("Excluir") } },
        dismissButton = { TextButton(onClick = { confirmarExclusao = false }) { Text("Cancelar") } },
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Separar faixas por IA", fontWeight = FontWeight.Bold)
        Text("Modelo de separação", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!premium, { roformer = false }, { Text("Spleeter · padrão") }, enabled = modelo.andamento == null)
            FilterChip(premium, { roformer = true }, { Text("RoFormer · experimental") }, enabled = modelo.andamento == null && temRoFormer)
        }
        Text(if (premium) "Mega 53 INT8 · 97 MB no disco. Separa guitarra e piano, além de voz, bateria, baixo e resíduo. Pode melhorar voz e baixo. A qualidade da guitarra varia. No PC, 8 s de áudio levaram cerca de 2 min e atingiram 2,9 GiB de RAM. No celular, tempo e memória ainda variam por aparelho. Comece por um trecho curto."
            else "78 MB no disco. Mais leve. Separa voz e instrumental ou quatro faixas, com guitarra e piano em Outros. No PC, 8 s levaram cerca de 5 s e atingiram 0,5 GiB de RAM. A execução no celular pode demorar mais.", fontSize = 12.sp)
        if (!temRoFormer) Text("O RoFormer exige o núcleo nativo e um processo Android de 64 bits.", fontSize = 12.sp)
        Text(if (baixado) "Baixado neste celular" else "Disponível para baixar", fontSize = 12.sp)
        OutlinedButton(onClick = { if (baixado) confirmarExclusao = true else modelo.alterarModelo(premium, false) }, enabled = modelo.andamento == null) {
            Text(if (baixado) "Excluir modelo baixado" else "Baixar modelo")
        }
        if (clipes.isEmpty()) { Text("Importe ou grave um áudio primeiro."); return@Column }
        val (faixa, clipe) = clipes.firstOrNull { it.second.id == escolhido } ?: clipes.first()
        Text("1. Escolha o áudio", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        // Uma linha por clipe: a faixa em cima, o clipe e a duração embaixo; nomes longos são cortados.
        clipes.forEach { (f, c) ->
            val marcado = c.id == clipe.id
            Card(
                Modifier.fillMaxWidth().clickable { escolhido = c.id }.semantics { contentDescription = "Áudio ${f.nome}" },
                border = if (marcado) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(marcado, { escolhido = c.id })
                    Column(Modifier.weight(1f)) {
                        Text(f.nome, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.nome ?: "Clipe"} · ${"%.1f".format(c.duracao.toDouble() / projeto.taxa)} s", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("2. Em quantas faixas", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (premium) Text("6 faixas")
            else SeparacaoDaMusica.MODOS.forEach { m -> FilterChip(modo == m.id, { modo = m.id }, { Text("${m.faixas.size} faixas") }) }
        }
        val modoAtual = if (premium) br.com.smellsliketech.converter.musica.RoFormer.modo else SeparacaoDaMusica.modo(modo)!!
        Text("${modoAtual.rotulo}. As faixas novas entram com o nome do que são: ${modoAtual.faixas.joinToString { Musica.nomeDaFaixaSeparada(it.rotulo, "…") }}", fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(limpar, { limpar = it }, Modifier.semantics { contentDescription = "Reduzir ruído nas faixas separadas" })
            Spacer(Modifier.width(8.dp))
            Text("Reduzir ruído nas faixas separadas", Modifier.weight(1f))
        }
        Text("Tira o chiado e o ruído constante que a separação costuma deixar em cada faixa (mais útil na voz). Fica desligado se a música original já é limpa.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (NucleoNativo.disponivel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(refinada && !premium, { refinada = it }, enabled = modelo.andamento == null && !premium)
                Text("Refinar agudos (experimental)", Modifier.padding(start = 8.dp))
            }
            Text("Restauração por instrumento ($intensidade%)", fontSize = 13.sp)
            AjusteDaMusica("Intensidade da restauração", intensidade.toDouble(), 0.0, 100.0, 1.0, false) {
                intensidade = it.toInt()
            }
        }
        Button(onClick = { modelo.separar(faixa.id, clipe.id, modoAtual.id, limpar, refinada && NucleoNativo.disponivel && !premium,
            if (NucleoNativo.disponivel) intensidade else 0) }, enabled = modelo.andamento == null, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Separar faixas" }) { Text("Separar") }
        Text(if (premium) "Pode haver vazamento entre faixas. A mistura original fica no projeto." else SeparacaoDaMusica.AVISO, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Os pesos têm licença MIT e são conferidos por SHA-256. O áudio é processado neste celular.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Separar músicas de outras pessoas não muda os direitos autorais delas: use em gravações suas ou que você tem direito de usar.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AbaDeExportar(modelo: MusicaViewModel, projeto: ProjetoMusical, podeCriar: Boolean, abrirLicenca: () -> Unit) {
    var formato by rememberSaveable { mutableStateOf("wav-24") }
    var master by rememberSaveable { mutableStateOf("sem-alvo") }
    var cadaFaixa by rememberSaveable { mutableStateOf(false) }
    var soLoop by rememberSaveable { mutableStateOf(false) }
    var cauda by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Formato", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MasterDaMusica.FORMATOS.forEach { f -> FilterChip(formato == f.id, { formato = f.id }, { Text(f.rotulo) }) } }
        Text("Master", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MasterDaMusica.PRESETS.forEach { p -> FilterChip(master == p.id, { master = p.id }, { Text(p.rotulo) }, enabled = !cadaFaixa) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cadaFaixa, { cadaFaixa = it }); Text("Cada faixa num arquivo (stems, sem o master)") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(soLoop, { soLoop = it }, enabled = projeto.loop.fim > projeto.loop.inicio); Text("Só a região do loop") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cauda, { cauda = it }); Text("Incluir a cauda do reverb e do delay") }
        Button(onClick = { if (podeCriar) modelo.exportar(formato, master, soLoop, cadaFaixa, cauda, null) else abrirLicenca() }, enabled = modelo.andamento == null, modifier = Modifier.semantics { contentDescription = "Exportar música" }) { Text("Exportar para Downloads") }
        modelo.exportados.forEach { Text(it, fontSize = 13.sp) }
        Text("WAV de 16 e 24 bits leva dither; o float32 não. Nenhum alvo de volume é imposto: escolha o master pela finalidade e confira a medida.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Barra de ajuste com − e + nas pontas: a barra para o gesto largo, os botões para o passo fino
 * (encaixado na grade do passo, ou relativo nas frequências). Ver Musica.ajusteFino.
 */
@Composable
internal fun AjusteDaMusica(descricao: String, valor: Double, de: Double, ate: Double, passo: Double, relativo: Boolean = false, rotulo: String? = null, valorTexto: String? = null, mudar: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (rotulo != null || valorTexto != null) Column(Modifier.width(if (valorTexto != null) 82.dp else 30.dp)) {
            if (rotulo != null) Text(rotulo, fontSize = 11.sp)
            if (valorTexto != null) Text(valorTexto, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        AcaoDoEditor(AcaoEditor.DIMINUIR, "Diminuir $descricao", habilitada = valor > de) { mudar(Musica.ajusteFino(valor, -1, passo, de, ate, relativo)) }
        Slider(valor.toFloat().coerceIn(de.toFloat(), ate.toFloat()), { mudar(it.toDouble()) }, Modifier.weight(1f).semantics { contentDescription = descricao }, valueRange = de.toFloat()..ate.toFloat())
        AcaoDoEditor(AcaoEditor.ADICIONAR, "Aumentar $descricao", habilitada = valor < ate) { mudar(Musica.ajusteFino(valor, 1, passo, de, ate, relativo)) }
    }
}
