package br.com.smellsliketech.converter.figurinhas

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * A remoção automática de fundo: a segmentação de assunto do ML Kit separa pessoas, animais e
 * objetos do resto, no aparelho. O modelo vem pelo Google Play Services (baixado uma vez);
 * depois funciona sem internet. O resultado é uma máscara do tamanho da imagem: alfa 255 na
 * figura, 0 no fundo, com a borda suave que o modelo devolve.
 */
object Fundo {
    private val segmentador by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    class Indisponivel(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

    suspend fun mascara(imagem: Bitmap): Bitmap {
        // O modelo trabalha bem até ~1 megapixel; maior só gasta memória.
        val reduzida = reduzir(imagem, 1024)
        // O ML Kit continua trabalhando quando a coroutine é cancelada. Sua cópia só pode
        // ser reciclada quando a tarefa nativa termina, independentemente de quem chamou.
        val entrada = if (reduzida === imagem) imagem.copy(Bitmap.Config.ARGB_8888, false) else reduzida
        val largura = entrada.width
        val altura = entrada.height
        val resultado = suspendCancellableCoroutine { continuacao ->
            val tarefa = try {
                segmentador.process(InputImage.fromBitmap(entrada, 0))
            } catch (erro: Throwable) {
                // Sem tarefa nativa, ninguém mais vai soltar a cópia.
                entrada.recycle()
                throw erro
            }
            tarefa
                .addOnCompleteListener { entrada.recycle() }
                .addOnSuccessListener { continuacao.resume(it) }
                .addOnFailureListener { erro ->
                    continuacao.resumeWithException(
                        Indisponivel(
                            if (erro.message.orEmpty().contains("download", ignoreCase = true) || erro.message.orEmpty().contains("waiting", ignoreCase = true))
                                "O modelo de remoção de fundo ainda está sendo baixado pelo Google Play. Conecte à internet e tente de novo em instantes."
                            else "A remoção de fundo precisa do Google Play Services atualizado neste celular.",
                            erro,
                        ),
                    )
                }
        }
        val confianca = resultado.foregroundConfidenceMask ?: throw Indisponivel("O modelo não devolveu a figura desta imagem.")
        confianca.rewind()
        val pontos = IntArray(largura * altura) { Color.argb((confianca.get().coerceIn(0f, 1f) * 255).roundToInt(), 255, 255, 255) }
        val mascara = Bitmap.createBitmap(pontos, largura, altura, Bitmap.Config.ARGB_8888)
        return if (mascara.width == imagem.width && mascara.height == imagem.height) mascara
        else Bitmap.createScaledBitmap(mascara, imagem.width, imagem.height, true).also { mascara.recycle() }
    }

    /** Uma máscara que mantém tudo (para o pincel começar do zero, sem remoção automática). */
    fun mascaraCheia(largura: Int, altura: Int): Bitmap =
        Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun reduzir(imagem: Bitmap, lado: Int): Bitmap {
        val maior = maxOf(imagem.width, imagem.height)
        if (maior <= lado) return imagem
        val escala = lado.toFloat() / maior
        return Bitmap.createScaledBitmap(imagem, (imagem.width * escala).roundToInt().coerceAtLeast(1), (imagem.height * escala).roundToInt().coerceAtLeast(1), true)
    }
}
