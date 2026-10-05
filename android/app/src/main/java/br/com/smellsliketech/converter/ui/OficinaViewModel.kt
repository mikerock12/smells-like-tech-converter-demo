package br.com.smellsliketech.converter.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.smellsliketech.converter.conversao.FilaDeConversoes
import br.com.smellsliketech.converter.conversao.Trabalho
import br.com.smellsliketech.converter.conversao.Entrada
import br.com.smellsliketech.converter.conversao.Midia
import br.com.smellsliketech.converter.conversao.Ferramenta
import br.com.smellsliketech.converter.conversao.ModeloWhisper
import br.com.smellsliketech.converter.conversao.ModelosWhisper
import br.com.smellsliketech.converter.conversao.Motor
import br.com.smellsliketech.converter.conversao.Narracao
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Resultado
import br.com.smellsliketech.converter.conversao.Tipo
import br.com.smellsliketech.converter.conversao.Transcricao
import br.com.smellsliketech.converter.licenca.Acesso
import br.com.smellsliketech.converter.loja.Loja
import br.com.smellsliketech.converter.loja.novaLoja
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.UUID

class OficinaViewModel(aplicacao: Application) : AndroidViewModel(aplicacao) {
    private val contexto get() = getApplication<Application>()
    private val acesso = Acesso(aplicacao)
    private val fila = FilaDeConversoes.obter(aplicacao)

    var situacao by mutableStateOf(acesso.situacao())
        private set

    /** O preço que a loja mostra (só a Google Play sabe) e o último recado dela. */
    var precoDaLoja by mutableStateOf<String?>(null)
        private set
    var recadoDaLoja by mutableStateOf<String?>(null)

    private val loja = novaLoja(aplicacao, object : Loja.Ouvinte {
        override fun comprou(json: String, assinatura: String): Boolean {
            val jaTinha = situacao is Acesso.Situacao.Comprada
            val recibo = acesso.guardarCompra(json, assinatura)
            atualizarSituacao()
            if (recibo == null) {
                recadoDaLoja = "A Google Play mandou um recibo que não confere. A compra não foi liberada; fale com converter@smellsliketech.com.br."
                return false
            }
            if (!jaTinha) recadoDaLoja = "Compra confirmada. O app está liberado para sempre, também nos outros celulares com a mesma conta Google."
            return true
        }

        override fun pendente() {
            recadoDaLoja = "O pagamento ainda não caiu. O app libera sozinho quando a Google Play confirmar."
        }

        override fun semCompra() {
            if (situacao is Acesso.Situacao.Comprada) {
                acesso.esquecerCompra()
                atualizarSituacao()
            }
        }

        override fun falhou(mensagem: String) {
            recadoDaLoja = mensagem
        }
    })

    init {
        viewModelScope.launch {
            loja.sincronizar()
            precoDaLoja = loja.preco()
        }
    }

    fun comprar(atividade: Activity) {
        recadoDaLoja = null
        loja.comprar(atividade)
    }

    /** "Restaurar compra": pergunta de novo à loja, por exemplo depois de trocar de celular. */
    fun restaurar() {
        viewModelScope.launch {
            loja.sincronizar()
            if (situacao !is Acesso.Situacao.Comprada && recadoDaLoja == null) {
                recadoDaLoja = "A Google Play não achou a compra nesta conta. Confira se a Play Store está com a conta usada na compra."
            }
        }
    }

    override fun onCleared() {
        loja.encerrar()
    }
    val entradas = mutableStateListOf<Entrada>()
    val trabalhos = fila.trabalhos
    val ferramentaDoTipo = mutableStateMapOf<Tipo, Ferramenta>()
    val opcoesDaFerramenta = mutableStateMapOf<Ferramenta, Opcoes>()
    var aviso by mutableStateOf<String?>(null)
    var mostrarLicenca by mutableStateOf(false)
    var mostrarModelos by mutableStateOf(false)
    var textoParaNarrar by mutableStateOf<String?>(null)
    var opcoesDaNarracao by mutableStateOf(Ferramenta.TEXTO_NARRAR.opcoesPadrao())

    /** Os modelos do Whisper no celular; a tela dos modelos atualiza enquanto está aberta. */
    val estadoDosModelos = mutableStateMapOf<ModeloWhisper, ModelosWhisper.Estado>()

