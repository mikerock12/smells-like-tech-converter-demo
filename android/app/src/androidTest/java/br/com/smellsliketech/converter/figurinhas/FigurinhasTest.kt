package br.com.smellsliketech.converter.figurinhas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * O caminho inteiro das figurinhas, menos o toque no WhatsApp: desenhar, codificar dentro dos
 * limites, montar o pacote e ler pelo provedor como o WhatsApp lê.
 */
@RunWith(AndroidJUnit4::class)
class FigurinhasTest {
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val pacotes = Pacotes(contexto)
    private val criados = mutableListOf<PacoteDeFigurinhas>()

    @After fun limpar() = criados.forEach(pacotes::excluir)

    private fun imagem(cor: Int): Bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888).apply {
        val tela = Canvas(this)
        tela.drawColor(cor)
        tela.drawCircle(450f, 300f, 220f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW })
    }

    @Test fun estaticaComTextoFiltroEContornoCabeNoLimite() {
        val edicao = Edicao(texto = "Bom dia!", filtro = Filtro.VIVIDO, contorno = true, brilho = 0.2f)
        val figurinha = Renderizador(contexto).desenhar(imagem(Color.BLUE), null, edicao)
        assertEquals(Regras.LADO, figurinha.width)
        assertEquals(Regras.LADO, figurinha.height)
        val webp = Producao.estatica(figurinha)
        assertTrue("${webp.size} bytes", webp.size <= Regras.ESTATICA_MAX_BYTES)
        val lida = BitmapFactory.decodeByteArray(webp, 0, webp.size)
        assertEquals(Regras.LADO, lida.width)
    }

    @Test fun mascaraRecortaOFundo() {
        val fonte = imagem(Color.BLUE)
        // Só o círculo é figura: o canto do quadrado (fundo) tem de ficar transparente.
        val mascara = Bitmap.createBitmap(fonte.width, fonte.height, Bitmap.Config.ARGB_8888)
        Canvas(mascara).drawCircle(450f, 300f, 220f, Paint().apply { color = Color.WHITE })
        val figurinha = Renderizador(contexto).desenhar(fonte, mascara, Edicao(removerFundo = true))
        assertEquals(0, Color.alpha(figurinha.getPixel(5, 256)))
        assertEquals(255, Color.alpha(figurinha.getPixel(256, 256)))
    }

    @Test fun pacoteDeTresApareceParaOWhatsAppPeloProvedor() {
        var pacote = pacotes.criar("Teste do provedor", animado = false).also { criados += it }
        listOf(Color.RED, Color.GREEN, Color.BLUE).forEach { cor ->
            val webp = Producao.estatica(Renderizador(contexto).desenhar(imagem(cor), null, Edicao()))
            pacote = pacotes.adicionar(pacote, webp, listOf("🔥"))
        }
        assertTrue(pacote.prontoParaWhatsApp)
        val autoridade = ProvedorDeFigurinhas.autoridade(contexto.packageName)
        val resolvedor = contexto.contentResolver

        resolvedor.query(Uri.parse("content://$autoridade/metadata/${pacote.id}"), null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(pacote.id, cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_identifier")))
            assertEquals("Teste do provedor", cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_name")))
            assertEquals(Pacotes.BANDEJA, cursor.getString(cursor.getColumnIndexOrThrow("sticker_pack_icon")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("animated_sticker_pack")))
            assertEquals(pacote.versao.toString(), cursor.getString(cursor.getColumnIndexOrThrow("image_data_version")))
        }
        val arquivos = resolvedor.query(Uri.parse("content://$autoridade/stickers/${pacote.id}"), null, null, null, null)!!.use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) to cursor.getString(1) else null }.toList()
        }
        assertEquals(3, arquivos.size)
        assertTrue(arquivos.all { it.second == "🔥" })

        // Os arquivos saem pelo provedor iguais aos gravados; a bandeja é PNG 96×96 de até 50 KB.
        arquivos.forEach { (nome, _) ->
            val lidos = resolvedor.openInputStream(Uri.parse("content://$autoridade/stickers_asset/${pacote.id}/$nome"))!!.use { it.readBytes() }
            assertArrayEquals(pacotes.arquivo(pacote, nome).readBytes(), lidos)
        }
        val bandeja = resolvedor.openInputStream(Uri.parse("content://$autoridade/stickers_asset/${pacote.id}/${Pacotes.BANDEJA}"))!!.use { it.readBytes() }
        assertTrue(bandeja.size <= Regras.BANDEJA_MAX_BYTES)
        assertEquals(Regras.BANDEJA_LADO, BitmapFactory.decodeByteArray(bandeja, 0, bandeja.size).width)

        // Pacote com menos de 3 não aparece na lista que o WhatsApp lê.
        val incompleto = pacotes.criar("Incompleto", animado = false).also { criados += it }
        val ids = resolvedor.query(Uri.parse("content://$autoridade/metadata"), null, null, null, null)!!.use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
        }
        assertTrue(pacote.id in ids)
        assertTrue(incompleto.id !in ids)
    }

    @Test fun provedorNaoEntregaArquivoForaDoPacote() {
        val pacote = pacotes.criar("Seguro", animado = false).also { criados += it }
        File(pacotes.pasta(pacote.id), "segredo.webp").writeText("x")
        val autoridade = ProvedorDeFigurinhas.autoridade(contexto.packageName)
        val falhou = runCatching {
            contexto.contentResolver.openInputStream(Uri.parse("content://$autoridade/stickers_asset/${pacote.id}/segredo.webp"))?.close()
        }.isFailure
        assertTrue("arquivo fora da lista do pacote não pode sair", falhou)
    }

    @Test fun animadaComFfmpegCabeEmQuinhentosKb() = runBlocking {
        assumeTrue("FFmpeg do app", Ffmpeg.disponivel(contexto))
        val pasta = File(contexto.cacheDir, "teste-animada").apply { deleteRecursively(); mkdirs() }
        val renderizador = Renderizador(contexto)
        val quadros = (0 until 24).map { indice ->
            val fonte = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawCircle(100f + indice * 16f, 300f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA })
            }
            File(pasta, "d%04d.png".format(indice)).also { destino ->
                destino.outputStream().use { renderizador.desenhar(fonte, null, Edicao(texto = "oi", contorno = true)).compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val webp = Producao.animada(contexto, quadros)
        pasta.deleteRecursively()
        assertTrue("${webp.size} bytes", webp.size in 1..Regras.ANIMADA_MAX_BYTES)
        // RIFF....WEBPVP8X com o bit de animação ligado.
        assertEquals("RIFF", String(webp.copyOfRange(0, 4)))
        assertEquals("WEBP", String(webp.copyOfRange(8, 12)))
        assertEquals("VP8X", String(webp.copyOfRange(12, 16)))
        assertTrue("animada", webp[20].toInt() and 0x02 != 0)
        var pacote = pacotes.criar("Animado", animado = true).also { criados += it }
        pacote = pacotes.adicionar(pacote, webp, listOf("🎉"))
        assertEquals(1, pacote.figurinhas.size)
    }
}
