package br.com.smellsliketech.converter.figurinhas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.max
import kotlin.math.min

/**
 * Do arquivo escolhido à figurinha pronta: tira os quadros (vídeo e GIF), desenha cada um com
 * a edição e codifica em WebP dentro dos limites do WhatsApp (100 KB estática, 500 KB animada).
 */
object Producao {
    /**
     * Quadros por segundo da figurinha animada: mais suave nos trechos curtos, mais leve nos
     * longos (10 s a 10 quadros/s = 100 quadros), para caber nos 500 KB sem várias tentativas.
     */
    fun quadrosPorSegundo(duracao: Double): Int = when {
        duracao <= 3.0 -> 15
        duracao <= 6.0 -> 12
        else -> 10
    }

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
     * Os quadros do trecho (até 10 s), numa pasta temporária. Vídeo sai em JPEG (rápido de ler);
     * GIF em PNG, para manter a transparência. O número de quadros tem teto explícito: alguns
     * vídeos não respeitam só o `-t`.
     */
    suspend fun quadros(
        contexto: Context,
        arquivo: File,
        inicio: Double,
        fim: Double,
        transparencia: Boolean,
        aoAvancar: (Double) -> Unit,
    ): Quadros = withContext(Dispatchers.IO) {
        val duracao = (fim - inicio).coerceIn(0.1, Regras.ANIMADA_MAX_SEGUNDOS)
        val porSegundo = quadrosPorSegundo(duracao)
        val maximo = ceil(duracao * porSegundo).toInt().coerceAtLeast(1)
        val pasta = File(contexto.cacheDir, "figurinha-quadros").apply { deleteRecursively(); mkdirs() }
        val extensao = if (transparencia) "png" else "jpg"
        Ffmpeg.executar(
            contexto,
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-progress", "pipe:1", "-nostats",
                "-ss", numero(inicio), "-t", numero(duracao), "-i", arquivo.absolutePath,
                "-vf", "fps=$porSegundo,$ESCALA", "-frames:v", maximo.toString()) +
                (if (transparencia) emptyList() else listOf("-q:v", "3")) +
                listOf("-start_number", "0", File(pasta, "f%04d.$extensao").absolutePath),
            duracao = duracao,
            aoAvancar = { aoAvancar(it / 100.0) },
        )
        val arquivos = pasta.listFiles { f -> f.name.startsWith("f") }.orEmpty().sortedBy { it.name }
            .ifEmpty { throw ErroDeConversao("O trecho escolhido não tem quadros de vídeo.") }
        Quadros(arquivos, porSegundo)
    }

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
        var quantidade = 0
            private set

        fun gravar(quadro: Bitmap) {
            require(quadro.width == Regras.LADO && quadro.height == Regras.LADO)
            quadro.getPixels(pontos, 0, Regras.LADO, 0, 0, Regras.LADO, Regras.LADO)
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

    /**
     * WebP animado de até 500 KB. Primeiro um ensaio com um pedaço do meio (até 24 quadros) na
     * qualidade 75 estima o tamanho do todo; daí sai a qualidade (e, se preciso, quantos
     * quadros pular) para caber de primeira. Se ainda passar, a próxima tentativa é calculada
     * pela sobra, no máximo mais duas vezes. `aoAvancar` vai de 0 a 1 o tempo todo.
     */
    suspend fun animada(
        contexto: Context,
        crus: QuadrosCrus,
        aoTentar: (String) -> Unit = {},
        aoAvancar: (Double) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.IO) {
        val total = crus.quantidade
        require(total > 0) { "Nenhum quadro para compactar." }
        val saida = File(contexto.cacheDir, "figurinha-animada.webp")
        val alvo = Regras.ANIMADA_MAX_BYTES * 0.9
        try {
            // 1. Ensaio (10% da etapa).
            val amostra = min(total, 24)
            val inicioDaAmostra = (total - amostra) / 2
            val tamanhoDaAmostra = codificar(
                contexto, crus, saida, qualidade = 75, pular = 1,
                filtroExtra = "trim=start_frame=$inicioDaAmostra:end_frame=${inicioDaAmostra + amostra},setpts=PTS-STARTPTS",
                quadrosNaSaida = amostra,
            ) { aoAvancar(it * 0.1) }
            aoTentar("ensaio $amostra quadros q=75 ${tamanhoDaAmostra / 1024}KB")
            var previsto = tamanhoDaAmostra.toDouble() * total / amostra

            // 2. Qualidade e quantos quadros pular para caber na primeira passada.
            var (qualidade, pular) = escolher(previsto, alvo)
            var feito = 0.1
            repeat(3) { tentativa ->
                val quadros = ceil(total / pular.toDouble()).toInt()
                val parte = if (tentativa == 0) 0.9 else (1 - feito)
                val inicioDaParte = feito
                val tamanho = codificar(contexto, crus, saida, qualidade, pular, null, quadros) { aoAvancar(inicioDaParte + it * parte * 0.95) }
                aoTentar("q=$qualidade pular=$pular ${tamanho / 1024}KB")
                if (tamanho in 1..Regras.ANIMADA_MAX_BYTES.toLong()) {
                    val webp = saida.readBytes()
                    if (!temImagem(webp)) throw ErroDeConversao("A figurinha ficou vazia: a imagem saiu do quadro. Toque em Centralizar e tente de novo.")
                    aoAvancar(1.0)
                    return@withContext webp
                }
                feito = inicioDaParte + parte * 0.95
                // Passou: corrige a previsão pelo que saiu de verdade e escolhe de novo, mais justo.
                previsto = tamanho.toDouble() * pular / fator(qualidade)
                val proxima = escolher(previsto, alvo * 0.9)
                qualidade = min(proxima.first, qualidade - 10).coerceAtLeast(15)
                pular = max(proxima.second, pular)
                if (tentativa == 1) pular = max(pular + 1, pular * 2)
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

    /** A maior qualidade que cabe; se nem a menor cabe, pula quadros na proporção que falta. */
    private fun escolher(previstoNa75: Double, alvo: Double): Pair<Int, Int> {
        FATOR_DA_QUALIDADE.firstOrNull { previstoNa75 * it.second <= alvo }?.let { return it.first to 1 }
        val pular = ceil(previstoNa75 * 0.43 / alvo).toInt().coerceIn(2, 6)
        val porQuadro = previstoNa75 / pular
        val qualidade = FATOR_DA_QUALIDADE.firstOrNull { porQuadro * it.second <= alvo }?.first ?: 15
        return qualidade to pular
    }

    /** Uma passada do libwebp_anim; devolve o tamanho do arquivo. */
    private suspend fun codificar(
        contexto: Context,
        crus: QuadrosCrus,
        saida: File,
        qualidade: Int,
        pular: Int,
        filtroExtra: String?,
        quadrosNaSaida: Int,
        aoAvancar: (Double) -> Unit,
    ): Long {
        val porSegundo = crus.porSegundo.toDouble() / pular
        val filtros = buildList {
            filtroExtra?.let(::add)
            if (pular > 1) add("select='not(mod(n\\,$pular))',setpts=N/(${numero(porSegundo)}*TB)")
        }
        Ffmpeg.executar(
            contexto,
            listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-progress", "pipe:1", "-nostats",
                "-f", "rawvideo", "-pix_fmt", "argb", "-s", "${Regras.LADO}x${Regras.LADO}",
                "-framerate", crus.porSegundo.toString(), "-i", crus.arquivo.absolutePath) +
                (if (filtros.isEmpty()) emptyList() else listOf("-vf", filtros.joinToString(","))) +
                listOf("-c:v", "libwebp_anim", "-lossless", "0", "-quality", qualidade.toString(),
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
