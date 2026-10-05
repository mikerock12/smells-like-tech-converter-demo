package br.com.smellsliketech.converter.conversao.ffmpeg

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer

/**
 * O H.264 pelo codificador do próprio celular (MediaCodec, com aceleração de hardware),
 * alimentado pelo FFmpeg: ele decodifica qualquer formato e aplica os filtros, e manda os
 * quadros crus pela saída padrão. O MediaCodec do FFmpeg, chamado de dentro do executável,
 * para no meio em vários aparelhos; pela API do Android ele é o mesmo que a câmera usa.
 */
object CodificadorDoCelular {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val ESPERA_US = 10_000L
    /** Sem nenhum quadro codificado neste tempo, o codificador travou. */
    private const val PACIENCIA_MS = 15_000L

    /** O codificador H.264 do aparelho aceita este tamanho e ritmo? */
    fun aceita(quadros: Quadros): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && MIME in info.supportedTypes.map(String::lowercase) &&
                info.getCapabilitiesForType(MIME).let { capacidades ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capacidades.colorFormats &&
                        capacidades.videoCapabilities.areSizeAndRateSupported(quadros.largura, quadros.altura, quadros.porSegundo.toDouble())
                }
        }
    }.getOrDefault(false)

    /**
     * Roda o FFmpeg (`argumentos` de [Comandos.videoParaOCelular]) e grava em `destino` um MP4
     * só com o vídeo. `totalDeQuadros`, se conhecido, vira a porcentagem.
     */
    suspend fun codificar(
        context: Context,
        argumentos: List<String>,
        quadros: Quadros,
        bitrateKbps: Int,
        destino: File,
        totalDeQuadros: Int?,
        aoAvancar: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val formato = MediaFormat.createVideoFormat(MIME, quadros.largura, quadros.altura).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, quadros.porSegundo)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MIME).apply { configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        } catch (erro: Exception) {
            throw ErroDeConversao("O codificador de vídeo do celular recusou ${quadros.largura}x${quadros.altura}.", erro)
        }
        val muxer = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var faixa = -1
        var muxerIniciado = false
        val ffmpeg = Ffmpeg.iniciar(context, argumentos)
        try {
            codec.start()
            val entrada = DataInputStream(ffmpeg.saida.buffered(quadros.bytes * 2))
            val quadro = ByteArray(quadros.bytes)
            val info = MediaCodec.BufferInfo()
            var lidos = 0
            var fimDaEntrada = false
            var fimDaSaida = false
            var ultimoAvanco = System.currentTimeMillis()

            fun esvaziar() {
                while (true) {
                    val indice = codec.dequeueOutputBuffer(info, if (fimDaEntrada) ESPERA_US else 0)
                    when {
                        indice == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        indice == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            faixa = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerIniciado = true
                        }
                        indice >= 0 -> {
                            val dados = codec.getOutputBuffer(indice)
                            if (dados != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerIniciado) {
                                dados.position(info.offset).limit(info.offset + info.size)
                                muxer.writeSampleData(faixa, dados, info)
                                ultimoAvanco = System.currentTimeMillis()
                            }
                            codec.releaseOutputBuffer(indice, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { fimDaSaida = true; return }
                        }
                    }
                }
            }

            while (!fimDaSaida) {
                currentCoroutineContext().ensureActive()
                if (!fimDaEntrada) {
                    val indice = codec.dequeueInputBuffer(ESPERA_US)
                    if (indice >= 0) {
                        val temQuadro = try {
                            entrada.readFully(quadro)
                            true
                        } catch (_: EOFException) {
                            false
                        }
                        val tempoUs = lidos * 1_000_000L / quadros.porSegundo
                        if (temQuadro) {
                            copiar(quadro, quadros, codec, indice)
                            codec.queueInputBuffer(indice, 0, quadros.bytes, tempoUs, 0)
                            lidos++
                            // Enquanto o codificador aceita quadros, ele está vivo (o FFmpeg pode ser lento).
                            ultimoAvanco = System.currentTimeMillis()
                            totalDeQuadros?.takeIf { it > 0 }?.let { aoAvancar((lidos * 95 / it).coerceIn(0, 95)) }
                        } else {
                            codec.queueInputBuffer(indice, 0, 0, tempoUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fimDaEntrada = true
                            ultimoAvanco = System.currentTimeMillis()
                        }
                    }
                }
                esvaziar()
                if (System.currentTimeMillis() - ultimoAvanco > PACIENCIA_MS && (lidos > 0 || fimDaEntrada)) {
                    throw ErroDeConversao("O codificador de vídeo do celular parou de responder.")
                }
            }
            ffmpeg.fim()
            if (lidos == 0 || !muxerIniciado) throw ErroDeConversao("O vídeo não tem nenhum quadro no trecho escolhido.")
        } finally {
            ffmpeg.matar()
            runCatching { codec.stop() }
            codec.release()
            if (muxerIniciado) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** Copia um quadro YUV 4:2:0 planar para a imagem de entrada do codificador, respeitando o passo de cada plano. */
    private fun copiar(quadro: ByteArray, quadros: Quadros, codec: MediaCodec, indice: Int) {
        val imagem = codec.getInputImage(indice) ?: throw ErroDeConversao("O codificador do celular não deu a imagem de entrada.")
        val largura = quadros.largura
        val altura = quadros.altura
        val tamanhoY = largura * altura
        val tamanhoC = tamanhoY / 4
        imagem.planes.forEachIndexed { plano, destino ->
            val (inicio, l, a) = when (plano) {
                0 -> Triple(0, largura, altura)
                1 -> Triple(tamanhoY, largura / 2, altura / 2)
                else -> Triple(tamanhoY + tamanhoC, largura / 2, altura / 2)
            }
            val buffer: ByteBuffer = destino.buffer
            val passoDaLinha = destino.rowStride
            val passoDoPixel = destino.pixelStride
            for (linha in 0 until a) {
                val origem = inicio + linha * l
                if (passoDoPixel == 1) {
                    buffer.position(linha * passoDaLinha)
                    buffer.put(quadro, origem, l)
                } else {
                    // Plano intercalado (NV12/NV21): um byte a cada passoDoPixel.
                    val base = linha * passoDaLinha
                    for (coluna in 0 until l) buffer.put(base + coluna * passoDoPixel, quadro[origem + coluna])
                }
            }
        }
    }
}
