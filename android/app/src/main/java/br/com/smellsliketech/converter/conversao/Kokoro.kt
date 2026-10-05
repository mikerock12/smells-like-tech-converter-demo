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
        val destino = File(context.filesDir, "kokoro-82m-v1-fp32-ptbr")
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
                "model.onnx" to "b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11",
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
            pronto.writeText("Kokoro-82M v1 fp32; lang=pt-br; Sherpa 1.13.8")
        }
        check(File(destino, "model.onnx").length() == 325560556L && File(destino, "voices.bin").length() == 28200960L)
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
                    model = File(pasta, "model.onnx").absolutePath,
                    voices = File(pasta, "voices.bin").absolutePath,
                    tokens = File(pasta, "tokens.txt").absolutePath,
                    dataDir = File(pasta, "espeak-ng-data").absolutePath,
                    lang = "pt-br",
                ), numThreads = 2, provider = "cpu",
            ), maxNumSentences = 1))
            try {
                PcmWav(wav, 24000).use { destino ->
                    fun gerar(parte: String, tentativa: Int): List<FloatArray> {
                        trabalho.ensureActive()
                        val audio = tts.generateWithCallback(parte, sid,
                            opcoes.velocidadeDaFala.coerceIn(0.5f, 2f), KokoroCallback(trabalho))
                        trabalho.ensureActive()
                        if (audio.sampleRate == 24000 && TextoDaNarracao.amostrasValidas(audio.samples))
                            return listOf(audio.samples)
                        val menores = if (tentativa < 3) TextoDaNarracao.partir(parte) else emptyList()
                        if (menores.isEmpty()) throw ErroDeConversao("Não foi possível narrar um trecho do texto, mesmo após dividi-lo. Confira o texto reconhecido ou escolha outra voz.")
                        return menores.flatMap { gerar(it, tentativa + 1) }
                    }
                    val normalizado = TextoDaNarracao.normalizar(texto)
                    if (normalizado.isBlank()) throw ErroDeConversao("Não há texto legível para narrar.")
                    val partes = Wav.dividirTexto(normalizado, 200)
                    partes.forEachIndexed { indice, parte ->
                        trabalho.ensureActive()
                        for (samples in gerar(parte, 0)) destino.adicionar(samples)
                        progresso(10 + (indice + 1) * 75 / partes.size)
                    }

                }
            } finally { tts.release() }
        }
    }
}
