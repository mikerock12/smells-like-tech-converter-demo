package br.com.smellsliketech.converter.estudio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.smellsliketech.converter.conversao.Andamento
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.Resultado
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Duração, tamanho e som de uma mídia do projeto, vistos pelo FFmpeg (ou pelo decodificador). */
data class InfoDaMidia(val nome: String, val duracao: Double?, val largura: Int?, val altura: Int?, val temAudio: Boolean)

/**
 * O estúdio do Android: assistente (destino → saída) e editor. O projeto fica salvo em
 * files/estudio/atual (o JSON e as cópias das mídias), para continuar de onde parou mesmo
 * que o Android feche o app.
 */
class EstudioViewModel(aplicacao: Application) : AndroidViewModel(aplicacao) {
    enum class Etapa { DESTINO, SAIDA, EDITOR }

    private val contexto get() = getApplication<Application>()
    private val pasta = File(aplicacao.filesDir, "estudio/atual")
    private val arquivoDoProjeto = File(pasta, "projeto.json")
    private val arquivoDasInfos = File(pasta, "midias.json")
    private val travaDoSalvamento = Any()
    private var revisaoDoSalvamento = 0L
    private val montador = PreviaDoEstudio(aplicacao)

    val presets: List<Preset> = aplicacao.assets.open("estudio/estudio-presets.json").bufferedReader().use { Preset.lista(it.readText()) }

    var etapa by mutableStateOf(Etapa.DESTINO)
    var projeto by mutableStateOf<Projeto?>(null)
        private set
    val infos = mutableStateMapOf<String, InfoDaMidia>()
    var instante by mutableStateOf(0.0)
        private set
    var previa by mutableStateOf<Bitmap?>(null)
        private set
    var clipeAberto by mutableStateOf<String?>(null)
    var textoAberto by mutableStateOf<String?>(null)
    var audioAberto by mutableStateOf<String?>(null)
    var camadaAberta by mutableStateOf<String?>(null)

    var ocupado by mutableStateOf<String?>(null)
        private set
    var progresso by mutableStateOf<Int?>(null)
        private set
    var restante by mutableStateOf<Int?>(null)
        private set
    var resultado by mutableStateOf<Resultado?>(null)
    var videoDaPrevia by mutableStateOf<File?>(null)
    var erro by mutableStateOf<String?>(null)
    private var trabalho: Job? = null
    private var trabalhoDaPrevia: Job? = null
    private var andamento = Andamento()

    init {
        runCatching {
            val textoDoProjeto = lerAtomico(arquivoDoProjeto)
            if (textoDoProjeto != null) {
                projeto = Projeto.deJson(JSONObject(textoDoProjeto))
                lerAtomico(arquivoDasInfos)?.let { texto ->
                    val json = JSONObject(texto)
                    json.keys().forEach { chave ->
                        val i = json.getJSONObject(chave)
                        infos[chave] = InfoDaMidia(
                            i.optString("nome"),
                            if (i.isNull("duracao")) null else i.optDouble("duracao"),
                            if (i.isNull("largura")) null else i.optInt("largura"),
                            if (i.isNull("altura")) null else i.optInt("altura"),
                            i.optBoolean("temAudio"),
                        )
                    }
                }
                etapa = Etapa.EDITOR
                atualizarPrevia()
            }
        }
    }

    val total get() = projeto?.let { Estudio.duracaoTotal(it) } ?: 0.0

    // ==================== assistente ====================

    fun escolherDestino(id: String, largura: Int? = null, altura: Int? = null) {
        val atual = projeto
        projeto = if (atual == null) Estudio.novoProjeto(presets, id, "video", largura, altura)
        else {
            val novo = Estudio.novoProjeto(presets, id, atual.saida, largura, altura)
            atual.copy(destino = novo.destino, largura = novo.largura, altura = novo.altura, fps = novo.fps)
        }
        etapa = if (atual == null) Etapa.SAIDA else Etapa.EDITOR
        salvar()
    }

    fun escolherSaida(id: String) {
        projeto = projeto?.copy(saida = id)
        etapa = Etapa.EDITOR
        salvar()
    }

    /** Apaga o projeto e as cópias das mídias e volta ao começo do assistente. */
    fun novoProjeto() {
        trabalho?.cancel()
        trabalhoDaPrevia?.cancel()
        synchronized(travaDoSalvamento) {
            revisaoDoSalvamento++
            pasta.deleteRecursively()
        }
        projeto = null
        infos.clear()
        previa = null
        instante = 0.0
        resultado = null
        montador.esquecer()
        etapa = Etapa.DESTINO
    }

    // ==================== mídias ====================

