package br.com.smellsliketech.converter.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.smellsliketech.converter.conversao.Resultado
import br.com.smellsliketech.converter.estudio.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CORREIO = "converter@smellsliketech.com.br"
private const val APLICATIVO = "br.com.smellsliketech.converter"

/** Pacotes da rede escolhida no início, para ela aparecer primeiro ao compartilhar. */
private fun pacotesDaRede(preset: Preset?): List<String> = when (preset?.id?.substringBefore('-')) {
    "instagram" -> listOf("com.instagram.android")
    "tiktok" -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    "kwai" -> listOf("com.kwai.video")
    "youtube" -> listOf("com.google.android.youtube")
    "facebook" -> listOf("com.facebook.katana")
    "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
    "x" -> listOf("com.twitter.android")
    "linkedin" -> listOf("com.linkedin.android")
    "pinterest" -> listOf("com.pinterest")
    "threads" -> listOf("com.instagram.barcelona")
    else -> emptyList()
}

/**
 * O resultado da exportação, logo que ela termina: o vídeo toca no player do app, o envio
 * prioriza a rede escolhida no início ("Outros" cobre o resto) e o feedback é obrigatório,
 * com nome, e-mail e mensagem, abrindo o app de e-mail para converter@smellsliketech.com.br.
 */
@Composable
fun ResultadoFinal(resultado: Resultado, preset: Preset?, fechar: () -> Unit) {
    val contexto = LocalContext.current
    Dialog(onDismissRequest = fechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(14.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Pronto!", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${resultado.nome} · ${tamanho(resultado.bytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PreviaDoResultado(resultado)
                Text("Enviar para", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BarraDeCompartilhar(resultado, pacotesDaRede(preset))
                if (preset != null && preset.id != "personalizado") {
                    Text("${preset.rede} aparece primeiro porque foi a rede escolhida; toque em Outros para o resto.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { avaliarNaPlay(contexto) }, modifier = Modifier.fillMaxWidth()) { Text("Avaliar na Google Play") }
                FormularioDeFeedback()
                TextButton(onClick = fechar, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Fechar") }
            }
        }
    }
}

@Composable
private fun PreviaDoResultado(resultado: Resultado) {
    val contexto = LocalContext.current
    when {
        resultado.mime.startsWith("video/") -> AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(resultado.uri)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { tocador -> tocador.isLooping = true; start() }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        )
        resultado.mime.startsWith("image/") -> {
            val imagem by produceState<ImageBitmap?>(null, resultado.uri) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(contexto.contentResolver, resultado.uri)
                        ) { decodificador, informacao, _ ->
                            // Só para exibir: acima de 1280 px o aparelho desperdiça memória (recomendação da Play).
                            val maior = maxOf(informacao.size.width, informacao.size.height)
                            if (maior > 1280) {
                                val escala = 1280.0 / maior
                                decodificador.setTargetSize(
                                    (informacao.size.width * escala).toInt().coerceAtLeast(1),
                                    (informacao.size.height * escala).toInt().coerceAtLeast(1)
                                )
                            }
                        }.asImageBitmap()
                    }.getOrNull()
                }
            }
            imagem?.let {
                Image(it, contentDescription = resultado.nome, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
            }
        }
        resultado.mime.startsWith("audio/") -> PlayerDeAudio(resultado)
    }
}

@Composable
private fun PlayerDeAudio(resultado: Resultado) {
    val contexto = LocalContext.current
    var tocando by remember { mutableStateOf(false) }
    val tocador = remember { MediaPlayer() }
    DisposableEffect(resultado.uri) {
        runCatching { tocador.setDataSource(contexto, resultado.uri); tocador.prepare() }
        onDispose { runCatching { tocador.release() } }
    }
    tocador.setOnCompletionListener { tocando = false }
    OutlinedButton(onClick = {
        runCatching {
            if (tocando) { tocador.pause(); tocando = false } else { tocador.start(); tocando = true }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text(if (tocando) "Pausar" else "Ouvir") }
}

private fun avaliarNaPlay(contexto: Context) {
    runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$APLICATIVO"))) }
        .onFailure {
            runCatching { contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$APLICATIVO"))) }
        }
}

@Composable
private fun FormularioDeFeedback() {
    val contexto = LocalContext.current
    var nome by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var mensagem by rememberSaveable { mutableStateOf("") }
    var aviso by remember { mutableStateOf<String?>(null) }
    val valido = nome.isNotBlank() && emailValido(email) && mensagem.isNotBlank()
    Text("Conte como foi", fontWeight = FontWeight.SemiBold)
    Text("Nome, e-mail e feedback são obrigatórios. A mensagem abre no seu app de e-mail.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(nome, { nome = it }, label = { Text("Seu nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(email, { email = it }, label = { Text("Seu e-mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(mensagem, { mensagem = it }, label = { Text("Seu feedback") }, minLines = 3, modifier = Modifier.fillMaxWidth())
    aviso?.let { Text(it, fontSize = 12.sp, color = Cores.amarelo) }
    Button(
        onClick = {
            val assunto = "Feedback do estúdio — ${nome.trim()}"
            val corpo = "Nome: ${nome.trim()}\nE-mail: ${email.trim()}\n\n${mensagem.trim()}"
            val uri = Uri.parse("mailto:$CORREIO?subject=${Uri.encode(assunto)}&body=${Uri.encode(corpo)}")
            runCatching { contexto.startActivity(Intent(Intent.ACTION_SENDTO, uri)) }
                .onFailure { aviso = "Nenhum app de e-mail abriu. Escreva para $CORREIO." }
        },
        enabled = valido,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Enviar feedback") }
}

private fun emailValido(email: String): Boolean =
    Regex("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$").matches(email.trim().lowercase())
