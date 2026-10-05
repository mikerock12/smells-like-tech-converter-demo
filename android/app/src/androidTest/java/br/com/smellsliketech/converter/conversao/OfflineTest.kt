package br.com.smellsliketech.converter.conversao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.documentos.Documento
import br.com.smellsliketech.converter.conversao.documentos.EscritorDePdf
import br.com.smellsliketech.converter.conversao.documentos.Escritores
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlin.random.Random

/** Arquivos artificiais próprios; nunca utiliza ou apaga arquivos do usuário. */
@RunWith(AndroidJUnit4::class)
class OfflineTest {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val gerados = mutableListOf<Resultado>()
    private val temporarios = mutableListOf<File>()
    private fun arquivo(extensao: String) = File.createTempFile("teste-offline-", ".$extensao", contexto.cacheDir).also { temporarios += it }
    private fun entrada(arquivo: File, tipo: Tipo) = Entrada(Uri.fromFile(arquivo), arquivo.name, tipo, arquivo.length())
    private fun bytes(resultado: Resultado) = contexto.contentResolver.openInputStream(resultado.uri)!!.use { it.readBytes() }
    private fun converter(ferramenta: Ferramenta, entradas: List<Entrada>, opcoes: Opcoes = ferramenta.opcoesPadrao()): List<Resultado> = runBlocking {
        withTimeout(180_000) { Motor.executar(contexto, ferramenta, entradas, opcoes) {} }
            .also { resultados -> gerados += resultados; resultados.forEach { assertTrue(it.nome, it.bytes > 0) } }
    }
    private fun desenho(): Bitmap = Bitmap.createBitmap(1200, 450, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawText("CONVERTER SEM INTERNET", 50f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f })
    }
    private fun imagem(): File = arquivo("png").also { saida -> desenho().let { bitmap ->
        try { saida.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
    } }
    private fun pdfTexto(): File = arquivo("pdf").also { saida -> saida.outputStream().use { EscritorDePdf.escrever(Documento.deTexto("Converter sem internet. Texto selecionavel."), it) } }
    private fun video(): File = arquivo("mp4").also { saida ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("video-com-audio.mp4").use { input ->
            saida.outputStream().use { input.copyTo(it) }
        }
    }

    @Before fun redeRealmenteDesligada() {
        assertEquals("Ative modo avião antes de executar estes testes", 1, Settings.Global.getInt(contexto.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val conexao = (URL("https://converter.smellsliketech.com.br/api/conta?verificacao-offline=android").openConnection() as HttpURLConnection).apply { connectTimeout = 2500; readTimeout = 2500 }
        val conectou = try { runCatching { conexao.responseCode; true }.getOrDefault(false) } finally { conexao.disconnect() }
        assertFalse("A prova de rede ainda conseguiu acessar a internet", conectou)
        PDFBoxResourceLoader.init(contexto)
    }
    @After fun limparSomenteArquivosDoTeste() {
        gerados.forEach { contexto.contentResolver.delete(it.uri, null, null) }
        temporarios.forEach { it.delete() }
    }

    @Test fun imagemConversaoIconeEPdf() {
        val entrada = entrada(imagem(), Tipo.IMAGEM)
        for ((ferramenta, opcoes) in listOf(
            Ferramenta.IMAGEM_CONVERTER to Opcoes("webp"), Ferramenta.IMAGEM_CONVERTER to Opcoes("avif"),
            Ferramenta.IMAGEM_COMPRIMIR to Opcoes("jpg", qualidade = 60),
            Ferramenta.IMAGEM_REDIMENSIONAR to Opcoes("png", larguraMaxima = 320),
            Ferramenta.IMAGEM_GIRAR to Opcoes("png", graus = 90),
            Ferramenta.IMAGEM_RECORTAR to Opcoes("png", proporcao = Proporcao.QUADRADA),
            Ferramenta.IMAGEM_ICONE to Opcoes("ico"), Ferramenta.IMAGENS_PARA_PDF to Opcoes("pdf"),
        )) converter(ferramenta, listOf(entrada), opcoes)
    }
    @Test fun ocrPngETiff() {
        val entrada = entrada(imagem(), Tipo.IMAGEM)
        val tiff = converter(Ferramenta.IMAGEM_CONVERTER, listOf(entrada), Opcoes("tiff")).single()
        for (foto in listOf(entrada, Entrada(tiff.uri, tiff.nome, Tipo.IMAGEM, tiff.bytes))) {
            val texto = bytes(converter(Ferramenta.IMAGEM_OCR, listOf(foto)).single()).toString(Charsets.UTF_8)
            assertTrue(texto, texto.contains("CONVERTER", true))
        }
    }
    @Test fun pdfJuntarDividirGirarExtrair() {
        val origem = entrada(pdfTexto(), Tipo.PDF)
        val junto = converter(Ferramenta.PDF_JUNTAR, listOf(origem, origem)).single()
        val entradaJunta = Entrada(junto.uri, junto.nome, Tipo.PDF, junto.bytes)
        assertEquals(2, converter(Ferramenta.PDF_DIVIDIR, listOf(entradaJunta)).size)
        converter(Ferramenta.PDF_GIRAR, listOf(origem))
        converter(Ferramenta.PDF_PARA_IMAGENS, listOf(origem))
        val texto = bytes(converter(Ferramenta.PDF_PARA_DOCUMENTO, listOf(origem), Opcoes("txt")).single()).toString(Charsets.UTF_8)
        assertTrue(texto, texto.contains("selecionavel"))
    }
    @Test fun pdfEscaneadoUsaOcrEmbutido() {
        val imagem = entrada(imagem(), Tipo.IMAGEM)
        val pdf = converter(Ferramenta.IMAGENS_PARA_PDF, listOf(imagem)).single()
        val texto = bytes(converter(Ferramenta.PDF_PARA_DOCUMENTO, listOf(Entrada(pdf.uri, pdf.nome, Tipo.PDF, pdf.bytes)), Opcoes("txt")).single()).toString(Charsets.UTF_8)
        assertTrue(texto, texto.contains("CONVERTER", true))
    }
    @Test fun comprimirPdfPreservaTextoERealmenteReduz() {
        val origem = arquivo("pdf")
        val foto = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        foto.setHasAlpha(false)
        val random = Random(42)
        val pixels = IntArray(2400 * 1600) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        foto.setPixels(pixels, 0, 2400, 0, 0, 2400, 1600)
        try {
            PDDocument().use { doc ->
                val pagina = PDPage(); doc.addPage(pagina)
                PDPageContentStream(doc, pagina).use { conteudo ->
                    conteudo.drawImage(LosslessFactory.createFromImage(doc, foto), 20f, 100f, 550f, 360f)
                    conteudo.beginText(); conteudo.setFont(PDType1Font.HELVETICA, 12f); conteudo.newLineAtOffset(20f, 750f)
                    conteudo.showText("Texto selecionavel permanece no PDF"); conteudo.endText()
                }
                doc.save(origem)
            }
        } finally { foto.recycle() }
        val resultado = converter(Ferramenta.PDF_COMPRIMIR, listOf(entrada(origem, Tipo.PDF)), Opcoes("pdf", compressaoPdf = CompressaoPdf.FORTE)).single()
        assertTrue("${origem.length()} → ${resultado.bytes}", resultado.bytes < origem.length())
        PDDocument.load(bytes(resultado)).use { assertTrue(PDFTextStripper().getText(it).contains("Texto selecionavel permanece")) }
    }
    @Test fun pdfJaCompactoNuncaAumenta() {
        val pdf = pdfTexto()
        val resultado = converter(Ferramenta.PDF_COMPRIMIR, listOf(entrada(pdf, Tipo.PDF))).single()
        assertTrue(resultado.bytes <= pdf.length())
    }
    @Test fun videoParaAudioGifQuadrosESemAudio() {
        val origem = entrada(video(), Tipo.VIDEO)
        val audio = converter(Ferramenta.VIDEO_PARA_AUDIO, listOf(origem)).single()
        val copia = arquivo("mp3").apply { writeBytes(bytes(audio)) }
        runBlocking { assertTrue(Ffmpeg.sondar(contexto, copia).temAudio); assertFalse(Ffmpeg.sondar(contexto, copia).temVideo) }
        converter(Ferramenta.VIDEO_GIF, listOf(origem))
        assertTrue(converter(Ferramenta.VIDEO_QUADROS, listOf(origem)).isNotEmpty())
        val silencioso = converter(Ferramenta.VIDEO_SEM_AUDIO, listOf(origem)).single()
        val mp4 = arquivo("mp4").apply { writeBytes(bytes(silencioso)) }
        runBlocking { assertFalse(Ffmpeg.sondar(contexto, mp4).temAudio) }
        converter(Ferramenta.VIDEO_COMPRIMIR, listOf(origem), Opcoes("mp4", alturaDoVideo = 360))
    }
    @Test fun gifAnimadoViraVideoComRepeticoes() {
        val origem = entrada(video(), Tipo.VIDEO)
        val gif = converter(Ferramenta.VIDEO_GIF, listOf(origem)).single()
        val resultado = converter(Ferramenta.GIF_PARA_VIDEO, listOf(Entrada(gif.uri, gif.nome, Tipo.IMAGEM, gif.bytes)), Opcoes("mp4", repeticoesDoGif = 2)).single()
        val mp4 = arquivo("mp4").apply { writeBytes(bytes(resultado)) }
        runBlocking { val info = Ffmpeg.sondar(contexto, mp4); assertTrue(info.temVideo); assertTrue(info.duracaoSegundos!! > 3.0) }
    }
    @Test fun audioEditarVolumeVelocidadeEFades() {
        val origem = entrada(video(), Tipo.VIDEO)
        val resultado = converter(Ferramenta.VIDEO_PARA_AUDIO, listOf(origem), Opcoes("wav")).single()
        val audio = Entrada(resultado.uri, resultado.nome, Tipo.AUDIO, resultado.bytes)
        converter(Ferramenta.AUDIO_CONVERTER, listOf(audio), Opcoes("opus"))
        val editado = converter(Ferramenta.AUDIO_EDITAR, listOf(audio), Opcoes("mp3", velocidade = 2.0, volume = 0.5, fadeEntrada = 0.2, fadeSaida = 0.2)).single()
        val mp3 = arquivo("mp3").apply { writeBytes(bytes(editado)) }
        runBlocking { assertTrue(Ffmpeg.sondar(contexto, mp3).duracaoSegundos!! < 1.3) }
    }
    @Test fun documentosELegendas() {
        val docx = arquivo("docx").apply { writeBytes(Escritores.docx(Documento.deTexto("Documento sem internet"))) }
        for (formato in listOf("pdf", "txt", "md", "html")) converter(Ferramenta.DOCUMENTO_CONVERTER, listOf(entrada(docx, Tipo.DOCUMENTO)), Opcoes(formato))
        val srt = arquivo("srt").apply { writeText("1\n00:00:01,000 --> 00:00:03,000\nConverter sem internet\n\n") }
        val vtt = bytes(converter(Ferramenta.LEGENDA_CONVERTER, listOf(entrada(srt, Tipo.LEGENDA)), Opcoes("vtt", atrasoLegendaSegundos = 0.5)).single()).toString(Charsets.UTF_8)
        assertTrue(vtt, "00:00:01.500" in vtt)
    }
    @Test fun narracaoDeTextoPdfImagemEDocx() {
        val texto = arquivo("txt").apply { writeText("Converter arquivos sem internet.") }
        val docx = arquivo("docx").apply { writeBytes(Escritores.docx(Documento.deTexto("Documento sem internet"))) }
        for ((ferramenta, origem) in listOf(
            Ferramenta.TEXTO_NARRAR to entrada(texto, Tipo.TEXTO), Ferramenta.PDF_NARRAR to entrada(pdfTexto(), Tipo.PDF),
            Ferramenta.IMAGEM_NARRAR to entrada(imagem(), Tipo.IMAGEM), Ferramenta.DOCUMENTO_NARRAR to entrada(docx, Tipo.DOCUMENTO),
        )) converter(ferramenta, listOf(origem), Opcoes("mp3"))
    }
    @Test fun kokoroTresVozesEmPortuguesSemRede() {
        val origem = entrada(arquivo("txt").apply { writeText("Olá. Esta é uma narração local em português do Brasil.") }, Tipo.TEXTO)
        val hashes = mutableSetOf<String>()
        for (voz in listOf("pf_dora", "pm_alex", "pm_santa")) {
            val audio = bytes(converter(Ferramenta.TEXTO_NARRAR, listOf(origem), Opcoes("wav", vozDaNarracao = voz)).single())
            assertEquals("RIFF", String(audio, 0, 4, Charsets.US_ASCII))
            assertEquals(24000, java.nio.ByteBuffer.wrap(audio).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24))
            assertTrue(audio.size > 48000)
            val pcm = java.nio.ByteBuffer.wrap(audio).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertTrue((44 until audio.size - 1 step 2).any { pcm.getShort(it) != 0.toShort() })
            hashes += MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
        }
        assertEquals(3, hashes.size)
    }
    @Test fun transcricaoLocalESeisFormatosDeSaida() = runBlocking {
        val modelo = ModelosWhisper.arquivo(contexto, ModeloWhisper.TINY)
        assertTrue("Instale Tiny antes de desligar a internet: ${modelo.absolutePath}", modelo.isFile)
        val hash = modelo.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bloco = ByteArray(65536)
            while (true) { val n = input.read(bloco); if (n < 0) break; digest.update(bloco, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(ModeloWhisper.TINY.sha256, hash)
        val audio = withTimeout(60_000) { Narracao.narrar(contexto, "Teste de conversão de arquivos no celular. Esta narração funciona sem internet. Vamos transformar este áudio em texto.", "teste-offline", Opcoes("wav")) {} }.also { gerados += it }
        val transcricao = withTimeout(180_000) { Midia.transcrever(contexto, Entrada(audio.uri, audio.nome, Tipo.AUDIO, audio.bytes), Opcoes("txt", modelo = "tiny"), {}) }
        assertTrue(transcricao.texto, transcricao.texto.length > 15)
        for (formato in Transcricao.FORMATOS) {
            val resultado = Midia.salvar(contexto, transcricao, formato).also { gerados += it }
            assertTrue(formato, resultado.bytes > 0)
        }
    }
}
