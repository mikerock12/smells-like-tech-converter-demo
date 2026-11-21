package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.graphics.Bitmap
import com.awxkee.jxlcoder.JxlChannelsConfiguration
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlCompressionOption
import com.awxkee.jxlcoder.PreferredColorConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal object JpegXl {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_PIXELS = 16L * 1024 * 1024
    private val caixa = byteArrayOf(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, -121, 10)

    fun abrir(context: Context, entrada: Entrada): Bitmap? {
        val dados = context.contentResolver.openInputStream(entrada.uri)?.buffered()?.use { fluxo ->
            fluxo.mark(caixa.size)
            val inicio = ByteArray(caixa.size)
            var lidos = 0
            while (lidos < inicio.size) {
                val n = fluxo.read(inicio, lidos, inicio.size - lidos)
                if (n <= 0) break
                lidos += n
            }
            if (!(lidos >= 2 && inicio[0] == (-1).toByte() && inicio[1] == 10.toByte()) &&
                !(lidos == caixa.size && inicio.contentEquals(caixa))) return null
            if (entrada.bytes > MAX_BYTES) throw ErroDeConversao("JPEG XL excede o limite de 64 MiB.")
            fluxo.reset()
            val memoria = ByteArrayOutputStream()
            val bloco = ByteArray(32768)
            while (true) {
                val n = fluxo.read(bloco)
                if (n < 0) break
                if (memoria.size() + n > MAX_BYTES) throw ErroDeConversao("JPEG XL excede o limite de 64 MiB.")
                memoria.write(bloco, 0, n)
            }
            memoria.toByteArray()
        } ?: return null
        val tamanho = JxlCoder.getSize(dados) ?: throw ErroDeConversao("Cabeçalho JPEG XL inválido.")
        conferir(tamanho.width, tamanho.height)
        return JxlCoder.decode(dados, PreferredColorConfig.RGBA_8888)
    }

    fun gravar(imagem: Bitmap, qualidade: Int, destino: OutputStream) {
        conferir(imagem.width, imagem.height)
        destino.write(JxlCoder.encode(imagem, channelsConfiguration = JxlChannelsConfiguration.RGBA,
            quality = qualidade.coerceIn(1, 100),
            compressionOption = if (qualidade == 100) JxlCompressionOption.LOSSLESS else JxlCompressionOption.LOSSY))
    }

    private fun conferir(largura: Int, altura: Int) {
        if (largura <= 0 || altura <= 0 || largura.toLong() * altura > MAX_PIXELS)
            throw ErroDeConversao("O limite de JPEG XL no celular é de 16 megapixels.")
    }
}
