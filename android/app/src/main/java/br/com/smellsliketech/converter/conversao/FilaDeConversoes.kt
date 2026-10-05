package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Estado compartilhado pela tela e pelo serviço, não pelo ciclo de vida de um ViewModel. */
class Trabalho(val id: String, val titulo: String, val arquivos: List<String>) {
    var progresso by mutableStateOf(0)
    var restante by mutableStateOf<Int?>(null)
    var etapa by mutableStateOf("Na fila…")
    internal var andamento = Andamento()
    var resultados by mutableStateOf<List<Resultado>>(emptyList())
    var transcricoes by mutableStateOf<List<Transcricao>>(emptyList())
    var salvando by mutableStateOf(false)
    var erro by mutableStateOf<String?>(null)
    var terminado by mutableStateOf(false)
    internal var tarefa: Job? = null
    internal var midia = false
}

class FilaDeConversoes private constructor(private val contexto: Context) {
    val trabalhos = mutableStateListOf<Trabalho>()
    private val pendentes = linkedMapOf<Trabalho, suspend () -> List<Resultado>>()
    private val vez = Mutex() // Não duplicar o modelo/RAM/CPU de narrações longas.
    internal var escopo: CoroutineScope? = null
    internal var aoMudar: (() -> Unit)? = null

    fun enfileirar(trabalho: Trabalho, midia: Boolean = true, uris: List<Uri> = emptyList(), fazer: suspend () -> List<Resultado>) {
        trabalho.midia = midia
        trabalhos.add(0, trabalho)
        pendentes[trabalho] = fazer
        try {
            // Ação do usuário com Activity visível. Notificar antes de copiar/OCR/carregar.
            val pedido = Intent(contexto, ServicoDeConversao::class.java)
            val documentos = uris.filter { it.scheme == "content" }
            if (documentos.isNotEmpty()) {
                pedido.clipData = ClipData.newRawUri("Arquivos da conversão", documentos.first()).apply {
                    documentos.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                pedido.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(contexto, pedido)
        } catch (erro: Exception) {
            pendentes.remove(trabalho)
            terminar(trabalho, "Não foi possível iniciar a conversão em segundo plano: ${erro.message}")
        }
    }

    internal fun iniciarPendentes(escopoDoServico: CoroutineScope) {
        escopo = escopoDoServico
        val pedidos = pendentes.toMap()
        pendentes.clear()
        for ((trabalho, fazer) in pedidos) {
            trabalho.tarefa = escopoDoServico.launch {
                try {
                    vez.withLock {
                        trabalho.etapa = "Preparando…"
                        trabalho.andamento = Andamento()
                        aoMudar?.invoke()
                        trabalho.resultados = fazer()
                    }
                    trabalho.progresso = 100
                } catch (erro: CancellationException) {
                    trabalho.erro = trabalho.erro ?: "Cancelada."
                } catch (erro: SecurityException) {
                    trabalho.erro = "O Android não deu acesso ao arquivo. Compartilhe de novo, ou escolha-o em Escolher arquivos."
                } catch (erro: OutOfMemoryError) {
                    trabalho.erro = "O arquivo é grande demais para a memória deste celular."
                } catch (erro: Exception) {
                    trabalho.erro = erro.message ?: "A conversão falhou."
                } finally {
                    terminar(trabalho, trabalho.erro)
                }
            }
        }
    }

    private fun terminar(trabalho: Trabalho, erro: String?) {
        trabalho.erro = erro
        trabalho.restante = null
        trabalho.terminado = true
        trabalho.tarefa = null
        aoMudar?.invoke()
    }

    fun cancelar(trabalho: Trabalho) {
        if (pendentes.remove(trabalho) != null) terminar(trabalho, "Cancelada.")
        else trabalho.tarefa?.cancel()
    }

    internal fun interromper(mensagem: String) {
        android.util.Log.w("FilaDeConversoes", mensagem)
        trabalhos.filterNot { it.terminado }.forEach {
            it.erro = mensagem
            cancelar(it)
        }
    }

    fun avancar(trabalho: Trabalho, parcial: Int, estimar: Boolean = true) {
        escopo?.launch(Dispatchers.Main.immediate) {
            if (trabalho.terminado) return@launch
            trabalho.progresso = maxOf(trabalho.progresso, parcial.coerceIn(0, 99))
            if (estimar) trabalho.restante = trabalho.andamento.registrar(trabalho.progresso)
            aoMudar?.invoke()
        }
    }

    fun estimar(trabalho: Trabalho, restante: Int?, etapa: String? = null) {
        escopo?.launch(Dispatchers.Main.immediate) {
            if (trabalho.terminado) return@launch
            trabalho.restante = restante // Null apaga a estimativa da etapa anterior.
            etapa?.let { trabalho.etapa = it }
            aoMudar?.invoke()
        }
    }

    companion object {
        // Guarda exclusivamente applicationContext, nunca uma Activity/Service.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instancia: FilaDeConversoes? = null
        fun obter(contexto: Context): FilaDeConversoes = instancia ?: synchronized(this) {
            instancia ?: FilaDeConversoes(contexto.applicationContext).also { instancia = it }
        }
    }
}