    // Depois do mapa acima: um download que terminou com o app fechado é conferido ao abrir.
    init {
        viewModelScope.launch { atualizarModelos() }
    }

    fun atualizarSituacao() {
        situacao = acesso.situacao()
        // Um modelo que terminou de baixar com o app em segundo plano é conferido e posto no lugar aqui.
        viewModelScope.launch { atualizarModelos() }
    }

    /**
     * `mimeDoRemetente`: o tipo que o app que compartilhou declarou. Vale quando o arquivo
     * chega sem nome nem tipo (alguns apps só mandam um content:// numerado).
     */
    fun adicionar(uris: List<Uri>, mimeDoRemetente: String? = null) {
        val recusados = mutableListOf<String>()
        for (uri in uris) {
            val mime = runCatching { contexto.contentResolver.getType(uri) }.getOrNull()
                ?: mimeDoRemetente?.takeUnless { it.endsWith("/*") || it == "application/octet-stream" }
            val (nomeInformado, bytes) = descrever(uri)
            // Sem extensão, o nome ganha a do tipo: é por ela que o app reconhece documentos.
            val nome = if ('.' in nomeInformado || mime == null) nomeInformado
            else MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { "$nomeInformado.$it" } ?: nomeInformado
            val tipo = Tipo.de(mime, nome)
            if (tipo == null) recusados += nome else if (entradas.none { it.uri == uri }) entradas += Entrada(uri, nome, tipo, bytes)
        }
        aviso = when (recusados.size) {
            0 -> null
            1 -> "“${recusados.first()}” não é de um tipo que o app converte."
            else -> "${recusados.size} arquivos não são de um tipo que o app converte."
        }
    }