    fun adicionarMidias(uris: List<Uri>) = executar("Copiando para o projeto…") {
        uris.forEachIndexed { indice, uri ->
            progresso = indice * 100 / uris.size
            val (arquivo, nome, mime) = copiar(uri)
            val imagem = mime.startsWith("image/") || nome.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp")
            val info = if (imagem) InfoDaMidia(nome, null, null, null, false) else {
                val sondagem = Ffmpeg.sondar(contexto, arquivo)
                if (!sondagem.temVideo) {
                    arquivo.delete()
                    throw ErroDeConversao("$nome não tem imagem de vídeo. Para música, use a aba Áudio.")
                }
                InfoDaMidia(nome, sondagem.duracaoSegundos, sondagem.largura, sondagem.altura, sondagem.temAudio)
            }
            infos[arquivo.absolutePath] = info
            val clipe = if (imagem) Clipe(novoId("c"), arquivo.absolutePath, "imagem", duracao = Estudio.DURACAO_PADRAO_DA_IMAGEM, ajuste = "caber-desfocado")
            else Clipe(novoId("c"), arquivo.absolutePath, "video", inicio = 0.0, fim = info.duracao ?: 10.0, ajuste = "caber-desfocado", volume = 1.0)
            projeto = projeto?.let { it.copy(clipes = it.clipes + clipe) }
        }
        salvar()
        atualizarPrevia()
    }

    fun adicionarAudio(uri: Uri) = executar("Copiando a música…") {
        val (arquivo, nome, _) = copiar(uri)
        val sondagem = Ffmpeg.sondar(contexto, arquivo)
        if (!sondagem.temAudio) {
            arquivo.delete()
            throw ErroDeConversao("$nome não tem áudio.")
        }
        infos[arquivo.absolutePath] = InfoDaMidia(nome, sondagem.duracaoSegundos, null, null, true)
        val duracao = sondagem.duracaoSegundos ?: 30.0
        val atual = projeto ?: return@executar
        val fim = if (total > 0) minOf(duracao, total) else duracao
        val audio = AudioExtra(novoId("m"), arquivo.absolutePath, entrada = 0.0, inicio = 0.0, fim = fim, volume = 0.8, fadeEntrada = 0.0, fadeSaida = 1.0)
        projeto = atual.copy(audios = atual.audios + audio)
        audioAberto = audio.id
        salvar()
    }

    fun adicionarCamada(uri: Uri, descartarAoAbrir: Boolean = false) = executar("Adicionando camada…") {
        try {
            val atual = projeto ?: return@executar
            if (atual.camadas.size >= 12) throw ErroDeConversao("Use até 12 camadas adicionais.")
            val (arquivo, nome, mime) = copiar(uri)
            try {
                val tipo = when {
                    mime == "image/gif" || nome.endsWith(".gif", true) -> "gif"
                    mime.startsWith("image/") || nome.substringAfterLast('.').lowercase() in listOf("png", "jpg", "jpeg", "webp", "heic", "heif", "avif", "bmp") -> "imagem"
                    else -> "video"
                }
                val info = if (tipo == "imagem") InfoDaMidia(nome, null, null, null, false) else {
                    val s = Ffmpeg.sondar(contexto, arquivo)
                    if (!s.temVideo) throw ErroDeConversao("Escolha uma imagem, um GIF ou um vídeo para a camada.")
                    InfoDaMidia(nome, s.duracaoSegundos, s.largura, s.altura, s.temAudio)
                }
                infos[arquivo.absolutePath] = info
                val inicio = instante.coerceAtMost(maxOf(0.0, total - .1))
                val camada = Camada(novoId("l"), arquivo.absolutePath, tipo, inicio, maxOf(inicio + .1, total),
                    ordem = (atual.camadas.maxOfOrNull { it.ordem } ?: 0) + 1, transformacao = Transformacao(escala = .5))
                mudar { it.copy(versao = Estudio.VERSAO_DO_PROJETO, camadas = it.camadas + camada) }
                camadaAberta = camada.id
            } catch (falha: Throwable) { arquivo.delete(); throw falha }
        } finally {
            if (descartarAoAbrir && uri.scheme == "file") {
                val temporario = File(uri.path ?: "")
                if (temporario.canonicalPath.startsWith(contexto.cacheDir.canonicalPath + File.separator)) temporario.delete()
            }
        }
    }

    fun mudarCamada(id: String, mudanca: (Camada) -> Camada) = mudar { p -> p.copy(camadas = p.camadas.map { if (it.id == id) mudanca(it) else it }) }

    fun removerCamada(id: String) {
        val camada = projeto?.camadas?.firstOrNull { it.id == id } ?: return
        mudar { it.copy(camadas = it.camadas.filterNot { c -> c.id == id }) }
        if (camadaAberta == id) camadaAberta = null
        apagarCopia(camada.arquivo)
    }

