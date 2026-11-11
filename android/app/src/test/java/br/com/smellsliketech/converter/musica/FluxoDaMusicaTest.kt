package br.com.smellsliketech.converter.musica

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin

class FluxoDaMusicaTest {
    @Test fun filtrosSeparamTempoDeTomELimitamDuracaoExata() {
        val quadros = 10_306_319L
        val subir = TransformacaoDaMusica.filtroEmFluxo(48000, quadros, 1.0, 12.0, true)
        assertEquals("asetrate=96000,aresample=48000,atempo=0.5,apad=whole_len=$quadros,atrim=end_sample=$quadros", subir)
        val descer = TransformacaoDaMusica.filtroEmFluxo(48000, quadros, 0.25, -12.0, false)
        assertEquals("asetrate=6000,aresample=48000,atempo=2.0,apad=whole_len=41225276,atrim=end_sample=41225276", descer)
    }

    @Test fun masterEmBlocosPreservaLimiteEMedidasEntreBlocos() {
        val taxa = 48000
        val sinal = FloatArray(taxa) { (0.8 * sin(2 * PI * 440 * it / taxa)).toFloat() }
        val memoria = FonteNaMemoria(arrayOf(sinal, sinal))
        val maximoPorLeitura = 8192 + 11
        val blocos = object : FonteDeAudio by memoria {
            override fun copiar(canal: Int, inicio: Long, n: Int, destino: FloatArray) {
                assertTrue(n <= maximoPorLeitura)
                memoria.copiar(canal, inicio, n, destino)
            }
        }
        val arquivo = Files.createTempFile("master-fluxo", ".wav").toFile()
        try {
            val esperado = MasterDaMusica.masterizar(sinal, sinal, taxa, "streaming")
            val preset = MasterDaMusica.PRESETS.first { it.id == "streaming" }
            val ganho = EscritorDeWav(arquivo, 2, taxa).use { MasterDaMusica.limitar(blocos, it, taxa, preset) {} }
            assertEquals(esperado.ganhoDb, ganho, 1e-9)
            FonteMapeada(arquivo).use { fonte ->
                assertEquals(esperado.depois.lufs, MasterDaMusica.medir(fonte, taxa) {}.lufs, 1e-9)
                val saida = ByteArrayOutputStream()
                MasterDaMusica.escreverWav(saida, fonte, taxa, 24, verificar = {})
                assertArrayEquals(MasterDaMusica.codificarWav(listOf(esperado.esq, esperado.dir), taxa, 24), saida.toByteArray())
            }
        } finally {
            arquivo.delete()
        }
    }
}
