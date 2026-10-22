package br.com.smellsliketech.converter.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color as CorAndroid
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.textoDoAndamento
import br.com.smellsliketech.converter.figurinhas.Edicao
import br.com.smellsliketech.converter.figurinhas.FigurinhasViewModel
import br.com.smellsliketech.converter.figurinhas.Filtro
import br.com.smellsliketech.converter.figurinhas.Fonte
import br.com.smellsliketech.converter.figurinhas.PacoteDeFigurinhas
import br.com.smellsliketech.converter.figurinhas.PosicaoDoTexto
import br.com.smellsliketech.converter.figurinhas.Regras
import br.com.smellsliketech.converter.figurinhas.TipoDaOrigem
import br.com.smellsliketech.converter.figurinhas.WhatsApp
import br.com.smellsliketech.converter.figurinhas.Mescla
import kotlin.math.roundToInt

private val CORES = listOf(
    CorAndroid.WHITE, CorAndroid.BLACK, 0xFFFF7A1A.toInt(), 0xFFFFD54A.toInt(), 0xFFE53935.toInt(),
    0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(),
)
private val EMOJIS = listOf("😀", "😂", "🤣", "😍", "😎", "😢", "😡", "🤔", "👍", "🙏", "🔥", "🎉", "❤️", "💀", "🥳", "😴")

/**
 * O gerador de figurinhas do WhatsApp: os pacotes guardados no app, o detalhe de um pacote e o
 * editor. Não salva nada em Downloads: a figurinha vai para um pacote, e o pacote vai para o
 * WhatsApp pela tela "Adicionar ao WhatsApp" do próprio WhatsApp.
 */
@Composable
fun TelaDasFigurinhas(modelo: FigurinhasViewModel, podeCriar: Boolean, abrirLicenca: () -> Unit, voltar: () -> Unit) {
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) modelo.abrir(uri)
    }
    val novaFigurinha = { if (podeCriar) escolher.launch(arrayOf("image/*", "video/*")) else abrirLicenca() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                modelo.origem != null -> {
                    BackHandler { modelo.fecharEditor() }
                    EditorDeFigurinha(modelo)
                }
                modelo.pacoteAberto != null -> {
                    BackHandler { modelo.pacoteAberto = null }
                    DetalheDoPacote(modelo, modelo.pacoteAberto!!, novaFigurinha)
                }
                else -> {
                    BackHandler { voltar() }
                    ListaDePacotes(modelo, novaFigurinha, voltar)
                }
            }
            modelo.ocupado?.let { texto -> Ocupado(texto, modelo.progresso, modelo.restante, modelo::cancelar) }
            modelo.aviso?.let { texto ->
                AlertDialog(
                    onDismissRequest = { modelo.aviso = null },
                    confirmButton = { TextButton(onClick = { modelo.aviso = null }) { Text("Ok") } },
                    text = { Text(texto) },
                )
            }
        }
    }
}

/**
 * O andamento da figurinha: a etapa, a barra (que desliza entre uma atualização e outra) e
 * "47% · faltam cerca de 20 s", pelo mesmo cálculo das conversões.
 */
