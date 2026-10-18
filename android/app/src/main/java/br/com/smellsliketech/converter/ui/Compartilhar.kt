package br.com.smellsliketech.converter.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.smellsliketech.converter.conversao.Resultado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Os apps mais usados para mandar um arquivo, na ordem em que aparecem. Só os que estão
 * instalados e aceitam aquele tipo de arquivo viram ícone; "Outros" abre a lista do Android.
 */
private val PREFERIDOS = listOf(
    "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "org.telegram.messenger",
    "com.facebook.katana", "com.facebook.orca", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
    "com.twitter.android", "com.discord", "org.thoughtcrime.securesms", "com.google.android.gm",
    "com.google.android.apps.docs",
)

private const val MAXIMO_DE_ICONES = 7

private class Destino(val componente: ComponentName, val rotulo: String, val icone: ImageBitmap)

/** Ícones dos apps para mandar o resultado direto, mais "Outros". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarraDeCompartilhar(resultado: Resultado) {
    val contexto = LocalContext.current
    val destinos by produceState(emptyList<Destino>(), resultado.mime) {
        value = withContext(Dispatchers.IO) { destinos(contexto, resultado.mime) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        destinos.forEach { destino ->
            Atalho(destino.rotulo, onClick = { enviar(contexto, resultado, destino.componente) }) {
                Image(destino.icone, contentDescription = "Enviar pelo ${destino.rotulo}", modifier = Modifier.size(36.dp))
            }
        }
        Atalho("Outros", onClick = { enviarPelaLista(contexto, resultado) }) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("⋯", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Atalho(rotulo: String, onClick: () -> Unit, icone: @Composable () -> Unit) {
    Column(
        Modifier.width(52.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icone()
        Text(rotulo, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Os apps preferidos que aceitam este tipo de arquivo, com o ícone e o nome que o celular mostra. */
private fun destinos(contexto: Context, mime: String): List<Destino> {
    val gerente = contexto.packageManager
    val candidatos: List<ResolveInfo> = runCatching {
        gerente.queryIntentActivities(Intent(Intent.ACTION_SEND).setType(mime), PackageManager.MATCH_DEFAULT_ONLY)
    }.getOrDefault(emptyList())
    val porPacote = candidatos.groupBy { it.activityInfo.packageName }
    return PREFERIDOS.mapNotNull { pacote ->
        // Alguns apps têm vários alvos (feed, story, conversa): o primeiro é o principal.
        val alvo = porPacote[pacote]?.firstOrNull() ?: return@mapNotNull null
        val rotulo = runCatching { gerente.getApplicationLabel(alvo.activityInfo.applicationInfo).toString() }.getOrDefault(pacote)
        val icone = runCatching { alvo.activityInfo.applicationInfo.loadIcon(gerente).paraBitmap(96).asImageBitmap() }.getOrNull()
            ?: return@mapNotNull null
        Destino(ComponentName(alvo.activityInfo.packageName, alvo.activityInfo.name), rotulo, icone)
    }.take(MAXIMO_DE_ICONES)
}

private fun android.graphics.drawable.Drawable.paraBitmap(lado: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, lado, lado)
    draw(Canvas(bitmap))
    return bitmap
}

private fun intencao(resultado: Resultado): Intent =
    Intent(Intent.ACTION_SEND).setType(resultado.mime).putExtra(Intent.EXTRA_STREAM, resultado.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

private fun enviar(contexto: Context, resultado: Resultado, componente: ComponentName) {
    runCatching { contexto.startActivity(intencao(resultado).setComponent(componente)) }
        .onFailure { enviarPelaLista(contexto, resultado) }
}

fun enviarPelaLista(contexto: Context, resultado: Resultado) {
    runCatching { contexto.startActivity(Intent.createChooser(intencao(resultado), "Enviar ${resultado.nome}")) }
}
