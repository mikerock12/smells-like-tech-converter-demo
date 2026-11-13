package br.com.smellsliketech.converter.musica

import android.os.SystemClock
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NucleoNativoNoAparelhoTest {
    private val taxa = 48_000

    @Test
    fun abiEMixagemComFonteUtf8() {
        assertTrue(NucleoNativo.disponivel)
        assertEquals(NucleoNativo.ABI, NucleoNativo.versao())
        assertEquals(NucleoNativo.CAPACIDADES, NucleoNativo.capacidades())
        assertEquals(1 shl NucleoNativo.OBOE, NucleoNativo.backends())

        NucleoNativo.Mixer(taxa).use { mixer ->
            val fonte = NucleoNativo.plano(9)
            fonte.position(Float.SIZE_BYTES)
            repeat(8) { fonte.putFloat((it + 1) * Float.SIZE_BYTES, 0.25f) }
            mixer.fonte("gravação 🎵", fonte, null, 8)
            mixer.projeto(projeto(0))
            val e = NucleoNativo.plano(8)
            val d = NucleoNativo.plano(8)
            mixer.renderizar(0, 8, e, d)
            repeat(8) {
                assertEquals(0.25f, e.float, 1e-6f)
                assertEquals(0f, d.float, 1e-6f)
            }
            assertEquals(0.25f, mixer.medidores("master")[0], 1e-6f)
        }
    }

    @Test
    fun stftComplexaReconstruiCanaisIndependentes() {
        val n = 151
        val e = NucleoNativo.plano(n)
        val d = NucleoNativo.plano(n)
        val se = NucleoNativo.plano(n)
        val sd = NucleoNativo.plano(n)
        repeat(n) { i ->
            e.putFloat(i * Float.SIZE_BYTES, sin(i * 0.17).toFloat())
            d.putFloat(i * Float.SIZE_BYTES, sin(i * 0.27).toFloat())
        }
        NucleoNativo.StftComplexa(32, 11, n).use { stft ->
            val espectro = NucleoNativo.plano(stft.tamanho)
            val mascara = NucleoNativo.plano(stft.tamanho)
            for (i in 0 until stft.tamanho step 4) {
                mascara.putFloat(i * Float.SIZE_BYTES, 0.5f)
                mascara.putFloat((i + 2) * Float.SIZE_BYTES, -0.25f)
            }
            stft.analisar(e, d, espectro)
            stft.sintetizar(espectro, mascara, se, sd)
            repeat(n) { i ->
                assertEquals(e.float * 0.5f, se.float, 1e-6f)
                assertEquals(d.float * -0.25f, sd.float, 1e-6f)
            }
        }
    }

    @Test
    fun restauracaoEmBlocosComGanhoParaComparacao() {
        val n = 2048
        val e = NucleoNativo.plano(n)
        val original = FloatArray(n) { (0.18 * sin(2 * PI * 220 * it / taxa) + 0.06 * sin(2 * PI * 9000 * it / taxa)).toFloat() }
        e.asFloatBuffer().put(original)
        NucleoNativo.Restaurador(taxa, 0, 0f).use { restaurador ->
            restaurador.processar(n, e)
            repeat(n) { assertEquals(original[it], e.getFloat(it * Float.SIZE_BYTES), 0f) }
            assertEquals(1f, restaurador.ganho(), 1e-6f)
        }
        NucleoNativo.Restaurador(taxa, 0, 100f).use { restaurador ->
            restaurador.processar(n, e)
            val ganho = restaurador.ganho()
            assertTrue(ganho in 0.25f..4f)
            assertTrue((0 until n).any { abs(original[it] - e.getFloat(it * Float.SIZE_BYTES) * ganho) > 0.001f })
            val energiaOriginal = original.sumOf { (it * it).toDouble() }
            val energiaRestaurada = (0 until n).sumOf {
                val nivelado = e.getFloat(it * Float.SIZE_BYTES) * ganho
                (nivelado * nivelado).toDouble()
            }
            assertEquals(energiaOriginal, energiaRestaurada, energiaOriginal * 0.001)
        }
    }

    @Test
    fun separacaoRefinadaSomaAMistura() {
        val comprimento = 512
        val fft = 128
        val passo = 32
        val porTrecho = 32
        val bandas = 32
        val tamanho = 2 * porTrecho * bandas
        val capacidade = porTrecho * passo + fft
        val mistura = FloatArray(comprimento) { (0.2 * sin(0.19 * it) + 0.1 * sin(2.1 * it)).toFloat() }
        NucleoNativo.Separador(comprimento.toLong(), fft, passo, porTrecho, bandas, 2, 2, true).use { sep ->
            assertEquals(1, sep.trechos())
            val janela = sep.janela(0)
            assertEquals(-fft.toLong(), janela.inicio)
            val fonte = NucleoNativo.plano(janela.amostras)
            repeat(janela.amostras) { i ->
                fonte.putFloat(i * Float.SIZE_BYTES, mistura.getOrElse((janela.inicio + i).toInt()) { 0f })
            }
            val magnitude = NucleoNativo.plano(tamanho)
            sep.analisar(0, janela.amostras, fonte, fonte, magnitude, tamanho)
            assertTrue((0 until tamanho).any { magnitude.getFloat(it * Float.SIZE_BYTES) > 0.1f })
            val estimativas = NucleoNativo.plano(2 * tamanho)
            repeat(2 * tamanho) { estimativas.putFloat(it * Float.SIZE_BYTES, if (it < tamanho) 1f else 2f) }

            val soma = FloatArray(comprimento)
            repeat(2) { i ->
                val e = NucleoNativo.plano(capacidade)
                val d = NucleoNativo.plano(capacidade)
                val trecho = sep.sintetizar(0, i, estimativas, 2 * tamanho, intArrayOf(i), e, d, capacidade)
                assertEquals(0L, trecho.inicio)
                assertEquals(comprimento, trecho.amostras)
                repeat(trecho.amostras) { j ->
                    val esquerda = e.float
                    assertEquals(esquerda, d.float, 1e-6f)
                    soma[j] += esquerda
                }
            }
            repeat(comprimento) { assertEquals(mistura[it], soma[it], 2e-6f) }
        }
    }

    @Test
    fun oboeMantemCallbacksETransporteEmSilencio() {
        NucleoNativo.Mixer(taxa).use { mixer ->
            val fonte = NucleoNativo.plano(taxa)
            mixer.fonte("gravação 🎵", fonte, null, taxa)
            mixer.projeto(projeto(-60, taxa))
            mixer.abrirFluxo().use { fluxo ->
                assertEquals(NucleoNativo.OBOE, fluxo.info().backend)
                assertEquals(taxa, fluxo.info().taxa)
                assertEquals(2, fluxo.info().canaisDaSaida)
                assertTrue(fluxo.info().bloco > 0)
                mixer.tocar(0)
                fluxo.iniciar()
                SystemClock.sleep(1100)
                val info = fluxo.info()
                val stats = fluxo.estatisticas()
                val posicao = mixer.posicao()
                Log.i("NucleoNativoTeste", "$info $stats posição=$posicao")
                assertTrue(info.rodando)
                assertFalse(info.perdido)
                assertTrue(stats.callbacks > 0)
                assertTrue(stats.quadros >= taxa / 2)
                assertTrue(posicao >= taxa / 2)
                assertEquals(0L, stats.falhas)
                assertEquals(0L, stats.transbordos)
                fluxo.parar()
                assertFalse(fluxo.info().rodando)
            }
        }
    }

    @Test
    fun motorDoAppTocaComOboeEmSilencio() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencias = contexto.getSharedPreferences("criacao-musical", Context.MODE_PRIVATE)
        val anterior = preferencias.all["motor-oboe"] as? Boolean
        preferencias.edit().putBoolean("motor-oboe", true).commit()
        val motor = MotorDaMusica(contexto, taxa)
        try {
            motor.iniciar()
            motor.definirFonte("gravação 🎵", FonteNaMemoria(arrayOf(FloatArray(taxa))))
            motor.definirProjeto(ProjetoMusical.deTexto(projeto(-60, taxa).decodeToString()))
            motor.tocar(0, false)
            val limite = SystemClock.elapsedRealtime() + 3000
            while (motor.posicao < taxa / 4 && SystemClock.elapsedRealtime() < limite) SystemClock.sleep(25)
            assertTrue(motor.usandoOboe)
            assertTrue(motor.posicao >= taxa / 4)
            assertTrue(motor.medidores.containsKey("master"))
            assertEquals(null, motor.consumirErroDeLeitura())
        } finally {
            motor.desligar()
            preferencias.edit().apply {
                if (anterior == null) remove("motor-oboe") else putBoolean("motor-oboe", anterior)
            }.commit()
        }
    }

    private fun projeto(ganhoDb: Int, duracao: Int = 8) = """
        {"taxa":$taxa,"master":{"ganhoDb":$ganhoDb},"faixas":[{"id":"faixa","pan":-100,
        "clipes":[{"midia":"gravação 🎵","inicio":0,"inicioNaMidia":0,"duracao":$duracao}]}]}
    """.trimIndent().toByteArray()
}
