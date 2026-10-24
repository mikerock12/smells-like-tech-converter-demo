package br.com.smellsliketech.converter.figurinhas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.smellsliketech.converter.conversao.Andamento
import br.com.smellsliketech.converter.conversao.Entrada
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.Tipo
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.roundToInt

/** De onde vem a figurinha: uma imagem parada, um GIF ou um vídeo. */
enum class TipoDaOrigem { IMAGEM, GIF, VIDEO }

/** O arquivo aberto no editor. `copia` é o arquivo no cache que o FFmpeg lê (vídeo e GIF). */
class Origem(val uri: Uri, val nome: String, val tipo: TipoDaOrigem, val copia: File?)

/**
 * O gerador de figurinhas: os pacotes guardados no app, o arquivo aberto no editor, a edição
 * e o trabalho de gerar a figurinha. Fica fora da tela para nada se perder ao girar o celular.
 */
class FigurinhasViewModel(aplicacao: Application) : AndroidViewModel(aplicacao) {
    private val contexto = aplicacao
    val pacotes = Pacotes(aplicacao)
    private val renderizador = Renderizador(aplicacao)
    private val compositor = CompositorDeFigurinhas(aplicacao)

    var lista by mutableStateOf(pacotes.listar())
        private set
    var pacoteAberto by mutableStateOf<PacoteDeFigurinhas?>(null)
    var aviso by mutableStateOf<String?>(null)

    // ---- Editor ----
    var origem by mutableStateOf<Origem?>(null)
        private set
    /** Animada (trecho) ou estática (um quadro); imagem parada é sempre estática. */
    var animada by mutableStateOf(false)

    private val preferencias = aplicacao.getSharedPreferences("figurinhas", android.content.Context.MODE_PRIVATE)

    /** Quadros por segundo da animada (8, 10, 12 ou 15), iguais em toda figurinha; a escolha fica guardada. */
    var quadrosPorSegundo by mutableStateOf(
        preferencias.getInt("quadrosPorSegundo", Regras.QUADROS_POR_SEGUNDO_PADRAO).takeIf { it in Regras.QUADROS_POR_SEGUNDO }
            ?: Regras.QUADROS_POR_SEGUNDO_PADRAO,
    )
        private set

    fun mudarQuadrosPorSegundo(valor: Int) {
        if (valor !in Regras.QUADROS_POR_SEGUNDO) return
        if (valor == quadrosPorSegundo) return
        if (origem != null) registrar()
        quadrosPorSegundo = valor
        preferencias.edit().putInt("quadrosPorSegundo", valor).apply()
    }
    var inicio by mutableStateOf(0.0)
    var fim by mutableStateOf(0.0)
    private var edicaoPrincipal by mutableStateOf(Edicao())
    var camadas by mutableStateOf<List<CamadaDeFigurinha>>(emptyList())
        private set
    var camadaSelecionada by mutableStateOf<String?>(null)
        private set
    var corDeFundo by mutableStateOf(android.graphics.Color.TRANSPARENT)
        private set
    var corFinalDoFundo by mutableStateOf<Int?>(null)
        private set
    var tempoDaPrevia by mutableStateOf(0f)
        private set
    private var instanteEscolhido = false
    private var trabalhoDaPrevia: Job? = null
    private fun selecionada() = camadas.firstOrNull { it.id == camadaSelecionada }
    val origemSelecionada get() = if (camadaSelecionada == null) origem else selecionada()?.origem
    val inicioSelecionado get() = selecionada()?.inicio ?: inicio
    val fimSelecionado get() = if (camadaSelecionada == null) fim
        else selecionada()?.let { it.fim ?: (it.inicio + (fim - inicio).coerceIn(0.2, 10.0)) } ?: 0.0
    var edicao: Edicao
        get() = selecionada()?.edicao ?: edicaoPrincipal
        set(valor) {
            if (camadaSelecionada == null) edicaoPrincipal = valor
            else camadas = camadas.map { if (it.id == camadaSelecionada) it.copy(edicao = valor) else it }
        }
    var emojis by mutableStateOf(listOf(Pacotes.EMOJI_PADRAO))

    /** A figurinha do pacote reaberta no editor (pacote, arquivo): salvar troca essa em vez de acrescentar. */
    var editando by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** As guias do enquadramento enquanto o dedo mexe (ou logo depois de um ajuste pelos controles). */
    var guias by mutableStateOf<List<Guias.Guia>>(emptyList())
        private set
    var mostrandoGuias by mutableStateOf(false)
        private set
    /** Girando, o giro encaixou no reto (0°, 90°, 180°). */
    var giroReto by mutableStateOf(false)
        private set
    private var trabalhoDasGuias: Job? = null

    /** A figurinha de verdade, gerada pelo botão de prévia: a mesma que o Salvar gravaria. */
    class PreviaFinal(
        val arquivo: File,
        val segundos: Double,
        val bytes: Int,
        val quadrosPorSegundo: Int,
        val animada: Boolean,
        val encurtada: Boolean,
        internal val assinatura: Any,
    )
    var previaFinal by mutableStateOf<PreviaFinal?>(null)
        private set
    var exibindoPreviaFinal by mutableStateOf(false)

    /** A imagem base do editor (a imagem, ou o quadro de vídeo escolhido) e a máscara do fundo. */
    private var basePrincipal by mutableStateOf<Bitmap?>(null)
    private var mascaraPrincipal by mutableStateOf<Bitmap?>(null)
    private var mascaraManualPrincipal = false
    var base: Bitmap?
        get() = selecionada()?.imagem ?: basePrincipal
        private set(valor) { basePrincipal = valor }
    var mascara: Bitmap?
        get() = selecionada()?.mascara ?: if (camadaSelecionada == null) mascaraPrincipal else null
        private set(valor) {
            if (camadaSelecionada == null) mascaraPrincipal = valor
            else camadas = camadas.map { if (it.id == camadaSelecionada) it.copy(mascara = valor) else it }
        }
    var previa by mutableStateOf<Bitmap?>(null)
        private set
    var ocupado by mutableStateOf<String?>(null)
        private set
    var progresso by mutableStateOf<Int?>(null)
        private set
    /** O tempo que falta (segundos), pelo mesmo cálculo das conversões; null enquanto não dá. */
    var restante by mutableStateOf<Int?>(null)
        private set
    private var andamento = Andamento()

    /**
     * Uma só porcentagem, de 0 a 100, somando as etapas pelo peso de cada uma; nunca volta
     * para trás. `etapa` é o que aparece no diálogo.
     */
    private fun relatar(fracao: Double, etapa: String) {
        val novo = (fracao * 100).toInt().coerceIn(0, 99)
        progresso = maxOf(progresso ?: 0, novo)
        ocupado = etapa
        restante = andamento.registrar(progresso!!)
    }
    private var trabalho: Job? = null
    private var trabalhoDaBase: Job? = null
    private val trabalhosDasCamadas = mutableMapOf<String, Job>()
    private var versaoDoEditor = 0

