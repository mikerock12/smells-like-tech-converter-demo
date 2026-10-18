package br.com.smellsliketech.converter.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController.MediaPlayerControl
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Relogio
import kotlinx.coroutines.delay

/**
 * O mini player do corte: assiste (ou ouve) o arquivo, marca início e fim no ponto em que
 * está tocando ou arrastando a faixa, e toca só o trecho escolhido. Usa o player do próprio
 * Android, no aparelho; nada é convertido aqui. Com vários arquivos, a prévia mostra o
 * primeiro e o corte vale para todos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviaDoTrecho(uri: Uri, video: Boolean, opcoes: Opcoes, mudar: (Opcoes) -> Unit) {
    val contexto = LocalContext.current
    var reprodutor by remember(uri) { mutableStateOf<MediaPlayerControl?>(null) }
    var duracao by remember(uri) { mutableDoubleStateOf(0.0) }
    var posicao by remember(uri) { mutableDoubleStateOf(0.0) }
    var tocando by remember(uri) { mutableStateOf(false) }
    var falhou by remember(uri) { mutableStateOf(false) }
    // "Tocar o trecho" para sozinho ao chegar no fim marcado.
    var pararEm by remember(uri) { mutableStateOf<Double?>(null) }

    val audio = remember(uri) { if (video) null else Audio(contexto, uri, { duracao = it }, { falhou = true }) }
    DisposableEffect(uri) {
        if (audio != null) reprodutor = audio
        onDispose {
            audio?.liberar()
            (reprodutor as? VideoView)?.stopPlayback()
            reprodutor = null
        }
    }

    if (video && !falhou) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { mp ->
                        duracao = mp.duration / 1000.0
                        seekTo(1) // Mostra o primeiro quadro em vez da tela preta.
                    }
                    setOnErrorListener { _, _, _ -> falhou = true; true }
                    setOnCompletionListener { tocando = false; pararEm = null }
                    setVideoURI(uri)
                    reprodutor = this
                }
            },
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        )
    }

    // Lê a posição enquanto toca: move a barra e para no fim do trecho.
    LaunchedEffect(reprodutor, tocando) {
        val player = reprodutor ?: return@LaunchedEffect
        while (tocando) {
            posicao = player.currentPosition / 1000.0
            pararEm?.let { fim -> if (posicao >= fim) { player.pause(); tocando = false; pararEm = null } }
            delay(100)
        }
    }

    if (falhou) {
        Text("A prévia não abriu este arquivo; o corte continua funcionando pelos campos abaixo.", color = Cores.amarelo, fontSize = 13.sp)
        return
    }
    if (duracao <= 0) {
        Text("Carregando a prévia…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        return
    }

    val inicio = opcoes.inicioSegundos.coerceIn(0.0, duracao)
    val fim = (if (opcoes.fimSegundos > 0) opcoes.fimSegundos else duracao).coerceIn(inicio, duracao)
    fun irPara(segundos: Double) {
        posicao = segundos.coerceIn(0.0, duracao)
        reprodutor?.seekTo((posicao * 1000).toInt())
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val player = reprodutor ?: return@OutlinedButton
                if (tocando) { player.pause(); tocando = false; pararEm = null } else { player.start(); tocando = true }
            }) { Text(if (tocando) "Pausar" else "Tocar") }
            Text("${Relogio.escrever(posicao)} / ${Relogio.escrever(duracao)}", fontSize = 13.sp)
        }
        Slider(value = posicao.toFloat(), onValueChange = { irPara(it.toDouble()) }, valueRange = 0f..duracao.toFloat())
        Text(
            "Trecho: ${Relogio.escrever(inicio)} até ${Relogio.escrever(fim)} · ${Relogio.escrever(fim - inicio)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
        RangeSlider(
            value = inicio.toFloat()..fim.toFloat(),
            onValueChange = { faixa ->
                val novoInicio = Relogio.decimo(faixa.start.toDouble())
                val novoFim = Relogio.decimo(faixa.endInclusive.toDouble())
                // Mostra o quadro da ponta que está sendo arrastada.
                irPara(if (novoInicio != Relogio.decimo(inicio)) novoInicio else novoFim)
                mudar(opcoes.copy(inicioSegundos = novoInicio, fimSegundos = novoFim))
            },
            valueRange = 0f..duracao.toFloat(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val agora = Relogio.decimo(posicao)
                // Início depois do fim marcado: o fim volta para o fim do arquivo.
                mudar(opcoes.copy(inicioSegundos = agora, fimSegundos = if (opcoes.fimSegundos in 0.0..agora) 0.0 else opcoes.fimSegundos))
            }) { Text("Início aqui", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val agora = Relogio.decimo(posicao)
                if (agora > inicio) mudar(opcoes.copy(fimSegundos = agora))
            }) { Text("Fim aqui", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val player = reprodutor ?: return@OutlinedButton
                irPara(inicio)
                pararEm = fim
                player.start()
                tocando = true
            }) { Text("Tocar o trecho", fontSize = 12.sp) }
        }
    }
}

/** Áudio sem tela: o MediaPlayer atrás da mesma interface do VideoView. */
private class Audio(contexto: Context, uri: Uri, pronto: (Double) -> Unit, erro: () -> Unit) : MediaPlayerControl {
    private val player = MediaPlayer()
    private var preparado = false

    init {
        try {
            player.setDataSource(contexto, uri)
            player.setOnPreparedListener { preparado = true; pronto(it.duration / 1000.0) }
            player.setOnErrorListener { _, _, _ -> erro(); true }
            player.prepareAsync()
        } catch (_: Exception) {
            erro()
        }
    }

    fun liberar() = player.release()
    override fun start() { if (preparado) player.start() }
    override fun pause() { if (preparado && player.isPlaying) player.pause() }
    override fun getDuration() = if (preparado) player.duration else 0
    override fun getCurrentPosition() = if (preparado) player.currentPosition else 0
    override fun seekTo(pos: Int) { if (preparado) player.seekTo(pos) }
    override fun isPlaying() = preparado && player.isPlaying
    override fun getBufferPercentage() = 100
    override fun canPause() = true
    override fun canSeekBackward() = true
    override fun canSeekForward() = true
    override fun getAudioSessionId() = player.audioSessionId
}
