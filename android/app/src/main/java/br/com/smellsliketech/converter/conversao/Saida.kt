package br.com.smellsliketech.converter.conversao

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * Onde os resultados vão parar: Downloads/SmellsLikeTech, pelo MediaStore. Não precisa de
 * permissão de armazenamento (Android 10+) e o arquivo aparece no gerenciador e na galeria.
 */
object Saida {
    private const val PASTA = "Download/SmellsLikeTech"

    fun nomeDeSaida(original: String, sufixo: String, extensao: String): String {
        val base = original.substringBeforeLast('.').ifBlank { "arquivo" }.take(80)
        return if (sufixo.isBlank()) "$base.$extensao" else "$base-$sufixo.$extensao"
    }

    /** Grava o que `escrever` produzir e devolve o resultado já visível em Downloads. */
    fun gravar(context: Context, nome: String, mime: String, escrever: (OutputStream) -> Unit): Resultado {
        val resolver = context.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, PASTA)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: error("O Android não deixou criar o arquivo em Downloads.")
        try {
            resolver.openOutputStream(uri)?.use(escrever) ?: error("Não deu para abrir o arquivo de saída.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (erro: Throwable) {
            resolver.delete(uri, null, null)
            throw erro
        }
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        return Resultado(nome, uri, mime, bytes)
    }

    /** Para motores que escrevem num arquivo (o Media3, a narração): copia para Downloads e apaga. */
    fun gravarArquivo(context: Context, nome: String, mime: String, arquivo: File): Resultado =
        try {
            gravar(context, nome, mime) { saida -> arquivo.inputStream().use { it.copyTo(saida) } }
        } finally {
            arquivo.delete()
        }

    fun temporario(context: Context, extensao: String): File = File.createTempFile("slt-", ".$extensao", context.cacheDir)
}
