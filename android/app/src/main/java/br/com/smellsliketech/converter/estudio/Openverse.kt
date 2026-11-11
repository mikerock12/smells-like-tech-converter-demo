package br.com.smellsliketech.converter.estudio

import br.com.smellsliketech.converter.conversao.ErroDeConversao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

internal object Openverse {
    private const val ENDERECO = "https://converter.smellsliketech.com.br/api/estudio/openverse"
    private const val LIMITE = 80L * 1024 * 1024
    private val formatos = setOf("mp3", "wav", "ogg", "oga")
    private val idValido = Regex("^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    private fun conectar(url: URI) = (url.toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 12000
        readTimeout = 12000
    }

    private suspend fun consultar(consulta: String): JSONObject = withContext(Dispatchers.IO) {
        val conexao = conectar(URI("$ENDERECO?$consulta"))
        try {
            val status = conexao.responseCode
            val corpo = (if (status == 200) conexao.inputStream else conexao.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val json = corpo?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (status != 200 || json == null) throw ErroDeConversao(json?.optString("erro") ?: "A biblioteca musical está indisponível.")
            json
        } finally {
            conexao.disconnect()
        }
    }

    suspend fun buscar(categoria: String, termo: String, pagina: Int) =
        consultar("category=$categoria&page=$pagina&q=${URLEncoder.encode(termo.trim(), "UTF-8")}")

    suspend fun baixar(categoria: String, id: String, pasta: File, progresso: (Long) -> Unit): Pair<File, JSONObject> {
        var arquivoPronto: File? = null
        try {
            return withContext(Dispatchers.IO) {
                if (!idValido.matches(id)) throw ErroDeConversao("Identificador de faixa inválido.")
                val item = consultar("category=$categoria&id=$id")
                if (item.optString("estado") != "APPROVED" || item.optString("id") != id) throw ErroDeConversao("A faixa precisa de nova revisão.")
                val uri = URI(item.getString("midia"))
                val formato = uri.path.substringAfterLast('.').lowercase()
                if (uri.scheme != "https" || uri.host != "upload.wikimedia.org" || uri.port !in listOf(-1, 443) ||
                    uri.userInfo != null || !uri.path.startsWith("/wikipedia/commons/") ||
                    formato !in formatos) throw ErroDeConversao("A origem ou o formato da faixa é incompatível.")

                pasta.mkdirs()
                val temporario = File(pasta, "${UUID.randomUUID()}.parcial.$formato")
                val destino = File(pasta, "${UUID.randomUUID()}.$formato")
                val conexao = conectar(uri).apply {
                    setRequestProperty("User-Agent", "SmellsLikeTechConverter/1.0 (Android; https://converter.smellsliketech.com.br)")
                }
                try {
                    if (conexao.responseCode != 200) throw ErroDeConversao("A origem da faixa está indisponível.")
                    if ((conexao.getHeaderField("Content-Length")?.toLongOrNull() ?: 0) > LIMITE) throw ErroDeConversao("O arquivo excede o limite de 80 MiB.")
                    val tipo = conexao.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (!tipo.startsWith("audio/") && tipo !in listOf("application/ogg", "application/octet-stream")) {
                        throw ErroDeConversao("A origem devolveu um arquivo que não é áudio.")
                    }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var ultimaMedida = 0L
                    conexao.inputStream.use { entrada ->
                        temporario.outputStream().use { saida ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                ensureActive()
                                val lidos = entrada.read(buffer)
                                if (lidos < 0) break
                                total += lidos
                                if (total > LIMITE) throw ErroDeConversao("O arquivo excede o limite de 80 MiB.")
                                hash.update(buffer, 0, lidos)
                                saida.write(buffer, 0, lidos)
                                if (total - ultimaMedida >= 262144) {
                                    withContext(Dispatchers.Main) { progresso(total) }
                                    ultimaMedida = total
                                }
                            }
                        }
                    }
                    if (hash.digest().joinToString("") { "%02x".format(it) } != item.optString("sha256")) {
                        throw ErroDeConversao("O arquivo da fonte mudou e precisa de nova revisão.")
                    }
                    ensureActive()
                    if (!temporario.renameTo(destino)) throw ErroDeConversao("Não foi possível guardar o áudio.")
                    arquivoPronto = destino
                    destino to item
                } finally {
                    conexao.disconnect()
                    temporario.delete()
                }
            }
        } catch (erro: Throwable) {
            arquivoPronto?.delete()
            throw erro
        }
    }
}
