package br.com.smellsliketech.converter.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.smellsliketech.converter.conversao.Imagem
import br.com.smellsliketech.converter.imagem.FonteImagem
import br.com.smellsliketech.converter.imagem.ProjetoImagem
import br.com.smellsliketech.converter.imagem.AmpliadorSlti
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EditorImagemModelo(app: Application) : AndroidViewModel(app) {
    var documento by mutableStateOf<JSONObject?>(null)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var indice by mutableIntStateOf(0)
    var ocupado by mutableStateOf(false)
        private set
    var mensagem by mutableStateOf("")
        private set
    private var fontes = mapOf<String, FonteImagem>()
    private val anteriores = mutableStateListOf<String>()
    private val posteriores = mutableStateListOf<String>()
    val podeDesfazer get() = anteriores.isNotEmpty()
    val podeRefazer get() = posteriores.isNotEmpty()
    private val resolver get() = getApplication<Application>().contentResolver

    private fun tarefa(acao: suspend () -> Unit) {
        if (ocupado) return
        ocupado = true
        mensagem = ""
        viewModelScope.launch {
            try { acao() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mensagem = e.localizedMessage ?: "Não foi possível processar a imagem." }
            finally { ocupado = false }
        }
    }

    private suspend fun mostrar(doc: JSONObject, midias: Map<String, FonteImagem>, selecionar: Int) {
        val pixels = withContext(Dispatchers.Default) { ProjetoImagem.render(doc, midias) }
        fontes = midias
        documento = doc
        preview = pixels
        indice = selecionar.coerceIn(0, (doc.getJSONArray("layers").length() - 1).coerceAtLeast(0))
    }

    fun abrir(uri: Uri) = tarefa {
        val nome = withContext(Dispatchers.IO) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Imagem"
        }
        if (nome.endsWith(".sltimage", ignoreCase = true)) {
            val (doc, bytes) = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)!!.use { ProjetoImagem.abrir(it) }
            }
            mostrar(doc, bytes, doc.getJSONArray("layers").length() - 1)
            anteriores.clear()
            posteriores.clear()
        } else {
            val f = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)!!.use { ProjetoImagem.fonte(ProjetoImagem.ler(it, 64 * 1024 * 1024)) }
            }
            val midias = fontes + (f.id to f)
            require(midias.values.sumOf { it.bitmap.width.toLong() * it.bitmap.height } <= 32L * 1024 * 1024 &&
                midias.values.sumOf { it.bytes.size.toLong() } <= 127L * 1024 * 1024) { "Projeto acima do limite de memória." }
            val doc = documento?.let { JSONObject(it.toString()) } ?: ProjetoImagem.novo(nome.substringBeforeLast('.'), f)
            val lista = doc.getJSONArray("layers")
            lista.put(ProjetoImagem.camada("foto", nome).put("asset", f.id))
            atualizar(doc, midias, lista.length() - 1)
        }
    }

    private suspend fun atualizar(doc: JSONObject, midias: Map<String, FonteImagem>, selecionar: Int) {
        val anterior = documento?.toString()
        mostrar(doc, midias, selecionar)
        if (anterior != null) {
            if (anteriores.size >= 50) anteriores.removeAt(0)
            anteriores.add(anterior)
        }
        posteriores.clear()
    }

    fun editar(chave: String, valor: Any) = tarefa {
        val doc = JSONObject(documento!!.toString())
        doc.getJSONArray("layers").getJSONObject(indice).put(chave, valor)
        atualizar(doc, fontes, indice)
    }

    fun recortar(esquerda: Int, topo: Int, direita: Int, base: Int) = tarefa {
        atualizar(ProjetoImagem.recortar(documento!!, esquerda, topo, direita, base), fontes, indice)
    }

    fun texto(valor: String) = tarefa {
        val doc = JSONObject(documento!!.toString())
        val lista = doc.getJSONArray("layers")
        lista.put(ProjetoImagem.camada("texto", "Texto").put("texto", valor))
        atualizar(doc, fontes, lista.length() - 1)
    }

    fun mover(delta: Int) = tarefa {
        val doc = JSONObject(documento!!.toString())
        val lista = doc.getJSONArray("layers")
        val alvo = indice + delta
        if (alvo !in 0 until lista.length()) return@tarefa
        val atual = lista.get(indice)
        lista.put(indice, lista.get(alvo))
        lista.put(alvo, atual)
        atualizar(doc, fontes, alvo)
    }

    fun historico(desfazer: Boolean) = tarefa {
        val origem = if (desfazer) anteriores else posteriores
        val destino = if (desfazer) posteriores else anteriores
        val anterior = origem.lastOrNull() ?: return@tarefa
        val atual = documento!!.toString()
        mostrar(JSONObject(anterior), fontes, indice)
        destino.add(atual)
        origem.removeAt(origem.lastIndex)
    }

    fun salvar(uri: Uri, formato: String, ampliar: Boolean) = tarefa {
        val doc = documento ?: return@tarefa
        val img = preview ?: return@tarefa
        val saida = if (ampliar && formato != "sltimage") AmpliadorSlti.ampliar(getApplication(), img) else img
        try { withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri, "wt")?.use { arquivo ->
                if (formato == "sltimage") ProjetoImagem.salvar(doc, fontes, arquivo)
                else Imagem.comprimir(saida, formato, if (formato == "jxl") 100 else 92, arquivo)
            } ?: error("Não foi possível gravar o arquivo escolhido.")
        } } finally { if (saida !== img) saida.recycle() }
        mensagem = if (formato == "sltimage") "Projeto salvo." else "Imagem exportada."
    }
}

