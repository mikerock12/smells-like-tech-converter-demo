package br.com.smellsliketech.converter.conversao.ffmpeg

import android.content.Context
import br.com.smellsliketech.converter.conversao.Entrada
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * O FFmpeg do app: um executável compilado por android/nativos/compilar.sh e empacotado como
 * `libffmpeg.so`, que o Android extrai (com permissão de execução) para a pasta das
 * bibliotecas nativas. Roda como um processo à parte: um travamento dele não derruba o app.
 */
object Ffmpeg {
    private fun executavel(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    fun disponivel(context: Context): Boolean = executavel(context).canExecute()

    /**
     * Roda o FFmpeg com `argumentos` e acompanha o `-progress pipe:1`. Cancelar a coroutine
     * mata o processo. `duracao` (segundos) transforma o tempo processado em porcentagem.
     */
    suspend fun executar(
        context: Context,
        argumentos: List<String>,
        duracao: Double?,
        aoLerLog: ((String) -> Unit)? = null,
        aoAvancar: (Int) -> Unit = {},
    ) {
        val programa = executavel(context)
        if (!programa.canExecute()) throw ErroDeConversao("O motor de vídeo e áudio não foi instalado com o app. Reinstale pela Google Play.")
        withContext(Dispatchers.IO) {
            val processo = ProcessBuilder(listOf(programa.absolutePath) + argumentos)
                .directory(context.cacheDir)
                .start()
            val erros = StringBuilder()
            val leitorDeErros = Thread {
                processo.errorStream.bufferedReader().useLines { linhas ->
                    linhas.forEach { linha ->
                        aoLerLog?.invoke(linha)
                        synchronized(erros) {
                            erros.appendLine(linha)
                            if (erros.length > 16_000) erros.delete(0, erros.length - 8_000)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                runInterruptible {
                    processo.inputStream.bufferedReader().useLines { linhas ->
                        linhas.forEach { linha ->
                            if (duracao != null && duracao > 0 && linha.startsWith("out_time_us=")) {
                                linha.substringAfter('=').toLongOrNull()?.let { microssegundos ->
                                    aoAvancar(((microssegundos / 1_000_000.0) / duracao * 100).roundToInt().coerceIn(0, 99))
                                }
                            }
                        }
                    }
                    processo.waitFor()
                }
            } finally {
                if (processo.isAlive) processo.destroyForcibly()
            }
            leitorDeErros.join(2_000)
            currentCoroutineContext().ensureActive()
            if (processo.exitValue() != 0) {
                throw ErroDeConversao(traduzir(motivo(synchronized(erros) { erros.toString() })))
            }
        }
    }

    /**
     * Um FFmpeg cuja saída padrão é lida por quem chamou (os quadros para o codificador do
     * celular). O stderr é guardado para explicar um erro; [fim] espera e confere a saída.
     */
    class Processo internal constructor(private val processo: Process) {
        private val erros = StringBuilder()
        private val leitor = Thread {
            processo.errorStream.bufferedReader().useLines { linhas ->
                linhas.forEach { linha -> synchronized(erros) { erros.appendLine(linha); if (erros.length > 16_000) erros.delete(0, erros.length - 8_000) } }
            }
        }.apply { isDaemon = true; start() }

        val saida: java.io.InputStream get() = processo.inputStream

        fun matar() { if (processo.isAlive) processo.destroyForcibly() }

        /** Espera o FFmpeg terminar; erro se ele saiu mal. */
        fun fim() {
            processo.waitFor()
            leitor.join(2_000)
            if (processo.exitValue() != 0) {
                throw ErroDeConversao(traduzir(motivo(synchronized(erros) { erros.toString() })))
            }
        }
    }

    fun iniciar(context: Context, argumentos: List<String>): Processo {
        val programa = executavel(context)
        if (!programa.canExecute()) throw ErroDeConversao("O motor de vídeo e áudio não foi instalado com o app. Reinstale pela Google Play.")
        return Processo(ProcessBuilder(listOf(programa.absolutePath) + argumentos).directory(context.cacheDir).start())
    }

    /** O que o FFmpeg sabe do arquivo: duração, trilhas e tamanho da imagem. */
    suspend fun sondar(context: Context, arquivo: File): Sondagem = withContext(Dispatchers.IO) {
        val programa = executavel(context)
        if (!programa.canExecute()) return@withContext Sondagem()
        // Sem saída, o FFmpeg sai com erro depois de descrever a entrada; é o que se quer.
        val processo = ProcessBuilder(programa.absolutePath, "-hide_banner", "-nostdin", "-i", arquivo.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            val texto = runInterruptible { processo.inputStream.bufferedReader().readText().also { processo.waitFor() } }
            Sondagem.ler(texto)
        } finally {
            if (processo.isAlive) processo.destroyForcibly()
        }
    }

    /**
     * O FFmpeg só lê caminhos de arquivo; o que o Android entrega é um content://. A entrada
     * é copiada para o cache do app e apagada no fim (ver [comCopia]).
     */
    suspend fun copiar(context: Context, entrada: Entrada): File = withContext(Dispatchers.IO) {
        val extensao = entrada.extensao.filter(Char::isLetterOrDigit).take(8).ifBlank { "bin" }
        val destino = File.createTempFile("entrada-", ".$extensao", context.cacheDir)
        try {
            val fonte = context.contentResolver.openInputStream(entrada.uri) ?: throw ErroDeConversao("Não deu para ler ${entrada.nome}.")
            fonte.use { entradaDeDados -> destino.outputStream().use { entradaDeDados.copyTo(it, 1 shl 16) } }
        } catch (erro: Throwable) {
            destino.delete()
            throw erro
        }
        destino
    }

    suspend fun <T> comCopia(context: Context, entrada: Entrada, fazer: suspend (File) -> T): T {
        val copia = copiar(context, entrada)
        try {
            return fazer(copia)
        } finally {
            copia.delete()
        }
    }

    /** A linha que explica a falha: a última que fala de erro (com "-v info" há outras depois), ou a última. */
    private fun motivo(log: String): String? {
        val linhas = log.lines().map(String::trim).filter(String::isNotEmpty)
        val palavras = listOf("error", "invalid", "failed", "could not", "no such", "not found", "unable")
        return linhas.lastOrNull { linha -> palavras.any { it in linha.lowercase() } } ?: linhas.lastOrNull()
    }

    private fun traduzir(mensagem: String?): String {
        val texto = mensagem.orEmpty()
        return when {
            texto.isEmpty() -> "O FFmpeg parou sem explicar o motivo."
            "Invalid data found" in texto -> "O arquivo está corrompido ou não é de um formato conhecido."
            "does not contain any stream" in texto || "matches no streams" in texto -> "O arquivo não tem a trilha pedida (por exemplo, um vídeo sem áudio)."
            "No space left" in texto -> "Não há espaço livre no celular."
            "whisper" in texto.lowercase() && "model" in texto.lowercase() -> "O modelo do Whisper não abriu. Apague-o e baixe de novo."
            else -> "O FFmpeg recusou a conversão: $texto"
        }
    }
}