    fun moverCamada(id: String, passo: Int) = mudar { p ->
        val fundo = p.camadas.firstOrNull { it.id == id }?.let { it.ordem < 0 } ?: return@mudar p
        val lista = p.camadas.filter { (it.ordem < 0) == fundo }.sortedBy { it.ordem }.toMutableList()
        val de = lista.indexOfFirst { it.id == id }
        if (de < 0) p else {
            val para = (de + passo).coerceIn(0, lista.lastIndex)
            lista.add(para, lista.removeAt(de))
            val ordenadas = lista.mapIndexed { i, c -> c.copy(ordem = if (fundo) i - lista.size else i + 1) }.associateBy { it.id }
            p.copy(camadas = p.camadas.map { ordenadas[it.id] ?: it })
        }
    }

    fun mudarClipe(id: String, mudanca: (Clipe) -> Clipe) = mudar { p -> p.copy(clipes = p.clipes.map { if (it.id == id) mudanca(it) else it }) }

    fun moverClipe(id: String, passo: Int) = mudar { p ->
        val lista = p.clipes.toMutableList()
        val de = lista.indexOfFirst { it.id == id }
        val para = (de + passo).coerceIn(0, lista.lastIndex)
        if (de < 0 || de == para) p else p.copy(clipes = lista.apply { add(para, removeAt(de)) })
    }

    fun removerClipe(id: String) {
        val clipe = projeto?.clipes?.firstOrNull { it.id == id } ?: return
        mudar { p -> p.copy(clipes = p.clipes.filterNot { it.id == id }) }
        if (projeto?.clipes?.none { it.arquivo == clipe.arquivo } == true) apagarCopia(clipe.arquivo)
        if (clipeAberto == id) clipeAberto = null
    }

    fun adicionarTexto(): String {
        val id = novoId("t")
        mudar { p ->
            val inicio = instante.coerceAtMost(maxOf(0.0, total - 0.5))
            p.copy(textos = p.textos + Texto(id, "Seu texto aqui", inicio, minOf(total.takeIf { it > 0 } ?: 3.0, inicio + 3.0), y = 0.15, tamanho = 0.06, fonte = "anton"))
        }
        textoAberto = id
        return id
    }

    fun mudarTexto(id: String, mudanca: (Texto) -> Texto) = mudar { p -> p.copy(textos = p.textos.map { if (it.id == id) mudanca(it) else it }) }

    fun removerTexto(id: String) {
        mudar { p -> p.copy(textos = p.textos.filterNot { it.id == id }) }
        if (textoAberto == id) textoAberto = null
    }

    fun mudarAudio(id: String, mudanca: (AudioExtra) -> AudioExtra) = mudar { p -> p.copy(audios = p.audios.map { if (it.id == id) mudanca(it) else it }) }

    fun removerAudio(id: String) {
        val audio = projeto?.audios?.firstOrNull { it.id == id } ?: return
        mudar { p -> p.copy(audios = p.audios.filterNot { it.id == id }) }
        apagarCopia(audio.arquivo)
        if (audioAberto == id) audioAberto = null
    }

    fun mudarProjeto(mudanca: (Projeto) -> Projeto) = mudar(mudanca)

    // ==================== prévia ====================

    fun irPara(t: Double) {
        instante = t.coerceIn(0.0, total)
        atualizarPrevia()
    }

    private fun atualizarPrevia() {
        val atual = projeto ?: return
        trabalhoDaPrevia?.cancel()
        trabalhoDaPrevia = viewModelScope.launch {
            delay(120)
            try { previa = montador.quadro(atual, instante) }
            catch (cancelado: CancellationException) { throw cancelado }
            catch (falha: Exception) { erro = falha.message ?: "Não foi possível montar a prévia." }
        }
    }

    fun gerarPreviaEmVideo() = executar("Montando a prévia…") {
        val atual = projeto ?: return@executar
        videoDaPrevia = Exportador.previa(contexto, atual) { relatar(it) }
    }

    // ==================== exportar ====================

    fun gerarLegendas(modeloWhisper: String) = executar("Gerando legendas automáticas…") {
        val atual=projeto ?: return@executar
        val textos=Exportador.legendas(contexto,atual,modeloWhisper) {relatar(it)}
        if(projeto!==atual) throw ErroDeConversao("O projeto mudou. Gere as legendas novamente.")
        mudar {p->p.copy(textos=p.textos.filterNot {it.id.startsWith("legenda-")}+textos)}
    }

    fun exportar(modeloWhisper: String) = executar("Exportando…") {
        val atual = projeto ?: return@executar
        resultado = null
        val etapa = when (atual.saida) {
            "texto" -> "Juntando o áudio e transcrevendo…"
            "gif" -> "Montando o GIF…"
            "imagem" -> "Desenhando a imagem…"
            "audio" -> "Mixando o áudio…"
            else -> "Montando o vídeo…"
        }
        ocupado = etapa
        resultado = Exportador.exportar(contexto, atual, modeloWhisper) { relatar(it) }
    }

