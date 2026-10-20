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
    var edicao by mutableStateOf(Edicao())
    var emojis by mutableStateOf(listOf(Pacotes.EMOJI_PADRAO))

    /** A imagem base do editor (a imagem, ou o quadro de vídeo escolhido) e a máscara do fundo. */
    var base by mutableStateOf<Bitmap?>(null)
        private set
    var mascara by mutableStateOf<Bitmap?>(null)
        private set
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
            if (base != null) delay(300)
            ocupado = "Preparando a imagem…"
            try {
                val imagem = when (atual.tipo) {
                    TipoDaOrigem.IMAGEM -> withContext(Dispatchers.IO) { decodificar(atual.uri) }
                    else -> Producao.quadro(contexto, atual.copia!!, inicio)
                }
                base = imagem
                mascara = null
                if (edicao.removerFundo) calcularMascara()
                atualizarPrevia()
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

    fun mudar(nova: Edicao) {
        val ligouRemocao = nova.removerFundo && !edicao.removerFundo
        edicao = nova
        if (ligouRemocao && mascara == null) {
            viewModelScope.launch {
                ocupado = "Removendo o fundo…"
                try { calcularMascara() } finally { ocupado = null }
                atualizarPrevia()
            }
        } else {
            atualizarPrevia()
        }
    }

    private suspend fun calcularMascara() {
        val imagem = base ?: return
        try {
            mascara = withContext(Dispatchers.Default) { Fundo.mascara(imagem) }
        } catch (erro: Exception) {
            aviso = erro.message
            edicao = edicao.copy(removerFundo = false)
        }
    }

    /**
     * O pincel: apaga (ou devolve) a figura em volta do ponto tocado. `x` e `y` são frações
     * (0..1) da imagem base; `raio` também, em relação ao lado maior.
     */
    fun pincelar(x: Float, y: Float, raio: Float, apagar: Boolean) {
        val imagem = base ?: return
        val atual = mascara ?: Fundo.mascaraCheia(imagem.width, imagem.height)
        val editavel = if (atual.isMutable) atual else atual.copy(Bitmap.Config.ARGB_8888, true)
        val tela = android.graphics.Canvas(editavel)
        val tinta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            if (apagar) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        tela.drawCircle(x * imagem.width, y * imagem.height, raio * maxOf(imagem.width, imagem.height), tinta)
        mascara = editavel
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
        val imagem = base ?: return
        val estaEdicao = edicao
        val estaMascara = mascara
        viewModelScope.launch {
            previa = withContext(Dispatchers.Default) { renderizador.desenhar(imagem, estaMascara, estaEdicao) }
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
                val webp = if (animada && atual.tipo != TipoDaOrigem.IMAGEM) gerarAnimada(atual) else gerarEstatica()
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
        val imagem = base ?: throw ErroDeConversao("Nada para salvar.")
        relatar(0.2, "Desenhando a figurinha…")
        val desenho = renderizador.desenhar(imagem, mascara, edicao)
        relatar(0.6, "Compactando para o WhatsApp (até 100 KB)…")
        Producao.estatica(desenho)
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
        base = null
        mascara = null
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
    }
}
