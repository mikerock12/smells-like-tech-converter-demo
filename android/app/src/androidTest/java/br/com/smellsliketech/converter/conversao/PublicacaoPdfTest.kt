package br.com.smellsliketech.converter.conversao

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Usa só a API dos motores, sem depender de nomes internos do PDFBox após R8. */
@RunWith(AndroidJUnit4::class)
class PublicacaoPdfTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private val temporarios = mutableListOf<File>()
    private val gerados = mutableListOf<Resultado>()
    private fun entrada(nome: String): Entrada {
        val arquivo = File.createTempFile("publicacao-pdf-", ".pdf", contexto.cacheDir).also { temporarios += it }
        i.context.assets.open("publicacao/$nome").use { de -> arquivo.outputStream().use { de.copyTo(it) } }
        return Entrada(Uri.fromFile(arquivo), nome, Tipo.PDF, arquivo.length())
    }
    private suspend fun converter(f: Ferramenta, entradas: List<Entrada>, opcoes: Opcoes = f.opcoesPadrao()) =
        Motor.executar(contexto, f, entradas, opcoes) {}.also { gerados += it }
    private fun entrada(r: Resultado) = Entrada(r.uri, r.nome, Tipo.PDF, r.bytes)

    @Test fun pdfLocalComJpegFlateTextoComprimeJuntaDivideGiraEExtrai() = runBlocking {
        val origem = entrada("foto-e-texto.pdf")
        val comprimido = converter(Ferramenta.PDF_COMPRIMIR, listOf(origem), Opcoes("pdf", compressaoPdf = CompressaoPdf.FORTE)).single()
        assertTrue("A imagem foi recomprimida", comprimido.bytes < origem.bytes * .9)
        val txt = converter(Ferramenta.PDF_PARA_DOCUMENTO, listOf(entrada(comprimido)), Opcoes("txt")).single()
        val texto = contexto.contentResolver.openInputStream(txt.uri)!!.bufferedReader().use { it.readText() }
        assertTrue(texto, texto.contains("Texto selecionavel preservado"))
        val junto = converter(Ferramenta.PDF_JUNTAR, listOf(origem, origem)).single()
        assertEquals(2, converter(Ferramenta.PDF_DIVIDIR, listOf(entrada(junto))).size)
        assertTrue(converter(Ferramenta.PDF_GIRAR, listOf(origem)).single().bytes > 0)
        assertTrue(converter(Ferramenta.PDF_PARA_IMAGENS, listOf(origem)).single().bytes > 0)
    }

    @Test fun handlerDeCriptografiaContinuaRecusandoPdfComSenha() = runBlocking {
        try {
            converter(Ferramenta.PDF_GIRAR, listOf(entrada("protegido.pdf")))
            fail("PDF protegido deveria pedir senha")
        } catch (erro: ErroDeConversao) {
            assertTrue(erro.message.orEmpty(), erro.message.orEmpty().contains("senha", true))
        }
    }

    @After fun limpar() {
        gerados.forEach { contexto.contentResolver.delete(it.uri, null, null) }
        temporarios.forEach { it.delete() }
    }
}
