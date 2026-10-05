package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconhecimento de texto (OCR) com o ML Kit, com o modelo embutido no app: funciona sem
 * internet e sem Google Play. Alfabeto latino, que cobre português, inglês e espanhol.
 */
object Ocr {
    private val reconhecedor by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun ler(imagem: InputImage): String = suspendCancellableCoroutine { continuacao ->
        reconhecedor.process(imagem)
            .addOnSuccessListener { continuacao.resume(it.text) }
            .addOnFailureListener { continuacao.resumeWithException(ErroDeConversao("O OCR não conseguiu ler esta imagem.", it)) }
    }

    suspend fun textoDaImagem(context: Context, entrada: Entrada): String {
        // O mesmo leitor da conversão inclui TIFF/AVIF pelo FFmpeg e aplica a orientação.
        val bitmap = Imagem.carregar(context, entrada)
        return try { lerBitmap(bitmap) } finally { bitmap.recycle() }
    }

    suspend fun lerBitmap(bitmap: Bitmap): String = ler(InputImage.fromBitmap(bitmap, 0))
}
