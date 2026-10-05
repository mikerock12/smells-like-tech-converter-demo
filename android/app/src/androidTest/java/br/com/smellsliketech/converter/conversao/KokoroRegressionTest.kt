package br.com.smellsliketech.converter.conversao

import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A foto de reprodução fica só no emulador; nunca integra fontes ou assets públicos. */
@RunWith(AndroidJUnit4::class)
class KokoroRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun validar(resultado: Resultado) {
        try {
            val bytes = context.contentResolver.openInputStream(resultado.uri)!!.use { it.readBytes() }
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertTrue(bytes.size > 48000)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue((44 until bytes.size - 1 step 2).any { buffer.getShort(it) != 0.toShort() })
        } finally { context.contentResolver.delete(resultado.uri, null, null) }
    }
    @Test fun imagemInformadaPeloUsuarioSemRede() = runBlocking {
        val caminho = InstrumentationRegistry.getArguments().getString("imagemReproducao")
        assumeTrue("Foto privada fornecida somente no teste local", caminho != null)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        val imagem = File(caminho!!)
        assertTrue(imagem.isFile)
        val entrada = Entrada(Uri.fromFile(imagem), imagem.name, Tipo.IMAGEM, imagem.length())
        val texto = Ocr.textoDaImagem(context, entrada)
        Log.i("KokoroRepro", "OCR: $texto")
        assertTrue("OCR sem texto", texto.length > 30)
        val audio = Narracao.narrar(context, texto, "imagem-reproducao", Opcoes("wav")) {}
        context.contentResolver.openInputStream(audio.uri)!!.use { input ->
            File(context.getExternalFilesDir(null), "kokoro-imagem-041.wav").outputStream().use { input.copyTo(it) }
        }
        validar(audio)
    }
    @Test fun avisoMaiusculoComDataEQuebras() = runBlocking {
        val texto = "ATENÇÃO FAMILIARES\nEM FUNÇÃO DA ORGANIZAÇÃO DA ESCOLA PARA AS ELEIÇÕES, COMUNICAMOS QUE\nAMANHÃ (SEXTA, DIA 02/10),\nOS TURNOS DA TARDE E NOITE NÃO TERÃO AULA."
        for (voz in Kokoro.vozes.keys) validar(Narracao.narrar(context, texto, "aviso-regressao", Opcoes("wav", vozDaNarracao = voz)) {})
    }
}
