package br.com.smellsliketech.converter.conversao

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Onde os resultados vão parar: Downloads/SmellsLikeTech. No Android 10+ pelo MediaStore, sem
 * permissão de armazenamento. No Android 9 o MediaStore.Downloads não existe: o arquivo vai
 * direto para a mesma pasta (com a permissão antiga de gravar), é registrado na galeria e
 * compartilhado por um FileProvider. Nos dois casos aparece no gerenciador de arquivos.
 */
object Saida {
    private const val PASTA = "Download/SmellsLikeTech"

    /**
     * Android 9: gravar em Downloads exige a permissão de armazenamento, pedida na abertura. Ler
     * e gravar vão juntas: só com a de gravar o sistema ainda recusou criar a pasta no teste.
     */
    val PERMISSOES = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun precisaDePermissao(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            PERMISSOES.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun nomeDeSaida(original: String, sufixo: String, extensao: String): String {
        val base = original.substringBeforeLast('.').ifBlank { "arquivo" }.take(80)
        return if (sufixo.isBlank()) "$base.$extensao" else "$base-$sufixo.$extensao"
    }

    /** Grava o que `escrever` produzir e devolve o resultado já visível em Downloads. */
    fun gravar(context: Context, nome: String, mime: String, escrever: (OutputStream) -> Unit): Resultado {
        if (Build.VERSION.SDK_INT < 29) return gravarNaPasta(context, nome, mime, escrever)
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

    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: o caminho certo até o Android 9.
    private fun gravarNaPasta(context: Context, nome: String, mime: String, escrever: (OutputStream) -> Unit): Resultado {
        if (precisaDePermissao(context)) {
            throw ErroDeConversao("Para salvar em Downloads, permita o acesso ao armazenamento nas configurações do app.")
        }
        val pasta = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmellsLikeTech")
        if (!pasta.isDirectory && !pasta.mkdirs()) error("O Android não deixou criar a pasta Downloads/SmellsLikeTech.")
        val destino = livre(pasta, nome)
        try {
            destino.outputStream().use(escrever)
        } catch (erro: Throwable) {
            destino.delete()
            throw erro
        }
        MediaScannerConnection.scanFile(context, arrayOf(destino.absolutePath), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.arquivos", destino)
        return Resultado(destino.name, uri, mime, destino.length())
    }

    /** O MediaStore numera nomes repetidos sozinho; na pasta, "nome (2).ext" faz o mesmo. */
    private fun livre(pasta: File, nome: String): File {
        val base = nome.substringBeforeLast('.')
        val extensao = nome.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidato = File(pasta, nome)
        var numero = 2
        while (candidato.exists()) candidato = File(pasta, "$base ($numero)$extensao").also { numero++ }
        return candidato
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
