package br.com.smellsliketech.converter.musica

import android.Manifest
import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * A criação musical no aparelho: o motor de áudio de verdade (AudioTrack/AudioRecord), a
 * exportação pelo FFmpeg do app, a separação com o modelo real pelo ONNX Runtime (quando o
 * modelo foi posto em files/modelos pelo adb) e a tela.
 */
@RunWith(AndroidJUnit4::class)
class CriacaoMusicalNoAparelhoTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val contexto = i.targetContext
    private val app get() = contexto.applicationContext as Application

    private fun seno(f: Double, n: Int, taxa: Int, a: Double = 0.4) = FloatArray(n) { (a * sin(2 * PI * f * it / taxa)).toFloat() }

    private fun wav(arquivo: File, canais: List<FloatArray>, taxa: Int) {
        EscritorDeWav(arquivo, canais.size, taxa).use { it.escreverCanais(canais, canais[0].size) }
    }

    @Test fun motorTocaEGravaUmaTomadaInteira() {
        i.uiAutomation.grantRuntimePermission(contexto.packageName, Manifest.permission.RECORD_AUDIO)
        val taxa = 48000
        val motor = MotorDaMusica(contexto)
        motor.iniciar()
        try {
            val cliques = FloatArray(taxa * 4)
            for (k in 0 until 7) { val p = 6000 + k * taxa / 2; for (j in 0 until 64) cliques[p + j] = 0.8f * (if (j % 2 == 0) 1 else -1) }
            var p = Musica.novoProjeto("teste-motor", "Motor").copy(contagem = 0.0, metronomo = Metronomo(ligado = false))
            p = p.copy(midias = listOf(MidiaMusical("cliques", taxa = taxa, canais = 1, quadros = cliques.size.toLong())), faixas = listOf(Musica.novaFaixa("faixa-cliques", "Cliques").copy(clipes = listOf(ClipeMusical("c1", "cliques", 0, 0, cliques.size.toLong())))))
            motor.definirFonte("cliques", FonteNaMemoria(arrayOf(cliques)))
            motor.definirProjeto(p)
            // Tocar sozinho: o transporte anda pelo relógio da saída.
            motor.tocar(0, false)
            val inicio = SystemClock.elapsedRealtime()
            Thread.sleep(2000)
            val andou = motor.posicao
            val passou = (SystemClock.elapsedRealtime() - inicio) / 1000.0
            motor.parar()
            Log.i("CriacaoMusical", "tocou ${andou / taxa.toDouble()} s em $passou s de relógio")
            assertTrue("o transporte andou ${andou / taxa.toDouble()} s em $passou s", abs(andou / taxa.toDouble() - passou) < 0.4)
            // Gravar ouvindo a faixa: a tomada tem a duração do relógio e o quadro de alinhamento sai.
            val entrada = motor.abrirEntrada(1)
            Log.i("CriacaoMusical", "entrada: $entrada, burst ${motor.burst}")
            val partes = mutableListOf<FloatArray>()
            var tomada: Tomada? = null
            val plano = Musica.planoDaGravacao(p, 0)
            motor.gravar(plano, 1, false, { synchronized(partes) { partes += it } }) { tomada = it }
            Thread.sleep(3000)
            motor.parar()
            val limite = SystemClock.elapsedRealtime() + 3000
            while (tomada == null && SystemClock.elapsedRealtime() < limite) Thread.sleep(50)
            val t = tomada!!
            val gravados = synchronized(partes) { partes.sumOf { it.size } }
            Log.i("CriacaoMusical", "tomada: ${t.quadros} quadros (${t.quadros / taxa.toDouble()} s), alinhada em ${t.quadroAlinhado} pelo relógio=${t.alinhadaPeloRelogio}, ${t.medidas} medidas, rota ${motor.rotaDaSaida()}")
            // O alinhamento é a mediana de várias medidas, não de um instante só.
            if (t.alinhadaPeloRelogio) assertTrue("medidas: ${t.medidas}", t.medidas >= 8)
            assertEquals(t.quadros, gravados.toLong())
            assertTrue("gravou ${t.quadros / taxa.toDouble()} s em 3 s", t.quadros / taxa.toDouble() in 2.6..3.3)
            assertTrue("alinhamento plausível: ${t.quadroAlinhado}", t.quadroAlinhado in -taxa.toLong()..taxa.toLong())
            val clipe = Musica.clipeDaTomada(p, "c2", "tomada", t.quadroAlinhado, t.quadros, plano)!!
            assertTrue(clipe.duracao > taxa * 2)
        } finally {
            motor.desligar()
        }
    }

    @Test fun exportaTodosOsFormatosPeloFfmpegDoAparelho() = runBlocking {
        val biblioteca = BibliotecaDaMusica(contexto)
        val taxa = 48000
        var p = Musica.projetoDoModelo("violao-e-voz", "teste-exportacao-android", "Exportação Android")
        wav(biblioteca.midia(p.id, "violao"), listOf(seno(220.0, taxa * 3, taxa)), taxa)
        wav(biblioteca.midia(p.id, "voz"), listOf(seno(440.0, taxa * 3, taxa, 0.2), seno(660.0, taxa * 3, taxa, 0.2)), taxa)
        p = p.copy(
            midias = listOf(MidiaMusical("violao", taxa = taxa, canais = 1, quadros = taxa * 3L), MidiaMusical("voz", taxa = taxa, canais = 2, quadros = taxa * 3L)),
            faixas = p.faixas.mapIndexed { k, f -> f.copy(clipes = listOf(ClipeMusical("c$k", if (k == 0) "violao" else "voz", k * 12000L, 0, taxa * 3L - k * 12000L))) },
        )
        assertEquals(emptyList<String>(), Musica.problemas(p))
        biblioteca.salvar(p)
        val pasta = File(contexto.cacheDir, "exportacao-teste").apply { deleteRecursively() }
        for (formato in MasterDaMusica.FORMATOS) {
            val r = biblioteca.exportar(p, formato.id, "streaming", false, false, true, null, pasta) { _, _ -> }.single()
            Log.i("CriacaoMusical", "${formato.id}: ${r.arquivo.length()} bytes, ${r.medidas?.lufs} LUFS, ${r.medidas?.realDb} dBTP")
            assertTrue("${formato.id} vazio", r.arquivo.length() > 10_000)
            assertTrue("${formato.id} fora do alvo: ${r.medidas?.lufs}", abs(r.medidas!!.lufs + 14) < 0.6)
        }
        val faixas = biblioteca.exportar(p, "wav-24", "sem-alvo", false, true, false, null, pasta) { _, _ -> }
        assertEquals(2, faixas.size)
        biblioteca.apagar(p.id)
    }

    @Test fun exportaTempoETomNoArquivoReal() = runBlocking {
        val biblioteca = BibliotecaDaMusica(contexto)
        val taxa = 48000
        val id = "teste-tempo-tom-exportacao"
        wav(biblioteca.midia(id, "seno"), listOf(seno(440.0, taxa * 2, taxa)), taxa)
        val midia = MidiaMusical("seno", taxa = taxa, canais = 1, quadros = taxa * 2L)
        val clipe = ClipeMusical("c", "seno", 0, 0, taxa.toLong(), velocidade = 2.0, semitons = 12.0, preservarTom = true)
        val p = Musica.projetoDoModelo("vazio", id, "Tempo e tom").copy(midias = listOf(midia), faixas = listOf(Musica.novaFaixa("f", "Seno").copy(clipes = listOf(clipe))))
        assertEquals(emptyList<String>(), Musica.problemas(p))
        biblioteca.salvar(p)
        val pasta = File(contexto.cacheDir, "tempo-tom-teste").apply { deleteRecursively() }
        val resultado = biblioteca.exportar(p, "wav-32f", "sem-alvo", false, false, false, null, pasta) { _, _ -> }.single()
        FonteWav(resultado.arquivo).use { fonte ->
            assertEquals(taxa.toLong(), fonte.quadros)
            val dados = FloatArray(taxa)
            fonte.copiar(0, 0, taxa, dados)
            assertTrue("o arquivo não pode ficar mudo", dados.any { abs(it) > 0.05 })
        }
        biblioteca.apagar(id)
        pasta.deleteRecursively()
        Unit
    }

    @Test fun trocaVelocidadeMantendoTomComMotorTocando() = runBlocking {
        val biblioteca = BibliotecaDaMusica(contexto)
        val taxa = 48000
        val id = "teste-velocidade-previa"
        val arquivo = biblioteca.midia(id, "seno")
        val motor = MotorDaMusica(contexto)
        val fontes = mutableListOf<FonteWav>()
        try {
            wav(arquivo, listOf(seno(440.0, taxa * 3, taxa), seno(440.0, taxa * 3, taxa)), taxa)
            val midia = MidiaMusical("seno", taxa = taxa, canais = 2, quadros = taxa * 3L)
            val clipe = ClipeMusical("c", "seno", 0, 0, midia.quadros)
            val p = Musica.projetoDoModelo("vazio", id, "Velocidade").copy(
                midias = listOf(midia), faixas = listOf(Musica.novaFaixa("f", "Seno").copy(clipes = listOf(clipe))),
            )
            motor.iniciar()
            motor.definirProjeto(p)
            val original = FonteWav(arquivo).also { fontes += it }
            motor.definirFonte(midia.id, original)
            motor.tocar(0, false)
            var chaveAnterior: String? = null
            for (velocidade in listOf(.25, 1.5, 4.0)) {
                val alterado = TransformacaoDaMusica.comVelocidade(clipe, midia, velocidade).copy(preservarTom = true)
                val novo = p.copy(faixas = p.faixas.map { it.copy(clipes = listOf(alterado)) })
                motor.definirProjeto(novo)
                motor.posicionar(0)
                chaveAnterior?.let { motor.definirFonte(it, null); fontes.last().close() }
                val fonte = biblioteca.prepararClipe(novo, alterado).also { fontes += it }
                assertEquals(alterado.duracao, fonte.quadros)
                val dados = FloatArray(taxa / 4)
                fonte.copiar(1, taxa / 8L, dados.size, dados)
                val cruzamentos = (1 until dados.size).count { dados[it - 1] <= 0f && dados[it] > 0f }
                assertTrue("Velocidade $velocidade produziu $cruzamentos ciclos em 250 ms", cruzamentos in 105..115)
                chaveAnterior = TransformacaoDaMusica.chaveDaFonte(alterado)
                motor.definirFonte(chaveAnterior, fonte)
                motor.posicionar(0)
                kotlinx.coroutines.delay(120)
                assertTrue("O transporte parou durante a troca de velocidade", motor.tocando && motor.posicao > 0)
                assertEquals(null, motor.consumirErroDeLeitura())
            }
        } finally {
            motor.desligar()
            fontes.forEach { it.close() }
            biblioteca.apagar(id)
        }
    }

    @Test fun exportaTempoETomNaMasterSemAlterarFaixasIndividuais() = runBlocking {
        val biblioteca = BibliotecaDaMusica(contexto)
        val taxa = 48000
        val id = "teste-tempo-master"
        val pasta = File(contexto.cacheDir, id)
        try {
            wav(biblioteca.midia(id, "seno"), listOf(seno(440.0, taxa * 2, taxa)), taxa)
            val midia = MidiaMusical("seno", taxa = taxa, canais = 1, quadros = taxa * 2L)
            val faixa = Musica.novaFaixa("f", "Seno").copy(clipes = listOf(ClipeMusical("c", "seno", 0, 0, taxa * 2L)))
            val projeto = Musica.projetoDoModelo("vazio", id, "Tempo na master").copy(
                midias = listOf(midia), faixas = listOf(faixa), master = Master(velocidade = 2.0, semitons = 12.0),
            )
            val mistura = biblioteca.exportar(projeto, "wav-32f", "sem-alvo", false, false, false, null, pasta) { _, _ -> }.single()
            FonteWav(mistura.arquivo).use { fonte ->
                assertEquals(taxa.toLong(), fonte.quadros)
                val dados = FloatArray(taxa / 2)
                fonte.copiar(0, taxa / 4L, dados.size, dados)
                val cruzamentos = (1 until dados.size).count { dados[it - 1] <= 0f && dados[it] > 0f }
                assertTrue("A oitava acima produziu $cruzamentos ciclos em meio segundo.", cruzamentos in 425..455)
            }
            val stem = biblioteca.exportar(projeto, "wav-32f", "sem-alvo", false, true, false, null, pasta) { _, _ -> }.single()
            FonteWav(stem.arquivo).use { assertEquals(taxa * 2L, it.quadros) }
        } finally {
            biblioteca.apagar(id)
            pasta.deleteRecursively()
        }
    }

    /** O build R8 `validacao` não é depurável (sem run-as): o modelo posto pelo adb em
     *  /data/local/tmp/spleeter.onnx chega pelo shell da instrumentação. */
    private fun copiarModeloDoAdb(destino: File) {
        val origem = "/data/local/tmp/spleeter.onnx"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $origem")
        destino.parentFile?.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        if (destino.length() != SeparacaoDaMusica.BYTES) destino.delete()
    }

    @Test fun separaComOModeloRealNoAparelho() = runBlocking {
        val biblioteca = BibliotecaDaMusica(contexto)
        if (!biblioteca.modeloPronto) copiarModeloDoAdb(biblioteca.modelo)
        if (!biblioteca.modeloPronto) { Log.w("CriacaoMusical", "modelo ausente em ${biblioteca.modelo}: separação não testada"); return@runBlocking }
        val taxa = 48000
        val n = taxa * 8
        val baixo = seno(55.0, n, taxa, 0.5)
        val melodia = seno(440.0, n, taxa, 0.2)
        val mistura = FloatArray(n) { baixo[it] + melodia[it] * (0.5 + 0.5 * sin(2 * PI * 5 * it / taxa)).toFloat() }
        val entrada = File(contexto.cacheDir, "mistura-teste.wav")
        wav(entrada, listOf(mistura, mistura), taxa)
        val inicio = SystemClock.elapsedRealtime()
        val faixas = biblioteca.separar(entrada, File(contexto.cacheDir, "faixas-teste"), "4", taxa) { _, _ -> }
        val segundos = (SystemClock.elapsedRealtime() - inicio) / 1000.0
        assertEquals(listOf("vozes", "bateria", "baixo", "outros"), faixas.map { it.first.id })
        val soma = DoubleArray(n)
        val energias = mutableMapOf<String, Double>()
        for ((definicao, arquivo) in faixas) FonteWav(arquivo).use { fonte ->
            val d = FloatArray(n)
            fonte.copiar(0, 0, n, d)
            var e = 0.0
            for (k in 0 until n) { soma[k] += d[k].toDouble(); e += d[k].toDouble() * d[k] }
            energias[definicao.id] = e
        }
        var erro = 0.0; var referencia = 0.0
        for (k in taxa until n - taxa) { erro += (soma[k] - mistura[k]).let { it * it }; referencia += mistura[k].toDouble() * mistura[k] }
        val snr = 10 * log10(referencia / erro)
        Log.i("CriacaoMusical", "separação de 8 s em $segundos s; soma contra a mistura $snr dB; energias $energias")
        assertTrue("as faixas não somam a mistura ($snr dB)", snr > 25)
        assertTrue("o grave devia ir para o baixo", energias["baixo"]!! > energias["bateria"]!!)
    }
}
