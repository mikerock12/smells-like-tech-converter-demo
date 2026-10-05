package br.com.smellsliketech.converter.conversao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import br.com.smellsliketech.converter.conversao.documentos.Bloco
import br.com.smellsliketech.converter.conversao.documentos.Documento
import br.com.smellsliketech.converter.conversao.documentos.Montador
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * PDF: o PdfBox-Android junta, divide, gira e lê o texto; o PdfRenderer do próprio Android
 * desenha as páginas (PDF para imagem, e o OCR de PDF escaneado).
 */
object Pdf {
    @Volatile private var pronto = false

    private fun preparar(context: Context) {
        if (!pronto) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pronto = true
        }
    }

    private fun abrir(context: Context, entrada: Entrada): PDDocument {
        preparar(context)
        val fluxo = context.contentResolver.openInputStream(entrada.uri) ?: error("Não deu para ler ${entrada.nome}.")
        return try {
            fluxo.use { PDDocument.load(it) }
        } catch (erro: InvalidPasswordException) {
            throw ErroDeConversao("${entrada.nome} está protegido por senha.", erro)
        } catch (erro: java.io.IOException) {
            throw ErroDeConversao("${entrada.nome} não é um PDF válido.", erro)
        }
    }

    fun juntar(context: Context, entradas: List<Entrada>, aoAvancar: (Int) -> Unit): Resultado {
        preparar(context)
        val juntador = PDFMergerUtility()
        val destino = PDDocument()
        try {
            entradas.forEachIndexed { indice, entrada ->
                abrir(context, entrada).use { juntador.appendDocument(destino, it) }
                aoAvancar(((indice + 1) * 90) / entradas.size)
            }
            val nome = Saida.nomeDeSaida(entradas.first().nome, "juntado", "pdf")
            return Saida.gravar(context, nome, "application/pdf") { destino.save(it) }
        } finally {
            destino.close()
        }
    }

    /** Uma por página, só as páginas escolhidas (num PDF) ou em blocos de N páginas. */
    fun dividir(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): List<Resultado> =
        abrir(context, entrada).use { documento ->
            val total = documento.numberOfPages
            val grupos: List<List<Int>> = when (opcoes.modoDeDividir) {
                ModoDeDividir.CADA_PAGINA -> (1..total).map { listOf(it) }
                ModoDeDividir.PAGINAS -> listOf(Intervalos.paginas(opcoes.paginas, total))
                ModoDeDividir.BLOCOS -> (1..total).chunked(opcoes.paginasPorBloco.coerceIn(1, 10_000))
            }
            grupos.mapIndexed { indice, paginas ->
                PDDocument().use { parte ->
                    paginas.forEach { parte.importPage(documento.getPage(it - 1)) }
                    val sufixo = when {
                        paginas.size == 1 -> "pagina-${paginas.first()}"
                        opcoes.modoDeDividir == ModoDeDividir.PAGINAS -> "paginas"
                        else -> "paginas-${paginas.first()}-a-${paginas.last()}"
                    }
                    Saida.gravar(context, Saida.nomeDeSaida(entrada.nome, sufixo, "pdf"), "application/pdf") { parte.save(it) }
                }.also { aoAvancar(((indice + 1) * 100) / grupos.size) }
            }
        }

    fun girar(context: Context, entrada: Entrada, graus: Int, aoAvancar: (Int) -> Unit = {}): Resultado =
        abrir(context, entrada).use { documento ->
            aoAvancar(40)
            for (pagina in documento.pages) pagina.rotation = (pagina.rotation + graus) % 360
            aoAvancar(60)
            Saida.gravar(context, Saida.nomeDeSaida(entrada.nome, "girado", "pdf"), "application/pdf") { documento.save(it) }
        }

    /** Recomprimi somente fotos; o texto, vetores, links e máscaras não são rasterizados. */
    fun comprimir(context: Context, entrada: Entrada, nivel: CompressaoPdf, aoAvancar: (Int) -> Unit): Resultado {
        val original = Saida.temporario(context, "pdf")
        val compacto = Saida.temporario(context, "pdf")
        try {
            (context.contentResolver.openInputStream(entrada.uri) ?: error("Não deu para ler ${entrada.nome}."))
                .use { fluxo -> original.outputStream().use { fluxo.copyTo(it) } }
            abrir(context, entrada.copy(uri = Uri.fromFile(original))).use { documento ->
                if (documento.isEncrypted) throw ErroDeConversao("Remova a proteção do PDF antes de comprimir.")
                if (documento.signatureDictionaries.isNotEmpty()) throw ErroDeConversao("Este PDF tem assinatura digital. Comprimir invalidaria a assinatura; use uma cópia sem assinatura.")
                // Os objetos incluem imagens dentro de formulários e recursos compartilhados.
                val streams = documento.document.objects.mapNotNull { it.`object` as? COSStream }
                val mascaras = streams.flatMap { stream -> listOfNotNull(
                    stream.getDictionaryObject(COSName.SMASK) as? COSStream,
                    stream.getDictionaryObject(COSName.MASK) as? COSStream,
                ) }.toSet()
                streams.forEachIndexed { indice, stream ->
                    if (stream !in mascaras && stream.getDictionaryObject(COSName.SUBTYPE) == COSName.IMAGE) {
                        recomprimirFoto(stream, nivel)
                    } else if (stream.getDictionaryObject(COSName.SUBTYPE) != COSName.IMAGE && stream.filters == null && stream.length > 512) {
                        PDStream(stream).addCompression()
                    }
                    aoAvancar((indice + 1) * 85 / streams.size.coerceAtLeast(1))
                }
                documento.save(compacto)
            }
            aoAvancar(95)
            // Não piora um PDF já otimizado; a comparação usa bytes reais, não metadados do remetente.
            val escolhido = if (compacto.length() < original.length()) compacto else original
            return Saida.gravarArquivo(context, Saida.nomeDeSaida(entrada.nome, "comprimido", "pdf"), "application/pdf", escolhido)
        } finally {
            original.delete()
            compacto.delete()
        }
    }

    private fun recomprimirFoto(stream: COSStream, nivel: CompressaoPdf) {
        val filtro = stream.filters
        val cor = stream.getDictionaryObject(COSName.COLORSPACE)
        if (filtro != COSName.DCT_DECODE && filtro != COSName.FLATE_DECODE) return
        if (nivel == CompressaoPdf.LEVE && filtro != COSName.DCT_DECODE) return
        // Perfis ICC, CMYK, índices, máscaras e Decode especiais ficam intactos.
        if (cor != COSName.DEVICERGB && cor != COSName.DEVICEGRAY) return
        if (stream.containsKey(COSName.SMASK) || stream.containsKey(COSName.MASK) ||
            stream.getBoolean(COSName.IMAGE_MASK, false) || stream.containsKey(COSName.DECODE)) return
        val largura = stream.getInt(COSName.WIDTH)
        val altura = stream.getInt(COSName.HEIGHT)
        if (largura <= 0 || altura <= 0 || largura.toLong() * altura !in 4096L..36_000_000L) return
        val amostra = ceil(max(largura, altura).toDouble() / nivel.ladoMaximo).toInt().coerceAtLeast(1)
        val foto = try { PDImageXObject(PDStream(stream), null).getImage(null, amostra) }
        catch (_: java.io.IOException) { return }
        val bytes = try {
            // Prints, diagramas e logos com poucas cores não devem ganhar artefatos JPEG.
            if (filtro == COSName.FLATE_DECODE && !pareceFoto(foto)) return
            ByteArrayOutputStream().use { saida ->
                if (!foto.compress(Bitmap.CompressFormat.JPEG, nivel.qualidade, saida)) return
                saida.toByteArray()
            }
        } finally { foto.recycle() }
        if (bytes.size >= stream.length * 0.9) return
        // Altera o próprio stream: não cria objetos órfãos nem duplica uma foto compartilhada.
        stream.createRawOutputStream().use { it.write(bytes) }
        stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
        stream.removeItem(COSName.DECODE_PARMS)
        stream.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
        stream.setInt(COSName.BITS_PER_COMPONENT, 8)
        stream.setInt(COSName.WIDTH, ceil(largura.toDouble() / amostra).toInt())
        stream.setInt(COSName.HEIGHT, ceil(altura.toDouble() / amostra).toInt())
    }

    private fun pareceFoto(bitmap: Bitmap): Boolean {
        val cores = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step (bitmap.height / 32).coerceAtLeast(1)) {
            for (x in 0 until bitmap.width step (bitmap.width / 32).coerceAtLeast(1)) {
                cores += bitmap.getPixel(x, y) and 0x00f8f8f8
                if (cores.size > 128) return true
            }
        }
        return false
    }

    /**
     * Cada página vira uma imagem, na resolução pedida (72 dpi = o tamanho do PDF). Páginas
     * enormes são limitadas a uns 36 milhões de pixels, para caber na memória do celular.
     */
    suspend fun paginas(context: Context, entrada: Entrada, dpi: Int = 144, aoPagina: suspend (indice: Int, total: Int, bitmap: Bitmap) -> Unit) {
        val descritor = context.contentResolver.openFileDescriptor(entrada.uri, "r") ?: error("Não deu para ler ${entrada.nome}.")
        descritor.use {
            val desenhista = try {
                PdfRenderer(it)
            } catch (erro: SecurityException) {
                throw ErroDeConversao("O PDF está protegido por senha.", erro)
            }
            desenhista.use {
                for (indice in 0 until desenhista.pageCount) {
                    desenhista.openPage(indice).use { pagina ->
                        var escala = dpi / 72f
                        val pixels = pagina.width * escala * pagina.height * escala
                        if (pixels > 36_000_000f) escala *= kotlin.math.sqrt(36_000_000f / pixels)
                        val bitmap = Bitmap.createBitmap((pagina.width * escala).toInt().coerceAtLeast(1), (pagina.height * escala).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        pagina.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        aoPagina(indice, desenhista.pageCount, bitmap)
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    suspend fun paraImagens(context: Context, entrada: Entrada, opcoes: Opcoes, aoAvancar: (Int) -> Unit): List<Resultado> {
        if (opcoes.dpi !in Listas.DPIS) throw ErroDeConversao("Resolução fora da lista.")
        val resultados = mutableListOf<Resultado>()
        paginas(context, entrada, opcoes.dpi) { indice, total, bitmap ->
            val nome = Saida.nomeDeSaida(entrada.nome, "pagina-${indice + 1}", opcoes.formato)
            resultados += Saida.gravar(context, nome, Mimes.de(opcoes.formato)) { saida -> Imagem.comprimir(bitmap, opcoes.formato, 92, saida) }
            aoAvancar(((indice + 1) * 100) / total)
        }
        return resultados
    }

    /**
     * O texto de cada página; a página sem texto (escaneada) passa pelo OCR. Vira um
     * documento com uma quebra entre as páginas.
     */
    suspend fun paraDocumento(context: Context, entrada: Entrada, aoAvancar: (Int) -> Unit): Documento {
        // O texto de cada página: a primeira metade da barra; o OCR das escaneadas, a segunda.
        val textos: List<String> = abrir(context, entrada).use { documento ->
            val total = documento.numberOfPages
            (1..total).map { pagina ->
                PDFTextStripper().apply { startPage = pagina; endPage = pagina }.getText(documento).trim()
                    .also { aoAvancar(pagina * 50 / total) }
            }
        }
        val montador = Montador()
        val precisamDeOcr = textos.indices.filter { textos[it].isBlank() }.toSet()
        val lidasPeloOcr = mutableMapOf<Int, String>()
        if (precisamDeOcr.isNotEmpty()) {
            paginas(context, entrada, 200) { indice, _, bitmap ->
                if (indice in precisamDeOcr) lidasPeloOcr[indice] = Ocr.lerBitmap(bitmap)
                aoAvancar(50 + ((indice + 1) * 50) / textos.size)
            }
        }
        textos.forEachIndexed { indice, texto ->
            if (indice > 0) montador.quebra()
            Documento.deTexto(lidasPeloOcr[indice] ?: texto).blocos.forEach { bloco ->
                (bloco as? Bloco.Paragrafo)?.let { montador.paragrafo(it.texto) }
            }
        }
        return montador.documento()
    }
}
