package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Imagem com os decodificadores do próprio Android (JPG, PNG, WEBP, GIF, BMP, ICO, HEIC e,
 * no Android 12+, AVIF), e o FFmpeg para o resto (TIFF). JPG, PNG e WEBP o Android grava;
 * AVIF, TIFF, BMP e GIF saem pelo FFmpeg; o ICO é montado aqui.
 */
object Imagem {
    /** Maior lado aceito na memória: 8192 px (uma foto de 48 MP chega reduzida a isto). */
    private const val LADO_MAXIMO = 8192

    suspend fun carregar(context: Context, entrada: Entrada, larguraMaxima: Int = 0): Bitmap = withContext(Dispatchers.IO) {
        try {
            decodificar(context, entrada, larguraMaxima)
        } catch (erro: Exception) {
            // O Android não abre TIFF (nem AVIF antes do 12): o FFmpeg converte para PNG antes.
            if (!Ffmpeg.disponivel(context)) throw ErroDeConversao("O Android não abre ${entrada.nome}.", erro)
            Ffmpeg.comCopia(context, entrada) { copia ->
                val png = Saida.temporario(context, "png")
                try {
                    Ffmpeg.executar(context, listOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-i", copia.absolutePath, "-frames:v", "1", "-update", "1", png.absolutePath), null)
                    val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(png.absolutePath, limites)
                    val alvo = if (larguraMaxima > 0) minOf(larguraMaxima, LADO_MAXIMO) else LADO_MAXIMO
                    var amostra = 1
                    while (max(limites.outWidth, limites.outHeight) / (amostra * 2) >= alvo) amostra *= 2
                    BitmapFactory.decodeFile(png.absolutePath, BitmapFactory.Options().apply { inSampleSize = amostra })
                        ?.let { reduzir(it, larguraMaxima) }
                        ?: throw ErroDeConversao("Não deu para abrir ${entrada.nome}.")
                } finally {
                    png.delete()
                }
            }
        }
    }

    private fun decodificar(context: Context, entrada: Entrada, larguraMaxima: Int): Bitmap {
        val fonte = ImageDecoder.createSource(context.contentResolver, entrada.uri)
        return ImageDecoder.decodeBitmap(fonte) { decodificador, info, _ ->
            decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largura = info.size.width
            val altura = info.size.height
            var escala = 1f
            if (larguraMaxima in 1 until largura) escala = larguraMaxima / largura.toFloat()
            if (max(largura, altura) * escala > LADO_MAXIMO) escala = LADO_MAXIMO / max(largura, altura).toFloat()
            if (escala < 1f) decodificador.setTargetSize((largura * escala).roundToInt().coerceAtLeast(1), (altura * escala).roundToInt().coerceAtLeast(1))
        }
    }

    private fun reduzir(bitmap: Bitmap, larguraMaxima: Int): Bitmap {
        if (larguraMaxima <= 0 || bitmap.width <= larguraMaxima) return bitmap
        val altura = (bitmap.height * larguraMaxima / bitmap.width.toFloat()).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, larguraMaxima, altura, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Girar, espelhar e mudar a proporção, nessa ordem (como o desktop). */
    fun transformar(original: Bitmap, opcoes: Opcoes, fundoTransparente: Boolean): Bitmap {
        var imagem = original
        if (opcoes.graus != 0 || opcoes.espelharHorizontal || opcoes.espelharVertical) {
            val matriz = Matrix().apply {
                postRotate(opcoes.graus.toFloat())
                postScale(if (opcoes.espelharHorizontal) -1f else 1f, if (opcoes.espelharVertical) -1f else 1f)
            }
            imagem = Bitmap.createBitmap(imagem, 0, 0, imagem.width, imagem.height, matriz, true)
        }
        val razao = opcoes.proporcao.razao ?: return imagem
        return encaixar(imagem, razao.first.toFloat() / razao.second, opcoes.encaixe, fundoTransparente)
    }

    fun encaixar(imagem: Bitmap, razao: Float, encaixe: Encaixe, fundoTransparente: Boolean): Bitmap {
        val largura = imagem.width
        val altura = imagem.height
        val atual = largura.toFloat() / altura
        val (alvoL, alvoA) = when (encaixe) {
            // Recortar cabe dentro da imagem; barras e fundo desfocado a envolvem.
            Encaixe.RECORTAR -> if (atual > razao) (altura * razao).roundToInt() to altura else largura to (largura / razao).roundToInt()
            Encaixe.AJUSTAR, Encaixe.DESFOCAR -> if (atual > razao) largura to (largura / razao).roundToInt() else (altura * razao).roundToInt() to altura
            Encaixe.ESTICAR -> largura to (largura / razao).roundToInt()
        }
        val saida = Bitmap.createBitmap(alvoL.coerceAtLeast(1), alvoA.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(saida)
        val pincel = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        when (encaixe) {
            Encaixe.RECORTAR -> {
                val x = (largura - alvoL) / 2
                val y = (altura - alvoA) / 2
                canvas.drawBitmap(imagem, Rect(x, y, x + alvoL, y + alvoA), Rect(0, 0, alvoL, alvoA), pincel)
            }
            Encaixe.ESTICAR -> canvas.drawBitmap(imagem, null, Rect(0, 0, alvoL, alvoA), pincel)
            Encaixe.AJUSTAR, Encaixe.DESFOCAR -> {
                if (encaixe == Encaixe.DESFOCAR) {
                    // Desfoque barato e bonito: reduz a 1/24 e amplia de novo, cobrindo tudo, um pouco mais escuro.
                    val escala = max(alvoL / largura.toFloat(), alvoA / altura.toFloat())
                    val pequeno = Bitmap.createScaledBitmap(imagem, (largura / 24).coerceAtLeast(1), (altura / 24).coerceAtLeast(1), true)
                    val l = largura * escala
                    val a = altura * escala
                    canvas.drawBitmap(pequeno, null, RectF((alvoL - l) / 2, (alvoA - a) / 2, (alvoL + l) / 2, (alvoA + a) / 2), pincel)
                    canvas.drawColor(Color.argb(60, 0, 0, 0))
                    pequeno.recycle()
                } else if (!fundoTransparente) {
                    canvas.drawColor(Color.WHITE)
                }
                canvas.drawBitmap(imagem, ((alvoL - largura) / 2).toFloat(), ((alvoA - altura) / 2).toFloat(), pincel)
            }
        }
        return saida
    }

    /** Grava nos formatos do Android: JPG, PNG, WEBP. */
    @Suppress("DEPRECATION")
    fun comprimir(bitmap: Bitmap, formato: String, qualidade: Int, saida: OutputStream) {
        val final = if (formato == "jpg" && bitmap.hasAlpha()) semTransparencia(bitmap) else bitmap
        val compressao = when (formato) {
            "jpg" -> Bitmap.CompressFormat.JPEG
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> throw ErroDeConversao("O Android não grava $formato.")
        }
        if (!final.compress(compressao, if (formato == "png") 100 else qualidade.coerceIn(1, 100), saida)) throw ErroDeConversao("O Android não conseguiu gravar a imagem.")
    }

    fun semTransparencia(bitmap: Bitmap): Bitmap {
        val opaco = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaco).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return opaco
    }

    private fun aceitaTransparencia(formato: String) = formato in setOf("png", "webp", "avif", "tiff", "gif", "ico")

    /** Converter, comprimir, redimensionar, mudar a proporção e girar: tudo passa por aqui. */
    suspend fun converter(context: Context, entrada: Entrada, opcoes: Opcoes, sufixo: String, aoAvancar: (Int) -> Unit = {}): Resultado {
        val formato = opcoes.formato
        aoAvancar(5)
        val original = carregar(context, entrada, opcoes.larguraMaxima)
        aoAvancar(40)
        val imagem = transformar(original, opcoes, aceitaTransparencia(formato))
        aoAvancar(60)
        val nome = Saida.nomeDeSaida(entrada.nome, sufixo.ifBlank { if (entrada.extensao == formato || (entrada.extensao == "jpeg" && formato == "jpg")) "convertido" else "" }, formato)
        return when (formato) {
            "jpg", "png", "webp" -> Saida.gravar(context, nome, Mimes.de(formato)) { comprimir(imagem, formato, opcoes.qualidade, it) }
            "ico" -> Saida.gravar(context, nome, Mimes.de("ico")) { it.write(icone(imagem)) }
            else -> pelaFfmpeg(context, imagem, nome, formato, opcoes.qualidade)
        }
    }

    /** Um ícone quadrado (o que sobra fica transparente) em todos os tamanhos até o da imagem. */
    fun icone(imagem: Bitmap): ByteArray {
        val quadrado = if (imagem.width == imagem.height) imagem else encaixar(imagem, 1f, Encaixe.AJUSTAR, fundoTransparente = true)
        val tamanhos = Ico.TAMANHOS.filter { it <= max(quadrado.width, 16) }
        // Reduz em duas etapas (até 256, depois cada tamanho): de 4000 px direto para 16 serrilha.
        val base = if (quadrado.width > 256) Bitmap.createScaledBitmap(quadrado, 256, 256, true) else quadrado
        return Ico.montar(
            tamanhos.map { lado ->
                val reduzido = if (lado == base.width) base else Bitmap.createScaledBitmap(base, lado, lado, true)
                lado to ByteArrayOutputStream().also { reduzido.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            },
        )
    }

    private suspend fun pelaFfmpeg(context: Context, imagem: Bitmap, nome: String, formato: String, qualidade: Int): Resultado {
        val png = Saida.temporario(context, "png")
        val saida = Saida.temporario(context, formato)
        try {
            // BMP e AVIF (yuv420) não guardam transparência: fundo branco, como no JPG.
            val base = if (formato == "bmp" || formato == "avif") semTransparencia(imagem) else imagem
            withContext(Dispatchers.IO) { png.outputStream().use { base.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            Ffmpeg.executar(context, Comandos.imagem(png.absolutePath, saida.absolutePath, formato, qualidade), null)
            return withContext(Dispatchers.IO) { Saida.gravarArquivo(context, nome, Mimes.de(formato), saida) }
        } finally {
            png.delete()
            saida.delete()
        }
    }

    /** Uma página por imagem, do tamanho da imagem (até 2480 px de largura, A4 a 300 dpi). */
    suspend fun paraPdf(context: Context, entradas: List<Entrada>, aoAvancar: (Int) -> Unit): Resultado {
        val documento = PdfDocument()
        try {
            entradas.forEachIndexed { indice, entrada ->
                val bitmap = carregar(context, entrada, 2480)
                val pagina = documento.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, indice + 1).create())
                pagina.canvas.drawColor(Color.WHITE)
                pagina.canvas.drawBitmap(bitmap, 0f, 0f, null)
                documento.finishPage(pagina)
                bitmap.recycle()
                aoAvancar(((indice + 1) * 100) / entradas.size)
            }
            val nome = Saida.nomeDeSaida(entradas.first().nome, if (entradas.size > 1) "e-mais-${entradas.size - 1}" else "", "pdf")
            return withContext(Dispatchers.IO) { Saida.gravar(context, nome, "application/pdf") { documento.writeTo(it) } }
        } finally {
            documento.close()
        }
    }
}
