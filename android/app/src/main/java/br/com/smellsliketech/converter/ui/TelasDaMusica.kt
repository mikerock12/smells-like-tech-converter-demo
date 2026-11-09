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
import br.com.smellsliketech.converter.musica.ProjetoMusical
import br.com.smellsliketech.converter.musica.SeparacaoDaMusica
import java.util.UUID
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
            val abas = listOf("Faixas", "Gravar", "Mixer", "FX", "Separar", "Exportar")
            ScrollableTabRow(selectedTabIndex = aba, edgePadding = 8.dp) {
                abas.forEachIndexed { i, rotulo -> Tab(selected = aba == i, onClick = { aba = i }, text = { Text(rotulo) }) }
            }
            Box(Modifier.weight(1f)) {
                when (aba) {
                    0 -> AbaDasFaixas(modelo, projeto)
                    1 -> AbaDeGravar(modelo, projeto)
                    2 -> AbaDoMixer(modelo, projeto)
                    3 -> AbaDeEfeitos(modelo, projeto)
                    4 -> AbaDeSeparar(modelo, projeto)
                    else -> AbaDeExportar(modelo, projeto, podeCriar, abrirLicenca)
                }
            }
        }
    }
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
private fun AbaDasFaixas(modelo: MusicaViewModel, projeto: ProjetoMusical) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", fontSize = 12.sp)
                Slider(zoom, { zoom = it }, Modifier.weight(1f), valueRange = 8f..300f)
            }
        }
        items(projeto.faixas.filter { it.oculta != true }, key = { it.id }) { faixa ->
            CartaoDaFaixa(modelo, projeto, faixa, zoom, larguraDp, rolagem, selecionado) { selecionado = it }
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
private fun CartaoDaFaixa(modelo: MusicaViewModel, projeto: ProjetoMusical, faixa: FaixaMusical, zoom: Float, larguraDp: androidx.compose.ui.unit.Dp, rolagem: androidx.compose.foundation.ScrollState, selecionado: String?, selecionar: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val corDaFaixa = cor(faixa.cor)
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Faixa ${faixa.nome}" }) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(6.dp).height(28.dp).background(corDaFaixa))
                OutlinedTextField(faixa.nome, { t -> modelo.mudarFaixa(faixa.id, "nome-${faixa.id}") { it.copy(nome = t.take(60)) } }, Modifier.weight(1f).padding(start = 6.dp), singleLine = true)
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Mais ações de ${faixa.nome}" }) { Text("⋯") }
                    DropdownMenu(menu, { menu = false }) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vol", fontSize = 11.sp)
                Slider(faixa.ganhoDb.toFloat(), { v -> modelo.mudarFaixa(faixa.id, "vol-${faixa.id}") { it.copy(ganhoDb = v.toDouble()) } }, Modifier.weight(1f), valueRange = -60f..12f)
                Text("Pan", fontSize = 11.sp)
                Slider(faixa.pan.toFloat(), { v -> modelo.mudarFaixa(faixa.id, "pan-${faixa.id}") { it.copy(pan = v.toDouble()) } }, Modifier.weight(1f), valueRange = -100f..100f)
            }
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
                    val k = ((clipe.inicioNaMidia + px / w * clipe.duracao) / 1024).toInt().coerceIn(0, colunas - 1)
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
            Text("${clipe.nome ?: "Clipe"} em ${"%.2f".format(clipe.inicio.toDouble() / projeto.taxa)} s", fontWeight = FontWeight.Bold)
            Text("Ganho: ${"%.1f".format(clipe.ganhoDb)} dB", fontSize = 12.sp)
            Slider(clipe.ganhoDb.toFloat(), { v -> mudarClipe("ganho") { it.copy(ganhoDb = v.toDouble()) } }, valueRange = -24f..12f)
            Text("Entrada suave: ${clipe.entradaSuave * 1000 / projeto.taxa} ms · saída suave: ${clipe.saidaSuave * 1000 / projeto.taxa} ms", fontSize = 12.sp)
            Slider(clipe.entradaSuave.toFloat(), { v -> mudarClipe("fadein") { it.copy(entradaSuave = minOf(v.roundToLong(), it.duracao - it.saidaSuave)) } }, valueRange = 0f..minOf(clipe.duracao.toFloat(), projeto.taxa * 5f))
            Slider(clipe.saidaSuave.toFloat(), { v -> mudarClipe("fadeout") { it.copy(saidaSuave = minOf(v.roundToLong(), it.duracao - it.entradaSuave)) } }, valueRange = 0f..minOf(clipe.duracao.toFloat(), projeto.taxa * 5f))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(!clipe.mudo, { a -> mudarClipe { it.copy(mudo = !a) } }); Text("Tomada ativa") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val corte = modelo.posicao
                    if (corte > clipe.inicio && corte < clipe.fim) modelo.mudarFaixa(faixa.id) { t ->
                        val esq = corte - clipe.inicio
                        t.copy(clipes = t.clipes.flatMap { if (it.id != clipe.id) listOf(it) else listOf(it.copy(duracao = esq, saidaSuave = 0, entradaSuave = minOf(it.entradaSuave, esq)), it.copy(id = novoId("clipe"), inicio = corte, inicioNaMidia = it.inicioNaMidia + esq, duracao = it.duracao - esq, entradaSuave = 0, saidaSuave = minOf(it.saidaSuave, it.duracao - esq))) })
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
        Text("Latência", fontWeight = FontWeight.Bold)
        Text(if (projeto.latencia.medida) "Medida: ${"%.1f".format((projeto.latencia.entrada + projeto.latencia.saida) * 1000 / projeto.taxa)} ms de ida e volta." else "A tomada é alinhada pelos relógios da saída e da entrada do Android; calibre para descontar também o atraso dos conversores do aparelho.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = modelo::calibrar, enabled = modelo.entrada != null) { Text("Calibrar com cliques") }
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
        Slider(projeto.metronomo.volumeDb.toFloat(), { v -> modelo.mudar("metro") { p -> p.copy(metronomo = p.metronomo.copy(volumeDb = v.toDouble())) } }, valueRange = -40f..6f)
    }
}

@Composable
private fun AbaDoMixer(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    LazyRow(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(projeto.faixas.filter { it.oculta != true }, key = { it.id }) { f ->
            Canal(f.nome, cor(f.cor), f.ganhoDb, f.pan, modelo.medidores[f.id], { v -> modelo.mudarFaixa(f.id, "vol-${f.id}") { it.copy(ganhoDb = v) } }, { v -> modelo.mudarFaixa(f.id, "pan-${f.id}") { it.copy(pan = v) } }) {
                Row { FilterChip(f.solo, { modelo.mudarFaixa(f.id) { it.copy(solo = !it.solo) } }, { Text("S") }); FilterChip(f.mudo, { modelo.mudarFaixa(f.id) { it.copy(mudo = !it.mudo) } }, { Text("M") }) }
                Text("L ${"%.1f".format(f.ajusteEsquerdoDb)} dB", fontSize = 11.sp)
                Slider(f.ajusteEsquerdoDb.toFloat(), { v -> modelo.mudarFaixa(f.id, "l-${f.id}") { it.copy(ajusteEsquerdoDb = v.toDouble()) } }, valueRange = Musica.AJUSTE_MINIMO_DB.toFloat()..Musica.AJUSTE_MAXIMO_DB.toFloat())
                Text("R ${"%.1f".format(f.ajusteDireitoDb)} dB", fontSize = 11.sp)
                Slider(f.ajusteDireitoDb.toFloat(), { v -> modelo.mudarFaixa(f.id, "r-${f.id}") { it.copy(ajusteDireitoDb = v.toDouble()) } }, valueRange = Musica.AJUSTE_MINIMO_DB.toFloat()..Musica.AJUSTE_MAXIMO_DB.toFloat())
                projeto.barramentos.forEach { b ->
                    val envio = f.envios.firstOrNull { it.barramento == b.id }
                    Text("→ ${b.nome}: ${envio?.let { "${it.nivelDb.roundToLong()} dB" } ?: "desligado"}", fontSize = 11.sp)
                    Slider((envio?.nivelDb ?: -61.0).toFloat(), { v ->
                        modelo.mudarFaixa(f.id, "envio-${f.id}-${b.id}") { t ->
                            t.copy(envios = if (v <= -61f) t.envios.filter { it.barramento != b.id } else if (envio == null) t.envios + Envio(b.id, v.toDouble()) else t.envios.map { if (it.barramento == b.id) it.copy(nivelDb = v.toDouble()) else it })
                        }
                    }, valueRange = -61f..6f)
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
    Card(Modifier.width(170.dp).border(2.dp, corDoCanal, RoundedCornerShape(12.dp)).semantics { contentDescription = "Canal $nome" }) {
        Column(Modifier.padding(8.dp)) {
            Text(nome, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(Modifier.height(8.dp).fillMaxWidth()) {
                Box(Modifier.weight(nivel(medidor?.get(0) ?: 0f).coerceAtLeast(0.001f)).height(8.dp).background(Color(0xFF43A047)))
                Spacer(Modifier.weight((1 - nivel(medidor?.get(0) ?: 0f)).coerceAtLeast(0.001f)))
            }
            Text("${if (ganhoDb <= -60) "−∞" else "%.1f".format(ganhoDb)} dB", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Slider(ganhoDb.toFloat(), { volume(it.toDouble()) }, valueRange = -60f..12f)
            if (mudarPan != null) {
                Text("Pan ${if (pan == 0.0) "C" else if (pan < 0) "L${(-pan).roundToLong()}" else "R${pan.roundToLong()}"}", fontSize = 12.sp)
                Slider(pan.toFloat(), { mudarPan(it.toDouble()) }, valueRange = -100f..100f)
            }
            extra()
        }
    }
}

@Composable
private fun AbaDeEfeitos(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    var alvo by rememberSaveable { mutableStateOf("master") }
    val alvos = projeto.faixas.map { it.id to "Faixa: ${it.nome}" } + projeto.barramentos.map { it.id to "Barramento: ${it.nome}" } + ("master" to "Master")
    fun cadeia(p: ProjetoMusical) = if (alvo == "master") p.master.efeitos else p.faixas.firstOrNull { it.id == alvo }?.efeitos ?: p.barramentos.firstOrNull { it.id == alvo }?.efeitos ?: emptyList()
    fun trocar(juntar: String? = null, f: (List<EfeitoMusical>) -> List<EfeitoMusical>) = modelo.mudar(juntar) { p ->
        if (alvo == "master") p.copy(master = p.master.copy(efeitos = f(p.master.efeitos)))
        else p.copy(faixas = p.faixas.map { if (it.id == alvo) it.copy(efeitos = f(it.efeitos)) else it }, barramentos = p.barramentos.map { if (it.id == alvo) it.copy(efeitos = f(it.efeitos)) else it })
    }
    var menuAlvo by remember { mutableStateOf(false) }
    var menuNovo by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { menuAlvo = true }) { Text(alvos.firstOrNull { it.first == alvo }?.second ?: "Master") }
                DropdownMenu(menuAlvo, { menuAlvo = false }) { alvos.forEach { (id, rotulo) -> DropdownMenuItem(text = { Text(rotulo) }, onClick = { alvo = id; menuAlvo = false }) } }
            }
            Box {
                Button(onClick = { menuNovo = true }) { Text("+ Efeito") }
                DropdownMenu(menuNovo, { menuNovo = false }) {
                    DspDaMusica.CATALOGO.forEach { d -> DropdownMenuItem(text = { Text("${d.grupo}: ${d.rotulo}") }, onClick = { menuNovo = false; trocar { it + DspDaMusica.novoEfeito(d.tipo, novoId("fx")) } }) }
                }
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
                        Slider(v.toFloat(), { nv -> trocar("${e.id}-${par.id}") { l -> l.map { if (it.id == e.id) it.com(par.id, nv.toDouble()) else it } } }, valueRange = par.min.toFloat()..par.max.toFloat())
                    }
                }
            }
        }
    }
}

@Composable
private fun AbaDeSeparar(modelo: MusicaViewModel, projeto: ProjetoMusical) {
    val clipes = projeto.faixas.flatMap { f -> f.clipes.filter { !it.mudo }.map { f to it } }
    var escolhido by rememberSaveable { mutableIntStateOf(0) }
    var modo by rememberSaveable { mutableStateOf("4") }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Separar faixas por IA", fontWeight = FontWeight.Bold)
        if (clipes.isEmpty()) { Text("Importe ou grave um áudio primeiro."); return@Column }
        val (faixa, clipe) = clipes[escolhido.coerceIn(0, clipes.size - 1)]
        Box {
            OutlinedButton(onClick = { menu = true }) { Text("${faixa.nome} — ${clipe.nome ?: "clipe"}") }
            DropdownMenu(menu, { menu = false }) { clipes.forEachIndexed { i, (f, c) -> DropdownMenuItem(text = { Text("${f.nome} — ${c.nome ?: "clipe"}") }, onClick = { escolhido = i; menu = false }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SeparacaoDaMusica.MODOS.forEach { m -> FilterChip(modo == m.id, { modo = m.id }, { Text("${m.faixas.size} faixas") }) }
        }
        Text(SeparacaoDaMusica.modo(modo)!!.rotulo, fontSize = 12.sp)
        Button(onClick = { modelo.separar(faixa.id, clipe.id, modo) }, enabled = modelo.andamento == null, modifier = Modifier.semantics { contentDescription = "Separar faixas" }) { Text("Separar") }
        OutlinedButton(onClick = { modelo.reduzirRuido(faixa.id, clipe.id) }, enabled = modelo.andamento == null) { Text("Reduzir ruído (offline)") }
        Text(SeparacaoDaMusica.AVISO, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Modelo: Spleeter 4 faixas (Deezer), licença ${SeparacaoDaMusica.LICENCA}. Roda no celular; o áudio não sai daqui. ${if (modelo.biblioteca.modeloPronto) "O modelo já está baixado." else "O modelo (78 MB) é baixado na primeira separação e conferido pelo SHA-256."} Músicas longas levam minutos e gastam bateria; aparelhos com pouca memória podem não dar conta.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
