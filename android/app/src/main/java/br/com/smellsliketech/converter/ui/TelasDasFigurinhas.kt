package br.com.smellsliketech.converter.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color as CorAndroid
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.textoDoAndamento
import br.com.smellsliketech.converter.figurinhas.Edicao
import br.com.smellsliketech.converter.figurinhas.Figurinha
import br.com.smellsliketech.converter.figurinhas.FigurinhasViewModel
import br.com.smellsliketech.converter.figurinhas.Filtro
import br.com.smellsliketech.converter.figurinhas.Fonte
import br.com.smellsliketech.converter.figurinhas.Guias
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
                    DetalheDoPacote(modelo, modelo.pacoteAberto!!, novaFigurinha) { pacote, figurinha ->
                        if (podeCriar) modelo.editar(pacote, figurinha) else abrirLicenca()
                    }
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
private fun DetalheDoPacote(
    modelo: FigurinhasViewModel,
    pacote: PacoteDeFigurinhas,
    novaFigurinha: () -> Unit,
    editar: (PacoteDeFigurinhas, Figurinha) -> Unit,
) {
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
                val editavel = remember(pacote.id, pacote.versao, figurinha.arquivo) { modelo.podeEditar(pacote, figurinha) }
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).fundoXadrez().clickable { editar(pacote, figurinha) }) {
                    if (imagem != null) Image(imagem.asImageBitmap(), contentDescription = "Editar figurinha ${figurinha.emojis.joinToString(" ")}", modifier = Modifier.fillMaxSize())
                    if (editavel) {
                        Text(
                            "✎", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 6.dp),
                        )
                    }
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
        if (pacote.figurinhas.isNotEmpty()) Dica("Toque numa figurinha para editar de novo.")
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
    val origemSelecionada = modelo.origemSelecionada
    val edicao = modelo.edicao
    val giroVisivel = if (edicao.giro in -180..180) edicao.giro else (edicao.giro + 180).mod(360) - 180
    var aba by remember { mutableStateOf(Aba.ENQUADRAR) }
    var pincel by remember { mutableStateOf(false) }
    var apagar by remember { mutableStateOf(true) }
    var tamanhoDoPincel by remember { mutableStateOf(0.05f) }
    var salvando by remember { mutableStateOf(false) }
    var importarComoFundo by remember { mutableStateOf(false) }
    val rolagemDosAjustes = rememberScrollState()
    LaunchedEffect(aba) { rolagemDosAjustes.scrollTo(0) }
    val adicionarMidia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) modelo.adicionarMidia(uri, importarComoFundo)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ladoDaPrevia = minOf(maxWidth - 16.dp, maxHeight * if (maxHeight < 440.dp) .25f else if (pincel && aba == Aba.FUNDO) .38f else .43f)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (modelo.editando != null) "Editar figurinha" else "Nova figurinha", style = MaterialTheme.typography.titleMedium)
            PreviaEditavelDaFigurinha(modelo, aba == Aba.FUNDO && pincel, apagar, tamanhoDoPincel, Modifier.size(ladoDaPrevia))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                Aba.entries.forEach { opcao ->
                    AcaoDoEditor(AcaoEditor.valueOf(opcao.name), opcao.rotulo, selecionada = aba == opcao) { aba = opcao }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rolagemDosAjustes).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(origemSelecionada?.nome ?: modelo.camadas.firstOrNull { it.id == modelo.camadaSelecionada }?.nome ?: origem.nome,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                when (aba) {
                    Aba.CAMADAS -> {
                        AcaoDoEditor(AcaoEditor.EMOJI, "Stickers e GIF stickers") { catalogoAberto = true }
                        Dica("Selecione uma camada para ajustar posição, giro, filtro ou recorte sem alterar as outras.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AcaoDoEditor(AcaoEditor.ADICIONAR, "Adicionar mídia, sticker ou GIF") { importarComoFundo = false; adicionarMidia.launch(arrayOf("image/*", "video/*")) }
                            AcaoDoEditor(AcaoEditor.TEXTO, "Adicionar texto") { modelo.adicionarTexto(); aba = Aba.TEXTO }
                            AcaoDoEditor(AcaoEditor.EMOJI, "Adicionar emoji") { aba = Aba.EMOJI }
                        }
                        FilterChip(selected = modelo.camadaSelecionada == null, onClick = { modelo.selecionarCamada(null) }, label = { Text("Mídia principal") })
                        modelo.camadas.sortedByDescending { it.ordem }.forEach { camada ->
                            Column {
                                FilterChip(selected = modelo.camadaSelecionada == camada.id, onClick = { modelo.selecionarCamada(camada.id) },
                                    label = { Text("${if (!camada.visivel) "Oculta · " else ""}${camada.nome}", maxLines = 1) })
                                FlowRow {
                                    AcaoDoEditor(AcaoEditor.SUBIR, "Trazer camada para frente") { modelo.moverCamada(camada.id, 1) }
                                    AcaoDoEditor(AcaoEditor.DESCER, "Levar camada para trás") { modelo.moverCamada(camada.id, -1) }
                                    AcaoDoEditor(AcaoEditor.VISIBILIDADE, if (camada.visivel) "Ocultar camada" else "Mostrar camada", selecionada = camada.visivel) { modelo.ocultarCamada(camada.id) }
                                    AcaoDoEditor(AcaoEditor.EXCLUIR, "Excluir camada") { modelo.excluirCamada(camada.id) }
                                }
                            }
                        }
                        Rotulado("Opacidade: ${(edicao.opacidade * 100).roundToInt()}%") {
                            DeslizanteComAjusteFino(edicao.opacidade, { modelo.mudar(edicao.copy(opacidade = it)) }, passo = .01f, descricao = "Opacidade")
                        }
                        FlowRow {
                            Mescla.entries.forEach { mescla ->
                                FilterChip(selected = edicao.mescla == mescla, onClick = { modelo.mudar(edicao.copy(mescla = mescla)) }, label = { Text(mescla.rotulo) })
                            }
                        }
                    }
                    Aba.ENQUADRAR -> {
                        Dica("Um dedo move. Dois toques e arraste no segundo inclinam frente/trás ou esquerda/direita. Pinça com dois dedos aproxima; torção gira no plano. As guias piscam quando a imagem fica no centro, encosta na borda ou se alinha com a camada de trás.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AcaoDoEditor(AcaoEditor.ENQUADRAR, "Preencher (1×1)") { modelo.preencher() }
                            AcaoDoEditor(AcaoEditor.CONTORNO, "Caber inteira") { modelo.ajustar(edicao.copy(zoom = 1f, deslocamentoX = 0f, deslocamentoY = 0f)) }
                            AcaoDoEditor(AcaoEditor.CENTRALIZAR, "Centralizar") { modelo.ajustar(edicao.copy(deslocamentoX = 0f, deslocamentoY = 0f)) }
                        }
                        Rotulado("Zoom: ${"%.2f".format(edicao.zoom)}×") { DeslizanteComAjusteFino(edicao.zoom.coerceIn(0.3f, 6f), { modelo.ajustar(edicao.copy(zoom = it)) }, limites = 0.3f..6f, passo = .01f, descricao = "Zoom") }
                        Rotulado("Horizontal") { DeslizanteComAjusteFino(edicao.deslocamentoX, { modelo.ajustar(edicao.copy(deslocamentoX = it)) }, limites = -1f..1f, passo = .01f, descricao = "Horizontal") }
                        Rotulado("Vertical") { DeslizanteComAjusteFino(edicao.deslocamentoY, { modelo.ajustar(edicao.copy(deslocamentoY = it)) }, limites = -1f..1f, passo = .01f, descricao = "Vertical") }
                        Rotulado("Giro no plano (Z): $giroVisivel°") {
                            DeslizanteComAjusteFino(giroVisivel.toFloat(), { modelo.ajustar(edicao.copy(giro = it.roundToInt())) }, limites = -180f..180f, passo = 1f, descricao = "Giro Z")
                        }
                        Rotulado("Inclinar para frente / trás (X): ${edicao.inclinacaoX.roundToInt()}°") {
                            DeslizanteComAjusteFino(edicao.inclinacaoX, { modelo.mudar(edicao.copy(inclinacaoX = it)) }, limites = -180f..180f, passo = 1f, descricao = "Inclinação X")
                        }
                        Rotulado("Inclinar para esquerda / direita (Y): ${edicao.inclinacaoY.roundToInt()}°") {
                            DeslizanteComAjusteFino(edicao.inclinacaoY, { modelo.mudar(edicao.copy(inclinacaoY = it)) }, limites = -180f..180f, passo = 1f, descricao = "Inclinação Y")
                        }
                        Rotulado("Girar e espelhar") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AcaoDoEditor(AcaoEditor.GIRAR_ESQUERDA, "Girar à esquerda") { modelo.mudar(edicao.copy(giro = (edicao.giro + 90).mod(360) - 180)) }
                                AcaoDoEditor(AcaoEditor.GIRAR_DIREITA, "Girar à direita") { modelo.mudar(edicao.copy(giro = (edicao.giro + 270).mod(360) - 180)) }
                                AcaoDoEditor(AcaoEditor.ESPELHAR, "Espelhar horizontalmente", selecionada = edicao.espelharHorizontal) { modelo.mudar(edicao.copy(espelharHorizontal = !edicao.espelharHorizontal)) }
                                AcaoDoEditor(AcaoEditor.INVERTER, "De cabeça para baixo", selecionada = edicao.espelharVertical) { modelo.mudar(edicao.copy(espelharVertical = !edicao.espelharVertical)) }
                            }
                        }
                        AcaoDoEditor(AcaoEditor.REDEFINIR, "Redefinir posição e giro") { modelo.ajustar(edicao.copy(zoom = 1f, deslocamentoX = 0f, deslocamentoY = 0f, giro = 0, inclinacaoX = 0f, inclinacaoY = 0f)) }
                    }
                    Aba.FUNDO -> {
                        Linha("Remover o fundo automaticamente", edicao.removerFundo) { modelo.mudar(edicao.copy(removerFundo = it)) }
                        if (modelo.animada && origemSelecionada?.tipo in listOf(TipoDaOrigem.VIDEO, TipoDaOrigem.GIF)) {
                            Dica("Na figurinha animada o fundo é removido em cada quadro ao salvar (leva um pouco mais).")
                        } else {
                            Linha("Pincel (ajuste à mão)", pincel) { pincel = it }
                            if (pincel) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AcaoDoEditor(AcaoEditor.APAGAR, "Apagar com o pincel", selecionada = apagar) { apagar = true }
                                    AcaoDoEditor(AcaoEditor.PINCEL, "Restaurar com o pincel", selecionada = !apagar) { apagar = false }
                                }
                                Dica("Toque e arraste para posicionar o pincel acima do dedo. Dê dois toques e arraste no segundo para apagar ou restaurar. Use caixas de zoom para trabalhar nos detalhes; dois dedos movem a área ampliada.")
                                Rotulado("Tamanho do pincel") { DeslizanteComAjusteFino(tamanhoDoPincel, { tamanhoDoPincel = it }, limites = 0.01f..0.15f, passo = .001f, descricao = "Tamanho do pincel") }
                            }
                        }
                        Text("Cor do fundo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SeletorDeCor(listOf(CorAndroid.TRANSPARENT) + CORES, modelo.corDeFundo) { modelo.mudarFundo(it, modelo.corFinalDoFundo) }
                        Linha("Fundo em gradiente", modelo.corFinalDoFundo != null) { modelo.mudarFundo(modelo.corDeFundo, if (it) CORES[2] else null) }
                        modelo.corFinalDoFundo?.let { cor ->
                            Text("Segunda cor")
                            SeletorDeCor(CORES, cor) { modelo.mudarFundo(modelo.corDeFundo, it) }
                        }
                        AcaoDoEditor(AcaoEditor.FUNDO, "Usar mídia como fundo") { importarComoFundo = true; adicionarMidia.launch(arrayOf("image/*", "video/*")) }
                    }
                    Aba.FILTRO -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Filtro.entries.forEach { filtro ->
                                FilterChip(selected = edicao.filtro == filtro, onClick = { modelo.mudar(edicao.copy(filtro = filtro)) }, label = { Text(filtro.rotulo, fontSize = 12.sp) })
                            }
                        }
                        Rotulado("Brilho") { DeslizanteComAjusteFino(edicao.brilho, { modelo.mudar(edicao.copy(brilho = it)) }, limites = -1f..1f, passo = .01f, descricao = "Brilho") }
                        Rotulado("Contraste") { DeslizanteComAjusteFino(edicao.contraste, { modelo.mudar(edicao.copy(contraste = it)) }, limites = -0.8f..1f, passo = .01f, descricao = "Contraste") }
                        Rotulado("Saturação") { DeslizanteComAjusteFino(edicao.saturacao, { modelo.mudar(edicao.copy(saturacao = it)) }, limites = -1f..1f, passo = .01f, descricao = "Saturação") }
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
                        Rotulado("Tamanho") { DeslizanteComAjusteFino(edicao.tamanhoDoTexto, { modelo.mudar(edicao.copy(tamanhoDoTexto = it)) }, limites = 28f..120f, passo = 1f, descricao = "Tamanho do texto") }
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
                            Rotulado("Largura") { DeslizanteComAjusteFino(edicao.larguraDoContorno, { modelo.mudar(edicao.copy(larguraDoContorno = it)) }, limites = 4f..24f, passo = 1f, descricao = "Largura do contorno") }
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
                                        modelo.mudarEmojis(when {
                                            marcado && modelo.emojis.size > 1 -> modelo.emojis - emoji
                                            !marcado && modelo.emojis.size < Regras.EMOJIS_MAX -> modelo.emojis + emoji
                                            else -> modelo.emojis
                                        })
                                    },
                                    label = { Text(emoji, fontSize = 18.sp) },
                                )
                            }
                        }
                    }
                }

                // Vídeo e GIF: estática (um quadro) ou animada (trecho de até 10 s).
                if (modelo.temAnimacao) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Reeditando, o tipo segue o do pacote (o WhatsApp não mistura estática e animada).
                        FilterChip(selected = !modelo.animada, onClick = { modelo.mudarAnimada(false) }, enabled = modelo.editando == null, label = { Text("Estática (um quadro)") })
                        FilterChip(selected = modelo.animada, onClick = { modelo.mudarAnimada(true) }, enabled = modelo.editando == null, label = { Text("Animada (até 10 s)") })
                    }
                    if (origemSelecionada?.tipo == TipoDaOrigem.VIDEO) {
                        val opcoes = Opcoes(formato = "mp4", inicioSegundos = modelo.inicioSelecionado, fimSegundos = modelo.fimSelecionado)
                        androidx.compose.runtime.key(modelo.camadaSelecionada, origemSelecionada.uri) {
                        PreviaDoTrecho(origemSelecionada.uri, video = true, opcoes = opcoes,
                            aoEscolherInstante = modelo::mostrarInstanteDaSelecionada) { novas ->
                            val ate = if (novas.fimSegundos > 0) minOf(novas.fimSegundos, novas.inicioSegundos + Regras.ANIMADA_MAX_SEGUNDOS) else novas.inicioSegundos + Regras.ANIMADA_MAX_SEGUNDOS
                            modelo.mudarTrechoSelecionado(novas.inicioSegundos, ate)
                        }
                        }
                        Dica(if (!modelo.animada) "A figurinha estática usa o quadro marcado em Início."
                            else if (modelo.camadaSelecionada == null) "A figurinha animada usa o trecho entre Início e Fim (no máximo 10 s)."
                            else "O corte vale só para esta camada. O trecho se repete até o fim da composição.")
                    } else if (origemSelecionada?.tipo == TipoDaOrigem.GIF) {
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
                            DeslizanteComAjusteFino((modelo.fim - modelo.inicio).toFloat().coerceIn(0.2f, 10f),
                                { modelo.mudarTrecho(modelo.inicio, modelo.inicio + it) }, limites = 0.2f..10f, passo = .1f, descricao = "Duração")
                        }
                        Rotulado("Prévia: ${"%.1f".format(modelo.tempoDaPrevia)} s") {
                            DeslizanteComAjusteFino(modelo.tempoDaPrevia.coerceIn(0f, (modelo.fim - modelo.inicio).toFloat().coerceAtLeast(0.2f)),
                                modelo::mostrarInstante, limites = 0f..(modelo.fim - modelo.inicio).toFloat().coerceAtLeast(0.2f), passo = 1f / modelo.quadrosPorSegundo, descricao = "Instante da prévia")
                        }
                    }
                }

            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                AcaoDoEditor(AcaoEditor.DESFAZER, "Desfazer alteração", habilitada = modelo.podeDesfazer) { modelo.desfazer() }
                AcaoDoEditor(AcaoEditor.REFAZER, "Refazer alteração", habilitada = modelo.podeRefazer) { modelo.refazer() }
                AcaoDoEditor(AcaoEditor.TOCAR, "Ver como vai ficar", habilitada = modelo.previa != null) { modelo.verComoFica() }
                AcaoDoEditor(AcaoEditor.CANCELAR, "Cancelar edição") { modelo.fecharEditor() }
                AcaoDoEditor(AcaoEditor.SALVAR, if (modelo.editando != null) "Salvar alterações" else "Salvar no pacote", habilitada = modelo.previa != null) {
                    if (modelo.editando != null) modelo.salvar(null, "") else salvando = true
                }
            }
        }
    }

    if (salvando) EscolherPacote(modelo) { salvando = false }
    if (modelo.exibindoPreviaFinal) PreviaFinal(modelo) {
        modelo.exibindoPreviaFinal = false
        if (modelo.editando != null) modelo.salvar(null, "") else salvando = true
    }
}

/**
 * As guias por cima da prévia: a cruz do centro, fraca, enquanto o dedo mexe; e, piscando, cada
 * linha em que a imagem encaixou (centro, borda, outra camada). Vibra de leve ao encaixar.
 */
@Composable
internal fun GuiasDoEnquadramento(modelo: FigurinhasViewModel) {
    if (!modelo.mostrandoGuias) return
    val vibrar = LocalHapticFeedback.current
    val quantas = modelo.guias.size + if (modelo.giroReto) 1 else 0
    LaunchedEffect(quantas) { if (quantas > 0) vibrar.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val pisca by rememberInfiniteTransition(label = "guias").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "pisca",
    )
    val guias = modelo.guias
    Canvas(Modifier.fillMaxSize()) {
        val fraca = Color.White.copy(alpha = 0.22f)
        drawLine(fraca, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1.dp.toPx())
        drawLine(fraca, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        guias.forEach { guia ->
            val cor = when (guia.tipo) {
                Guias.Tipo.CENTRO -> Cores.laranja
                Guias.Tipo.BORDA -> Color(0xFF4FC3F7)
                Guias.Tipo.CAMADA -> Color(0xFFE040FB)
            }.copy(alpha = pisca)
            // A borda fica colada no canto arredondado: mais grossa para aparecer.
            val largura = (if (guia.tipo == Guias.Tipo.BORDA) 5.dp else 2.dp).toPx()
            if (guia.vertical) {
                val x = (guia.posicao * size.width).coerceIn(largura / 2, size.width - largura / 2)
                drawLine(cor, Offset(x, 0f), Offset(x, size.height), strokeWidth = largura)
            } else {
                val y = (guia.posicao * size.height).coerceIn(largura / 2, size.height - largura / 2)
                drawLine(cor, Offset(0f, y), Offset(size.width, y), strokeWidth = largura)
            }
        }
    }
    if (modelo.giroReto) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                "Reta ✓", color = Color.White.copy(alpha = pisca), fontSize = 12.sp,
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * A figurinha de verdade, gerada pelo mesmo caminho do Salvar, tocando em loop: o que se vê aqui
 * é exatamente o que vai para o pacote (duração, quadros por segundo, enquadramento, tamanho).
 */
@Composable
private fun PreviaFinal(modelo: FigurinhasViewModel, salvar: () -> Unit) {
    val previa = modelo.previaFinal ?: return
    AlertDialog(
        onDismissRequest = { modelo.exibindoPreviaFinal = false },
        title = { Text("Assim vai ficar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).fundoXadrez()) {
                    AndroidView(
                        factory = { contexto -> android.widget.ImageView(contexto).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                        update = { vista ->
                            val desenho = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(previa.arquivo))
                            vista.setImageDrawable(desenho)
                            (desenho as? android.graphics.drawable.AnimatedImageDrawable)?.apply { repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; start() }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val kb = (previa.bytes + 1023) / 1024
                Text(
                    if (previa.animada) "${"%.1f".format(previa.segundos)} s · ${previa.quadrosPorSegundo} quadros/s · $kb KB de ${Regras.ANIMADA_MAX_BYTES / 1024} KB"
                    else "Estática · $kb KB de ${Regras.ESTATICA_MAX_BYTES / 1024} KB",
                    fontSize = 13.sp,
                )
                if (previa.encurtada) {
                    Text(
                        "O vídeo tem movimento demais para os 500 KB do WhatsApp: para manter ${previa.quadrosPorSegundo} quadros/s, ela ficou com " +
                            "${"%.1f".format(previa.segundos)} s. Com menos quadros por segundo ou um trecho mais calmo, ela fica mais longa.",
                        fontSize = 12.sp, color = Cores.amarelo,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = salvar) { Text(if (modelo.editando != null) "Salvar alterações" else "Salvar no pacote") } },
        dismissButton = { TextButton(onClick = { modelo.exibindoPreviaFinal = false }) { Text("Ajustar mais") } },
    )
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
        Switch(checked = ligado, onCheckedChange = mudar, modifier = Modifier.semantics { contentDescription = rotulo })
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
