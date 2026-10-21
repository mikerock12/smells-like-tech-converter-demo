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
    private var trabalhoDaPrevia: Job? = null
    private fun selecionada() = camadas.firstOrNull { it.id == camadaSelecionada }
    var edicao: Edicao
        get() = selecionada()?.edicao ?: edicaoPrincipal
        set(valor) {
            if (camadaSelecionada == null) edicaoPrincipal = valor
            else camadas = camadas.map { if (it.id == camadaSelecionada) it.copy(edicao = valor) else it }
        }
    var emojis by mutableStateOf(listOf(Pacotes.EMOJI_PADRAO))

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
    private var versaoDoEditor = 0

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
                val mime = resolvedor.getType(uri).orEmpty()
                val nome = resolvedor.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "arquivo"
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
        corDeFundo = cor
        corFinalDoFundo = final
        atualizarPrevia()
    }

    fun moverCamada(id: String, passo: Int) {
        // Inclui a mídia principal (ordem zero) na ordenação.
        val ordem = (camadas.map { it.id to it.ordem } + ("" to 0)).sortedBy { it.second }.map { it.first }.toMutableList()
        val indice = ordem.indexOf(id)
        if (indice < 0) return
        val alvo = (indice + passo).coerceIn(0, ordem.lastIndex)
        ordem.add(alvo, ordem.removeAt(indice))
        val principal = ordem.indexOf("")
        camadas = camadas.map { it.copy(ordem = ordem.indexOf(it.id) - principal) }
        atualizarPrevia()
    }

    fun ocultarCamada(id: String) {
        camadas = camadas.map { if (it.id == id) it.copy(visivel = !it.visivel) else it }
        atualizarPrevia()
    }

    fun excluirCamada(id: String) {
        val removida = camadas.firstOrNull { it.id == id } ?: return
        camadas = camadas.filterNot { it.id == id }
        removida.origem?.copia?.delete()
        if (camadaSelecionada == id) camadaSelecionada = null
        if (!temAnimacao) animada = false
        atualizarPrevia()
    }

    fun adicionarTexto(texto: String = "Seu texto", emoji: Boolean = false) {
        if (camadas.size >= 12) { aviso = "Use até 12 camadas adicionais por figurinha."; return }
        val camada = CamadaDeFigurinha(nome = if (emoji) "Emoji $texto" else "Texto",
            imagem = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888), texto = true,
            edicao = Edicao(texto = texto, tamanhoDoTexto = if (emoji) 180f else 64f, posicaoDoTexto = PosicaoDoTexto.MEIO,
                corDoContornoDoTexto = if (emoji) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK),
            ordem = (camadas.maxOfOrNull { it.ordem } ?: 0) + 1)
        camadas = camadas + camada
        camadaSelecionada = camada.id
        atualizarPrevia()
    }

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
                val mime = resolvedor.getType(uri).orEmpty()
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
                camadas = camadas + camada
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
    fun pincelar(x: Float, y: Float, raio: Float, apagar: Boolean) {
        val imagem = base ?: return
        val atual = mascara ?: Fundo.mascaraCheia(imagem.width, imagem.height)
        // A prévia pode estar lendo a máscara anterior em outra thread.
        val editavel = atual.copy(Bitmap.Config.ARGB_8888, true)
        val tela = android.graphics.Canvas(editavel)
        val tinta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            if (apagar) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        tela.drawCircle(x * imagem.width, y * imagem.height, raio * maxOf(imagem.width, imagem.height), tinta)
        mascara = editavel
        if (camadaSelecionada == null) mascaraManualPrincipal = true
        else camadas = camadas.map { if (it.id == camadaSelecionada) it.copy(mascaraManual = true) else it }
        if (!edicao.removerFundo) edicao = edicao.copy(removerFundo = true)
        atualizarPrevia()
    }

    /** O pincel a partir da prévia: (x, y) e `raio` em frações da figurinha de 512×512. */
    fun pincelarNaPrevia(x: Float, y: Float, raio: Float, apagar: Boolean) {
        val imagem = base ?: return
        val (ix, iy, ir) = renderizador.pontoNaImagem(x, y, raio, imagem.width, imagem.height, edicao)
        pincelar(ix, iy, ir, apagar)
    }

    fun atualizarPrevia() {
        val lista = composicao()
        if (lista.isEmpty()) return
        trabalhoDaPrevia?.cancel()
        trabalhoDaPrevia = viewModelScope.launch {
            delay(35)
            val desenho = withContext(Dispatchers.Default) { compositor.desenhar(lista, corDeFundo, corFinalDoFundo) }
            ensureActive()
            previa = desenho
        }
    }

    fun mostrarInstante(segundos: Float) {
        tempoDaPrevia = segundos
        trabalhoDaPrevia?.cancel()
        val lista = composicao()
        trabalhoDaPrevia = viewModelScope.launch {
            delay(150)
            val temporarios = mutableListOf<Bitmap>()
            try {
                val quadros = lista.map { camada ->
                    val arquivo = camada.origem?.copia
                    if (arquivo == null) camada else {
                        val quadro = Producao.quadro(contexto, arquivo, (if (camada.id == "principal") inicio else 0.0) + segundos)
                        temporarios += quadro
                        val recorte = if (camada.mascaraManual) camada.mascara else if (camada.edicao.removerFundo) Fundo.mascara(quadro).also { temporarios += it } else null
                        camada.copy(imagem = quadro, mascara = recorte)
                    }
                }
                previa = withContext(Dispatchers.Default) { compositor.desenhar(quadros, corDeFundo, corFinalDoFundo) }
            } catch (erro: CancellationException) { throw erro }
            catch (erro: Exception) { aviso = "Não foi possível mostrar este instante: ${erro.message}" }
            finally { temporarios.forEach { it.recycle() } }
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
                val webp = if (animada && temAnimacao) gerarAnimadaEmCamadas() else gerarEstatica()
                val pacote = destino?.let { pacotes.ler(it.id) } ?: pacotes.criar(nomeDoNovo, animada)
                val atualizado = withContext(Dispatchers.IO) { pacotes.adicionar(pacote, webp, emojis) }
                WhatsApp.avisarMudanca(contexto)
                recarregar()
                pacoteAberto = atualizado
                fecharEditor()
                aviso = if (atualizado.prontoParaWhatsApp) "Figurinha salva em “${atualizado.nome}”."
                else "Figurinha salva. Faltam ${atualizado.faltam} para o WhatsApp aceitar o pacote (mínimo ${Regras.MINIMO})."
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
        val desenho = compositor.desenhar(composicao(), corDeFundo, corFinalDoFundo)
        relatar(0.6, "Compactando para o WhatsApp (até 100 KB)…")
        try { Producao.estatica(desenho) } finally { desenho.recycle() }
    }

    private suspend fun gerarAnimadaEmCamadas(): ByteArray {
        val lista = composicao().filter { it.visivel }
        val duracao = (fim - inicio).coerceIn(0.2, 10.0)
        val taxa = Producao.quadrosPorSegundo(duracao)
        val extraidos = mutableMapOf<String, Producao.Quadros>()
        val pasta = File.createTempFile("composicao-", "", contexto.cacheDir).apply { delete(); mkdirs() }
        try {
            for ((indice, camada) in lista.withIndex()) {
                val arquivo = camada.origem?.copia ?: continue
                val comeco = if (camada.id == "principal") inicio else 0.0
                extraidos[camada.id] = Producao.quadros(contexto, arquivo, comeco, comeco + duracao,
                    transparencia = camada.origem.tipo != TipoDaOrigem.VIDEO) {
                    relatar((indice + it) / lista.size * 0.2, "Preparando as camadas…")
                }
            }
            val crus = Producao.QuadrosCrus(File(pasta, "composicao.argb"), taxa)
            val total = kotlin.math.ceil(duracao * taxa).toInt()
            crus.use {
                repeat(total) { indice ->
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val temporarios = mutableListOf<Bitmap>()
                    try {
                        val quadros = lista.map { camada ->
                            val sequencia = extraidos[camada.id]
                            if (sequencia == null) camada else {
                                val posicao = ((indice.toDouble() / taxa * sequencia.porSegundo).toInt() % sequencia.arquivos.size)
                                val imagem = android.graphics.BitmapFactory.decodeFile(sequencia.arquivos[posicao].absolutePath)
                                    ?: throw ErroDeConversao("Não deu para abrir um quadro da camada ${camada.nome}.")
                                temporarios += imagem
                                val recorte = if (camada.mascaraManual) camada.mascara else if (camada.edicao.removerFundo) Fundo.mascara(imagem).also { temporarios += it } else null
                                camada.copy(imagem = imagem, mascara = recorte)
                            }
                        }
                        withContext(Dispatchers.Default) {
                            val desenho = compositor.desenhar(quadros, corDeFundo, corFinalDoFundo)
                            try { crus.gravar(desenho) } finally { desenho.recycle() }
                        }
                    } finally { temporarios.forEach { it.recycle() } }
                    relatar(0.2 + 0.5 * (indice + 1) / total, "Compondo quadro ${indice + 1} de $total…")
                }
            }
            return Producao.animada(contexto, crus) { relatar(0.7 + it * 0.3, "Compactando para o WhatsApp…") }
        } finally {
            extraidos.values.forEach { it.arquivos.firstOrNull()?.parentFile?.deleteRecursively() }
            pasta.deleteRecursively()
        }
    }

    /**
     * A figurinha animada em três etapas, com peso pelo custo de cada uma: tirar os quadros
     * (12%), desenhar (43%, ou 60% removendo o fundo, que é o mais caro) e compactar (o resto).
     */
    private suspend fun gerarAnimada(atual: Origem): ByteArray {
        val arquivo = atual.copia ?: throw ErroDeConversao("Nada para salvar.")
        val pesoDaExtracao = 0.12
        val pesoDoDesenho = if (edicao.removerFundo) 0.60 else 0.43
        val inicioDaCompactacao = pesoDaExtracao + pesoDoDesenho
        relatar(0.0, "Tirando os quadros do vídeo…")
        val quadros = Producao.quadros(
            contexto, arquivo, inicio, fim.coerceAtLeast(inicio + 0.1),
            transparencia = atual.tipo == TipoDaOrigem.GIF,
        ) { relatar(it * pesoDaExtracao, "Tirando os quadros do vídeo…") }
        val pasta = quadros.arquivos.first().parentFile!!
        try {
            val crus = Producao.QuadrosCrus(File(pasta, "desenhados.argb"), quadros.porSegundo)
            crus.use {
                val total = quadros.arquivos.size
                Producao.desenharTodos(quadros, crus, desenho = { imagem ->
                    // Remoção de fundo quadro a quadro: a figura se move no vídeo.
                    val mascaraDoQuadro = if (edicao.removerFundo) runCatching { Fundo.mascara(imagem) }.getOrNull() else null
                    try { renderizador.desenhar(imagem, mascaraDoQuadro, edicao) } finally { mascaraDoQuadro?.recycle() }
                }) { feitos ->
                    val etapa = if (edicao.removerFundo) "Removendo o fundo e desenhando ($feitos de $total)…" else "Desenhando os quadros ($feitos de $total)…"
                    relatar(pesoDaExtracao + pesoDoDesenho * feitos / total, etapa)
                }
            }
            return Producao.animada(contexto, crus) { fracao ->
                relatar(inicioDaCompactacao + (1 - inicioDaCompactacao) * fracao, "Compactando para o WhatsApp (até 500 KB)…")
            }
        } finally {
            pasta.deleteRecursively()
        }
    }

    fun cancelar() {
        restante = null
        trabalho?.cancel()
        trabalhoDaBase?.cancel()
        ocupado = null
        progresso = null
    }

    fun fecharEditor() {
        limparEditor()
        origem?.copia?.delete()
        origem = null
    }

    private fun limparEditor() {
        versaoDoEditor++
        trabalhoDaBase?.cancel()
        trabalhoDaPrevia?.cancel()
        camadas.forEach { it.origem?.copia?.delete() }
        camadas = emptyList()
        camadaSelecionada = null
        corDeFundo = android.graphics.Color.TRANSPARENT
        corFinalDoFundo = null
        tempoDaPrevia = 0f
        base = null
        mascara = null
        mascaraManualPrincipal = false
        previa = null
        edicao = Edicao()
        emojis = listOf(Pacotes.EMOJI_PADRAO)
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
    }
}
