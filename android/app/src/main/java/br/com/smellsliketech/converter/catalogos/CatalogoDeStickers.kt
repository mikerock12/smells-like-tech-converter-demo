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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

data class StickerDoCatalogo(val id: String, val nome: String, val previa: String, val url: String, val fonte: String, val autor: String)
data class ResultadoDoCatalogo(val fonte: String, val itens: List<StickerDoCatalogo>, val aviso: String? = null, val tentarEm: Long? = null, val proximaPagina: Int? = null)

/** Uma instância por seção. Persiste só a cota, nunca resultados, URLs ou mídia do GIPHY. */
class CatalogoDeStickers private constructor(private val contexto: Context, secao: String, private val chave: String) {
    private val preferencias = contexto.getSharedPreferences("catalogo-$secao", Context.MODE_PRIVATE)
    private val trava = Mutex()
    private val cliente = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val miniaturas = MiniaturasDoCatalogo(contexto)
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
                    status = conexao.code
                    retry = conexao.header("Retry-After")
                    if (status == 200) JSONObject(ler(conexao, 2_000_000).toString(Charsets.UTF_8)) else null
                } finally { conexao.close() }
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

    suspend fun previa(item: StickerDoCatalogo): Drawable = miniaturas.carregar(item)

    /** Cópia temporária apenas do item escolhido para edição; removida após importar no editor. */
    suspend fun importar(item: StickerDoCatalogo, animado: Boolean): File = withContext(Dispatchers.IO) {
        // O nome do arquivo vira o título da camada no estúdio.
        val nome = item.nome.filterNot { it in "\\/:*?\"<>|" }.trim().take(40).ifBlank { "Sticker" }
        // createTempFile pede prefixo de pelo menos 3 caracteres.
        val arquivo = File.createTempFile("$nome-".padEnd(3, '_'), if (animado) ".gif" else ".png", contexto.cacheDir)
        try {
            baixarPara(item, arquivo)
            if (!animado) {
                val imagem = ImageDecoder.decodeBitmap(ImageDecoder.createSource(arquivo)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val escala = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                    d.setTargetSize((info.size.width * escala).toInt().coerceAtLeast(1),
                        (info.size.height * escala).toInt().coerceAtLeast(1))
                }
                try { arquivo.outputStream().use { imagem.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { imagem.recycle() }
            }
            currentCoroutineContext().ensureActive()
            arquivo
        } catch (erro: Exception) { arquivo.delete(); throw erro }
    }

    private suspend fun baixarPara(item: StickerDoCatalogo, arquivo: File) {
        val limite = 32_000_000L
        suspend fun copiar(entrada: java.io.InputStream) {
            entrada.use { fonte -> arquivo.outputStream().use { saida ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = fonte.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= limite) { "Este sticker é grande demais." }
                    saida.write(buffer, 0, n)
                }
            } }
        }
        if (item.fonte == "noto") { copiar(contexto.assets.open(item.url)); return }
        val conexao = abrir(item.url)
        try {
            require(conexao.code == 200) { "Não foi possível abrir o sticker." }
            require(requireNotNull(conexao.body).contentLength() <= limite) { "Este sticker é grande demais." }
            copiar(requireNotNull(conexao.body).byteStream())
        } finally { conexao.close() }
    }
    private fun abrir(url: String): Response {
        val uri = URI(url)
        require(uri.scheme == "https" && (uri.host == "api.giphy.com" || uri.host == "giphy.com" || uri.host?.endsWith(".giphy.com") == true)) {
            "Endereço de sticker inválido."
        }
        return cliente.newCall(Request.Builder().url(url).build()).execute()
    }
    private suspend fun ler(conexao: Response, limite: Int): ByteArray {
        require(requireNotNull(conexao.body).contentLength() <= limite) { "Este sticker é grande demais." }
        return requireNotNull(conexao.body).byteStream().use { entrada ->
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
        @Synchronized fun obter(contexto: Context, secao: String): CatalogoDeStickers {
            val grupo = if (BuildConfig.GIPHY_FIGURINHAS == BuildConfig.GIPHY_EDITOR) "compartilhado" else secao
            return instancias.getOrPut(grupo) { CatalogoDeStickers(contexto.applicationContext, grupo,
                ConfiguracaoGiphy.abrir(if (secao == "figurinhas") BuildConfig.GIPHY_FIGURINHAS else BuildConfig.GIPHY_EDITOR)) }
        }
        private fun normalizar(texto: String) = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}
