package br.com.smellsliketech.converter.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.smellsliketech.converter.catalogos.CatalogoDeStickers
import br.com.smellsliketech.converter.catalogos.ResultadoDoCatalogo
import br.com.smellsliketech.converter.catalogos.StickerDoCatalogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** O mesmo seletor serve às figurinhas e ao editor de imagem/vídeo. */
@Composable
fun SeletorDeStickers(secao: String, fechar: () -> Unit, adicionar: (File) -> Unit) {
    val contexto = LocalContext.current
    val catalogo = remember(secao) { CatalogoDeStickers.obter(contexto, secao) }
    var consulta by remember { mutableStateOf("") }
    var animado by remember { mutableStateOf(true) }
    var pagina by remember { mutableStateOf(0) }
    var resultado by remember { mutableStateOf<ResultadoDoCatalogo?>(null) }
    var buscando by remember { mutableStateOf(true) }
    var importando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    val escopo = rememberCoroutineScope()
    val miniaturas = remember { Semaphore(4) }

    // A espera pertence ao seletor: fechá-lo cancela pesquisas e a tentativa automática.
    LaunchedEffect(consulta, pagina) {
        resultado = null
        buscando = true
        delay(550)
        do {
            buscando = true
            try {
                resultado = catalogo.buscar(consulta, pagina)
                erro = null
            } catch (cancelado: CancellationException) { throw cancelado }
            catch (_: Exception) { erro = "Não foi possível abrir o catálogo. Tente novamente." }
            finally { buscando = false }
            val tentarEm = resultado?.tentarEm ?: break
            delay((tentarEm - System.currentTimeMillis()).coerceAtLeast(1000))
        } while (true)
    }

    Dialog(onDismissRequest = { if (!importando) fechar() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!animado, { animado = false }, label = { Text("Stickers") })
                    FilterChip(animado, { animado = true }, label = { Text("GIF stickers") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = fechar, enabled = !importando) { Text("Fechar") }
                }
                OutlinedTextField(consulta, { consulta = it.take(50); pagina = 0 }, singleLine = true,
                    label = { Text("Procurar stickers") }, modifier = Modifier.fillMaxWidth(), enabled = !importando)
                resultado?.let { atual ->
                    Text(if (atual.fonte == "giphy") "Powered by GIPHY" else "Google Noto · CC BY 4.0")
                    atual.aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (atual.itens.isEmpty() && !buscando) Text("Nenhum sticker encontrado. Tente outro termo.")
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (buscando || importando) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resultado?.itens.orEmpty(), key = { "${it.fonte}/${it.id}" }) { item ->
                        Column(Modifier.clickable(enabled = !importando && !buscando) {
                            importando = true
                            escopo.launch {
                                var arquivo: File? = null
                                try {
                                    arquivo = catalogo.importar(item, animado)
                                    adicionar(arquivo)
                                    arquivo = null // O editor passa a ser responsável pela cópia temporária.
                                    fechar()
                                } catch (cancelado: CancellationException) { throw cancelado }
                                catch (_: Exception) { erro = "Não foi possível adicionar este sticker. Tente outro." }
                                finally { arquivo?.delete(); importando = false }
                            }
                        }) {
                            PreviaDoSticker(catalogo, item, animado, miniaturas)
                            Text(item.nome, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pagina > 0) OutlinedButton(onClick = { pagina-- }, enabled = !buscando && !importando) { Text("Anterior") }
                    resultado?.proximaPagina?.let { proxima ->
                        OutlinedButton(onClick = { pagina = proxima }, enabled = !buscando && !importando) { Text("Próxima") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviaDoSticker(catalogo: CatalogoDeStickers, item: StickerDoCatalogo, animado: Boolean, trava: Semaphore) {
    var desenho by remember(item) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(item) {
        try { desenho = trava.withPermit { catalogo.previa(item) } }
        catch (cancelado: CancellationException) { throw cancelado }
        catch (_: Exception) { /* O nome permanece disponível; o usuário pode tentar importar. */ }
    }
    DisposableEffect(desenho) { onDispose { (desenho as? Animatable)?.stop() } }
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        update = { view ->
            view.setImageDrawable(desenho)
            view.contentDescription = item.nome
            (desenho as? Animatable)?.let { if (animado) it.start() else it.stop() }
        })
}
