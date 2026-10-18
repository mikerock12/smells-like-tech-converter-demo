package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Do arquivo escolhido à figurinha pronta: tira os quadros (vídeo e GIF), desenha cada um com
 * a edição e codifica em WebP dentro dos limites do WhatsApp (100 KB estática, 500 KB animada).
 */
object Producao {
    /** Quadros por segundo da figurinha animada: suave sem estourar os 500 KB. */
    const val QUADROS_POR_SEGUNDO = 12

    /** Um quadro de vídeo ou GIF, no instante escolhido (para a figurinha estática). */
    suspend fun quadro(contexto: Context, arquivo: File, segundos: Double): Bitmap = withContext(Dispatchers.IO) {
        val saida = File.createTempFile("quadro-", ".png", contexto.cacheDir)
        try {
            Ffmpeg.executar(
                contexto,
                listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-ss", numero(segundos), "-i", arquivo.absolutePath,
                    "-frames:v", "1", "-vf", ESCALA, saida.absolutePath),
                duracao = null,
            )
            BitmapFactory.decodeFile(saida.absolutePath) ?: throw ErroDeConversao("Não deu para tirar esse quadro do vídeo.")
        } finally {
            saida.delete()
        }
    }

    /** Os quadros do trecho (até 10 s), em PNG numa pasta temporária, para desenhar cada um. */
    suspend fun quadros(contexto: Context, arquivo: File, inicio: Double, fim: Double, aoAvancar: (Int) -> Unit): List<File> =
        withContext(Dispatchers.IO) {
            val duracao = (fim - inicio).coerceIn(0.1, Regras.ANIMADA_MAX_SEGUNDOS)
            val pasta = File(contexto.cacheDir, "figurinha-quadros").apply { deleteRecursively(); mkdirs() }
            Ffmpeg.executar(
                contexto,
                listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-progress", "pipe:1", "-nostats",
                    "-ss", numero(inicio), "-t", numero(duracao), "-i", arquivo.absolutePath,
                    "-vf", "fps=$QUADROS_POR_SEGUNDO,$ESCALA", "-start_number", "0", File(pasta, "f%04d.png").absolutePath),
                duracao = duracao,
                aoAvancar = aoAvancar,
            )
            pasta.listFiles { f -> f.name.startsWith("f") }.orEmpty().sortedBy { it.name }
                .ifEmpty { throw ErroDeConversao("O trecho escolhido não tem quadros de vídeo.") }
        }

    /** WebP estático de até 100 KB: baixa a qualidade aos poucos até caber. */
    fun estatica(figurinha: Bitmap): ByteArray {
        val formato = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        for (qualidade in listOf(95, 88, 80, 70, 60, 50, 40, 30, 20, 10)) {
            val saida = ByteArrayOutputStream()
            figurinha.compress(formato, qualidade, saida)
            if (saida.size() <= Regras.ESTATICA_MAX_BYTES) return saida.toByteArray()
        }
        throw ErroDeConversao("Esta figurinha ficou grande demais para o WhatsApp (100 KB). Tente sem filtros fortes ou com menos detalhes.")
    }

    /**
     * WebP animado de até 500 KB a partir dos quadros já desenhados (PNG 512×512 com
     * transparência). Primeiro baixa a qualidade; se ainda passar, usa metade dos quadros.
     */
    suspend fun animada(contexto: Context, quadros: List<File>): ByteArray = withContext(Dispatchers.IO) {
        val pasta = quadros.first().parentFile!!
        val saida = File(contexto.cacheDir, "figurinha-animada.webp")
        try {
            for ((passo, qualidades) in listOf(1 to listOf(75, 60, 45, 32), 2 to listOf(50, 35, 22))) {
                val usados = quadros.filterIndexed { indice, _ -> indice % passo == 0 }
                // A sequência de entrada precisa ser contínua: renomeia os usados em ordem.
                val sequencia = File(pasta, "seq-$passo").apply { deleteRecursively(); mkdirs() }
                usados.forEachIndexed { indice, quadro -> quadro.copyTo(File(sequencia, "q%04d.png".format(indice)), overwrite = true) }
                for (qualidade in qualidades) {
                    Ffmpeg.executar(
                        contexto,
                        listOf("-hide_banner", "-nostdin", "-v", "error", "-y",
                            "-framerate", numero(QUADROS_POR_SEGUNDO.toDouble() / passo), "-i", File(sequencia, "q%04d.png").absolutePath,
                            "-c:v", "libwebp_anim", "-lossless", "0", "-quality", qualidade.toString(), "-compression_level", "6",
                            "-loop", "0", "-pix_fmt", "yuva420p", "-an", "-f", "webp", saida.absolutePath),
                        duracao = null,
                    )
                    if (saida.length() in 1..Regras.ANIMADA_MAX_BYTES.toLong()) return@withContext saida.readBytes()
                }
            }
            throw ErroDeConversao("A figurinha animada não coube nos 500 KB do WhatsApp. Escolha um trecho mais curto.")
        } finally {
            saida.delete()
        }
    }

    /** Lê um quadro desenhado de volta (pré-visualização e bandeja). */
    fun ler(arquivo: File): Bitmap? = BitmapFactory.decodeFile(arquivo.absolutePath)

    /** Reduz o quadro para no máximo 1024 px no lado maior: suficiente para 512 com zoom 2x. */
    private const val ESCALA = "scale=w='min(1024,iw)':h='min(1024,ih)':force_original_aspect_ratio=decrease"

    private fun numero(valor: Double) = String.format(Locale.ROOT, "%.3f", valor)
}
