package br.com.smellsliketech.converter.conversao

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Prepara exclusivamente o emulador de teste com um modelo oficial já baixado e verificado. */
@RunWith(AndroidJUnit4::class)
class PreparacaoTest {
    @Test fun instalarTinyBaixadoNoHost() {
        val instrumentacao = InstrumentationRegistry.getInstrumentation()
        val destino = ModelosWhisper.arquivo(instrumentacao.targetContext, ModeloWhisper.TINY)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand("cat /data/local/tmp/ggml-tiny.bin")).use { input ->
            destino.outputStream().use { input.copyTo(it) }
        }
        assertEquals(ModeloWhisper.TINY.bytes, destino.length())
        val digest = MessageDigest.getInstance("SHA-256")
        destino.inputStream().use { input ->
            val bloco = ByteArray(65536)
            while (true) { val n = input.read(bloco); if (n < 0) break; digest.update(bloco, 0, n) }
        }
        assertEquals(ModeloWhisper.TINY.sha256, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
