package br.com.smellsliketech.converter.imagem

import android.content.Context
import android.graphics.Bitmap
import br.com.smellsliketech.converter.licenca.Acesso
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Modelo de ampliação subpixel 2× criado e treinado pela SLTI. A foto permanece no aparelho. */
object AmpliadorSlti {
    const val SHA = "1905ec3cc0ab29ea395326c2362f1466cb92bf8211e4aa5769308489c4e00dc3"
    private fun conferir(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == SHA) { "Os pesos do ampliador SLTI falharam na verificação SHA-256." }
        return bytes
    }
    private suspend fun instalar(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val acesso = Acesso(context)
        when (acesso.situacao()) {
            is Acesso.Situacao.Comprada, is Acesso.Situacao.Assinada -> conferir(pesosDaPlay(context))
            is Acesso.Situacao.Ativa -> {
                val chave = requireNotNull(acesso.chaveAndroidAtiva())
                val file = File(context.filesDir, "ampliador-$SHA.json")
                if (file.isFile && file.length() <= 4096) {
                    runCatching { conferir(file.readBytes()) }.getOrNull()?.let { return@withContext it }
                }
                val conn = URL("https://converter.smellsliketech.com.br/api/motores/ampliador-slti?cliente=android").openConnection() as HttpsURLConnection
                val bytes = try {
                    conn.requestMethod = "POST"
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("Authorization", "Bearer $chave")
                    check(conn.responseCode == 200) { "Confira a conexão e a licença para instalar o ampliador SLTI." }
                    conferir(conn.inputStream.use { ProjetoImagem.ler(it, 4096) })
                } finally { conn.disconnect() }
                val temp = File.createTempFile("ampliador-", ".tmp", context.filesDir)
                try { temp.writeBytes(bytes); android.system.Os.rename(temp.path, file.path) } finally { temp.delete() }
                bytes
            }
            else -> error("Ative a licença paga do Android para usar o ampliador SLTI.")
        }
    }
    suspend fun ampliar(context: Context, fonte: Bitmap): Bitmap {
        val w = fonte.width
        val h = fonte.height
        require(w <= 8192 && h <= 8192 && w.toLong() * h <= 4L * 1024 * 1024) { "A saída 2× admite até 16 megapixels e 16.384 pixels por lado." }
        val bytes = instalar(context)
        return withContext(Dispatchers.Default) { aplicar(fonte, bytes) }
    }
    internal fun aplicar(fonte: Bitmap, bytes: ByteArray): Bitmap {
        val w = fonte.width
        val h = fonte.height
        val pesos = JSONObject(conferir(bytes).toString(Charsets.UTF_8)).getJSONArray("kernels")
        val filtros = Array(4) { i -> val f = pesos.getJSONArray(i); DoubleArray(25) { f.getDouble(it) } }
        val entrada = IntArray(w * h)
        fonte.getPixels(entrada, 0, w, 0, 0, w, h)
        val saida = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
        val linhas = IntArray(w * 4)
        val vizinhos = IntArray(25)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var i = 0
                for (dy in -2..2) {
                    val linha = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -2..2) vizinhos[i++] = entrada[linha + (x + dx).coerceIn(0, w - 1)]
                }
                for (fase in 0..3) {
                    var a = 0.0
                    var r = 0.0
                    var g = 0.0
                    var b = 0.0
                    for (k in 0..24) {
                        val cor = vizinhos[k]
                        val peso = (cor ushr 24) * filtros[fase][k]
                        a += peso
                        r += ((cor ushr 16) and 255) * peso
                        g += ((cor ushr 8) and 255) * peso
                        b += (cor and 255) * peso
                    }
                    val alfa = canal(a)
                    linhas[(fase / 2) * 2 * w + x * 2 + fase % 2] = if (alfa == 0) 0 else
                        android.graphics.Color.argb(alfa, canal(r / a), canal(g / a), canal(b / a))
                }
            }
            saida.setPixels(linhas, 0, w * 2, 0, y * 2, w * 2, 2)
        }
        return saida
    }
    private fun canal(valor: Double) = kotlin.math.round(valor).toInt().coerceIn(0, 255)
}