    fun cancelar() {
        trabalho?.cancel()
    }

    // ==================== interno ====================

    private fun relatar(porcentagem: Int) {
        progresso = maxOf(progresso ?: 0, porcentagem.coerceIn(0, 99))
        restante = andamento.registrar(progresso!!)
    }

    private fun executar(texto: String, bloco: suspend () -> Unit) {
        if (trabalho?.isActive == true) return
        trabalho = viewModelScope.launch {
            ocupado = texto
            progresso = 0
            restante = null
            andamento = Andamento()
            try {
                bloco()
            } catch (cancelado: CancellationException) {
                throw cancelado
            } catch (falha: ErroDeConversao) {
                erro = falha.message
            } catch (falha: Exception) {
                erro = "Algo deu errado: ${falha.message ?: falha.javaClass.simpleName}"
            } finally {
                ocupado = null
                progresso = null
                restante = null
            }
        }
    }

    private fun mudar(mudanca: (Projeto) -> Projeto) {
        val atual = projeto ?: return
        projeto = mudanca(atual)
        if (instante > total) instante = total
        salvar()
        atualizarPrevia()
    }

    private fun salvar() {
        val atual = projeto ?: return
        val copiaDasInfos = infos.toMap()
        val revisao = synchronized(travaDoSalvamento) { ++revisaoDoSalvamento }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    synchronized(travaDoSalvamento) {
                        if (revisao != revisaoDoSalvamento) return@synchronized
                        pasta.mkdirs()
                        val json = JSONObject()
                        copiaDasInfos.forEach { (chave, info) ->
                            json.put(chave, JSONObject().put("nome", info.nome).put("duracao", info.duracao ?: JSONObject.NULL)
                                .put("largura", info.largura ?: JSONObject.NULL).put("altura", info.altura ?: JSONObject.NULL).put("temAudio", info.temAudio))
                        }
                        gravarAtomico(arquivoDasInfos, json.toString())
                        gravarAtomico(arquivoDoProjeto, atual.paraJson().toString())
                    }
                }
            } catch (cancelado: CancellationException) { throw cancelado }
            catch (_: Exception) {
                if (synchronized(travaDoSalvamento) { revisao == revisaoDoSalvamento })
                    erro = "Não foi possível salvar o projeto. Confira o espaço livre do aparelho."
            }
        }
    }

    /** Lê pelo AtomicFile, que recupera a cópia anterior se o app morreu no meio de uma gravação. */
    private fun lerAtomico(arquivo: File): String? =
        try { AtomicFile(arquivo).readFully().toString(Charsets.UTF_8) } catch (_: java.io.FileNotFoundException) { null }

    private fun gravarAtomico(destino: File, texto: String) {
        val atomico = AtomicFile(destino)
        val saida = atomico.startWrite()
        try { saida.write(texto.toByteArray(Charsets.UTF_8)); atomico.finishWrite(saida) }
        catch (erro: Exception) { atomico.failWrite(saida); throw erro }
    }

    /** Copia o arquivo escolhido para a pasta do projeto (o FFmpeg lê arquivo, não Uri). */
    private suspend fun copiar(uri: Uri): Triple<File, String, String> = withContext(Dispatchers.IO) {
        val resolvedor = contexto.contentResolver
        val nome = (if (uri.scheme == "file") File(uri.path ?: "").name else resolvedor.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }) ?: "midia"
        val mime = resolvedor.getType(uri) ?: ""
        val extensao = nome.substringAfterLast('.', "bin").filter(Char::isLetterOrDigit).take(8).ifBlank { "bin" }
        val destino = File(File(pasta, "midias").apply { mkdirs() }, "${UUID.randomUUID()}.$extensao")
        (resolvedor.openInputStream(uri) ?: throw ErroDeConversao("Não deu para ler $nome.")).use { entrada ->
            destino.outputStream().use { entrada.copyTo(it, 1 shl 16) }
        }
        Triple(destino, nome, mime)
    }

    private fun apagarCopia(caminho: String) {
        if (projeto?.let { p -> p.clipes.any { it.arquivo == caminho } || p.camadas.any { it.arquivo == caminho } || p.audios.any { it.arquivo == caminho } } == true) return
        infos.remove(caminho)
        File(caminho).takeIf { it.parentFile == File(pasta, "midias") }?.delete()
        salvar()
    }

    private fun novoId(prefixo: String) = "$prefixo${UUID.randomUUID().toString().take(8)}"

    override fun onCleared() {
        videoDaPrevia?.delete()
    }
}
