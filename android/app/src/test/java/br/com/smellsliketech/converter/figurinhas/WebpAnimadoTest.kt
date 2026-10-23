package br.com.smellsliketech.converter.figurinhas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebpAnimadoTest {
    /** FFmpeg android-nativos-7: 100 quadros a 10 fps, select a cada 3, libwebp_anim. */
    private fun exemplo() = javaClass.getResourceAsStream("/figurinhas/animada-pulo3-10200ms.webp")!!.use { it.readBytes() }

    @Test fun dezSegundosComSaltoDeQuadrosPassavamDoLimiteReal() {
        val antes = exemplo()
        assertEquals(34, WebpAnimado.ler(antes).quadros.size)
        assertEquals(10_200L, WebpAnimado.ler(antes).duracaoMs)
        val depois = WebpAnimado.normalizar(antes)
        val info = WebpAnimado.ler(depois)
        assertEquals(10_000L, info.duracaoMs)
        assertTrue(info.quadros.all { it.duracaoMs >= WebpAnimado.QUADRO_MIN_MS })
        // A correção não toca nos pixels, na compressão, na posição ou no descarte dos quadros.
        val tempos = info.quadros.flatMap { (it.posicaoDoTempo until it.posicaoDoTempo + 3).toList() }.toSet()
        assertArrayEquals(antes.filterIndexed { i, _ -> i !in tempos }.toByteArray(), depois.filterIndexed { i, _ -> i !in tempos }.toByteArray())
        assertEquals(10_200L, WebpAnimado.ler(antes).duracaoMs)
        assertSame(depois, WebpAnimado.normalizar(depois))
    }

    @Test fun conservaDuracaoDoTrechoQuandoOSaltoArredondaParaCima() {
        val corrigida = WebpAnimado.normalizar(exemplo(), 3_250)
        assertEquals(3_250L, WebpAnimado.ler(corrigida).duracaoMs)
    }

    @Test fun temposMuitoCurtosSaoAjustadosSemPassarDeDezSegundos() {
        val webp = exemplo()
        val quadros = WebpAnimado.ler(webp).quadros
        repeat(3) { k -> webp[quadros.first().posicaoDoTempo + k] = 0 }
        val info = WebpAnimado.ler(WebpAnimado.normalizar(webp))
        assertEquals(8, info.quadros.first().duracaoMs)
        assertTrue(info.duracaoMs <= 10_000)
        assertTrue(info.quadros.all { it.duracaoMs >= 8 })
    }

    @Test fun rejeitaArquivoTruncadoOuComTamanhoDeChunkCorrompido() {
        val webp = exemplo()
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.ler(webp.copyOf(webp.size - 1)) }
        repeat(4) { webp[16 + it] = 0xff.toByte() }
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.ler(webp) }
    }

    @Test fun rejeitaTelaForaDe512EQuadroForaDaTela() {
        val webp = exemplo()
        webp[24] = 0
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.ler(webp) }
        val fora = exemplo()
        val primeiro = WebpAnimado.ler(fora).quadros.first()
        fora[primeiro.posicaoDoTempo - 12] = 0xff.toByte()
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.ler(fora) }
    }

    private fun completos(): ByteArray {
        val fonte = exemplo()
        val primeiro = WebpAnimado.ler(fonte).quadros.first()
        assertEquals(512, primeiro.largura); assertEquals(512, primeiro.altura)
        val inicio = primeiro.posicaoDoTempo - 20
        val tamanho = (0..3).fold(0) { n, k -> n or ((fonte[inicio + 4 + k].toInt() and 255) shl (8 * k)) }
        val quadro = fonte.copyOfRange(inicio, inicio + 8 + tamanho + (tamanho and 1))
        val webp = fonte.copyOfRange(0, inicio) + quadro + quadro
        repeat(4) { webp[4 + it] = ((webp.size - 8) ushr (8 * it)).toByte() }
        return webp
    }

    @Test fun quadrosCompletosSubstituemOAnteriorSemAlterarPixelsOuTempos() {
        val antes = completos(); val depois = WebpAnimado.quadrosIndependentes(antes)
        val permitidos = WebpAnimado.ler(antes).quadros.map { it.posicaoDoTempo + 3 }.toSet() + (38..41).toSet()
        assertArrayEquals(antes.filterIndexed { i, _ -> i !in permitidos }.toByteArray(), depois.filterIndexed { i, _ -> i !in permitidos }.toByteArray())
        assertTrue(WebpAnimado.ler(depois).quadros.all { depois[it.posicaoDoTempo + 3].toInt() == 2 })
        assertTrue((38..41).all { depois[it].toInt() == 0 })
        assertArrayEquals(antes, completos())
    }

    @Test fun naoAplicaSubstituicaoEmQuadroParcial() {
        val webp = completos(); val quadro = WebpAnimado.ler(webp).quadros.last()
        webp[quadro.posicaoDoTempo - 6] = 0 // largura 257 em vez de 512
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.quadrosIndependentes(webp) }
    }

    @Test fun naoTransformaUmaImagemParadaEmAnimacao() {
        val webp = exemplo()
        val primeiro = WebpAnimado.ler(webp).quadros.first()
        val inicio = primeiro.posicaoDoTempo - 20
        val tamanho = (0..3).fold(0) { valor, k -> valor or ((webp[inicio + 4 + k].toInt() and 0xff) shl (k * 8)) }
        val soUm = webp.copyOf(inicio + 8 + tamanho + (tamanho and 1))
        repeat(4) { k -> soUm[4 + k] = ((soUm.size - 8) ushr (8 * k)).toByte() }
        assertThrows(IllegalArgumentException::class.java) { WebpAnimado.normalizar(soUm) }
    }
}
