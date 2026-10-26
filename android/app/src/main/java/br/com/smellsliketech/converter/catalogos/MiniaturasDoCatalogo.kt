package br.com.smellsliketech.converter.catalogos

import android.content.Context
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Coil dimensiona e cancela a decodificação. GIPHY nunca é guardado em cache. */
internal class MiniaturasDoCatalogo(contexto: Context, personalizarTransporte: (OkHttpClient.Builder.() -> Unit)? = null) {
    private val contexto = contexto.applicationContext
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .apply { personalizarTransporte?.invoke(this) }
        .addNetworkInterceptor { cadeia ->
            val url = cadeia.request().url
            if (url.scheme != "https" || !(url.host == "giphy.com" || url.host.endsWith(".giphy.com")))
                throw IOException("Endereço de sticker inválido.")
            val resposta = cadeia.proceed(cadeia.request())
            val corpo = resposta.body ?: return@addNetworkInterceptor resposta
            if (corpo.contentLength() > LIMITE) { resposta.close(); throw IOException("Miniatura grande demais.") }
            val limitado = object : ResponseBody() {
                private val fonte = object : ForwardingSource(corpo.source()) {
                    var lidos = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val n = super.read(sink, minOf(byteCount, LIMITE - lidos + 1))
                        if (n > 0) lidos += n
                        if (lidos > LIMITE) throw IOException("Miniatura grande demais.")
                        return n
                    }
                }.buffer()
                override fun contentType() = corpo.contentType()
                override fun contentLength() = corpo.contentLength()
                override fun source() = fonte
            }
            resposta.newBuilder().body(limitado).build()
        }.build()
    private val carregador = ImageLoader.Builder(this.contexto)
        .components {
            add(AnimatedImageDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { cliente }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(4L * 1024 * 1024).weakReferencesEnabled(false).build() }
        .diskCache(null)
        .build()

    suspend fun carregar(item: StickerDoCatalogo): Drawable {
        val dado = if (item.fonte == "noto") "file:///android_asset/${item.previa}" else item.previa
        val politica = if (item.fonte == "giphy") CachePolicy.DISABLED else CachePolicy.ENABLED
        val pedido = ImageRequest.Builder(contexto).data(dado).size(160, 160)
            .scale(Scale.FIT).precision(Precision.EXACT).allowHardware(false)
            .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(politica)
            .build()
        val resultado = carregador.execute(pedido)
        if (resultado is ErrorResult) throw resultado.throwable
        return requireNotNull(resultado.image).asDrawable(contexto.resources)
    }

    private companion object { const val LIMITE = 8_000_000L }
}
