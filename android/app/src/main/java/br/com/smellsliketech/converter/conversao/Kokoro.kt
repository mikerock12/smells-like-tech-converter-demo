package br.com.smellsliketech.converter.conversao

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/** JNI procura invoke(float[]) por nome; não usar uma lambda otimizada pelo R8. */
class KokoroCallback(private val trabalho: CoroutineContext) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (trabalho.isActive) 1 else 0
}

/** As mesmas vozes, modelo e idioma do site e do Windows. Sem serviço de rede. */
object Kokoro {
    val vozes = linkedMapOf("pf_dora" to "Dora", "pm_alex" to "Alex", "pm_santa" to "Santa")
    private val fila = Mutex()
    fun locutor(voz: String): Int = when (voz) {
        "pf_dora" -> 42; "pm_alex" -> 43; "pm_santa" -> 44
        else -> throw ErroDeConversao("Escolha Dora, Alex ou Santa do Kokoro-82M.")
    }

    private fun preparar(context: Context): File {
        val destino = File(context.filesDir, "kokoro-82m-v1-int8")
        val pronto = File(destino, ".pronto-1")
        if (!pronto.exists()) {
            fun copiar(origem: String, saida: File) {
                val filhos = context.assets.list(origem).orEmpty()
                if (filhos.isNotEmpty()) {
                    check(saida.mkdirs() || saida.isDirectory)
                    filhos.forEach { copiar("$origem/$it", File(saida, it)) }
                } else {
                    context.assets.open(origem).use { input -> saida.outputStream().use { input.copyTo(it) } }
                }
            }
            copiar("kokoro", destino)
            val hashes = mapOf(
                "model.int8.onnx" to "4b86207ef680e394d8343bee22dfc4c512e5c707c6d9578e3f35ab09bffd6b36",
                "voices.bin" to "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29",
            )
            hashes.forEach { (nome, esperado) ->
                val digest = MessageDigest.getInstance("SHA-256")
                File(destino, nome).inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val lidos = input.read(buffer); if (lidos < 0) break; digest.update(buffer, 0, lidos) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == esperado) { "Pacote Kokoro danificado. Reinstale o app." }
            }
            pronto.writeText("Kokoro-82M v1 int8; lang=pt; Sherpa 1.13.8")
        }
        check(File(destino, "model.int8.onnx").length() == 114203756L && File(destino, "voices.bin").length() == 28200960L)
        return destino
    }

    suspend fun gravar(context: Context, texto: String, opcoes: Opcoes, wav: File, progresso: (Int) -> Unit) = fila.withLock {
        val sid = locutor(opcoes.vozDaNarracao)
        require(opcoes.velocidadeDaFala.isFinite()) { "Velocidade de fala inválida." }
        val pasta = withContext(Dispatchers.IO) { preparar(context) }
        progresso(10)
        withContext(Dispatchers.Default) {
            val trabalho = currentCoroutineContext()
            trabalho.ensureActive()
            val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(pasta, "model.int8.onnx").absolutePath,
                    voices = File(pasta, "voices.bin").absolutePath,
                    tokens = File(pasta, "tokens.txt").absolutePath,
                    dataDir = File(pasta, "espeak-ng-data").absolutePath,
                    lang = "pt",
                ), numThreads = 2, provider = "cpu",
            ), maxNumSentences = 1))
            try {
                PcmWav(wav, 24000).use { destino ->
                    val partes = Wav.dividirTexto(texto, 200)
                    partes.forEachIndexed { indice, parte ->
                        trabalho.ensureActive()
                        val audio = tts.generateWithCallback(parte, sid,
                            opcoes.velocidadeDaFala.coerceIn(0.5f, 2f), KokoroCallback(trabalho))
                        trabalho.ensureActive()
                        check(audio.sampleRate == 24000 && audio.samples.isNotEmpty()) { "Kokoro não gerou áudio válido." }
                        check(audio.samples.all { it.isFinite() } && audio.samples.any { kotlin.math.abs(it) > 0.00001f }) {
                            "Kokoro produziu áudio inválido ou silencioso. Tente novamente."
                        }
                        destino.adicionar(audio.samples)
                        progresso(10 + (indice + 1) * 75 / partes.size)
                    }
                }
            } finally { tts.release() }
        }
    }
}
