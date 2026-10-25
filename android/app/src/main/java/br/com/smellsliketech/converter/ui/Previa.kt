package br.com.smellsliketech.converter.ui

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Relogio
import br.com.smellsliketech.converter.conversao.TempoDoCorte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Velocidades da prévia: devagar para achar o quadro exato, rápido para passar o resto. */
private val VELOCIDADES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)

/**
 * O que a prévia guarda ao entrar e sair da tela cheia: o player é recriado no outro lugar e
 * volta para o mesmo ponto, na mesma velocidade.
 */
private class EstadoDaPrevia {
    var posicao by mutableDoubleStateOf(0.0)
    var velocidade by mutableFloatStateOf(1f)
    var quadrosPorSegundo by mutableDoubleStateOf(30.0)
}

/**
 * O mini player do corte: assiste (ou ouve) o arquivo, anda quadro a quadro, toca devagar
 * (até 0,25x), marca início e fim no ponto exato (milissegundo) e toca só o trecho. Abre em
 * tela cheia com os mesmos controles embaixo. Usa o player do próprio Android, no aparelho;
 * nada é convertido aqui. Com vários arquivos, a prévia mostra o primeiro e o corte vale
 * para todos.
 */
@Composable
fun PreviaDoTrecho(uri: Uri, video: Boolean, opcoes: Opcoes, posicaoInicial: Double = opcoes.inicioSegundos,
    aoEscolherInstante: (Double) -> Unit = {}, mudar: (Opcoes) -> Unit) {
    val contexto = LocalContext.current
    val estado = remember(uri) { EstadoDaPrevia().apply { posicao = posicaoInicial.coerceAtLeast(0.0) } }
    var telaCheia by remember(uri) { mutableStateOf(false) }

    // A taxa de quadros do arquivo, para andar exatamente um quadro (30 quando não informa).
    LaunchedEffect(uri) {
        if (video) quadrosDoVideo(contexto, uri)?.let { estado.quadrosPorSegundo = it }
    }

    key(uri) {
    if (telaCheia) {
        Text("A prévia está em tela cheia.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Dialog(
            onDismissRequest = { telaCheia = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Player(uri, video, opcoes, mudar, estado, telaCheia = true, aoEscolherInstante = aoEscolherInstante) { telaCheia = false }
            }
        }
    } else {
        Player(uri, video, opcoes, mudar, estado, telaCheia = false, aoEscolherInstante = aoEscolherInstante) { telaCheia = true }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Player(
    uri: Uri,
    video: Boolean,
    opcoes: Opcoes,
    mudar: (Opcoes) -> Unit,
    estado: EstadoDaPrevia,
    telaCheia: Boolean,
    aoEscolherInstante: (Double) -> Unit,
    alternarTelaCheia: () -> Unit,
) {
    val contexto = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var duracao by remember { mutableDoubleStateOf(0.0) }
    var tocando by remember { mutableStateOf(false) }
    var falhou by remember { mutableStateOf(false) }
    // "Tocar o trecho" para sozinho ao chegar no fim marcado.
    var pararEm by remember { mutableStateOf<Double?>(null) }

    fun preparado(mp: MediaPlayer) {
        player = mp
        duracao = mp.duration / 1000.0
        mp.setOnSeekCompleteListener { estado.posicao = it.currentPosition / 1000.0 }
        // Volta ao ponto em que estava (ou mostra o primeiro quadro em vez da tela preta).
        mp.seekTo((estado.posicao * 1000).toLong().coerceAtLeast(1), MediaPlayer.SEEK_CLOSEST)
    }

    val audio = remember { if (video) null else Audio(contexto, uri, ::preparado) { falhou = true } }
    DisposableEffect(Unit) {
        onDispose {
            audio?.liberar()
            videoView?.stopPlayback()
            player = null
        }
    }

    fun tocar() {
        val mp = player ?: return
        if (video) videoView?.start() else mp.start()
        // A velocidade só vale com o player andando; aplicada parada, alguns aparelhos já tocam.
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(estado.velocidade) }
        tocando = true
    }

    fun pausar() {
        val mp = player ?: return
        val agora = runCatching { mp.currentPosition / 1000.0 }.getOrNull()
        if (video) videoView?.pause() else if (mp.isPlaying) mp.pause()
        tocando = false
        pararEm = null
        if (agora != null && agora > 0) { estado.posicao = agora; aoEscolherInstante(agora) }
    }

    fun irPara(segundos: Double) {
        val mp = player ?: return
        estado.posicao = segundos.coerceIn(0.0, duracao)
        mp.seekTo((estado.posicao * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        aoEscolherInstante(estado.posicao)
    }

    fun quadro(sentido: Int) {
        pausar()
        irPara(estado.posicao + sentido / estado.quadrosPorSegundo)
    }

    // Lê a posição enquanto toca: move a barra e para no fim do trecho.
    LaunchedEffect(player, tocando) {
        val mp = player ?: return@LaunchedEffect
        while (tocando) {
            estado.posicao = mp.currentPosition / 1000.0
            pararEm?.let { fim -> if (estado.posicao >= fim) pausar() }
            if (!mp.isPlaying && estado.posicao >= duracao - 0.05) tocando = false
            delay(50)
        }
    }

    val inicio = opcoes.inicioSegundos.coerceIn(0.0, duracao.coerceAtLeast(0.0))
    val fim = (if (opcoes.fimSegundos > 0) opcoes.fimSegundos else duracao).coerceIn(inicio, duracao.coerceAtLeast(inicio))

    Column(
        Modifier.fillMaxWidth().then(if (telaCheia) Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding() else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (telaCheia) {
            // Em tela cheia a saída fica no alto, sempre à vista; os outros controles ficam embaixo.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { pausar(); alternarTelaCheia() }) { Text("Sair da tela cheia", fontSize = 12.sp) }
            }
        }
        if (video && !falhou) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp -> preparado(mp) }
                        setOnErrorListener { _, _, _ -> falhou = true; true }
                        setOnCompletionListener { tocando = false; pararEm = null; estado.posicao = duracao }
                        setVideoURI(uri)
                        videoView = this
                    }
                },
                modifier = if (telaCheia) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            )
        }

        if (falhou) {
            Text("A prévia não abriu este arquivo; o corte continua funcionando pelos campos.", color = Cores.amarelo, fontSize = 13.sp)
            if (telaCheia) OutlinedButton(onClick = alternarTelaCheia) { Text("Fechar") }
            return@Column
        }
        if (duracao <= 0) {
            Text("Carregando a prévia…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            return@Column
        }

        // Em tela cheia os controles ficam embaixo do vídeo e rolam se o celular estiver deitado.
        Column(
            (if (telaCheia) Modifier.padding(horizontal = 12.dp).verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${Relogio.completo(estado.posicao)} / ${Relogio.completo(duracao)}",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White.takeIf { telaCheia } ?: Color.Unspecified,
            )
            Slider(value = estado.posicao.toFloat(), onValueChange = { irPara(it.toDouble()) }, valueRange = 0f..duracao.toFloat())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (video) OutlinedButton(onClick = { quadro(-1) }) { Text("◀ quadro", fontSize = 12.sp) }
                OutlinedButton(onClick = { if (tocando) pausar() else tocar() }) { Text(if (tocando) "Pausar" else "Tocar") }
                if (video) OutlinedButton(onClick = { quadro(1) }) { Text("quadro ▶", fontSize = 12.sp) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VELOCIDADES.forEach { velocidade ->
                    FilterChip(
                        selected = estado.velocidade == velocidade,
                        onClick = {
                            estado.velocidade = velocidade
                            if (tocando) player?.let { runCatching { it.playbackParams = PlaybackParams().setSpeed(velocidade) } }
                        },
                        label = { Text("${velocidade.toString().removeSuffix(".0").replace('.', ',')}x", fontSize = 12.sp) },
                    )
                }
            }
            Text(
                "Trecho: ${Relogio.completo(inicio)} até ${Relogio.completo(fim)} · ${Relogio.completo(fim - inicio)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            RangeSlider(
                value = inicio.toFloat()..fim.toFloat(),
                onValueChange = { faixa ->
                    val novoInicio = Relogio.milesimo(faixa.start.toDouble())
                    val novoFim = Relogio.milesimo(faixa.endInclusive.toDouble())
                    // Mostra o quadro da ponta que está sendo arrastada.
                    mudar(opcoes.copy(inicioSegundos = novoInicio, fimSegundos = novoFim))
                    irPara(if (novoInicio != Relogio.milesimo(inicio)) novoInicio else novoFim)
                },
                valueRange = 0f..duracao.toFloat(),
            )
            CampoDeTempoDoCorte("Início", inicio, 0.0, (duracao - .001).coerceAtLeast(0.0), 1.0 / estado.quadrosPorSegundo) { de ->
                val ate = if (fim > de) fim else minOf(duracao, de + (fim - inicio).coerceAtLeast(.001))
                mudar(opcoes.copy(inicioSegundos = de, fimSegundos = ate))
                irPara(de)
            }
            CampoDeTempoDoCorte("Fim", fim, inicio + .001, duracao.coerceAtLeast(inicio + .001), 1.0 / estado.quadrosPorSegundo) { ate ->
                mudar(opcoes.copy(fimSegundos = ate))
                irPara(ate)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    val agora = Relogio.milesimo(estado.posicao)
                    // Início depois do fim marcado: o fim volta para o fim do arquivo.
                    mudar(opcoes.copy(inicioSegundos = agora, fimSegundos = if (opcoes.fimSegundos in 0.0..agora) 0.0 else opcoes.fimSegundos))
                }) { Text("Início aqui", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val agora = Relogio.milesimo(estado.posicao)
                    if (agora > inicio) mudar(opcoes.copy(fimSegundos = agora))
                }) { Text("Fim aqui", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    irPara(inicio)
                    pararEm = fim
                    tocar()
                }) { Text("Tocar o trecho", fontSize = 12.sp) }
                if (!telaCheia) OutlinedButton(onClick = { pausar(); alternarTelaCheia() }) { Text("Tela cheia", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun CampoDeTempoDoCorte(rotulo: String, valor: Double, minimo: Double, maximo: Double,
    passo: Double, mudar: (Double) -> Unit) {
    var texto by remember { mutableStateOf(Relogio.completo(valor)) }
    var focado by remember { mutableStateOf(false) }
    val foco = LocalFocusManager.current
    val digitado = TempoDoCorte.ler(texto)
    val valido = digitado != null && digitado in minimo..maximo
    LaunchedEffect(valor) { if (!focado) texto = Relogio.completo(valor) }
    fun aplicar() {
        if (valido) {
            val novo = Relogio.milesimo(digitado!!)
            texto = Relogio.completo(novo)
            mudar(novo)
        }
    }
    fun ajustar(sentido: Int) {
        val novo = Relogio.milesimo((if (valido) digitado!! else valor) + sentido * passo).coerceIn(minimo, maximo)
        texto = Relogio.completo(novo)
        mudar(novo)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AcaoDoEditor(AcaoEditor.DIMINUIR, "Diminuir $rotulo do trecho", habilitada = valor > minimo) { ajustar(-1) }
        OutlinedTextField(texto, { texto = it }, label = { Text("$rotulo do trecho") }, singleLine = true,
            trailingIcon = { AcaoDoEditor(AcaoEditor.SALVAR, "Aplicar $rotulo do trecho", habilitada = valido) { aplicar(); foco.clearFocus() } },
            supportingText = { Text(if (valido) "Tempo ou segundos, ex.: 0:05,125 ou 5.125" else "Informe um tempo entre ${Relogio.completo(minimo)} e ${Relogio.completo(maximo)}") },
            isError = !valido, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { aplicar(); foco.clearFocus() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = "$rotulo do trecho" }.onFocusChanged { estado ->
                val perdeuFoco = focado && !estado.isFocused
                focado = estado.isFocused
                if (perdeuFoco) aplicar()
            })
        AcaoDoEditor(AcaoEditor.ADICIONAR, "Aumentar $rotulo do trecho", habilitada = valor < maximo) { ajustar(1) }
    }
}

/** Quadros por segundo da trilha de vídeo, ou nulo se o arquivo não diz. */
private suspend fun quadrosDoVideo(contexto: Context, uri: Uri): Double? = withContext(Dispatchers.IO) {
    val extrator = MediaExtractor()
    try {
        extrator.setDataSource(contexto, uri, null)
        (0 until extrator.trackCount).map { extrator.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
            ?.let { formato ->
                runCatching { formato.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { formato.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
            }
            ?.takeIf { it in 1.0..240.0 }
    } catch (_: Exception) {
        null
    } finally {
        extrator.release()
    }
}

/** Áudio sem tela: um MediaPlayer preparado de forma assíncrona. */
private class Audio(contexto: Context, uri: Uri, pronto: (MediaPlayer) -> Unit, erro: () -> Unit) {
    private val player = MediaPlayer()

    init {
        try {
            player.setDataSource(contexto, uri)
            player.setOnPreparedListener { pronto(it) }
            player.setOnErrorListener { _, _, _ -> erro(); true }
            player.prepareAsync()
        } catch (_: Exception) {
            erro()
        }
    }

    fun liberar() = player.release()
}