    private data class EstadoDoEditor(
        val base: Bitmap?, val edicao: Edicao, val mascara: Bitmap?, val manual: Boolean,
        val camadas: List<CamadaDeFigurinha>, val selecionada: String?, val fundo: Int, val fundoFinal: Int?,
        val emojis: List<String>, val inicio: Double, val fim: Double, val animada: Boolean, val fps: Int,
    )
    private val historico = HistoricoDeEdicao<EstadoDoEditor>(peso = { estado ->
        (listOfNotNull(estado.base, estado.mascara) + estado.camadas.flatMap { listOfNotNull(it.imagem, it.mascara) }).distinct().sumOf { it.allocationByteCount.toLong() }
    })
    private val copiasRetidas = mutableSetOf<File>()
    var podeDesfazer by mutableStateOf(false)
        private set
    var podeRefazer by mutableStateOf(false)
        private set
    private fun estadoDoEditor() = EstadoDoEditor(basePrincipal, edicaoPrincipal, mascaraPrincipal, mascaraManualPrincipal,
        camadas, camadaSelecionada, corDeFundo, corFinalDoFundo, emojis, inicio, fim, animada, quadrosPorSegundo)
    private fun atualizarHistorico() { podeDesfazer = historico.podeDesfazer; podeRefazer = historico.podeRefazer }
    private fun registrar(chave: String = "") {
        historico.registrar(estadoDoEditor(), chave)
        atualizarHistorico()
    }
    fun iniciarAlteracao() = historico.iniciar()
    fun terminarAlteracao() = historico.terminar()
    fun desfazer() { historico.desfazer(estadoDoEditor())?.let(::restaurar) }
    fun refazer() { historico.refazer(estadoDoEditor())?.let(::restaurar) }
    private fun restaurar(estado: EstadoDoEditor) {
        trabalhoDaBase?.cancel(); versaoDoEditor++
        trabalhosDasCamadas.values.forEach { it.cancel() }; trabalhosDasCamadas.clear()
        basePrincipal = estado.base
        edicaoPrincipal = estado.edicao; mascaraPrincipal = estado.mascara; mascaraManualPrincipal = estado.manual
        camadas = estado.camadas; camadaSelecionada = estado.selecionada
        corDeFundo = estado.fundo; corFinalDoFundo = estado.fundoFinal; emojis = estado.emojis
        inicio = estado.inicio; fim = estado.fim; animada = estado.animada
        quadrosPorSegundo = estado.fps
        tempoDaPrevia = tempoDaPrevia.coerceIn(0f, (fim - inicio).toFloat().coerceAtLeast(0f))
        preferencias.edit().putInt("quadrosPorSegundo", estado.fps).apply()
        atualizarHistorico(); atualizarPrevia()
    }
    fun mudarAnimada(valor: Boolean) { if (animada != valor) { registrar(); animada = valor } }
    fun mudarEmojis(valor: List<String>) { if (emojis != valor) { registrar(); emojis = valor } }
    fun mudarTrecho(de: Double, ate: Double) {
        if (de == inicio && ate == fim) return
        registrar("trecho")
        val inicioMudou = inicio != de
        inicio = de; fim = ate
        tempoDaPrevia = tempoDaPrevia.coerceIn(0f, (fim - inicio).toFloat().coerceAtLeast(0f))
        if (inicioMudou) carregarBase()
        else atualizarPrevia()
    }

    /** O corte do player pertence à camada selecionada; a duração da composição é separada. */
    fun mudarTrechoSelecionado(de: Double, ate: Double) {
        val camada = selecionada() ?: run { if (camadaSelecionada == null) mudarTrecho(de, ate); return }
        if (camada.origem?.tipo != TipoDaOrigem.VIDEO || !de.isFinite() || !ate.isFinite() || de < 0 || ate <= de) return
        val limite = minOf(ate, de + Regras.ANIMADA_MAX_SEGUNDOS)
        if (de == camada.inicio && limite == camada.fim) return
        registrar("trecho:${camada.id}")
        camadas = camadas.map { if (it.id == camada.id) it.copy(inicio = de, fim = limite) else it }
        instanteEscolhido = true
        atualizarPrevia()
        if (de != camada.inicio) carregarBaseDaCamada(camada.id)
    }

    fun mostrarInstanteDaSelecionada(segundosNaOrigem: Double) {
        mostrarInstante((segundosNaOrigem - inicioSelecionado).toFloat())
    }

    private fun carregarBaseDaCamada(id: String) {
        trabalhosDasCamadas[id]?.cancel()
        val camada = camadas.firstOrNull { it.id == id } ?: return
        val arquivo = camada.origem?.copia ?: return
        trabalhosDasCamadas[id] = viewModelScope.launch {
            delay(300)
            ocupado = "Preparando a imagem…"
            try {
                val imagem = Producao.quadro(contexto, arquivo, camada.inicio)
                ensureActive()
                val atual = camadas.firstOrNull { it.id == id }
                if (atual == null || atual.origem !== camada.origem || atual.inicio != camada.inicio) { imagem.recycle(); return@launch }
                camadas = camadas.map { if (it.id == id) it.copy(imagem = imagem, mascara = null, mascaraManual = false) else it }
                if (atual.edicao.removerFundo) calcularMascara(id)
                atualizarPrevia()
            } catch (erro: CancellationException) { throw erro }
            catch (erro: Exception) { aviso = erro.message ?: "Não deu para preparar a camada." }
            finally { ocupado = null }
        }
    }

    fun recarregar() {
        lista = pacotes.listar()
        pacoteAberto = pacoteAberto?.let { pacotes.ler(it.id) }
    }

    // ---- Abrir um arquivo ----

    fun abrir(uri: Uri) {
        trabalho?.cancel()
        trabalho = viewModelScope.launch {
            ocupado = "Abrindo o arquivo…"
            try {
                val resolvedor = contexto.contentResolver
                val nome = resolvedor.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "arquivo"
                val mime = tipoMime(uri, nome)
                val tipo = when {
                    mime == "image/gif" || nome.endsWith(".gif", true) -> TipoDaOrigem.GIF
                    mime.startsWith("video/") -> TipoDaOrigem.VIDEO
                    mime.startsWith("image/") -> TipoDaOrigem.IMAGEM
                    else -> throw ErroDeConversao("Escolha uma imagem, um GIF ou um vídeo.")
                }
                limparEditor()
                val copia = if (tipo == TipoDaOrigem.IMAGEM) null
                else Ffmpeg.copiar(contexto, Entrada(uri, nome, if (tipo == TipoDaOrigem.VIDEO) Tipo.VIDEO else Tipo.IMAGEM, 0))
                origem = Origem(uri, nome, tipo, copia)
                animada = tipo != TipoDaOrigem.IMAGEM
                inicio = 0.0
                fim = if (tipo == TipoDaOrigem.IMAGEM) 0.0 else Regras.ANIMADA_MAX_SEGUNDOS
                carregarBase()
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para abrir o arquivo."
                origem = null
            } finally {
                ocupado = null
            }
        }
    }

