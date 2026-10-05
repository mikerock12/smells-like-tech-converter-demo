package br.com.smellsliketech.converter.conversao

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Os modelos de transcrição (whisper.cpp, formato ggml), baixados do repositório oficial no
 * Hugging Face quando a pessoa pede. Depois do download, a transcrição é local: o áudio
 * nunca sai do celular. Cada arquivo é conferido pelo SHA-256 antes de ser usado. Medium e Large vêm
 * comprimidos (q5_0): cabem no celular com pouca perda.
 */
enum class ModeloWhisper(
    val id: String,
    val rotulo: String,
    val arquivo: String,
    val bytes: Long,
    val sha256: String,
    val nota: String,
    /** Segundos de processamento por segundo de áudio num celular médio, até o app medir o deste. */
    val ritmoPadrao: Double,
) {
    TINY("tiny", "Tiny", "ggml-tiny.bin", 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", "O mais rápido. Erra mais.", 0.15),
    BASE("base", "Base", "ggml-base.bin", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "Rápido, para áudio limpo.", 0.3),
    SMALL("small", "Small", "ggml-small.bin", 487_601_967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", "Recomendado: bom em português, sem pesar.", 0.8),
    MEDIUM("medium", "Medium", "ggml-medium-q5_0.bin", 539_212_467, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f", "Mais preciso. Lento em celular simples.", 2.0),
    LARGE_TURBO("large-v3-turbo", "Large v3 Turbo", "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", "Quase o Large, bem mais rápido.", 1.5),
    LARGE("large-v3", "Large v3", "ggml-large-v3-q5_0.bin", 1_081_140_203, "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1", "O mais preciso. Pede celular potente e paciência.", 4.0);

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$arquivo"

    /** Segundos para carregar o modelo na memória (lido do armazenamento a uns 200 MB/s). */
    val cargaSegundos: Double get() = 1.0 + bytes / 200_000_000.0

    companion object {
        fun de(id: String): ModeloWhisper? = entries.firstOrNull { it.id == id }
    }
}

object ModelosWhisper {
    sealed interface Estado {
        data object Ausente : Estado
        data class Baixando(val bytes: Long, val total: Long) : Estado
        data object Conferindo : Estado
        data object Pronto : Estado
        data class Falhou(val motivo: String) : Estado
    }

    private const val PREFERENCIAS = "whisper"

    /** Modelos cujo SHA-256 está sendo calculado (leva segundos no Large). */
    private val conferindo = ConcurrentHashMap.newKeySet<String>()

    /** A pasta do app no armazenamento externo: não pede permissão e some ao desinstalar. */
    private fun pasta(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "whisper").apply { mkdirs() }

    fun arquivo(context: Context, modelo: ModeloWhisper): File = File(pasta(context), modelo.arquivo)
    private fun parcial(context: Context, modelo: ModeloWhisper): File = File(pasta(context), modelo.arquivo + ".baixando")

    fun instalado(context: Context, modelo: ModeloWhisper): Boolean = arquivo(context, modelo).length() == modelo.bytes

    private fun gerenciador(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun preferencias(context: Context) = context.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    /** Começa a baixar pelo gerenciador de downloads do Android: continua com o app fechado. */
    fun baixar(context: Context, modelo: ModeloWhisper) {
        if (instalado(context, modelo) || idDoDownload(context, modelo) != null) return
        parcial(context, modelo).delete()
        val pedido = DownloadManager.Request(Uri.parse(modelo.url))
            .setTitle("Modelo de transcrição ${modelo.rotulo}")
            .setDescription("Smells Like Tech Converter")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "whisper/${parcial(context, modelo).name}")
        val id = gerenciador(context).enqueue(pedido)
        preferencias(context).edit().putLong(modelo.id, id).apply()
    }

    fun cancelar(context: Context, modelo: ModeloWhisper) {
        idDoDownload(context, modelo)?.let { gerenciador(context).remove(it) }
        preferencias(context).edit().remove(modelo.id).apply()
        parcial(context, modelo).delete()
    }

    fun apagar(context: Context, modelo: ModeloWhisper) {
        cancelar(context, modelo)
        arquivo(context, modelo).delete()
    }

    private fun idDoDownload(context: Context, modelo: ModeloWhisper): Long? =
        preferencias(context).getLong(modelo.id, -1L).takeIf { it >= 0 }

    /**
     * Onde está cada modelo. Quando um download termina, confere o SHA-256 e só então põe o
     * arquivo no lugar; um arquivo que não confere é apagado.
     */
    suspend fun estado(context: Context, modelo: ModeloWhisper): Estado = withContext(Dispatchers.IO) {
        if (instalado(context, modelo)) return@withContext Estado.Pronto
        if (modelo.id in conferindo) return@withContext Estado.Conferindo
        val id = idDoDownload(context, modelo) ?: return@withContext Estado.Ausente
        val consulta = gerenciador(context).query(DownloadManager.Query().setFilterById(id))
        val (situacao, baixados, total, motivo) = consulta.use { cursor ->
            if (!cursor.moveToFirst()) return@use listOf(-1L, 0L, 0L, 0L)
            listOf(
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)).toLong(),
            )
        }
        when (situacao.toInt()) {
            DownloadManager.STATUS_SUCCESSFUL ->
                if (!conferindo.add(modelo.id)) Estado.Conferindo
                else try { conferir(context, modelo) } finally { conferindo.remove(modelo.id) }
            DownloadManager.STATUS_FAILED -> {
                cancelar(context, modelo)
                Estado.Falhou(if (motivo.toInt() == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Falta espaço no celular." else "O download falhou. Tente de novo.")
            }
            -1 -> { cancelar(context, modelo); Estado.Ausente }
            else -> Estado.Baixando(baixados, if (total > 0) total else modelo.bytes)
        }
    }

    private fun conferir(context: Context, modelo: ModeloWhisper): Estado {
        val baixado = parcial(context, modelo)
        preferencias(context).edit().remove(modelo.id).apply()
        if (baixado.length() != modelo.bytes || sha256(baixado) != modelo.sha256) {
            baixado.delete()
            return Estado.Falhou("O arquivo baixado não confere. Tente de novo.")
        }
        val final = arquivo(context, modelo)
        final.delete()
        if (!baixado.renameTo(final)) {
            baixado.delete()
            return Estado.Falhou("Não deu para guardar o modelo.")
        }
        return Estado.Pronto
    }

    /** O ritmo deste aparelho com o modelo, medido nas transcrições anteriores. */
    fun ritmo(context: Context, modelo: ModeloWhisper): Double =
        context.getSharedPreferences(RITMOS, Context.MODE_PRIVATE).getFloat(modelo.id, modelo.ritmoPadrao.toFloat()).toDouble()

    fun guardarRitmo(context: Context, modelo: ModeloWhisper, medido: Double) {
        val anterior = ritmo(context, modelo)
        // Média com o anterior: uma medida torta (o celular ocupado com outra coisa) não estraga a próxima estimativa.
        context.getSharedPreferences(RITMOS, Context.MODE_PRIVATE).edit()
            .putFloat(modelo.id, ((anterior + medido) / 2).toFloat()).apply()
    }

    private const val RITMOS = "whisper-ritmo"

    fun sha256(arquivo: File): String {
        val resumo = MessageDigest.getInstance("SHA-256")
        arquivo.inputStream().use { fluxo ->
            val bloco = ByteArray(1 shl 20)
            while (true) {
                val lidos = fluxo.read(bloco)
                if (lidos < 0) break
                resumo.update(bloco, 0, lidos)
            }
        }
        return resumo.digest().joinToString("") { "%02x".format(it) }
    }
}