    private fun descrever(uri: Uri): Pair<String, Long> {
        runCatching {
            contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nome = cursor.getString(0) ?: uri.lastPathSegment ?: "arquivo"
                    val bytes = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                    return nome to bytes
                }
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "arquivo") to 0L
    }

    fun remover(entrada: Entrada) {
        entradas.remove(entrada)
    }

    fun ferramentaDe(tipo: Tipo): Ferramenta {
        val disponiveis = Ferramenta.para(tipo, entradas.filter { it.tipo == tipo }.map { it.extensao })
        return ferramentaDoTipo[tipo]?.takeIf { it in disponiveis } ?: disponiveis.first()
    }

    /** As escolhas de cada ferramenta ficam guardadas enquanto o app está aberto. */
    fun opcoesDe(ferramenta: Ferramenta): Opcoes = opcoesDaFerramenta[ferramenta] ?: ferramenta.opcoesPadrao().let { padrao ->
        // A transcrição abre com o melhor modelo que já está no celular.
        if (!ferramenta.transcreve) padrao
        else padrao.copy(modelo = modelosInstalados().let { instalados -> instalados.firstOrNull { it.id == padrao.modelo } ?: instalados.lastOrNull() }?.id ?: padrao.modelo)
    }

    fun modelosInstalados(): List<ModeloWhisper> = ModeloWhisper.entries.filter { ModelosWhisper.instalado(contexto, it) }

    suspend fun atualizarModelos() {
        for (modelo in ModeloWhisper.entries) estadoDosModelos[modelo] = ModelosWhisper.estado(contexto, modelo)
    }

    fun baixarModelo(modelo: ModeloWhisper) {
        runCatching { ModelosWhisper.baixar(contexto, modelo) }
            .onFailure { estadoDosModelos[modelo] = ModelosWhisper.Estado.Falhou("O Android não deixou baixar: ${it.message}") }
        viewModelScope.launch { atualizarModelos() }
    }

    fun cancelarModelo(modelo: ModeloWhisper) {
        ModelosWhisper.cancelar(contexto, modelo)
        viewModelScope.launch { atualizarModelos() }
    }

    fun apagarModelo(modelo: ModeloWhisper) {
        ModelosWhisper.apagar(contexto, modelo)
        opcoesDaFerramenta.keys.filter { it.transcreve }.forEach { opcoesDaFerramenta.remove(it) }
        viewModelScope.launch { atualizarModelos() }
    }

    fun cancelar(trabalho: Trabalho) {
        fila.cancelar(trabalho)
    }

    /** Converte todos os arquivos de um tipo com a ferramenta escolhida para ele. */
    fun converter(tipo: Tipo) {
        atualizarSituacao()
        if (!situacao.podeConverter) {
            mostrarLicenca = true
            return
        }
        val grupo = entradas.filter { it.tipo == tipo }
        if (grupo.isEmpty()) return
        val ferramenta = ferramentaDe(tipo)
        val opcoes = opcoesDe(ferramenta)
        if (ferramenta.transcreve && modelosInstalados().none { it.id == opcoes.modelo }) {
            aviso = "Para transcrever, baixe um modelo do Whisper primeiro (uma vez só; depois funciona sem internet)."
            mostrarModelos = true
            return
        }
        val trabalho = Trabalho(UUID.randomUUID().toString(), ferramenta.titulo, grupo.map { it.nome })
        entradas.removeAll(grupo)
        if (ferramenta.transcreve) {
            fila.enfileirar(trabalho, uris = grupo.map { it.uri }) {
                trabalho.transcricoes = Motor.transcrever(
                    contexto, grupo, opcoes,
                    aoAvancar = { parcial -> avancar(trabalho, parcial, estimar = grupo.size > 1) },
                    aoEstimar = { restante -> fila.estimar(trabalho, restante, "Transcrevendo…") },
                )
                emptyList()
            }
            return
        }
        fila.enfileirar(trabalho, midia = ferramenta.narra || tipo in setOf(Tipo.AUDIO, Tipo.VIDEO, Tipo.IMAGEM), uris = grupo.map { it.uri }) {
            Motor.executar(contexto, ferramenta, grupo, opcoes,
                aoEstimarNarracao = { restante, etapa -> fila.estimar(trabalho, restante, etapa) },
            ) { parcial -> avancar(trabalho, parcial, estimar = !ferramenta.narra) }
        }
    }

    /** A porcentagem e, a partir dela, o tempo que falta (quem sabe estimar melhor manda em aoEstimar). */
    private fun avancar(trabalho: Trabalho, parcial: Int, estimar: Boolean = true) {
        fila.avancar(trabalho, parcial, estimar)
    }

    /** Salva uma transcrição no formato escolhido; o arquivo aparece na lista do trabalho. */
    fun salvarTranscricao(trabalho: Trabalho, transcricao: Transcricao, formato: String) {
        if (trabalho.salvando) return
        trabalho.salvando = true
        viewModelScope.launch {
            try {
                val resultado = Midia.salvar(contexto, transcricao, formato)
                trabalho.resultados = trabalho.resultados.filterNot { it.nome == resultado.nome } + resultado
            } catch (erro: Exception) {
                aviso = erro.message ?: "Não deu para salvar a transcrição."
            } finally {
                trabalho.salvando = false
            }
        }
    }

    /** O arquivo leva as primeiras palavras do texto: "bom-dia-este-texto-vira-narracao.mp3". */
    private fun nomeDaNarracao(texto: String): String =
        Normalizer.normalize(texto.trim().split(Regex("\\s+")).take(5).joinToString("-"), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Za-z0-9-]"), "")
            .lowercase()
            .trim('-')
            .take(40)
            .ifBlank { "texto" }

    fun narrar(texto: String) {
        textoParaNarrar = null
        atualizarSituacao()
        if (!situacao.podeConverter) {
            mostrarLicenca = true
            return
        }
        val trabalho = Trabalho(UUID.randomUUID().toString(), "Narração", listOf(texto.take(60) + if (texto.length > 60) "…" else ""))
        val opcoes = opcoesDaNarracao
        fila.enfileirar(trabalho) {
            listOf(
                Narracao.narrar(contexto, texto, nomeDaNarracao(texto), opcoes,
                    aoEstimar = { restante, etapa -> fila.estimar(trabalho, restante, etapa) },
                ) { parcial -> avancar(trabalho, parcial, estimar = false) },
            )
        }
    }

    fun limparTerminados() {
        trabalhos.removeAll { it.terminado }
    }

    suspend fun ativar(chave: String): Acesso.Ativacao = withContext(Dispatchers.Default) { acesso.ativar(chave) }
        .also { atualizarSituacao() }

    fun removerLicenca() {
        acesso.remover()
        atualizarSituacao()
    }
}