    /** A imagem que o editor mostra: a imagem, ou o quadro do vídeo/GIF no início marcado. */
    fun carregarBase() {
        val atual = origem ?: return
        trabalhoDaBase?.cancel()
        trabalhoDaBase = viewModelScope.launch {
            // Arrastando o início do trecho: espera o dedo parar antes de tirar o quadro.
            if (basePrincipal != null) delay(300)
            ocupado = "Preparando a imagem…"
            try {
                val imagem = when (atual.tipo) {
                    TipoDaOrigem.IMAGEM -> withContext(Dispatchers.IO) { decodificar(atual.uri) }
                    else -> Producao.quadro(contexto, atual.copia!!, inicio)
                }
                ensureActive()
                if (origem !== atual) return@launch
                basePrincipal = imagem
                mascaraPrincipal = null
                mascaraManualPrincipal = false
                if (edicaoPrincipal.removerFundo) calcularMascara(null)
                atualizarPrevia()
            } catch (erro: CancellationException) {
                throw erro
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para preparar a imagem."
            } finally {
                ocupado = null
            }
        }
    }

    private fun decodificar(uri: Uri): Bitmap {
        val fonte = ImageDecoder.createSource(contexto.contentResolver, uri)
        return ImageDecoder.decodeBitmap(fonte) { decodificador, info, _ ->
            decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decodificador.isMutableRequired = false
            val maior = maxOf(info.size.width, info.size.height)
            if (maior > 1024) {
                val escala = 1024f / maior
                decodificador.setTargetSize((info.size.width * escala).roundToInt(), (info.size.height * escala).roundToInt())
            }
        }
    }

    // ---- Editar ----

    val temAnimacao get() = origem?.tipo?.let { it != TipoDaOrigem.IMAGEM } == true || camadas.any { it.origem?.tipo?.let { tipo -> tipo != TipoDaOrigem.IMAGEM } == true }

    fun selecionarCamada(id: String?) { camadaSelecionada = id }

    fun mudarFundo(cor: Int, final: Int? = null) {
        if (cor == corDeFundo && final == corFinalDoFundo) return
        registrar("fundo")
        corDeFundo = cor
        corFinalDoFundo = final
        atualizarPrevia()
    }

    fun moverCamada(id: String, passo: Int) {
        // Inclui a mídia principal (ordem zero) na ordenação.
        val ordem = (camadas.map { it.id to it.ordem } + ("" to 0)).sortedBy { it.second }.map { it.first }.toMutableList()
        val indice = ordem.indexOf(id)
        if (indice < 0) return
        registrar()
        val alvo = (indice + passo).coerceIn(0, ordem.lastIndex)
        ordem.add(alvo, ordem.removeAt(indice))
        val principal = ordem.indexOf("")
        camadas = camadas.map { it.copy(ordem = ordem.indexOf(it.id) - principal) }
        atualizarPrevia()
    }

    fun ocultarCamada(id: String) {
        registrar()
        camadas = camadas.map { if (it.id == id) it.copy(visivel = !it.visivel) else it }
        atualizarPrevia()
    }

    fun excluirCamada(id: String) {
        val removida = camadas.firstOrNull { it.id == id } ?: return
        registrar()
        trabalhosDasCamadas.remove(id)?.cancel()
        camadas = camadas.filterNot { it.id == id }
        removida.origem?.copia?.let { copiasRetidas += it }
        if (camadaSelecionada == id) camadaSelecionada = null
        if (!temAnimacao) animada = false
        atualizarPrevia()
    }

