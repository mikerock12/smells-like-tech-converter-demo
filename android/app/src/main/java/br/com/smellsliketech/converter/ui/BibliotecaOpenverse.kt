package br.com.smellsliketech.converter.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.smellsliketech.converter.estudio.EstudioViewModel
import br.com.smellsliketech.converter.estudio.Openverse
import br.com.smellsliketech.converter.estudio.Projeto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
internal fun BibliotecaOpenverse(modelo: EstudioViewModel) {
    var aberta by rememberSaveable { mutableStateOf(false) }
    Button(onClick = { aberta = true }, modifier = Modifier.fillMaxWidth()) { Text("Músicas grátis") }
    if (aberta) CatalogoOpenverse(modelo) { aberta = false }
}

@Composable
internal fun CreditosOpenverse(modelo: EstudioViewModel, projeto: Projeto) {
    val faixas = projeto.audios.mapNotNull { it.openverse }.distinctBy { it.optString("id") }
    if (faixas.isEmpty()) return
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    val salvar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) escopo.launch {
            try {
                val texto = JSONObject().put("faixas", JSONArray(faixas)).put("fonte", "Openverse").put("endosso", false).toString(2)
                withContext(Dispatchers.IO) {
                    val saida = contexto.contentResolver.openOutputStream(uri) ?: error("Não foi possível abrir o arquivo de créditos.")
                    saida.bufferedWriter(Charsets.UTF_8).use { it.write(texto) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelo.erro = e.message ?: "Não foi possível salvar os créditos."
            }
        }
    }
    TextButton({ salvar.launch("creditos-openverse.json") }) { Text("Salvar créditos do Openverse") }
}

