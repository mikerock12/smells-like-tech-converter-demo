package br.com.smellsliketech.converter.catalogos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import br.com.smellsliketech.converter.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.text.Normalizer

data class StickerDoCatalogo(val id: String, val nome: String, val previa: String, val url: String, val fonte: String, val autor: String)
data class ResultadoDoCatalogo(val fonte: String, val itens: List<StickerDoCatalogo>, val aviso: String? = null, val tentarEm: Long? = null, val proximaPagina: Int? = null)

/** Uma instância por seção. Persiste só a cota, nunca resultados, URLs ou mídia do GIPHY. */
class CatalogoDeStickers private constructor(private val contexto: Context, secao: String, private val chave: String) {
    private val preferencias = contexto.getSharedPreferences("catalogo-$secao", Context.MODE_PRIVATE)
    private val trava = Mutex()
    private var estado = runCatching {
        val j = JSONObject(preferencias.getString("cota", "{}")!!)
        val tentativas = j.optJSONArray("tentativas") ?: JSONArray()
        EstadoDoCatalogo((0 until tentativas.length()).map { tentativas.getLong(it) },
            j.optLong("tentarEm"), j.optString("motivo").ifBlank { null }, j.optInt("falhas").coerceIn(0, 100))
    }.getOrDefault(EstadoDoCatalogo())

    private fun guardar() {
        preferencias.edit().putString("cota", JSONObject().put("tentativas", JSONArray(estado.tentativas))
            .put("tentarEm", estado.tentarEm).put("motivo", estado.motivo ?: "").put("falhas", estado.falhas).toString()).apply()
    }