@Composable
private fun Ocupado(texto: String, progresso: Int?, restante: Int?, cancelar: () -> Unit) {
    val suave by animateFloatAsState((progresso ?: 0) / 100f, animationSpec = tween(400), label = "progresso")
    AlertDialog(
        onDismissRequest = {},
        confirmButton = { TextButton(onClick = cancelar) { Text("Cancelar") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(texto)
                if (progresso != null) {
                    LinearProgressIndicator(progress = { suave }, modifier = Modifier.fillMaxWidth())
                    Text(textoDoAndamento(progresso, restante), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}

@Composable
private fun ListaDePacotes(modelo: FigurinhasViewModel, novaFigurinha: () -> Unit, voltar: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Figurinhas", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = voltar) { Text("Voltar") }
        }
        Text(
            "Transforme imagens, GIFs e vídeos em figurinhas do WhatsApp, com recorte, remoção de fundo, filtros, texto e contorno. Tudo no celular, sem anúncios.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        )
        Button(onClick = novaFigurinha, modifier = Modifier.fillMaxWidth()) { Text("Nova figurinha") }
        if (modelo.lista.isEmpty()) {
            Text("Nenhum pacote ainda. A primeira figurinha cria o pacote.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        modelo.lista.forEach { pacote ->
            Surface(
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, Cores.borda),
                modifier = Modifier.fillMaxWidth().clickable { modelo.pacoteAberto = pacote },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniaturaDoPacote(modelo, pacote)
                    Column(Modifier.weight(1f)) {
                        Text(pacote.nome, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${pacote.figurinhas.size}/${Regras.MAXIMO} · ${if (pacote.animado) "animadas" else "estáticas"}" +
                                if (pacote.prontoParaWhatsApp) "" else " · faltam ${pacote.faltam}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Dica("O WhatsApp exige de ${Regras.MINIMO} a ${Regras.MAXIMO} figurinhas por pacote, todas estáticas ou todas animadas. Depois de adicionado, o pacote atualiza no WhatsApp quando você põe ou tira figurinhas.")
    }
}

@Composable
private fun MiniaturaDoPacote(modelo: FigurinhasViewModel, pacote: PacoteDeFigurinhas) {
    val bandeja = remember(pacote.id, pacote.versao) {
        pacote.figurinhas.firstOrNull()?.let { BitmapFactory.decodeFile(modelo.pacotes.arquivo(pacote, it.arquivo).absolutePath) }
    }
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Cores.borda), contentAlignment = Alignment.Center) {
        if (bandeja != null) Image(bandeja.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        else Text("＋", fontSize = 20.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetalheDoPacote(modelo: FigurinhasViewModel, pacote: PacoteDeFigurinhas, novaFigurinha: () -> Unit) {
    val contexto = LocalContext.current
    var renomeando by remember { mutableStateOf(false) }
    var excluindo by remember { mutableStateOf(false) }
    val adicionar = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        when (resultado.resultCode) {
            Activity.RESULT_OK -> modelo.aviso = "Pacote “${pacote.nome}” no WhatsApp."
            else -> resultado.data?.getStringExtra("validation_error")?.let { erro ->
                modelo.aviso = "O WhatsApp recusou o pacote: $erro"
            }
        }
    }
    val instalados = remember { WhatsApp.instalados(contexto) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pacote.nome, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { modelo.pacoteAberto = null }) { Text("Pacotes") }
        }
        Text(
            "${pacote.figurinhas.size}/${Regras.MAXIMO} figurinhas ${if (pacote.animado) "animadas" else "estáticas"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pacote.figurinhas.forEach { figurinha ->
                val imagem = remember(pacote.id, figurinha.arquivo) {
                    BitmapFactory.decodeFile(modelo.pacotes.arquivo(pacote, figurinha.arquivo).absolutePath)
                }
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).fundoXadrez()) {
                    if (imagem != null) Image(imagem.asImageBitmap(), contentDescription = figurinha.emojis.joinToString(" "), modifier = Modifier.fillMaxSize())
                    Text(
                        "×", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape).background(Color(0x99000000))
                            .clickable { modelo.remover(pacote, figurinha) }.padding(horizontal = 7.dp),
                    )
                }
            }
            if (!pacote.cheio) {
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Cores.borda, RoundedCornerShape(8.dp)).clickable(onClick = novaFigurinha),
                    contentAlignment = Alignment.Center,
                ) { Text("＋", fontSize = 28.sp) }
            }
        }
        if (instalados.isEmpty()) {
            Aviso("O WhatsApp não está instalado neste celular.")
        } else if (!pacote.prontoParaWhatsApp) {
            Text("Faltam ${pacote.faltam} figurinha${if (pacote.faltam == 1) "" else "s"} para o WhatsApp aceitar o pacote.", color = Cores.amarelo, fontSize = 13.sp)
        }
        instalados.forEach { whatsapp ->
            val jaEsta = remember(pacote.versao, whatsapp) { WhatsApp.jaAdicionado(contexto, whatsapp, pacote) }
            Button(
                onClick = { modelo.adicionarAoWhatsApp(pacote, whatsapp) { adicionar.launch(it) } },
                enabled = pacote.prontoParaWhatsApp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val nome = if (whatsapp == WhatsApp.BUSINESS) "WhatsApp Business" else "WhatsApp"
                Text(if (jaEsta) "Já está no $nome (atualizado sozinho)" else "Adicionar ao $nome")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = novaFigurinha, enabled = !pacote.cheio) { Text("Nova figurinha") }
            OutlinedButton(onClick = { renomeando = true }) { Text("Renomear") }
            OutlinedButton(onClick = { excluindo = true }) { Text("Excluir") }
        }
    }

    if (renomeando) {
        var nome by remember { mutableStateOf(pacote.nome) }
        AlertDialog(
            onDismissRequest = { renomeando = false },
            confirmButton = { TextButton(onClick = { modelo.renomear(pacote, nome); renomeando = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renomeando = false }) { Text("Cancelar") } },
            title = { Text("Nome do pacote") },
            text = { OutlinedTextField(nome, { nome = it.take(Regras.NOME_MAX) }, singleLine = true) },
        )
    }
    if (excluindo) {
        AlertDialog(
            onDismissRequest = { excluindo = false },
            confirmButton = { TextButton(onClick = { modelo.excluir(pacote); excluindo = false }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { excluindo = false }) { Text("Cancelar") } },
            text = { Text("Excluir o pacote “${pacote.nome}” e as ${pacote.figurinhas.size} figurinhas dele? Se ele estiver no WhatsApp, some de lá também.") },
        )
    }
}

private enum class Aba(val rotulo: String) { CAMADAS("Camadas"), ENQUADRAR("Enquadrar"), FUNDO("Fundo"), FILTRO("Filtro"), TEXTO("Texto"), CONTORNO("Contorno"), EMOJI("Emoji") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorDeFigurinha(modelo: FigurinhasViewModel) {
    var catalogoAberto by remember { mutableStateOf(false) }
    if (catalogoAberto) SeletorDeStickers("figurinhas", { catalogoAberto = false }) {
        modelo.adicionarMidia(android.net.Uri.fromFile(it), descartarAoAbrir = true)
    }
    val origem = modelo.origem ?: return
    val edicao = modelo.edicao
    var aba by remember { mutableStateOf(Aba.ENQUADRAR) }
    var pincel by remember { mutableStateOf(false) }
    var apagar by remember { mutableStateOf(true) }
    var tamanhoDoPincel by remember { mutableStateOf(0.05f) }
    var salvando by remember { mutableStateOf(false) }
    var importarComoFundo by remember { mutableStateOf(false) }
    val adicionarMidia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) modelo.adicionarMidia(uri, importarComoFundo)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nova figurinha", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = modelo::fecharEditor) { Text("Cancelar") }
        }
        Text(origem.nome, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)

        // Vídeo e GIF: estática (um quadro) ou animada (trecho de até 10 s).
        if (modelo.temAnimacao) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !modelo.animada, onClick = { modelo.animada = false }, label = { Text("Estática (um quadro)") })
                FilterChip(selected = modelo.animada, onClick = { modelo.animada = true }, label = { Text("Animada (até 10 s)") })
            }
            if (origem.tipo == TipoDaOrigem.VIDEO) {
                val opcoes = Opcoes(formato = "mp4", inicioSegundos = modelo.inicio, fimSegundos = modelo.fim)
                PreviaDoTrecho(origem.uri, video = true, opcoes = opcoes) { novas ->
                    val inicioMudou = novas.inicioSegundos != modelo.inicio
                    modelo.inicio = novas.inicioSegundos
                    modelo.fim = if (novas.fimSegundos > 0) minOf(novas.fimSegundos, novas.inicioSegundos + Regras.ANIMADA_MAX_SEGUNDOS) else novas.inicioSegundos + Regras.ANIMADA_MAX_SEGUNDOS
                    if (inicioMudou) modelo.carregarBase()
                }
                Dica(if (modelo.animada) "A figurinha animada usa o trecho entre Início e Fim (no máximo 10 s)." else "A figurinha estática usa o quadro marcado em Início.")
            } else if (origem.tipo == TipoDaOrigem.GIF) {
                Dica(if (modelo.animada) "O GIF vira figurinha animada (os primeiros 10 s)." else "A figurinha estática usa o primeiro quadro do GIF.")
            }
            if (modelo.animada) {
                Rotulado("Quadros por segundo") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Regras.QUADROS_POR_SEGUNDO.forEach { valor ->
                            FilterChip(selected = modelo.quadrosPorSegundo == valor, onClick = { modelo.mudarQuadrosPorSegundo(valor) }, label = { Text("$valor") })
                        }
                    }
                }
                Dica("Toda figurinha anima nessa velocidade, sem pular quadros. Se o vídeo tiver movimento demais para os 500 KB do WhatsApp, ela fica mais curta. Mais quadros: mais suave e mais curta.")
                Rotulado("Duração da composição: ${(modelo.fim - modelo.inicio).toFloat()} s") {
                    Slider((modelo.fim - modelo.inicio).toFloat().coerceIn(0.2f, 10f),
                        { modelo.fim = modelo.inicio + it }, valueRange = 0.2f..10f)
                }
                Rotulado("Prévia: ${"%.1f".format(modelo.tempoDaPrevia)} s") {
                    Slider(modelo.tempoDaPrevia.coerceIn(0f, (modelo.fim - modelo.inicio).toFloat().coerceAtLeast(0.2f)),
                        modelo::mostrarInstante, valueRange = 0f..(modelo.fim - modelo.inicio).toFloat().coerceAtLeast(0.2f))
                }
            }
        }

        // A prévia: arrastar e pinçar enquadram; no modo pincel, o dedo apaga ou devolve.
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).fundoXadrez()
                .pointerInput(aba, pincel, apagar, tamanhoDoPincel, modelo.camadaSelecionada) {
                    if (aba == Aba.FUNDO && pincel) {
                        detectDragGestures(
                            onDragStart = { ponto -> modelo.pincelarNaPrevia(ponto.x / size.width, ponto.y / size.height, tamanhoDoPincel, apagar) },
                        ) { mudanca, _ ->
                            val ponto = mudanca.position
                            modelo.pincelarNaPrevia(ponto.x / size.width, ponto.y / size.height, tamanhoDoPincel, apagar)
                        }
                    } else {
                        detectTransformGestures { _, deslocamento, zoom, giro ->
                            val atual = modelo.edicao
                            modelo.mudar(
                                atual.copy(
                                    zoom = (atual.zoom * zoom).coerceIn(0.3f, 6f),
                                    deslocamentoX = (atual.deslocamentoX + deslocamento.x / size.width).coerceIn(-1f, 1f),
                                    deslocamentoY = (atual.deslocamentoY + deslocamento.y / size.height).coerceIn(-1f, 1f),
                                    giro = ((atual.giro + giro).roundToInt() + 540) % 360 - 180,
                                ),
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            modelo.previa?.let { Image(it.asImageBitmap(), contentDescription = "Prévia da figurinha", modifier = Modifier.fillMaxSize()) }
                ?: Text("Preparando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Aba.entries.forEach { FilterChip(selected = aba == it, onClick = { aba = it }, label = { Text(it.rotulo, fontSize = 12.sp) }) }
        }

        when (aba) {
            Aba.CAMADAS -> {
                OutlinedButton(onClick = { catalogoAberto = true }) { Text("Stickers e GIF stickers") }
                Dica("Selecione uma camada para ajustar posição, giro, filtro ou recorte sem alterar as outras.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { importarComoFundo = false; adicionarMidia.launch(arrayOf("image/*", "video/*")) }) { Text("Adicionar mídia / sticker / GIF") }
                    OutlinedButton(onClick = { modelo.adicionarTexto(); aba = Aba.TEXTO }) { Text("Adicionar texto") }
                    OutlinedButton(onClick = { aba = Aba.EMOJI }) { Text("Adicionar emoji") }
                }
                FilterChip(selected = modelo.camadaSelecionada == null, onClick = { modelo.selecionarCamada(null) }, label = { Text("Mídia principal") })
                modelo.camadas.sortedByDescending { it.ordem }.forEach { camada ->
                    Column {
                        FilterChip(selected = modelo.camadaSelecionada == camada.id, onClick = { modelo.selecionarCamada(camada.id) },
                            label = { Text("${if (!camada.visivel) "Oculta · " else ""}${camada.nome}", maxLines = 1) })
                        FlowRow {
                            TextButton(onClick = { modelo.moverCamada(camada.id, 1) }) { Text("Para frente") }
                            TextButton(onClick = { modelo.moverCamada(camada.id, -1) }) { Text("Para trás") }
                            TextButton(onClick = { modelo.ocultarCamada(camada.id) }) { Text(if (camada.visivel) "Ocultar" else "Mostrar") }
                            TextButton(onClick = { modelo.excluirCamada(camada.id) }) { Text("Excluir") }
                        }
                    }
                }
                Rotulado("Opacidade: ${(edicao.opacidade * 100).roundToInt()}%") {
                    Slider(edicao.opacidade, { modelo.mudar(edicao.copy(opacidade = it)) })
                }
                FlowRow {
                    Mescla.entries.forEach { mescla ->
                        FilterChip(selected = edicao.mescla == mescla, onClick = { modelo.mudar(edicao.copy(mescla = mescla)) }, label = { Text(mescla.rotulo) })
                    }
                }
            }
            Aba.ENQUADRAR -> {
                Dica("Arraste para mover e pince para aproximar. Ou use os controles:")
                Rotulado("Zoom") { Slider(edicao.zoom, { modelo.mudar(edicao.copy(zoom = it)) }, valueRange = 0.3f..4f) }
                Rotulado("Horizontal") { Slider(edicao.deslocamentoX, { modelo.mudar(edicao.copy(deslocamentoX = it)) }, valueRange = -1f..1f) }
                Rotulado("Vertical") { Slider(edicao.deslocamentoY, { modelo.mudar(edicao.copy(deslocamentoY = it)) }, valueRange = -1f..1f) }
                Rotulado("Giro no plano (Z): ${edicao.giro}°") {
                    Slider(edicao.giro.toFloat().coerceIn(-180f, 180f), { modelo.mudar(edicao.copy(giro = it.roundToInt())) }, valueRange = -180f..180f)
                }
                Rotulado("Inclinar para frente / trás (X): ${edicao.inclinacaoX.roundToInt()}°") {
                    Slider(edicao.inclinacaoX, { modelo.mudar(edicao.copy(inclinacaoX = it)) }, valueRange = -180f..180f)
                }
                Rotulado("Inclinar para esquerda / direita (Y): ${edicao.inclinacaoY.roundToInt()}°") {
                    Slider(edicao.inclinacaoY, { modelo.mudar(edicao.copy(inclinacaoY = it)) }, valueRange = -180f..180f)
                }
                Rotulado("Girar e espelhar") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { modelo.mudar(edicao.copy(giro = (edicao.giro + 270) % 360)) }) { Text("↺ Girar à esquerda") }
                        OutlinedButton(onClick = { modelo.mudar(edicao.copy(giro = (edicao.giro + 90) % 360)) }) { Text("↻ Girar à direita") }
                        FilterChip(
                            selected = edicao.espelharHorizontal,
                            onClick = { modelo.mudar(edicao.copy(espelharHorizontal = !edicao.espelharHorizontal)) },
                            label = { Text("⇆ Espelhar") },
                        )
                        FilterChip(
                            selected = edicao.espelharVertical,
                            onClick = { modelo.mudar(edicao.copy(espelharVertical = !edicao.espelharVertical)) },
                            label = { Text("⇅ De cabeça para baixo") },
                        )
                    }
                }
                TextButton(onClick = { modelo.mudar(edicao.copy(zoom = 1f, deslocamentoX = 0f, deslocamentoY = 0f, giro = 0, inclinacaoX = 0f, inclinacaoY = 0f)) }) { Text("Redefinir posição e giro") }
            }
            Aba.FUNDO -> {
                Linha("Remover o fundo automaticamente", edicao.removerFundo) { modelo.mudar(edicao.copy(removerFundo = it)) }
                if (modelo.animada && origem.tipo != TipoDaOrigem.IMAGEM) {
                    Dica("Na figurinha animada o fundo é removido em cada quadro ao salvar (leva um pouco mais).")
                } else {
                    Linha("Pincel (ajuste à mão)", pincel) { pincel = it }
                    if (pincel) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = apagar, onClick = { apagar = true }, label = { Text("Apagar") })
                            FilterChip(selected = !apagar, onClick = { apagar = false }, label = { Text("Restaurar") })
                        }
                        Rotulado("Tamanho do pincel") { Slider(tamanhoDoPincel, { tamanhoDoPincel = it }, valueRange = 0.01f..0.15f) }
                    }
                }
                Text("Cor do fundo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SeletorDeCor(listOf(CorAndroid.TRANSPARENT) + CORES, modelo.corDeFundo) { modelo.mudarFundo(it, modelo.corFinalDoFundo) }
                Linha("Fundo em gradiente", modelo.corFinalDoFundo != null) { modelo.mudarFundo(modelo.corDeFundo, if (it) CORES[2] else null) }
                modelo.corFinalDoFundo?.let { cor ->
                    Text("Segunda cor")
                    SeletorDeCor(CORES, cor) { modelo.mudarFundo(modelo.corDeFundo, it) }
                }
                OutlinedButton(onClick = { importarComoFundo = true; adicionarMidia.launch(arrayOf("image/*", "video/*")) }) { Text("Usar mídia como fundo") }
            }
            Aba.FILTRO -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Filtro.entries.forEach { filtro ->
                        FilterChip(selected = edicao.filtro == filtro, onClick = { modelo.mudar(edicao.copy(filtro = filtro)) }, label = { Text(filtro.rotulo, fontSize = 12.sp) })
                    }
                }
                Rotulado("Brilho") { Slider(edicao.brilho, { modelo.mudar(edicao.copy(brilho = it)) }, valueRange = -1f..1f) }
                Rotulado("Contraste") { Slider(edicao.contraste, { modelo.mudar(edicao.copy(contraste = it)) }, valueRange = -0.8f..1f) }
                Rotulado("Saturação") { Slider(edicao.saturacao, { modelo.mudar(edicao.copy(saturacao = it)) }, valueRange = -1f..1f) }
            }
            Aba.TEXTO -> {
                OutlinedTextField(edicao.texto, { modelo.mudar(edicao.copy(texto = it.take(60))) }, label = { Text("Texto") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fonte.entries.forEach { fonte ->
                        FilterChip(selected = edicao.fonte == fonte, onClick = { modelo.mudar(edicao.copy(fonte = fonte)) }, label = { Text(fonte.rotulo, fontSize = 12.sp) })
                    }
                }
                Text("Cor do texto", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SeletorDeCor(CORES, edicao.corDoTexto) { modelo.mudar(edicao.copy(corDoTexto = it)) }
                Text("Contorno do texto", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SeletorDeCor(listOf(CorAndroid.TRANSPARENT) + CORES, edicao.corDoContornoDoTexto) { modelo.mudar(edicao.copy(corDoContornoDoTexto = it)) }
                Rotulado("Tamanho") { Slider(edicao.tamanhoDoTexto, { modelo.mudar(edicao.copy(tamanhoDoTexto = it)) }, valueRange = 28f..120f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PosicaoDoTexto.entries.forEach { posicao ->
                        FilterChip(selected = edicao.posicaoDoTexto == posicao, onClick = { modelo.mudar(edicao.copy(posicaoDoTexto = posicao)) }, label = { Text(posicao.rotulo, fontSize = 12.sp) })
                    }
                }
            }
            Aba.CONTORNO -> {
                Linha("Contorno de figurinha", edicao.contorno) { modelo.mudar(edicao.copy(contorno = it)) }
                if (edicao.contorno) {
                    SeletorDeCor(CORES, edicao.corDoContorno) { modelo.mudar(edicao.copy(corDoContorno = it)) }
                    Rotulado("Largura") { Slider(edicao.larguraDoContorno, { modelo.mudar(edicao.copy(larguraDoContorno = it)) }, valueRange = 4f..24f) }
                    if (!edicao.removerFundo) Dica("Com o fundo removido, o contorno acompanha a figura; sem remover, ele contorna o quadro.")
                }
            }
            Aba.EMOJI -> {
                Dica("Toque para desenhar um emoji como camada.")
                FlowRow {
                    EMOJIS.forEach { emoji -> TextButton(onClick = { modelo.adicionarTexto(emoji, emoji = true) }) { Text(emoji, fontSize = 24.sp) } }
                }
                Dica("O WhatsApp usa os emojis para sugerir a figurinha. Escolha até ${Regras.EMOJIS_MAX}.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJIS.forEach { emoji ->
                        val marcado = emoji in modelo.emojis
                        FilterChip(
                            selected = marcado,
                            onClick = {
                                modelo.emojis = when {
                                    marcado && modelo.emojis.size > 1 -> modelo.emojis - emoji
                                    !marcado && modelo.emojis.size < Regras.EMOJIS_MAX -> modelo.emojis + emoji
                                    else -> modelo.emojis
                                }
                            },
                            label = { Text(emoji, fontSize = 18.sp) },
                        )
                    }
                }
            }
        }

        Button(onClick = { salvando = true }, enabled = modelo.previa != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (modelo.animada && modelo.temAnimacao) "Gerar figurinha animada" else "Gerar figurinha")
        }
    }

    if (salvando) EscolherPacote(modelo) { salvando = false }
}

/** Para qual pacote vai a figurinha: um que já existe (do mesmo tipo e com espaço) ou um novo. */
@Composable
private fun EscolherPacote(modelo: FigurinhasViewModel, fechar: () -> Unit) {
    val compativeis = remember { modelo.pacotesCompativeis() }
    var escolhido by remember { mutableStateOf(compativeis.firstOrNull()) }
    var nome by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = fechar,
        title = { Text("Salvar no pacote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                compativeis.forEach { pacote ->
                    Row(Modifier.fillMaxWidth().clickable { escolhido = pacote }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = escolhido?.id == pacote.id, onClick = { escolhido = pacote })
                        Text("${pacote.nome} (${pacote.figurinhas.size}/${Regras.MAXIMO})")
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { escolhido = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = escolhido == null, onClick = { escolhido = null })
                    Text("Novo pacote")
                }
                if (escolhido == null) {
                    OutlinedTextField(nome, { nome = it.take(Regras.NOME_MAX) }, label = { Text("Nome do pacote") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { modelo.salvar(escolhido, nome); fechar() }, enabled = escolhido != null || nome.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = fechar) { Text("Cancelar") } },
    )
}

@Composable
private fun Rotulado(rotulo: String, conteudo: @Composable () -> Unit) {
    Column {
        Text(rotulo, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        conteudo()
    }
}

@Composable
private fun Linha(rotulo: String, ligado: Boolean, mudar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = ligado, onCheckedChange = mudar)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeletorDeCor(cores: List<Int>, atual: Int, escolher: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cores.forEach { cor ->
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .then(if (cor == CorAndroid.TRANSPARENT) Modifier.fundoXadrez() else Modifier.background(Color(cor)))
                    .border(if (cor == atual) 3.dp else 1.dp, if (cor == atual) Cores.laranja else Cores.borda, CircleShape)
                    .clickable { escolher(cor) },
            )
        }
    }
}

/** O xadrez cinza que mostra onde a figurinha é transparente. */
private fun Modifier.fundoXadrez(): Modifier = this.background(Color(0xFF2A2A33)).drawBehind {
    val quadrado = 12.dp.toPx()
    var y = 0f
    var linha = 0
    while (y < size.height) {
        var x = if (linha % 2 == 0) 0f else quadrado
        while (x < size.width) {
            drawRect(Color(0xFF3A3A44), Offset(x, y), Size(quadrado, quadrado))
            x += quadrado * 2
        }
        y += quadrado
        linha++
    }
}