@Composable
private fun CatalogoOpenverse(modelo: EstudioViewModel, fechar: () -> Unit) {
    var categoria by rememberSaveable { mutableStateOf("music") }
    var termo by rememberSaveable { mutableStateOf("") }
    var pagina by rememberSaveable { mutableStateOf(1) }
    var tentativa by remember { mutableStateOf(0) }
    var itens by remember { mutableStateOf(emptyList<JSONObject>()) }
    var proxima by remember { mutableStateOf(false) }
    var buscando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf("") }
    var salvarItem by rememberSaveable { mutableStateOf<String?>(null) }
    var download by remember { mutableStateOf("") }
    var operacao by remember { mutableStateOf<Job?>(null) }
    var previa by remember { mutableStateOf<File?>(null) }
    val escopo = rememberCoroutineScope()
    val links = LocalUriHandler.current
    LaunchedEffect(termo, categoria, pagina, tentativa) {
        itens = emptyList()
        buscando = true
        erro = ""
        proxima = false
        try {
            delay(400)
            val dados = Openverse.buscar(categoria, termo, pagina)
            val lista = dados.getJSONArray("resultados")
            itens = List(lista.length()) { lista.getJSONObject(it) }
            proxima = dados.optBoolean("proxima")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            erro = e.message ?: "Não foi possível consultar a biblioteca."
        } finally {
            buscando = false
        }
    }
    DisposableEffect(previa) {
        val arquivo = previa
        val player = if (arquivo != null) runCatching { MediaPlayer() }.getOrElse {
            erro = "Não foi possível abrir a prévia."
            previa = null
            null
        } else null
        if (player != null && arquivo != null) {
            runCatching {
                player.setDataSource(arquivo.absolutePath)
                player.setOnPreparedListener {
                    runCatching { it.start() }.onFailure { erro = "Não foi possível ouvir a faixa."; previa = null }
                }
                player.setOnCompletionListener { previa = null }
                player.setOnErrorListener { _, _, _ -> erro = "Não foi possível ouvir a faixa."; previa = null; true }
                player.prepareAsync()
            }.onFailure { erro = "Não foi possível ouvir a faixa."; previa = null }
        }
        onDispose {
            runCatching {
                player?.setOnPreparedListener(null)
                player?.setOnCompletionListener(null)
                player?.setOnErrorListener(null)
                player?.release()
            }
            arquivo?.delete()
        }
    }

    val contexto = LocalContext.current
    fun obter(item: JSONObject, inserir: Boolean, salvarEm: Uri? = null) {
        if (operacao?.isActive == true || item.optString("estado") != "APPROVED") return
        if (!inserir && salvarEm == null) previa = null
        erro = ""
        aviso = ""
        download = "Conferindo a faixa…"
        val projeto = modelo.projeto
        val instante = modelo.instante
        operacao = escopo.launch {
            var arquivo: File? = null
            var manter = false
            try {
                withTimeout(65000) {
                    val (baixado, fonte) = Openverse.baixar(categoria, item.getString("id"), modelo.pastaOpenverse(inserir)) {
                        download = "Baixando áudio · %.1f MiB".format(it / 1048576.0)
                    }
                    arquivo = baixado
                    val duracao = modelo.validarAudioOpenverse(baixado)
                    if (inserir) {
                        modelo.inserirAudioOpenverse(projeto, instante, baixado, duracao, fonte)
                        manter = true
                        aviso = "Adicionado ao projeto: ${item.optString("titulo")}"
                    } else if (salvarEm != null) {
                        withContext(Dispatchers.IO) {
                            val saida = contexto.contentResolver.openOutputStream(salvarEm, "wt")
                                ?: error("Não foi possível salvar o áudio.")
                            saida.use { out -> baixado.inputStream().use { it.copyTo(out) } }
                        }
                        aviso = "Áudio baixado."
                    } else {
                        modelo.tocandoPrevia = false
                        previa = baixado
                        manter = true
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                erro = "A origem demorou demais para enviar a faixa."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                erro = e.message ?: "Não foi possível baixar a faixa."
            } finally {
                if (!manter) arquivo?.delete()
                download = ""
                operacao = null
            }
        }
    }

    val salvar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val item = salvarItem
        salvarItem = null
        if (uri != null && item != null) obter(JSONObject(item), false, uri)
    }

    AlertDialog(
        onDismissRequest = fechar,
        title = { Text("Músicas grátis") },
        confirmButton = { TextButton(onClick = fechar) { Text("Fechar") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Busca de músicas disponibilizada com o uso da API Openverse. O Smells Like Tech Converter é independente e não é endossado nem certificado pelo Openverse.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(categoria == "music", { categoria = "music"; pagina = 1; previa = null }, { Text("Músicas") }, enabled = download.isEmpty())
                        FilterChip(categoria == "sound_effect", { categoria = "sound_effect"; pagina = 1; previa = null }, { Text("Efeitos sonoros") }, enabled = download.isEmpty())
                    }
                    OutlinedTextField(termo, { termo = it.take(200); pagina = 1 }, label = { Text("Pesquisar") }, singleLine = true, enabled = download.isEmpty())
                    if (buscando) Text("Buscando faixas…")
                    if (aviso.isNotEmpty()) Text(aviso)
                    if (erro.isNotEmpty()) {
                        Text(erro)
                        TextButton({ tentativa++ }) { Text("Tentar novamente") }
                    }
                    if (!buscando && erro.isEmpty() && itens.isEmpty()) Text("Nenhuma faixa encontrada. Tente outro termo.")
                    if (download.isNotEmpty()) {
                        Text(download)
                        TextButton({ operacao?.cancel() }) { Text("Cancelar download") }
                    }
                    if (previa != null) TextButton({ previa = null }) { Text("Parar prévia") }
                }
                items(itens, key = { it.getString("id") }) { item ->
                    Column {
                        Text(item.getString("titulo"), fontWeight = FontWeight.Bold)
                        val estado = item.optString("estado")
                        val duracao = item.optLong("duracaoMs").takeIf { it > 0 }?.let { "${it / 1000} s" }
                        val status = when (estado) {
                            "APPROVED" -> "CC0 revisada"
                            "BLOCKED" -> "Indisponível"
                            "RECHECK" -> "Aguardando nova revisão"
                            else -> "Licença em verificação"
                        }
                        Text(listOfNotNull(item.optString("autor").takeUnless { it.isEmpty() || it == "null" }, item.optString("fonte"), duracao, status).joinToString(" · "))
                        if (estado == "APPROVED") {
                            Button({ obter(item, true) }, enabled = download.isEmpty() && modelo.projeto != null && modelo.ocupado == null) { Text("Adicionar ao projeto") }
                            Row {
                                TextButton({ obter(item, false) }, enabled = download.isEmpty()) { Text("Ouvir") }
                                TextButton({
                                    val formato = item.optString("midia").substringBefore('?').substringAfterLast('.').lowercase()
                                    val ext = formato.takeIf { it in setOf("mp3", "wav", "ogg", "oga") } ?: "ogg"
                                    val nome = item.optString("titulo").take(70)
                                        .map { c -> if (c.isISOControl() || c in "\\/:*?\"<>|") '_' else c }.joinToString("")
                                        .trim(' ', '.').ifEmpty { "Áudio" }
                                    salvarItem = item.toString()
                                    try { salvar.launch("$nome.$ext") }
                                    catch (e: Exception) { salvarItem = null; erro = "Não foi possível abrir a opção de salvar áudio." }
                                }, enabled = download.isEmpty() && salvarItem == null) { Text("Baixar") }
                            }
                        }
                        var creditos by remember { mutableStateOf(false) }
                        TextButton({ creditos = !creditos }) { Text("Créditos e licença") }
                        if (creditos) Row {
                            TextButton({ runCatching { links.openUri(item.getString("origem")) }.onFailure { erro = "Não foi possível abrir a origem." } }) { Text("Origem") }
                            TextButton({ runCatching { links.openUri(item.getString("licenca")) }.onFailure { erro = "Não foi possível abrir a licença." } }) { Text("CC0 1.0") }
                        }
                    }
                }
                if (pagina > 1 || proxima) item {
                    Row {
                        TextButton({ pagina-- }, enabled = pagina > 1 && download.isEmpty()) { Text("Anterior") }
                        TextButton({ pagina++ }, enabled = proxima && download.isEmpty()) { Text("Próxima") }
                    }
                }
            }
        },
    )
}