@Composable
fun EditorDeImagem(voltar: () -> Unit, podeExportar: Boolean, ampliadorPago: Boolean, abrirLicenca: () -> Unit, modelo: EditorImagemModelo = viewModel()) {
    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(modelo::abrir) }
    var formato by rememberSaveable { mutableStateOf("jxl") }
    var destino by rememberSaveable { mutableStateOf("sltimage") }
    var ampliar by rememberSaveable { mutableStateOf(false) }
    val salvar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { modelo.salvar(it, destino, ampliar && ampliadorPago) }
    }
    var texto by rememberSaveable { mutableStateOf("") }
    val doc = modelo.documento
    val livre = !modelo.ocupado
    val lista = doc?.getJSONArray("layers")
    val c = lista?.optJSONObject(modelo.indice)
    BackHandler(onBack = voltar)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = voltar) { Text("Voltar ao início") }
        Text("Estúdio de imagem", style = MaterialTheme.typography.headlineMedium)
        Text("SDR · sRGB · 8 bits")
        Button(onClick = { abrir.launch(arrayOf("*/*")) }, enabled = livre) { Text("Abrir foto ou projeto .sltimage") }
        modelo.preview?.let { Image(it.asImageBitmap(), "Prévia da composição", Modifier.fillMaxWidth().heightIn(max = 340.dp)) }
        if (doc != null && lista != null) {
            var recorteAberto by remember(doc) { mutableStateOf(false) }
            TextButton(onClick = { recorteAberto = !recorteAberto }, enabled = livre) { Text("Recortar composição") }
            if (recorteAberto) {
                val quadro = doc.getJSONObject("canvas")
                Text("${quadro.getInt("width")} × ${quadro.getInt("height")} px")
                val margens = remember { mutableStateListOf("0", "0", "0", "0") }
                listOf("Esquerda", "Topo", "Direita", "Base").forEachIndexed { i, nome ->
                    OutlinedTextField(value = margens[i], onValueChange = { valor ->
                        if (valor.length <= 5 && valor.all { it in '0'..'9' }) margens[i] = valor
                    }, label = { Text("$nome (px)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                TextButton(enabled = livre && margens.all { it.isNotEmpty() }, onClick = {
                    modelo.recortar(margens[0].toInt(), margens[1].toInt(), margens[2].toInt(), margens[3].toInt())
                }) { Text("Aplicar recorte") }
            }
            Text("Camadas", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in lista.length() - 1 downTo 0) FilterChip(selected = i == modelo.indice, enabled = livre,
                    onClick = { modelo.indice = i }, label = { Text(lista.getJSONObject(i).getString("nome")) })
            }
            if (c != null) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { modelo.mover(1) }, enabled = livre && modelo.indice + 1 < lista.length()) { Text("Para frente") }
                    TextButton(onClick = { modelo.mover(-1) }, enabled = livre && modelo.indice > 0) { Text("Para trás") }
                    TextButton(onClick = { modelo.editar("visivel", !c.getBoolean("visivel")) }, enabled = livre) { Text(if (c.getBoolean("visivel")) "Ocultar" else "Mostrar") }
                }
                for ((chave, nome, faixa) in listOf(
                    Triple("opacidade", "Opacidade", 0f..1f), Triple("exposicao", "Exposição (EV)", -4f..4f),
                    Triple("contraste", "Contraste", 0f..4f), Triple("saturacao", "Saturação", 0f..4f),
                    Triple("escala", "Escala", 0.01f..4f), Triple("angulo", "Rotação", -180f..180f),
                    Triple("x", "Posição horizontal", -doc.getJSONObject("canvas").getInt("width").toFloat()..doc.getJSONObject("canvas").getInt("width").toFloat()),
                    Triple("y", "Posição vertical", -doc.getJSONObject("canvas").getInt("height").toFloat()..doc.getJSONObject("canvas").getInt("height").toFloat())
                )) {
                    var valor by remember(c, chave) { mutableFloatStateOf(c.getDouble(chave).toFloat()) }
                    Text("$nome · %.2f".format(valor))
                    Slider(value = valor.coerceIn(faixa), enabled = livre, valueRange = faixa, onValueChange = { valor = it },
                        onValueChangeFinished = { modelo.editar(chave, valor.toDouble()) })
                }
            }
            OutlinedTextField(value = texto, onValueChange = { texto = it.take(4096) }, label = { Text("Texto da camada") })
            if (c?.getString("tipo") == "texto") {
                TextButton(onClick = { modelo.editar("texto", texto) }, enabled = livre && texto.isNotBlank()) { Text("Substituir texto da camada") }
            }
            TextButton(onClick = { modelo.texto(texto) }, enabled = livre && texto.isNotBlank() && lista.length() < 64) { Text("Adicionar texto") }
            Row {
                TextButton(onClick = { modelo.historico(true) }, enabled = livre && modelo.podeDesfazer) { Text("Desfazer") }
                TextButton(onClick = { modelo.historico(false) }, enabled = livre && modelo.podeRefazer) { Text("Refazer") }
            }
            Button(onClick = { destino = "sltimage"; salvar.launch(doc.getString("title") + ".sltimage") }, enabled = livre) { Text("Salvar projeto") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = ampliar && ampliadorPago, enabled = livre && ampliadorPago, onCheckedChange = { ampliar = it })
                Text("Ampliar 2× na exportação · criação da SLTI (licença paga)")
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (f in listOf("jxl", "png", "webp", "jpg")) FilterChip(selected = f == formato, onClick = { formato = f }, label = { Text(f.uppercase()) })
            }
            Button(onClick = {
                if (podeExportar) { destino = formato; salvar.launch(doc.getString("title") + (if (ampliar && ampliadorPago) "-2x." else ".") + formato) } else abrirLicenca()
            }, enabled = livre) { Text("Exportar imagem") }
        }
        if (!livre) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (modelo.mensagem.isNotEmpty()) Text(modelo.mensagem)
    }
}