    suspend fun buscar(consulta: String, pagina: Int = 0): ResultadoDoCatalogo = trava.withLock {
        withContext(Dispatchers.IO) {
            estado = PoliticaDoCatalogo.preparar(estado, System.currentTimeMillis())
            guardar()
            if (chave.isBlank()) return@withContext noto(consulta)
            if (estado.tentarEm > System.currentTimeMillis()) return@withContext noto(consulta, estado.motivo, estado.tentarEm)
            val termo = consulta.trim().take(100)
            val endereco = "https://api.giphy.com/v1/stickers/${if (termo.isBlank()) "trending" else "search"}" +
                "?api_key=${URLEncoder.encode(chave, "UTF-8")}&limit=24&offset=${pagina.coerceIn(0, 20) * 24}&rating=pg" +
                if (termo.isBlank()) "" else "&lang=pt&q=${URLEncoder.encode(termo, "UTF-8")}"
            estado = estado.copy(tentativas = estado.tentativas + System.currentTimeMillis())
            guardar()
            var status = 0
            var retry: String? = null
            try {
                val conexao = abrir(endereco)
                val corpo = try {
                    status = conexao.responseCode
                    retry = conexao.getHeaderField("Retry-After")
                    if (status == 200) JSONObject(ler(conexao, 2_000_000).toString(Charsets.UTF_8)) else null
                } finally { conexao.disconnect() }
                currentCoroutineContext().ensureActive()
                status = corpo?.optJSONObject("meta")?.optInt("status", status) ?: status
                val dados = corpo?.optJSONArray("data")
                if (status == 200 && dados != null) {
                    estado = PoliticaDoCatalogo.resposta(estado, 200, System.currentTimeMillis())
                    guardar()
                    val itens = (0 until dados.length()).map { indice ->
                        val item = dados.getJSONObject(indice)
                        val imagens = item.getJSONObject("images")
                        val previa = imagens.optJSONObject("fixed_height")
                        StickerDoCatalogo(item.getString("id"), item.optString("title", "Sticker do GIPHY"),
                            previa?.optString("webp")?.ifBlank { null } ?: previa?.optString("url").orEmpty(),
                            imagens.getJSONObject("original").getString("url"), "giphy",
                            item.optJSONObject("user")?.optString("display_name")?.ifBlank { null } ?: "GIPHY")
                    }
                    val paginacao = corpo.optJSONObject("pagination")
                    val proxima = if (paginacao != null && pagina < 20 &&
                        paginacao.optInt("offset") + paginacao.optInt("count") < paginacao.optInt("total_count")) pagina + 1 else null
                    return@withContext ResultadoDoCatalogo("giphy", itens, proximaPagina = proxima)
                }
                if (status == 200) status = 502
            } catch (erro: CancellationException) { throw erro }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); status = 0 }
            estado = PoliticaDoCatalogo.resposta(estado, status, System.currentTimeMillis(), retry)
            guardar()
            noto(consulta, estado.motivo, estado.tentarEm)
        }
    }

    private fun noto(consulta: String, motivo: String? = null, tentarEm: Long? = null): ResultadoDoCatalogo {
        val dados = contexto.assets.open("stickers/noto/catalogo.json").bufferedReader().use { JSONArray(it.readText()) }
        val termos = normalizar(consulta).split(" ").filter { it.isNotBlank() }
        val itens = (0 until dados.length()).map { dados.getJSONObject(it) }.filter { item ->
            val texto = normalizar(item.getString("nome") + " " + item.getString("termos"))
            termos.all { it in texto }
        }.map { item ->
            val arquivo = "stickers/noto/${item.getString("codigo")}.gif"
            StickerDoCatalogo(item.getString("codigo"), item.getString("nome"), arquivo, arquivo, "noto", "Google Noto · CC BY 4.0")
        }
        return ResultadoDoCatalogo("noto", itens, PoliticaDoCatalogo.aviso(motivo), tentarEm)
    }

    suspend fun previa(item: StickerDoCatalogo): Drawable = withContext(Dispatchers.IO) {
        val bytes = bytes(item, true)
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val escala = 160f / maxOf(info.size.width, info.size.height)
            decoder.setTargetSize((info.size.width * escala).toInt().coerceAtLeast(1), (info.size.height * escala).toInt().coerceAtLeast(1))
        }
    }

    /** Cópia temporária apenas do item escolhido para edição; removida após importar no editor. */
    suspend fun importar(item: StickerDoCatalogo, animado: Boolean): File = withContext(Dispatchers.IO) {
        val dados = bytes(item, false)
        // O nome do arquivo vira o título da camada no estúdio.
        val nome = item.nome.filterNot { it in "\\/:*?\"<>|" }.trim().take(40).ifBlank { "Sticker" }
        // createTempFile pede prefixo de pelo menos 3 caracteres.
        val arquivo = File.createTempFile("$nome-".padEnd(3, '_'), if (animado) ".gif" else ".png", contexto.cacheDir)
        try {
            if (animado) arquivo.writeBytes(dados) else {
                val imagem = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(dados))) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try { arquivo.outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { imagem.recycle() }
            }
            currentCoroutineContext().ensureActive()
            arquivo
        } catch (erro: Exception) { arquivo.delete(); throw erro }
    }

    private suspend fun bytes(item: StickerDoCatalogo, miniatura: Boolean): ByteArray {
        if (item.fonte == "noto") return contexto.assets.open(if (miniatura) item.previa else item.url).use { it.readBytes() }
        val conexao = abrir(if (miniatura) item.previa else item.url)
        return try {
            require(conexao.responseCode == 200) { "Não foi possível abrir o sticker." }
            ler(conexao, if (miniatura) 8_000_000 else 32_000_000)
        } finally { conexao.disconnect() }
    }

    private fun abrir(url: String): HttpURLConnection {
        val uri = URI(url)
        require(uri.scheme == "https" && (uri.host == "api.giphy.com" || uri.host == "giphy.com" || uri.host?.endsWith(".giphy.com") == true)) {
            "Endereço de sticker inválido."
        }
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            useCaches = false
            instanceFollowRedirects = false
        }
    }

    private suspend fun ler(conexao: HttpURLConnection, limite: Int): ByteArray {
        require(conexao.contentLengthLong <= limite) { "Este sticker é grande demais." }
        return conexao.inputStream.use { entrada ->
            val saida = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = entrada.read(buffer)
                if (n < 0) break
                require(saida.size() + n <= limite) { "Este sticker é grande demais." }
                saida.write(buffer, 0, n)
            }
            saida.toByteArray()
        }
    }

    companion object {
        private val instancias = mutableMapOf<String, CatalogoDeStickers>()
        @Synchronized fun obter(contexto: Context, secao: String): CatalogoDeStickers =
            instancias.getOrPut(secao) { CatalogoDeStickers(contexto.applicationContext, secao,
                if (secao == "figurinhas") BuildConfig.GIPHY_FIGURINHAS else BuildConfig.GIPHY_EDITOR) }
        private fun normalizar(texto: String) = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}