    fun adicionarTexto(texto: String = "Seu texto", emoji: Boolean = false) {
        if (camadas.size >= 12) { aviso = "Use até 12 camadas adicionais por figurinha."; return }
        registrar()
        val camada = CamadaDeFigurinha(nome = if (emoji) "Emoji $texto" else "Texto",
            imagem = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), texto = true,
            edicao = Edicao(texto = texto, tamanhoDoTexto = if (emoji) 180f else 64f, posicaoDoTexto = PosicaoDoTexto.MEIO,
                corDoContornoDoTexto = if (emoji) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK),
            ordem = (camadas.maxOfOrNull { it.ordem } ?: 0) + 1)
        camadas = camadas + camada
        camadaSelecionada = camada.id
        atualizarPrevia()
    }

    /** O tipo pelo provedor; num `file://` (stickers do catálogo, testes), pela extensão. */
    private fun tipoMime(uri: Uri, nome: String): String =
        contexto.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(nome.substringAfterLast('.', "").lowercase())
            ?: ""

    fun adicionarMidia(uri: Uri, comoFundo: Boolean = false, descartarAoAbrir: Boolean = false) {
        trabalho = viewModelScope.launch {
            ocupado = "Adicionando a camada…"
            var copia: File? = null
            try {
                require(camadas.size < 12) { "Use até 12 camadas adicionais por figurinha." }
                val resolvedor = contexto.contentResolver
                val nome = resolvedor.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment.orEmpty()
                val mime = tipoMime(uri, nome)
                val tipo = when {
                    mime == "image/gif" || nome.endsWith(".gif", true) -> TipoDaOrigem.GIF
                    mime.startsWith("video/") -> TipoDaOrigem.VIDEO
                    mime.startsWith("image/") || nome.endsWith(".png", true) || nome.endsWith(".webp", true) -> TipoDaOrigem.IMAGEM
                    else -> throw ErroDeConversao("Escolha uma imagem, um GIF ou um vídeo.")
                }
                copia = if (tipo == TipoDaOrigem.IMAGEM) null else Ffmpeg.copiar(contexto, Entrada(uri, nome, if (tipo == TipoDaOrigem.VIDEO) Tipo.VIDEO else Tipo.IMAGEM, 0))
                val imagem = if (copia == null) withContext(Dispatchers.IO) { decodificar(uri) } else Producao.quadro(contexto, copia, 0.0)
                val camada = CamadaDeFigurinha(nome = nome, origem = Origem(uri, nome, tipo, copia), imagem = imagem,
                    edicao = Edicao(zoom = if (comoFundo) 1f else 0.5f),
                    ordem = if (comoFundo) minOf(-1, (camadas.minOfOrNull { it.ordem } ?: 0) - 1) else (camadas.maxOfOrNull { it.ordem } ?: 0) + 1)
                registrar()
                camadas = camadas + camada
                copia?.let { copiasRetidas += it }
                camadaSelecionada = camada.id
                if (tipo != TipoDaOrigem.IMAGEM) {
                    animada = true
                    if (fim <= inicio) fim = inicio + 3.0
                }
                atualizarPrevia()
            } catch (erro: CancellationException) { copia?.delete(); throw erro }
            catch (erro: Exception) { copia?.delete(); aviso = erro.message }
            finally {
                if (descartarAoAbrir && uri.scheme == "file") uri.path?.let { File(it).delete() }
                ocupado = null
            }
        }
    }

    private fun composicao(): List<CamadaDeFigurinha> {
        val principal = basePrincipal ?: return emptyList()
        return listOf(CamadaDeFigurinha(id = "principal", nome = origem?.nome.orEmpty(), origem = origem,
            imagem = principal, mascara = mascaraPrincipal, mascaraManual = mascaraManualPrincipal, edicao = edicaoPrincipal, ordem = 0)) + camadas
    }

    fun mudar(nova: Edicao) {
        if (nova == edicao) return
        val anterior = edicao
        val campo = when {
            nova.copy(zoom = anterior.zoom) == anterior -> "zoom"
            nova.copy(deslocamentoX = anterior.deslocamentoX) == anterior -> "deslocamentoX"
            nova.copy(deslocamentoY = anterior.deslocamentoY) == anterior -> "deslocamentoY"
            nova.copy(giro = anterior.giro) == anterior -> "giro"
            nova.copy(inclinacaoX = anterior.inclinacaoX) == anterior -> "inclinacaoX"
            nova.copy(inclinacaoY = anterior.inclinacaoY) == anterior -> "inclinacaoY"
            nova.copy(opacidade = anterior.opacidade) == anterior -> "opacidade"
            nova.copy(brilho = anterior.brilho) == anterior -> "brilho"
            nova.copy(contraste = anterior.contraste) == anterior -> "contraste"
            nova.copy(saturacao = anterior.saturacao) == anterior -> "saturacao"
            nova.copy(texto = anterior.texto) == anterior -> "texto"
            nova.copy(tamanhoDoTexto = anterior.tamanhoDoTexto) == anterior -> "tamanhoDoTexto"
            nova.copy(larguraDoContorno = anterior.larguraDoContorno) == anterior -> "larguraDoContorno"
            else -> ""
        }
        registrar(if (campo.isEmpty()) "" else "edicao:$camadaSelecionada:$campo")
        val ligouRemocao = nova.removerFundo && !edicao.removerFundo
        edicao = nova
        if (ligouRemocao && mascara == null) {
            val id = camadaSelecionada
            viewModelScope.launch {
                ocupado = "Removendo o fundo…"
                try { calcularMascara(id) } finally { ocupado = null }
                atualizarPrevia()
            }
        } else {
            atualizarPrevia()
        }
    }

    private suspend fun calcularMascara(id: String?) {
        val versao = versaoDoEditor
        val imagem = if (id == null) basePrincipal else camadas.firstOrNull { it.id == id }?.imagem
        imagem ?: return
        try {
            val recorte = withContext(Dispatchers.Default) { Fundo.mascara(imagem) }
            if (versao != versaoDoEditor || (if (id == null) basePrincipal else camadas.firstOrNull { it.id == id }?.imagem) !== imagem) {
                recorte.recycle()
                return
            }
            if (id == null) { mascaraPrincipal = recorte; mascaraManualPrincipal = false }
            else camadas = camadas.map { if (it.id == id) it.copy(mascara = recorte, mascaraManual = false) else it }
        } catch (erro: CancellationException) {
            throw erro
        } catch (erro: Exception) {
            if (versao != versaoDoEditor) return
            aviso = erro.message
            if (id == null) edicaoPrincipal = edicaoPrincipal.copy(removerFundo = false)
            else camadas = camadas.map { if (it.id == id) it.copy(edicao = it.edicao.copy(removerFundo = false)) else it }
        }
    }

    /**
     * O pincel: apaga (ou devolve) a figura em volta do ponto tocado. `x` e `y` são frações
     * (0..1) da imagem base; `raio` também, em relação ao lado maior.
     */
    fun pincelar(x: Float, y: Float, raio: Float, apagar: Boolean, deX: Float = x, deY: Float = y) {
        val imagem = base ?: return
        if (x !in 0f..1f || y !in 0f..1f || raio <= 0) return
        registrar("pincel:$camadaSelecionada")
        val atual = mascara ?: Fundo.mascaraCheia(imagem.width, imagem.height)
        // A prévia pode estar lendo a máscara anterior em outra thread.
        val editavel = atual.copy(Bitmap.Config.ARGB_8888, true)
        val tela = android.graphics.Canvas(editavel)
        val tinta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            strokeWidth = raio * maxOf(imagem.width, imagem.height) * 2
            strokeCap = android.graphics.Paint.Cap.ROUND
            if (apagar) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        tela.drawLine(deX * imagem.width, deY * imagem.height, x * imagem.width, y * imagem.height, tinta)
        tela.drawCircle(x * imagem.width, y * imagem.height, raio * maxOf(imagem.width, imagem.height), tinta)
        mascara = editavel
        if (camadaSelecionada == null) mascaraManualPrincipal = true
        else camadas = camadas.map { if (it.id == camadaSelecionada) it.copy(mascaraManual = true) else it }
        if (!edicao.removerFundo) edicao = edicao.copy(removerFundo = true)
        atualizarPrevia()
    }

    /** O pincel a partir da prévia: (x, y) e `raio` em frações da figurinha de 512×512. */
    fun pincelarNaPrevia(x: Float, y: Float, raio: Float, apagar: Boolean, deX: Float = x, deY: Float = y) {
        val imagem = base ?: return
        val (ix, iy, ir) = renderizador.pontoNaImagem(x, y, raio, imagem.width, imagem.height, edicao)
        val (anteriorX, anteriorY, _) = renderizador.pontoNaImagem(deX, deY, raio, imagem.width, imagem.height, edicao)
        pincelar(ix, iy, ir, apagar, anteriorX, anteriorY)
    }

    /**
     * Redesenha a prévia acompanhando o dedo. Enquanto um desenho está em curso, os gestos só
     * marcam que há mudança; ao terminar, o próximo já sai com o estado mais recente. Assim a
     * imagem se move em tempo real (um quadro por desenho) sem acumular trabalho. Cancelar e
     * esperar a cada evento, como antes, deixava a prévia parada até o dedo soltar.
     */
    fun atualizarPrevia() {
        previaPendente = true
        if (trabalhoDaPrevia?.isActive == true && desenhandoPrevia) return
        trabalhoDaPrevia?.cancel()
        trabalhoDaPrevia = viewModelScope.launch {
            desenhandoPrevia = true
            try {
                while (previaPendente) {
                    previaPendente = false
                    val lista = composicao()
                    if (lista.isEmpty()) break
                    val segundo = tempoDaPrevia
                    val desenho = if (instanteEscolhido) desenharInstante(lista, segundo)
                    else withContext(Dispatchers.Default) { compositor.desenhar(lista, corDeFundo, corFinalDoFundo) }
                    ensureActive()
                    if (segundo == tempoDaPrevia) previa = desenho else desenho.recycle()
                }
            } catch (erro: CancellationException) { throw erro }
            catch (erro: Exception) { aviso = "Não foi possível atualizar a prévia: ${erro.message}" }
            finally {
                desenhandoPrevia = false
            }
        }
    }
    private var previaPendente = false
    private var desenhandoPrevia = false

    fun mostrarInstante(segundos: Float) {
        if (!segundos.isFinite()) return
        tempoDaPrevia = segundos.coerceIn(0f, (fim - inicio).toFloat().coerceAtLeast(0f))
        instanteEscolhido = true
        atualizarPrevia()
    }

    /** O quadro escolhido também é a base visual dos ajustes seguintes. */
    private suspend fun desenharInstante(lista: List<CamadaDeFigurinha>, segundos: Float): Bitmap {
        val temporarios = mutableListOf<Bitmap>()
        try {
            val quadros = lista.map { camada ->
                val trecho = trechoDe(camada)
                if (trecho == null) camada else {
                    val sequencia = quadrosDoTrecho(trecho) { ocupado = CONVERTENDO_PARA_A_PREVIA }
                    val indice = (segundos * sequencia.porSegundo).roundToInt().let {
                        if (camada.id == "principal") it.coerceAtMost(sequencia.arquivos.lastIndex) else it
                    }
                    val quadro = withContext(Dispatchers.IO) { lerQuadro(sequencia, indice, camada) }
                    temporarios += quadro
                    val recorte = if (camada.mascaraManual) camada.mascara else if (camada.edicao.removerFundo) Fundo.mascara(quadro).also { temporarios += it } else null
                    camada.copy(imagem = quadro, mascara = recorte)
                }
            }
            return withContext(Dispatchers.Default) { compositor.desenhar(quadros, corDeFundo, corFinalDoFundo) }
        } finally {
            if (ocupado == CONVERTENDO_PARA_A_PREVIA) ocupado = null
            temporarios.forEach { it.recycle() }
        }
    }

    // ---- Salvar no pacote ----

    fun pacotesCompativeis(): List<PacoteDeFigurinhas> = lista.filter { it.animado == animada && !it.cheio }

    /**
     * Gera a figurinha (estática ou animada) e põe no pacote escolhido, ou num novo com
     * `nomeDoNovo`. No fim abre o pacote, pronto para ir ao WhatsApp.
     */
    fun salvar(destino: PacoteDeFigurinhas?, nomeDoNovo: String) {
        val atual = origem ?: return
        trabalho?.cancel()
        trabalho = viewModelScope.launch {
            ocupado = "Gerando a figurinha…"
            progresso = 0
            restante = null
            andamento = Andamento()
            try {
                var encurtada: Double? = null
                val pronta = previaFinal?.takeIf { it.assinatura == assinatura() }
                val webp = when {
                    pronta != null -> withContext(Dispatchers.IO) { pronta.arquivo.readBytes() }.also { if (pronta.encurtada) encurtada = pronta.segundos }
                    animada && temAnimacao -> gerarAnimadaEmCamadas().also { if (it.encurtada) encurtada = it.segundos }.webp
                    else -> gerarEstatica()
                }
                val alvo = editando
                val (atualizado, arquivo) = if (alvo != null) {
                    val pacote = pacotes.ler(alvo.first) ?: throw ErroDeConversao("O pacote desta figurinha não existe mais.")
                    val figurinha = pacote.figurinhas.firstOrNull { it.arquivo == alvo.second } ?: throw ErroDeConversao("A figurinha saiu do pacote.")
                    if (pacote.animado != (animada && temAnimacao)) throw ErroDeConversao(
                        if (pacote.animado) "Este pacote é de figurinhas animadas: deixe a figurinha animada." else "Este pacote é de figurinhas estáticas: deixe a figurinha estática.")
                    withContext(Dispatchers.IO) { pacotes.substituir(pacote, figurinha, webp, emojis) } to figurinha.arquivo
                } else {
                    val pacote = destino?.let { pacotes.ler(it.id) } ?: pacotes.criar(nomeDoNovo, animada)
                    val novo = withContext(Dispatchers.IO) { pacotes.adicionar(pacote, webp, emojis) }
                    novo to novo.figurinhas.last().arquivo
                }
                relatar(0.99, "Guardando para editar depois…")
                runCatching { guardarProjeto(atualizado, arquivo) }.onFailure { android.util.Log.w("Figurinhas", "projeto não guardado", it) }
                WhatsApp.avisarMudanca(contexto)
                recarregar()
                pacoteAberto = atualizado
                fecharEditor()
                aviso = (if (alvo != null) "Figurinha atualizada em “${atualizado.nome}”."
                else if (atualizado.prontoParaWhatsApp) "Figurinha salva em “${atualizado.nome}”."
                else "Figurinha salva. Faltam ${atualizado.faltam} para o WhatsApp aceitar o pacote (mínimo ${Regras.MINIMO}).") +
                    (encurtada?.let {
                        " Para manter $quadrosPorSegundo quadros/s, ela ficou com ${"%.1f".format(it)} s: o vídeo tem movimento demais para os 500 KB do WhatsApp."
                    } ?: "")
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para gerar a figurinha."
            } finally {
                ocupado = null
                progresso = null
                restante = null
            }
        }
    }

    private suspend fun gerarEstatica(): ByteArray = withContext(Dispatchers.Default) {
        if (basePrincipal == null) throw ErroDeConversao("Nada para salvar.")
        relatar(0.2, "Desenhando a figurinha…")
        // O Salvar pode acontecer antes da carga assíncrona do quadro inicial da camada.
        val temporarios = mutableListOf<Bitmap>()
        val desenho = try {
            val quadros = composicao().map { camada ->
                if (camada.origem?.tipo != TipoDaOrigem.VIDEO) camada else {
                    val comeco = if (camada.id == "principal") inicio else camada.inicio
                    val imagem = Producao.quadro(contexto, camada.origem.copia!!, comeco).also { temporarios += it }
                    val mascara = if (camada.mascaraManual) camada.mascara
                        else if (camada.edicao.removerFundo) Fundo.mascara(imagem).also { temporarios += it } else null
                    camada.copy(imagem = imagem, mascara = mascara)
                }
            }
            compositor.desenhar(quadros, corDeFundo, corFinalDoFundo)
        } finally { temporarios.forEach { it.recycle() } }
        relatar(0.6, "Compactando para o WhatsApp (até 100 KB)…")
        try { Producao.estatica(desenho) } finally { desenho.recycle() }
    }

    private suspend fun gerarAnimadaEmCamadas(): Producao.Animada {
        val lista = composicao().filter { it.visivel }
        val duracao = (fim - inicio).coerceIn(0.2, 10.0)
        val taxa = quadrosPorSegundo
        val extraidos = mutableMapOf<String, Producao.Quadros>()
        val pasta = File.createTempFile("composicao-", "", contexto.cacheDir).apply { delete(); mkdirs() }
        try {
            // 1. O vídeo de cada camada vira uma versão leve (uma vez por trecho; a prévia do
            //    instante pode já ter feito isso).
            for ((indice, camada) in lista.withIndex()) {
                val trecho = trechoDe(camada) ?: continue
                extraidos[camada.id] = quadrosDoTrecho(trecho) {
                    relatar((indice + it) / lista.size * 0.2, "Convertendo o vídeo para uma versão leve…")
                }
            }
            // 2. A composição, vários quadros de uma vez (um por núcleo, até 4): cada quadro é
            //    independente e desenhar é a parte mais longa.
            val crus = Producao.QuadrosCrus(File(pasta, "composicao.argb"), taxa)
            val total = kotlin.math.ceil(duracao * taxa).toInt()
            val paralelos = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            crus.use {
                var feitos = 0
                (0 until total).chunked(paralelos).forEach { lote ->
                    val prontos = coroutineScope {
                        lote.map { indice -> async(Dispatchers.Default) { comporQuadro(lista, extraidos, indice, taxa) } }.awaitAll()
                    }
                    withContext(Dispatchers.IO) { prontos.forEach { desenho -> try { crus.gravar(desenho) } finally { desenho.recycle() } } }
                    feitos += lote.size
                    relatar(0.2 + 0.5 * feitos / total, "Compondo quadro $feitos de $total…")
                }
            }
            return Producao.animada(contexto, crus) { relatar(0.7 + it * 0.3, "Compactando para o WhatsApp…") }
        } finally {
            pasta.deleteRecursively()
        }
    }

    private suspend fun comporQuadro(lista: List<CamadaDeFigurinha>, extraidos: Map<String, Producao.Quadros>, indice: Int, taxa: Int): Bitmap {
        val temporarios = mutableListOf<Bitmap>()
        try {
            val quadros = lista.map { camada ->
                val sequencia = extraidos[camada.id]
                if (sequencia == null) camada else {
                    val imagem = lerQuadro(sequencia, (indice.toDouble() / taxa * sequencia.porSegundo).toInt(), camada)
                    temporarios += imagem
                    val recorte = if (camada.mascaraManual) camada.mascara
                    else if (camada.edicao.removerFundo) Fundo.mascara(imagem).also { temporarios += it } else null
                    camada.copy(imagem = imagem, mascara = recorte)
                }
            }
            return compositor.desenhar(quadros, corDeFundo, corFinalDoFundo)
        } finally {
            temporarios.forEach { it.recycle() }
        }
    }

    // ---- A versão leve de cada trecho de vídeo ----

    /** O trecho que uma camada de vídeo ou GIF usa: arquivo, começo, duração e lado dos quadros. */
    private data class Trecho(val arquivo: File, val comeco: Double, val duracao: Double, val lado: Int, val transparencia: Boolean, val porSegundo: Int)

    private fun trechoDe(camada: CamadaDeFigurinha): Trecho? {
        val origem = camada.origem ?: return null
        val arquivo = origem.copia ?: return null
        val comeco = if (camada.id == "principal") inicio else camada.inicio
        val duracao = if (camada.id == "principal") fim - inicio else camada.fim?.minus(camada.inicio) ?: (fim - inicio)
        return Trecho(arquivo, comeco, duracao.coerceIn(0.2, 10.0),
            Producao.ladoDosQuadros(camada.edicao.zoom), origem.tipo != TipoDaOrigem.VIDEO, quadrosPorSegundo)
    }

    private val trechos = mutableMapOf<Trecho, Producao.Quadros>()
    private val travaDosTrechos = Mutex()

    /**
     * A versão leve do trecho: convertida uma vez e reaproveitada pela prévia do instante e
     * pela figurinha. Um trecho igual com lado maior (zoom maior) também serve.
     */
    private suspend fun quadrosDoTrecho(trecho: Trecho, aoAvancar: (Double) -> Unit): Producao.Quadros =
        travaDosTrechos.withLock {
            trechos.entries.firstOrNull { (t, q) -> t.copy(lado = trecho.lado) == trecho && t.lado >= trecho.lado && q.arquivos.all { it.isFile } }
                ?.let { return@withLock it.value }
            // Outro trecho do mesmo arquivo não volta a ser usado: sai do cache.
            trechos.keys.filter { it.arquivo == trecho.arquivo }.forEach(::esquecerTrecho)
            aoAvancar(0.0)
            val quadros = Producao.quadros(contexto, trecho.arquivo, trecho.comeco, trecho.comeco + trecho.duracao,
                trecho.transparencia, trecho.lado, trecho.porSegundo, aoAvancar)
            trechos[trecho] = quadros
            quadros
        }

    private fun esquecerTrecho(trecho: Trecho) {
        trechos.remove(trecho)?.arquivos?.firstOrNull()?.parentFile?.deleteRecursively()
    }

    private fun lerQuadro(sequencia: Producao.Quadros, posicao: Int, camada: CamadaDeFigurinha): Bitmap =
        android.graphics.BitmapFactory.decodeFile(sequencia.arquivos[posicao.coerceAtLeast(0) % sequencia.arquivos.size].absolutePath)
            ?: throw ErroDeConversao("Não deu para abrir um quadro da camada ${camada.nome}.")

    fun cancelar() {
        restante = null
        trabalho?.cancel()
        trabalhoDaBase?.cancel()
        trabalhosDasCamadas.values.forEach { it.cancel() }; trabalhosDasCamadas.clear()
        ocupado = null
        progresso = null
    }

    fun fecharEditor() {
        limparEditor()
        origem?.copia?.delete()
        origem = null
        editando = null
    }

    private fun limparEditor() {
        historico.limpar(); atualizarHistorico()
        copiasRetidas.forEach { it.delete() }; copiasRetidas.clear()
        versaoDoEditor++
        trabalhoDaBase?.cancel()
        trabalhoDaPrevia?.cancel()
        trechos.keys.toList().forEach(::esquecerTrecho)
        previaFinal?.arquivo?.delete()
        previaFinal = null
        exibindoPreviaFinal = false
        guias = emptyList()
        mostrandoGuias = false
        camadas.forEach { it.origem?.copia?.delete() }
        camadas = emptyList()
        camadaSelecionada = null
        corDeFundo = android.graphics.Color.TRANSPARENT
        corFinalDoFundo = null
        tempoDaPrevia = 0f
        instanteEscolhido = false
        base = null
        mascara = null
        mascaraManualPrincipal = false
        previa = null
        edicao = Edicao()
        emojis = listOf(Pacotes.EMOJI_PADRAO)
    }

    // ---- Guias do enquadramento ----

    /** O tamanho da imagem de uma camada como o renderizador a vê (texto é um quadro de 512). */
    private fun medidas(camada: CamadaDeFigurinha): Pair<Int, Int> =
        if (camada.texto) Regras.LADO to Regras.LADO else camada.imagem.width to camada.imagem.height

    private fun referencias(): List<Guias.Caixa> {
        val atual = camadaSelecionada ?: "principal"
        return composicao().filter { it.visivel && !it.texto && it.id != atual }
            .mapNotNull { camada -> medidas(camada).let { (w, h) -> Guias.caixa(w, h, camada.edicao) } }
    }

    private fun medidasDaSelecionada(): Pair<Int, Int>? {
        val camada = composicao().firstOrNull { it.id == (camadaSelecionada ?: "principal") } ?: return null
        return medidas(camada)
    }

    fun iniciarGesto() {
        iniciarAlteracao()
        trabalhoDasGuias?.cancel()
        mostrandoGuias = true
    }

    /** O dedo propôs `bruta`: encaixa nas guias próximas e aplica. `girando`: o gesto já girou a imagem. */
    fun moverPorGesto(bruta: Edicao, girando: Boolean = false) {
        val (w, h) = medidasDaSelecionada() ?: return mudar(bruta)
        val alinhamento = Guias.alinhar(bruta, w, h, referencias())
        guias = alinhamento.guias
        giroReto = alinhamento.reta && girando
        mudar(alinhamento.edicao)
    }

    fun terminarGesto() { terminarAlteracao(); esconderGuiasDepois(700) }

    /** Mudança pelos controles (deslizantes, botões): sem encaixe, mas as guias mostram onde está. */
    fun ajustar(nova: Edicao) {
        val girou = nova.giro != edicao.giro
        mudar(nova)
        val (w, h) = medidasDaSelecionada() ?: return
        guias = Guias.caixa(w, h, nova)?.let { Guias.guiasDe(it, referencias()) }.orEmpty()
        giroReto = girou && nova.giro % 90 == 0
        mostrandoGuias = true
        esconderGuiasDepois(1200)
    }

    private fun esconderGuiasDepois(ms: Long) {
        trabalhoDasGuias?.cancel()
        trabalhoDasGuias = viewModelScope.launch {
            delay(ms)
            mostrandoGuias = false
            guias = emptyList()
        }
    }

    /** Preencher: a imagem cobre a figurinha 1×1 inteira, centralizada e reta. Depois dá para ajustar. */
    fun preencher() {
        val camada = composicao().firstOrNull { it.id == (camadaSelecionada ?: "principal") } ?: return
        if (camada.texto) return
        val (w, h) = medidas(camada)
        val atual = camada.edicao
        ajustar(atual.copy(zoom = Guias.zoomParaPreencher(w, h, atual), deslocamentoX = 0f, deslocamentoY = 0f,
            giro = if (atual.giro % 90 == 0) atual.giro else 0, inclinacaoX = 0f, inclinacaoY = 0f))
    }

    // ---- Prévia final ----

    /** Tudo o que muda a figurinha gerada: se nada mudou desde a prévia final, o Salvar a reaproveita. */
    private fun assinatura(): Any = listOf(composicao(), corDeFundo, corFinalDoFundo, inicio, fim, animada && temAnimacao, quadrosPorSegundo)

    /** Gera a figurinha exatamente como o Salvar faria e mostra (animada, com duração e tamanho). */
    fun verComoFica() {
        if (origem == null) return
        trabalho?.cancel()
        trabalho = viewModelScope.launch {
            ocupado = "Montando a figurinha…"
            progresso = 0
            restante = null
            andamento = Andamento()
            try {
                val assinada = assinatura()
                val animadaAgora = animada && temAnimacao
                val gerada = if (animadaAgora) gerarAnimadaEmCamadas() else Producao.Animada(gerarEstatica(), 0.0, false)
                val arquivo = File.createTempFile("previa-final-", ".webp", contexto.cacheDir)
                withContext(Dispatchers.IO) { arquivo.writeBytes(gerada.webp) }
                previaFinal?.arquivo?.delete()
                previaFinal = PreviaFinal(arquivo, gerada.segundos, gerada.webp.size, quadrosPorSegundo, animadaAgora, gerada.encurtada, assinada)
                exibindoPreviaFinal = true
            } catch (erro: CancellationException) {
                throw erro
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para montar a prévia."
            } finally {
                ocupado = null
                progresso = null
                restante = null
            }
        }
    }

    // ---- Editar de novo uma figurinha do pacote ----

    fun podeEditar(pacote: PacoteDeFigurinhas, figurinha: Figurinha): Boolean =
        File(pacotes.pastaDoProjeto(pacote, figurinha.arquivo), Projetos.ARQUIVO).isFile

    /** Reabre no editor uma figurinha salva com projeto. As mídias vão para cópias de trabalho no cache. */
    fun editar(pacote: PacoteDeFigurinhas, figurinha: Figurinha) {
        trabalho?.cancel()
        trabalho = viewModelScope.launch {
            ocupado = "Abrindo a figurinha…"
            val copias = mutableListOf<File>()
            try {
                val pasta = pacotes.pastaDoProjeto(pacote, figurinha.arquivo)
                val projeto = withContext(Dispatchers.IO) { Projetos.ler(pasta) }
                    ?: throw ErroDeConversao("Esta figurinha foi criada antes de o app guardar a edição: só as novas podem ser editadas de novo.")
                suspend fun copiaDeTrabalho(nome: String): File = withContext(Dispatchers.IO) {
                    File(pasta, nome).copyTo(File.createTempFile("edicao-", "." + nome.substringAfterLast('.'), contexto.cacheDir), overwrite = true)
                }.also { copias += it }
                suspend fun imagem(nome: String): android.graphics.Bitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(File(pasta, nome).absolutePath)
                } ?: throw ErroDeConversao("Não deu para abrir a imagem guardada da figurinha.")
                suspend fun abrirCamada(salva: CamadaSalva, segundos: Double): Pair<Origem?, android.graphics.Bitmap> = when {
                    salva.texto -> null to android.graphics.Bitmap.createBitmap(Regras.LADO, Regras.LADO, android.graphics.Bitmap.Config.ARGB_8888)
                    salva.tipo == TipoDaOrigem.IMAGEM -> Origem(Uri.fromFile(File(pasta, salva.arquivo!!)), salva.nome, TipoDaOrigem.IMAGEM, null) to imagem(salva.arquivo)
                    else -> {
                        val copia = copiaDeTrabalho(salva.arquivo!!)
                        Origem(Uri.fromFile(copia), salva.nome, salva.tipo ?: TipoDaOrigem.VIDEO, copia) to Producao.quadro(contexto, copia, segundos)
                    }
                }

                fecharEditor()
                val (origemPrincipal, basePrincipalNova) = abrirCamada(projeto.principal, projeto.inicio)
                val restauradas = projeto.camadas.map { salva ->
                    val (origemDaCamada, imagemDaCamada) = abrirCamada(salva, salva.inicio)
                    CamadaDeFigurinha(id = salva.id, nome = salva.nome, origem = origemDaCamada, imagem = imagemDaCamada,
                        mascara = salva.mascara?.let { imagem(it) }, mascaraManual = salva.mascara != null,
                        edicao = salva.edicao, visivel = salva.visivel, ordem = salva.ordem, texto = salva.texto,
                        inicio = salva.inicio, fim = salva.fim)
                }
                origem = origemPrincipal
                animada = projeto.animada
                quadrosPorSegundo = projeto.quadrosPorSegundo
                inicio = projeto.inicio
                fim = projeto.fim
                corDeFundo = projeto.corDeFundo
                corFinalDoFundo = projeto.corFinalDoFundo
                emojis = projeto.emojis.ifEmpty { listOf(Pacotes.EMOJI_PADRAO) }
                edicaoPrincipal = projeto.principal.edicao
                basePrincipal = basePrincipalNova
                mascaraPrincipal = projeto.principal.mascara?.let { imagem(it) }
                mascaraManualPrincipal = projeto.principal.mascara != null
                camadas = restauradas
                editando = pacote.id to figurinha.arquivo
                copias.clear() // agora pertencem ao editor, que as apaga ao fechar
                if (edicaoPrincipal.removerFundo && mascaraPrincipal == null) calcularMascara(null)
                restauradas.filter { it.edicao.removerFundo && it.mascara == null }.forEach { calcularMascara(it.id) }
                atualizarPrevia()
            } catch (erro: CancellationException) {
                throw erro
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para abrir a figurinha."
            } finally {
                copias.forEach { it.delete() }
                ocupado = null
            }
        }
    }

    /**
     * Guarda o projeto da figurinha recém-salva, para editar de novo depois: imagens em PNG,
     * GIF como veio e vídeo como trecho leve (só a parte usada). Grava numa pasta temporária e
     * troca no fim, para um projeto antigo nunca ficar pela metade.
     */
    private suspend fun guardarProjeto(pacote: PacoteDeFigurinhas, arquivo: String) {
        val destino = pacotes.pastaDoProjeto(pacote, arquivo)
        val temporaria = File(destino.parentFile, destino.name + ".novo")
        withContext(Dispatchers.IO) { temporaria.deleteRecursively(); temporaria.mkdirs() }
        val duracao = (fim - inicio).coerceIn(0.2, Regras.ANIMADA_MAX_SEGUNDOS)
        val animadaAgora = animada && temAnimacao
        var inicioGuardado = inicio
        var fimGuardado = fim

        suspend fun png(bitmap: android.graphics.Bitmap, nome: String): String = withContext(Dispatchers.IO) {
            File(temporaria, nome).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            nome
        }
        suspend fun midia(camada: CamadaDeFigurinha, prefixo: String, comeco: Double, segundos: Double = duracao): String? {
            val origemDaCamada = camada.origem
            return when {
                camada.texto -> null
                origemDaCamada == null || origemDaCamada.tipo == TipoDaOrigem.IMAGEM -> png(camada.imagem, "$prefixo.png")
                origemDaCamada.tipo == TipoDaOrigem.GIF -> withContext(Dispatchers.IO) {
                    origemDaCamada.copia!!.copyTo(File(temporaria, "$prefixo.gif"), overwrite = true); "$prefixo.gif"
                }
                else -> {
                    Producao.trechoParaOProjeto(contexto, origemDaCamada.copia!!, comeco, if (animadaAgora) segundos else 0.5, File(temporaria, "$prefixo.mp4"))
                    "$prefixo.mp4"
                }
            }
        }

        val lista = composicao()
        val principal = lista.first { it.id == "principal" }
        val arquivoPrincipal = midia(principal, "principal", inicio)
        if (principal.origem?.tipo == TipoDaOrigem.VIDEO) { inicioGuardado = 0.0; fimGuardado = if (animadaAgora) duracao else 0.5 }
        val salvas = lista.filter { it.id != "principal" }.mapIndexed { indice, camada ->
            val video = camada.origem?.tipo == TipoDaOrigem.VIDEO
            val segundos = (camada.fim?.minus(camada.inicio) ?: duracao).coerceIn(0.2, 10.0)
            CamadaSalva(camada.id, camada.nome, camada.origem?.tipo ?: if (camada.texto) null else TipoDaOrigem.IMAGEM,
                midia(camada, "camada-$indice", camada.inicio, segundos),
                camada.mascara?.takeIf { camada.mascaraManual }?.let { png(it, "camada-$indice-mascara.png") },
                camada.edicao, camada.visivel, camada.ordem, camada.texto,
                inicio = if (video) 0.0 else camada.inicio,
                fim = if (video) { if (animadaAgora) segundos else 0.5 } else camada.fim)
        }
        val projeto = ProjetoSalvo(
            animada = animadaAgora, quadrosPorSegundo = quadrosPorSegundo, inicio = inicioGuardado, fim = fimGuardado,
            corDeFundo = corDeFundo, corFinalDoFundo = corFinalDoFundo, emojis = emojis,
            principal = CamadaSalva("principal", principal.nome, principal.origem?.tipo ?: TipoDaOrigem.IMAGEM, arquivoPrincipal,
                principal.mascara?.takeIf { principal.mascaraManual }?.let { png(it, "principal-mascara.png") }, principal.edicao),
            camadas = salvas,
        )
        withContext(Dispatchers.IO) {
            Projetos.gravar(temporaria, projeto)
            destino.deleteRecursively()
            check(temporaria.renameTo(destino)) { "Não deu para guardar o projeto da figurinha." }
        }
    }

    // ---- Pacotes ----

    fun adicionarAoWhatsApp(pacote: PacoteDeFigurinhas, whatsapp: String, abrir: (android.content.Intent) -> Unit) {
        trabalho = viewModelScope.launch {
            ocupado = "Preparando o pacote para o WhatsApp…"
            try {
                val atualizado = withContext(Dispatchers.IO) {
                    pacotes.prepararParaWhatsApp(pacotes.ler(pacote.id) ?: throw ErroDeConversao("O pacote não existe mais."))
                }
                WhatsApp.avisarMudanca(contexto)
                recarregar()
                abrir(WhatsApp.intencaoDeAdicionar(contexto, whatsapp, atualizado))
            } catch (erro: Exception) {
                aviso = "Não deu para adicionar o pacote: ${erro.message}"
            } finally {
                ocupado = null
            }
        }
    }

    fun renomear(pacote: PacoteDeFigurinhas, nome: String) {
        pacoteAberto = pacotes.renomear(pacote, nome)
        WhatsApp.avisarMudanca(contexto)
        recarregar()
    }

    fun remover(pacote: PacoteDeFigurinhas, figurinha: Figurinha) {
        pacoteAberto = pacotes.remover(pacote, figurinha)
        WhatsApp.avisarMudanca(contexto)
        recarregar()
    }

    fun excluir(pacote: PacoteDeFigurinhas) {
        pacotes.excluir(pacote)
        pacoteAberto = null
        WhatsApp.avisarMudanca(contexto)
        recarregar()
    }

    override fun onCleared() {
        origem?.copia?.delete()
        camadas.forEach { it.origem?.copia?.delete() }
        trechos.keys.toList().forEach(::esquecerTrecho)
    }

    private companion object {
        const val CONVERTENDO_PARA_A_PREVIA = "Convertendo o trecho para uma versão leve…"
    }
}
