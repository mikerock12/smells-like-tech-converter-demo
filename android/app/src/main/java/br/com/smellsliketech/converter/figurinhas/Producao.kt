package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Do arquivo escolhido à figurinha pronta: tira os quadros (vídeo e GIF), desenha cada um com
 * a edição e codifica em WebP dentro dos limites do WhatsApp (100 KB estática, 500 KB animada).
 */
object Producao {
    /** Os quadros extraídos de um trecho e a taxa em que foram tirados. */
    class Quadros(val arquivos: List<File>, val porSegundo: Int)

    /** Um quadro de vídeo ou GIF, no instante escolhido (para a figurinha estática). */
    suspend fun quadro(contexto: Context, arquivo: File, segundos: Double): Bitmap = withContext(Dispatchers.IO) {
        val saida = File.createTempFile("quadro-", ".png", contexto.cacheDir)
        try {
            Ffmpeg.executar(
                contexto,
                listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-ss", numero(segundos), "-i", arquivo.absolutePath,
                    "-frames:v", "1", "-vf", ESCALA, saida.absolutePath),
                duracao = null,
            )
            BitmapFactory.decodeFile(saida.absolutePath) ?: throw ErroDeConversao("Não deu para tirar esse quadro do vídeo.")
        } finally {
            saida.delete()
        }
    }

    /**
     * Lado maior dos quadros tirados do vídeo para o zoom da camada: 512 no zoom 1 (a figura
     * inteira cabe na figurinha) e mais só quando o zoom pede, até 1024.
     */
    fun ladoDosQuadros(zoom: Float): Int = (ceil(Regras.LADO * zoom.coerceIn(1f, 2f) / 64.0).toInt() * 64)

    /**
     * Converte o trecho (até 10 s) para uma versão leve, uma vez só, e guarda numa pasta
     * temporária: quadros na taxa da figurinha, no lado de [ladoDosQuadros], reduzidos com
     * média de área (some o granulado do vídeo da câmera) e em JPEG mais leve. O vídeo HD
     * (1080p/4K, 60 quadros/s, HEVC de 10 bits) é decodificado só nesta passada; a prévia de
     * cada instante e a figurinha saem desses quadros. GIF sai em PNG, para manter a
     * transparência. O número de quadros tem teto explícito: alguns vídeos não respeitam só o `-t`.
     */
    suspend fun quadros(
        contexto: Context,
        arquivo: File,
        inicio: Double,
        fim: Double,
        transparencia: Boolean,
        lado: Int = 1024,
        porSegundo: Int = Regras.QUADROS_POR_SEGUNDO_PADRAO,
        aoAvancar: (Double) -> Unit,
    ): Quadros = withContext(Dispatchers.IO) {
        val duracao = (fim - inicio).coerceIn(0.1, Regras.ANIMADA_MAX_SEGUNDOS)
        val maximo = ceil(duracao * porSegundo).toInt().coerceAtLeast(1)
        val pasta = File.createTempFile("figurinha-quadros-", "", contexto.cacheDir).apply { delete(); mkdirs() }
        val extensao = if (transparencia) "png" else "jpg"
        // A figurinha usa 10 a 15 quadros/s; com o vídeo a 30 ou 60, o decodificador pode pular
        // os quadros que nenhum outro referencia (metade do trabalho no HEVC de 60 quadros/s).
        // Com folga de 2,5x: abaixo disso poderiam faltar quadros e a animação repetiria alguns.
        val pularNaoReferenciados = (quadrosPorSegundoDoVideo(arquivo) ?: 0f) >= porSegundo * 2.5f
        try {
        Ffmpeg.executar(
            contexto,
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-progress", "pipe:1", "-nostats",
                "-ss", numero(inicio), "-t", numero(duracao)) +
                (if (pularNaoReferenciados) listOf("-skip_frame", "noref") else emptyList()) +
                listOf("-i", arquivo.absolutePath, "-map", "0:v:0", "-an", "-sn",
                "-vf", "fps=$porSegundo,scale=w='min($lado,iw)':h='min($lado,ih)':force_original_aspect_ratio=decrease:flags=area",
                "-frames:v", maximo.toString()) +
                (if (transparencia) emptyList() else listOf("-q:v", "5")) +
                listOf("-start_number", "0", File(pasta, "f%04d.$extensao").absolutePath),
            duracao = duracao,
            aoAvancar = { aoAvancar(it / 100.0) },
        )
        val arquivos = pasta.listFiles { f -> f.name.startsWith("f") }.orEmpty().sortedBy { it.name }
            .ifEmpty { throw ErroDeConversao("O trecho escolhido não tem quadros de vídeo.") }
        Quadros(arquivos, porSegundo)
        } catch (erro: Exception) {
            pasta.deleteRecursively()
            throw erro
        }
    }

    /**
     * O trecho do vídeo guardado no projeto da figurinha (para reabrir no editor): até 10 s, no
     * máximo 1024 px (o que o editor usa), sem som, em MPEG-4 (codificador do próprio FFmpeg,
     * LGPL). Bem menor que o vídeo original, e abre igual no editor.
     */
    suspend fun trechoParaOProjeto(contexto: Context, arquivo: File, inicio: Double, duracao: Double, destino: File) = withContext(Dispatchers.IO) {
        Ffmpeg.executar(
            contexto,
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y",
                "-ss", numero(inicio), "-t", numero(duracao.coerceIn(0.1, Regras.ANIMADA_MAX_SEGUNDOS)), "-i", arquivo.absolutePath,
                "-map", "0:v:0", "-an", "-sn", "-vf", "$ESCALA:force_divisible_by=2:flags=area,format=yuv420p",
                "-c:v", "mpeg4", "-q:v", "3", "-g", "15", "-f", "mp4", destino.absolutePath),
            duracao = null,
        )
    }

    /** Quadros por segundo do vídeo, pelo próprio Android (rápido, sem FFmpeg); null se não souber. */
    private fun quadrosPorSegundoDoVideo(arquivo: File): Float? = runCatching {
        val extrator = MediaExtractor()
        try {
            extrator.setDataSource(arquivo.absolutePath)
            (0 until extrator.trackCount).asSequence().map(extrator::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.let { formato ->
                    runCatching { formato.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { formato.getFloat(MediaFormat.KEY_FRAME_RATE) }
                }
        } finally {
            extrator.release()
        }
    }.getOrNull()

    /** WebP estático de até 100 KB: baixa a qualidade aos poucos até caber. */
    fun estatica(figurinha: Bitmap): ByteArray {
        val formato = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        for (qualidade in listOf(95, 88, 80, 70, 60, 50, 40, 30, 20, 10)) {
            val saida = ByteArrayOutputStream()
            figurinha.compress(formato, qualidade, saida)
            if (saida.size() <= Regras.ESTATICA_MAX_BYTES) return saida.toByteArray()
        }
        throw ErroDeConversao("Esta figurinha ficou grande demais para o WhatsApp (100 KB). Tente sem filtros fortes ou com menos detalhes.")
    }

    /**
     * Os quadros já desenhados (512×512), crus, um atrás do outro num arquivo só: nada de PNG
     * no meio, que é lento para gravar e para o FFmpeg ler. Cada ponto é ARGB sem
     * pré-multiplicação, como o FFmpeg espera (`-pix_fmt argb`).
     */
    class QuadrosCrus(val arquivo: File, val porSegundo: Int) : Closeable {
        private val saida = arquivo.outputStream().buffered(1 shl 20)
        private val pontos = IntArray(Regras.LADO * Regras.LADO)
        private val bytes = ByteBuffer.allocate(pontos.size * 4).order(ByteOrder.BIG_ENDIAN)
        private val visiveis = mutableListOf<Boolean>()
        var quantidade = 0
            private set

        fun temImagem(ate: Int = quantidade): Boolean = visiveis.take(ate).any { it }

        fun gravar(quadro: Bitmap) {
            require(quadro.width == Regras.LADO && quadro.height == Regras.LADO)
            quadro.getPixels(pontos, 0, Regras.LADO, 0, 0, Regras.LADO, Regras.LADO)
            visiveis += pontos.any { it ushr 24 != 0 }
            bytes.clear()
            bytes.asIntBuffer().put(pontos)
            saida.write(bytes.array())
            quantidade++
        }

        override fun close() = saida.close()
    }

    /**
     * Desenha os quadros extraídos e grava em `crus`, na ordem. Vários de uma vez (um por núcleo,
     * até 4): cada quadro é independente e o desenho é a parte mais longa da figurinha animada.
     * Cada quadro de origem é apagado assim que é lido; `aoDesenhar` recebe quantos já foram.
     */
    suspend fun desenharTodos(
        quadros: Quadros,
        crus: QuadrosCrus,
        desenho: suspend (Bitmap) -> Bitmap,
        paralelos: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        aoDesenhar: (Int) -> Unit,
    ) = coroutineScope {
        var feitos = 0
        quadros.arquivos.chunked(paralelos).forEach { lote ->
            val prontos = lote.map { arquivo ->
                async(Dispatchers.Default) {
                    val imagem = BitmapFactory.decodeFile(arquivo.absolutePath) ?: throw ErroDeConversao("Quadro ilegível.")
                    arquivo.delete()
                    try { desenho(imagem) } finally { imagem.recycle() }
                }
            }
            prontos.forEach { pronto ->
                val quadro = pronto.await()
                withContext(Dispatchers.IO) { crus.gravar(quadro) }
                quadro.recycle()
                aoDesenhar(++feitos)
            }
        }
    }

    /**
     * Fator de tamanho de cada qualidade em relação à 75 (medido no libwebp com quadros de
     * vídeo): o ensaio diz o tamanho na 75, e a tabela diz qual qualidade cabe.
     */
    private val FATOR_DA_QUALIDADE = listOf(85 to 1.35, 75 to 1.0, 65 to 0.8, 55 to 0.68, 45 to 0.58, 35 to 0.5, 25 to 0.43, 15 to 0.36)

    /** A figurinha animada pronta e quantos segundos ela ficou (menos que o trecho se encurtou). */
    class Animada(val webp: ByteArray, val segundos: Double, val encurtada: Boolean)

    /**
     * WebP animado de até 500 KB, sempre na taxa de quadros dos `crus`: nenhum quadro é pulado,
     * para todas as figurinhas terem a mesma fluidez. Um ensaio com um pedaço do meio (até 24
     * quadros) na qualidade 75 estima o todo; daí sai a maior qualidade que cabe, até
     * [Regras.QUALIDADE_MINIMA_ANIMADA]. Se nem ela couber (vídeo com muito movimento: cada
     * quadro vira quase uma foto inteira), a figurinha encurta a partir do começo do trecho.
     * Até três passadas, cada uma corrigida pelo tamanho real. `aoAvancar` vai de 0 a 1.
     */
    suspend fun animada(
        contexto: Context,
        crus: QuadrosCrus,
        aoTentar: (String) -> Unit = {},
        aoAvancar: (Double) -> Unit = {},
    ): Animada = withContext(Dispatchers.IO) {
        val total = crus.quantidade
        require(total > 0) { "Nenhum quadro para compactar." }
        val saida = File(contexto.cacheDir, "figurinha-animada.webp")
        val alvo = Regras.ANIMADA_MAX_BYTES * 0.92
        val minima = Regras.QUALIDADE_MINIMA_ANIMADA
        try {
            // 1. Ensaio (10% da etapa): bytes por quadro na qualidade 75.
            val amostra = min(total, 24)
            val inicioDaAmostra = (total - amostra) / 2
            val tamanhoDaAmostra = codificar(
                contexto, crus, saida, qualidade = 75,
                filtroExtra = "trim=start_frame=$inicioDaAmostra:end_frame=${inicioDaAmostra + amostra},setpts=PTS-STARTPTS",
                quadrosNaSaida = amostra,
            ) { aoAvancar(it * 0.1) }
            aoTentar("ensaio $amostra quadros q=75 ${tamanhoDaAmostra / 1024}KB")
            var porQuadroNa75 = tamanhoDaAmostra.toDouble() / amostra

            // 2. A maior qualidade que cabe com todos os quadros; senão, a mínima com menos quadros.
            fun plano(alvoDaVez: Double): Pair<Int, Int> {
                FATOR_DA_QUALIDADE.firstOrNull { it.first >= minima && porQuadroNa75 * it.second * total <= alvoDaVez }
                    ?.let { return it.first to total }
                val cabem = (alvoDaVez / (porQuadroNa75 * fator(minima))).toInt()
                return minima to cabem.coerceIn(2, total)
            }
            var (qualidade, quadros) = plano(alvo)
            var feito = 0.1
            repeat(3) { tentativa ->
                val parte = if (tentativa == 0) 0.9 else (1 - feito)
                val inicioDaParte = feito
                val corte = if (quadros < total) "trim=end_frame=$quadros" else null
                val tamanho = codificar(contexto, crus, saida, qualidade, corte, quadros) { aoAvancar(inicioDaParte + it * parte * 0.95) }
                aoTentar("q=$qualidade quadros=$quadros/$total ${tamanho / 1024}KB")
                if (tamanho in 1..Regras.ANIMADA_MAX_BYTES.toLong()) {
                    val gerada = saida.readBytes()
                    if (!crus.temImagem(quadros)) throw ErroDeConversao("A figurinha ficou vazia: a imagem saiu do quadro. Toque em Centralizar e tente de novo.")
                    val duracaoMs = (quadros * 1000L / crus.porSegundo).coerceIn(16, WebpAnimado.DURACAO_MAX_MS.toLong()).toInt()
                    val webp = WebpAnimado.normalizar(WebpAnimado.quadrosIndependentes(gerada), duracaoMs)
                    aoAvancar(1.0)
                    return@withContext Animada(webp, quadros.toDouble() / crus.porSegundo, quadros < total)
                }
                feito = inicioDaParte + parte * 0.95
                // Passou: corrige a estimativa pelo que saiu de verdade e planeja de novo, mais justo.
                porQuadroNa75 = tamanho.toDouble() / quadros / fator(qualidade)
                val proximo = plano(alvo * 0.9)
                qualidade = min(proximo.first, qualidade)
                quadros = min(proximo.second, if (qualidade == minima && quadros > 2) quadros - 1 else quadros)
            }
            throw ErroDeConversao("A figurinha animada não coube nos 500 KB do WhatsApp. Escolha um trecho mais curto.")
        } finally {
            saida.delete()
        }
    }

    /** Os pedaços (chunks) de um WebP, com o retângulo de cada quadro animado. */
    private class Pedaco(val tipo: String, val x: Int = 0, val y: Int = 0, val largura: Int = 0, val altura: Int = 0)

    private fun pedacos(webp: ByteArray): List<Pedaco> {
        fun le(i: Int, n: Int) = (0 until n).fold(0) { acc, k -> acc or ((webp[i + k].toInt() and 0xff) shl (8 * k)) }
        val lista = mutableListOf<Pedaco>()
        var i = 12
        while (i + 8 <= webp.size) {
            val tipo = String(webp, i, 4, Charsets.US_ASCII)
            val tamanho = le(i + 4, 4)
            if (tipo == "ANMF" && i + 24 <= webp.size) {
                lista += Pedaco(tipo, le(i + 8, 3) * 2, le(i + 11, 3) * 2, le(i + 14, 3) + 1, le(i + 17, 3) + 1)
                i += 24 // os pedaços do quadro vêm dentro do ANMF
                continue
            }
            lista += Pedaco(tipo)
            i += 8 + tamanho + (tamanho and 1)
        }
        return lista
    }

    /** "3 quadros: 512x512@0,0 …", para os testes e o log. */
    fun descrever(webp: ByteArray): String {
        val quadros = pedacos(webp).filter { it.tipo == "ANMF" }
        return "${quadros.size} quadros: " + quadros.take(4).joinToString(" ") { "${it.largura}x${it.altura}@${it.x},${it.y}" }
    }

    /**
     * A animação tem alguma imagem. Quando todos os quadros saem vazios (a figura foi arrastada
     * para fora do quadro), o libwebp grava um único ponto transparente de 1×1.
     */
    fun temImagem(webp: ByteArray): Boolean =
        pedacos(webp).firstOrNull { it.tipo == "ANMF" }?.let { it.largura > 1 || it.altura > 1 } == true

    private fun fator(qualidade: Int): Double = FATOR_DA_QUALIDADE.firstOrNull { it.first <= qualidade }?.second ?: 0.36

    /** Uma passada do libwebp_anim; devolve o tamanho do arquivo. */
    private suspend fun codificar(
        contexto: Context,
        crus: QuadrosCrus,
        saida: File,
        qualidade: Int,
        filtroExtra: String?,
        quadrosNaSaida: Int,
        aoAvancar: (Double) -> Unit,
    ): Long {
        val porSegundo = crus.porSegundo.toDouble()
        val filtros = listOfNotNull(filtroExtra)
        Ffmpeg.executar(
            contexto,
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-progress", "pipe:1", "-nostats",
                "-f", "rawvideo", "-pix_fmt", "argb", "-s", "${Regras.LADO}x${Regras.LADO}",
                "-framerate", crus.porSegundo.toString(), "-i", crus.arquivo.absolutePath) +
                (if (filtros.isEmpty()) emptyList() else listOf("-vf", filtros.joinToString(","))) +
                // O animador elimina áreas "parecidas" entre quadros em modo com perdas. Isso
                // acumula resíduos nas bordas em movimento. Cada quadro completo substitui o anterior.
                listOf("-c:v", "libwebp", "-lossless", "0", "-quality", qualidade.toString(),
                    "-compression_level", "4", "-loop", "0", "-pix_fmt", "yuva420p", "-an", "-f", "webp", saida.absolutePath),
            duracao = quadrosNaSaida / porSegundo,
            aoAvancar = { aoAvancar(it / 100.0) },
        )
        return saida.length()
    }

    /** Reduz o quadro para no máximo 1024 px no lado maior: suficiente para 512 com zoom 2x. */
    private const val ESCALA = "scale=w='min(1024,iw)':h='min(1024,ih)':force_original_aspect_ratio=decrease"

    private fun numero(valor: Double) = String.format(Locale.ROOT, "%.3f", valor)
}
