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
    /** O projeto exatamente como estava quando a prévia leve foi montada: editar a invalida. */
    private var projetoDaPrevia by mutableStateOf<Projeto?>(null)
    val previaMontada get() = videoDaPrevia?.isFile == true && projetoDaPrevia != null && projetoDaPrevia == projeto
    var tocandoPrevia by mutableStateOf(false)

    /** Qualidade escolhida para exportar (baixa, média ou alta). */
    var qualidade by mutableStateOf("alta")
    private val preferencias = aplicacao.getSharedPreferences("estudio", android.content.Context.MODE_PRIVATE)
    /** A velocidade medida neste celular, que alimenta os tempos estimados. */
    var velocidade by mutableStateOf(Velocidade(
        preferencias.getFloat("segundosPorMegapixelQuadro", Velocidade().segundosPorMegapixelQuadro.toFloat()).toDouble(),
        preferencias.getFloat("segundosPorMascara", Velocidade().segundosPorMascara.toFloat()).toDouble()))
        private set
    private var estimativa: Double? = null
    private var comecoDoTrabalho = 0L
    var falhaNoPlayerDireto by mutableStateOf(false)
    val previaDireta get() = !falhaNoPlayerDireto && projeto?.let(PreviaImediata::aceita) == true

    fun falhouPlayer() { falhaNoPlayerDireto = true; tocandoPrevia = false; atualizarPrevia() }

    fun avancarPlayer(tempo: Double) { instante = tempo.coerceIn(0.0, total) }
    fun alternarPlayer() {
        if (instante >= total - .05) instante = 0.0
        tocandoPrevia = !tocandoPrevia
    }
    var erro by mutableStateOf<String?>(null)
    private var trabalho: Job? = null
    private var trabalhoDaPrevia: Job? = null
    private var andamento = Andamento()

    private val historico = br.com.smellsliketech.converter.figurinhas.HistoricoDeEdicao<Projeto>()
    var podeDesfazer by mutableStateOf(false)
        private set
    var podeRefazer by mutableStateOf(false)
        private set
    private fun atualizarHistorico() { podeDesfazer = historico.podeDesfazer; podeRefazer = historico.podeRefazer }
    fun desfazer() { projeto?.let { historico.desfazer(it)?.let(::restaurar) } }
    fun refazer() { projeto?.let { historico.refazer(it)?.let(::restaurar) } }
    private fun restaurar(valor: Projeto) { projeto = valor; instante = instante.coerceAtMost(total); atualizarHistorico(); salvar(); atualizarPrevia() }
    fun iniciarGesto() { brutaDoGesto = null; historico.iniciar() }
    fun terminarGesto() { brutaDoGesto = null; historico.terminar() }
    fun inclinarSelecionada(dx: Float, dy: Float, secao: String) = transformarSelecionada(secao) {
            brutaDoGesto = null
            it.copy(giroX = (it.giroX + dy * 180).coerceIn(-180.0, 180.0), giroY = (it.giroY + dx * 180).coerceIn(-180.0, 180.0))
    }
    fun ampliarSelecionada(fator: Float, secao: String) = transformarSelecionada(secao) {
        brutaDoGesto = null
        Animacao.mudarBase(it, it.copy(escala = (it.escala * fator).coerceIn(.05, 6.0)))
    }

    // ==================== guias (as do criador de figurinhas) ====================

    /** As linhas encaixadas que a prévia mostra piscando; a tela apaga depois de um instante. */
    var guias by mutableStateOf<List<GuiasDoEstudio.Guia>>(emptyList())
        private set
    /** A posição que o dedo propôs, sem o encaixe: é ela que anda, para dar para sair da guia. */
    private var brutaDoGesto: Transformacao? = null

    /** Clipes e textos ocupam o quadro inteiro antes da transformação. */
    val medidasDoQuadro get() = projeto?.let { GuiasDoEstudio.Medidas(it.largura.toDouble(), it.altura.toDouble(), it.largura.toDouble(), it.altura.toDouble()) }

    fun medidasDaCamada(id: String): GuiasDoEstudio.Medidas? {
        val p = projeto ?: return null
        val camada = p.camadas.firstOrNull { it.id == id } ?: return null
        val (largura, altura) = tamanhoDaMidia(camada.arquivo) ?: return null
        return GuiasDoEstudio.Medidas(largura.toDouble(), altura.toDouble(), p.largura.toDouble(), p.altura.toDouble())
    }

    /** Fotos entram sem largura e altura em [infos]: lê só o cabeçalho do arquivo, uma vez. */
    private val tamanhos = mutableMapOf<String, Pair<Int, Int>?>()
    private fun tamanhoDaMidia(arquivo: String): Pair<Int, Int>? {
        infos[arquivo]?.let { i -> if (i.largura != null && i.altura != null) return i.largura to i.altura }
        return tamanhos.getOrPut(arquivo) {
            val opcoes = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(arquivo, opcoes)
            (opcoes.outWidth to opcoes.outHeight).takeIf { it.first > 0 && it.second > 0 }
        }
    }

    /** As caixas das outras camadas visíveis no cursor: as guias também encostam nelas. */
    fun referenciasDaCamada(id: String): List<GuiasDoEstudio.Caixa> =
        projeto?.camadas?.filter { it.id != id && it.visivel && instante in it.inicio..it.fim }
            ?.mapNotNull { GuiasDoEstudio.caixa(medidasDaCamada(it.id), it.transformacao ?: Transformacao()) } ?: emptyList()

    /** Os controles mostram as guias em que a camada ficou, sem encaixar (encaixar prenderia o − e o +). */
    fun mostrarGuias(medidas: GuiasDoEstudio.Medidas?, t: Transformacao, referencias: List<GuiasDoEstudio.Caixa> = emptyList()) {
        guias = GuiasDoEstudio.guias(medidas, t, referencias)
    }

    fun apagarGuias() { guias = emptyList() }

    /** Arrastar com um dedo: move o que está aberto e encaixa no centro, nas bordas e nas outras camadas. */
    fun moverSelecionada(dx: Float, dy: Float, secao: String) {
        val camada = if (secao == "CAMADAS") camadaAberta else null
        val medidas = if (camada != null) medidasDaCamada(camada) else medidasDoQuadro
        val referencias = camada?.let(::referenciasDaCamada) ?: emptyList()
        var encaixadas: List<GuiasDoEstudio.Guia> = emptyList()
        transformarSelecionada(secao) { atual ->
            val bruta = (brutaDoGesto ?: atual).let { it.copy(x = it.x + dx, y = it.y + dy) }
            brutaDoGesto = bruta
            val alinhamento = GuiasDoEstudio.alinhar(bruta, medidas, referencias)
            encaixadas = alinhamento.guias
            Animacao.mudarBase(atual, alinhamento.transformacao)
        }
        guias = encaixadas
    }
    private fun transformarSelecionada(secao: String, transformar: (Transformacao) -> Transformacao) {
        fun aplicar(t: Transformacao?) = transformar(t ?: Transformacao())
        val p = projeto ?: return
        when {
            secao == "CAMADAS" && camadaAberta != null -> mudarCamada(camadaAberta!!) { it.copy(transformacao = aplicar(it.transformacao)) }
            secao == "TEXTOS" && textoAberto != null -> mudarTexto(textoAberto!!) { it.copy(transformacao = aplicar(it.transformacao)) }
            else -> {
                val id = clipeAberto ?: Estudio.clipeNoTempo(p, instante)?.second?.id ?: p.clipes.firstOrNull()?.id
                if (id != null) mudarClipe(id) { it.copy(transformacao = aplicar(it.transformacao)) }
            }
        }
    }

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
        historico.limpar(); atualizarHistorico()
        infos.clear()
        previa = null
        instante = 0.0
        resultado = null
        videoDaPrevia?.delete()
        videoDaPrevia = null
        projetoDaPrevia = null
        tocandoPrevia = false
        falhaNoPlayerDireto = false
        montador.esquecer()
        etapa = Etapa.DESTINO
    }

    fun salvarProjeto(uri: Uri) = executar("Salvando projeto com mídias…") {
        val atual=projeto ?: return@executar
        val arquivo=File.createTempFile("projeto-", ".sltstudio", contexto.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                arquivo.outputStream().use { ArquivoDoProjeto.salvar(atual,it) }
                contexto.contentResolver.openOutputStream(uri,"wt").use { destino ->
                    requireNotNull(destino) { "Não foi possível gravar o projeto." };arquivo.inputStream().use {it.copyTo(destino)}
                }
            }
        } finally {arquivo.delete()}
    }

    fun abrirProjeto(uri: Uri) = executar("Abrindo projeto salvo…") {
        val importado=File(pasta,"importado-${UUID.randomUUID()}")
        val temporario=File.createTempFile("abrir-projeto-", ".zip", contexto.cacheDir)
        val novo=withContext(Dispatchers.IO) {
            val entrada=requireNotNull(contexto.contentResolver.openInputStream(uri)) {"Não foi possível ler o projeto."}
            ArquivoDoProjeto.abrir(entrada,importado,temporario)
        }
        val novasInfos=mutableMapOf<String,InfoDaMidia>()
        for(caminho in Criacao.arquivos(novo)) {
            val arquivo=File(caminho)
            val sondagem=runCatching { Ffmpeg.sondar(contexto,arquivo) }.getOrNull()
            novasInfos[caminho]=InfoDaMidia(arquivo.name,sondagem?.duracaoSegundos,sondagem?.largura,sondagem?.altura,sondagem?.temAudio?:false)
        }
        historico.limpar(); atualizarHistorico()
        projeto=novo;infos.clear();infos.putAll(novasInfos);instante=0.0;resultado=null;etapa=Etapa.EDITOR
        montador.esquecer();salvar();atualizarPrevia()
    }

    fun aplicarModelo(id: String) {runCatching {mudar {Criacao.modelo(it,id)}}.onFailure {erro=it.message}}
    fun adicionarContagem(quantidade: Int) {runCatching {mudar {Criacao.contagem(it,instante,quantidade)}}.onFailure {erro=it.message}}

    fun narrarNaLinhaDoTempo(texto: String,voz: String,velocidade: Float=1.0f) = executar("Gerando narração Kokoro…") {
        require(texto.isNotBlank()&&texto.length<=50000) {"Escreva até 50 mil caracteres para narrar."}
        val atual=projeto ?: return@executar
        val entrada=instante
        val arquivo=File(pasta,"narracao-${UUID.randomUUID()}.wav").apply {parentFile?.mkdirs()}
        try {
            br.com.smellsliketech.converter.conversao.Kokoro.gravar(contexto,texto,
                br.com.smellsliketech.converter.conversao.Opcoes(formato="wav",vozDaNarracao=voz,velocidadeDaFala=velocidade),arquivo) {progresso=it}
            val s=Ffmpeg.sondar(contexto,arquivo);val duracao=requireNotNull(s.duracaoSegundos)
            if(projeto!==atual) throw ErroDeConversao("O projeto mudou durante a narração. Gere novamente.")
            infos[arquivo.absolutePath]=InfoDaMidia("Narração Kokoro",duracao,null,null,true)
            val audio=AudioExtra(novoId("n"),arquivo.absolutePath,entrada=entrada,inicio=0.0,fim=duracao,volume=1.0)
            mudar {it.copy(versao=4,audios=it.audios+audio)};audioAberto=audio.id
        } catch(e: Exception) {arquivo.delete();throw e}
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
            clipeAberto = clipe.id
            falhaNoPlayerDireto = false
        }
        salvar()
        atualizarPrevia()
    }

    internal fun pastaOpenverse(inserir: Boolean) = File(if (inserir) pasta else contexto.cacheDir, "openverse")

    internal suspend fun validarAudioOpenverse(arquivo: File): Double {
        val info = Ffmpeg.sondar(contexto, arquivo)
        val duracao = info.duracaoSegundos ?: 0.0
        if (!info.temAudio || !duracao.isFinite() || duracao <= 0 || duracao > 900) throw ErroDeConversao("Formato incompatível ou duração fora do limite de 15 minutos.")
        return duracao
    }

    internal fun inserirAudioOpenverse(original: Projeto?, cursor: Double, arquivo: File, duracao: Double, fonte: JSONObject) {
        if (original == null || projeto !== original || ocupado != null) {
            throw ErroDeConversao("O projeto mudou durante a importação. Tente novamente.")
        }
        val origem = JSONObject(fonte.toString()).put("importadoEm", java.time.Instant.now().toString())
        val entrada = cursor.coerceIn(0.0, maxOf(0.0, total - 1.0 / original.fps))
        val fim = if (total > 0) minOf(duracao, total - entrada) else duracao
        infos[arquivo.absolutePath] = InfoDaMidia(fonte.optString("titulo", "Áudio"), duracao, null, null, true)
        val audio = AudioExtra(novoId("ov"), arquivo.absolutePath, entrada, 0.0, fim, .8, 0.0, minOf(1.0, fim), origem)
        mudar { it.copy(audios = it.audios + audio) }
        audioAberto = audio.id
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

    fun editarTrechoDeAudio(id: String, dividir: Boolean) {
        val audio = projeto?.audios?.firstOrNull { it.id == id } ?: return
        val entrada = audio.entrada ?: 0.0
        val fim = entrada + audio.fim - audio.inicio
        val novo = novoId("m")
        if (dividir) {
            if (instante - entrada < .05 || fim - instante < .05) return
            val corte = audio.inicio + instante - entrada
            val primeiro = audio.copy(fim = corte, fadeSaida = 0.0)
            val segundo = audio.copy(id = novo, entrada = instante, inicio = corte, fadeEntrada = 0.0)
            mudar { p -> p.copy(audios = p.audios.flatMap { if (it.id == id) listOf(primeiro, segundo) else listOf(it) }) }
        } else {
            if (fim >= total) return
            val copia = audio.copy(id = novo, entrada = fim, fim = minOf(audio.fim, audio.inicio + total - fim))
            mudar { p -> p.copy(audios = p.audios + copia) }
        }
        audioAberto = novo
    }

    fun removerAudio(id: String) {
        val audio = projeto?.audios?.firstOrNull { it.id == id } ?: return
        mudar { p -> p.copy(audios = p.audios.filterNot { it.id == id }) }
        apagarCopia(audio.arquivo)
        if (audioAberto == id) audioAberto = null
    }

    fun mudarProjeto(mudanca: (Projeto) -> Projeto) = mudar(mudanca)

    // ==================== prévia ====================

    fun irPara(t: Double) {
        tocandoPrevia = false
        instante = t.coerceIn(0.0, total)
        atualizarPrevia()
    }

    /**
     * O play do player fixo: vídeo simples toca direto; com efeitos, monta antes a prévia leve
     * (com barra, porcentagem e tempo restante) e então toca no mesmo lugar.
     */
    fun tocarOuPausar() {
        val atual = projeto ?: return
        if (atual.clipes.isEmpty()) return
        if (previaDireta || tocandoPrevia || previaMontada) { alternarPlayer(); return }
        gerarPreviaEmVideo(tocarDepois = true)
    }

    /** ±5 s, início e fim: tocando, continua tocando do novo ponto. */
    fun pular(t: Double) {
        instante = t.coerceIn(0.0, maxOf(0.0, total - 1.0 / (projeto?.fps ?: 30)))
        if (!tocandoPrevia) atualizarPrevia()
    }

    /** Quadro a quadro pausa, para conferir um ponto exato. */
    fun quadro(passo: Int) {
        val fps = projeto?.fps ?: return
        irPara((Math.round(instante * fps) + passo).toDouble() / fps)
    }

    fun mostrarInstanteDoClipe(id: String, segundosNaOrigem: Double) {
        if (!segundosNaOrigem.isFinite()) return
        val p = projeto ?: return
        val clipe = p.clipes.firstOrNull { it.id == id } ?: return
        val segmento = Estudio.segmentos(p).first { it.id == id }
        val local = (segundosNaOrigem - (clipe.inicio ?: 0.0)) / Estudio.velocidadeDoClipe(clipe)
        irPara(segmento.inicio + local.coerceIn(0.0, segmento.duracao))
    }

    private fun atualizarPrevia() {
        val atual = projeto ?: return
        trabalhoDaPrevia?.cancel()
        if (previaDireta) return
        tocandoPrevia = false
        trabalhoDaPrevia = viewModelScope.launch {
            delay(120)
            try { previa = montador.quadro(atual, instante) }
            catch (cancelado: CancellationException) { throw cancelado }
            catch (falha: Exception) { erro = falha.message ?: "Não foi possível montar a prévia." }
        }
    }

    fun gerarPreviaEmVideo(tocarDepois: Boolean = false) = executar("Montando a prévia leve (baixa resolução)…") {
        tocandoPrevia = false
        val atual = projeto ?: return@executar
        estimativa = Estimativa.segundos(atual, Qualidade.PREVIA, velocidade)
        val video = Exportador.previa(contexto, atual) { relatar(it) }
        aprender(Qualidade.PREVIA.aplicar(atual).copy(saida = "video"), Qualidade.PREVIA)
        videoDaPrevia?.takeIf { it != video }?.delete()
        videoDaPrevia = video
        projetoDaPrevia = atual
        if (tocarDepois && projeto == atual) {
            if (instante >= total - .05) instante = 0.0
            tocandoPrevia = true
        }
    }

    /** Aprende a velocidade deste celular com a montagem que acabou (só sem recorte de fundo). */
    private fun aprender(p: Projeto, nivel: Qualidade) {
        val segundos = (System.nanoTime() / 1_000_000 - comecoDoTrabalho) / 1000.0
        val quadros = Estudio.duracaoTotal(p) * p.fps
        if (p.saida !in listOf("video", "gif") || quadros < 30 || Estimativa.quadrosRecortados(p) > 0) return
        velocidade = velocidade.aprender((segundos / quadros - Velocidade.SEGUNDOS_POR_QUADRO) / (p.largura * p.altura / 1e6 * Estimativa.pesoDaComposicao(p) * nivel.custo), null)
        preferencias.edit().putFloat("segundosPorMegapixelQuadro", velocidade.segundosPorMegapixelQuadro.toFloat())
            .putFloat("segundosPorMascara", velocidade.segundosPorMascara.toFloat()).apply()
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
        val nivel = Qualidade.porId(qualidade)
        estimativa = Estimativa.segundos(atual, nivel, velocidade)
        resultado = Exportador.exportar(contexto, atual, modeloWhisper, qualidade = nivel) { relatar(it) }
        if (atual.saida != "audio" && atual.saida != "texto") aprender(nivel.aplicar(atual), nivel)
    }

    fun cancelar() {
        trabalho?.cancel()
    }

    // ==================== interno ====================

    private fun relatar(porcentagem: Int) {
        progresso = maxOf(progresso ?: 0, porcentagem.coerceIn(0, 99))
        // Enquanto o andamento não dá para medir, vale a estimativa feita antes de começar.
        val decorrido = (System.nanoTime() / 1_000_000 - comecoDoTrabalho) / 1000.0
        restante = andamento.registrar(progresso!!) ?: estimativa?.let { maxOf(1.0, it - decorrido).toInt() }
    }

    private fun executar(texto: String, bloco: suspend () -> Unit) {
        if (trabalho?.isActive == true) return
        trabalho = viewModelScope.launch {
            ocupado = texto
            progresso = 0
            restante = null
            andamento = Andamento()
            estimativa = null
            comecoDoTrabalho = System.nanoTime() / 1_000_000
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
        val novo = mudanca(atual)
        if (novo == atual) return
        historico.registrar(atual)
        atualizarHistorico()
        projeto = novo
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
        // A mídia continua disponível para desfazer/refazer; novoProjeto limpa a pasta.
        salvar()
    }

    private fun novoId(prefixo: String) = "$prefixo${UUID.randomUUID().toString().take(8)}"

    override fun onCleared() {
        videoDaPrevia?.delete()
    }
}
