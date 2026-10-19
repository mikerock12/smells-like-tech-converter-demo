package br.com.smellsliketech.converter.estudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import br.com.smellsliketech.converter.conversao.Entrada
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.Midia
import br.com.smellsliketech.converter.conversao.Mimes
import br.com.smellsliketech.converter.conversao.Opcoes
import br.com.smellsliketech.converter.conversao.Resultado
import br.com.smellsliketech.converter.conversao.Saida
import br.com.smellsliketech.converter.conversao.Tipo
import br.com.smellsliketech.converter.conversao.ffmpeg.CodificadorDoCelular
import br.com.smellsliketech.converter.conversao.ffmpeg.Comandos
import br.com.smellsliketech.converter.conversao.ffmpeg.Ffmpeg
import br.com.smellsliketech.converter.conversao.ffmpeg.Quadros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exporta um projeto do estúdio no celular. As fotos são endireitadas (ImageDecoder aplica a
 * orientação da câmera e lê HEIC), os textos viram PNG pelo [DesenhoDoTexto] e uma passada
 * do FFmpeg monta tudo pela receita comum ([Estudio.receita]). Vídeo: o FFmpeg entrega os
 * quadros crus ao H.264 do próprio celular ([CodificadorDoCelular]) e depois junta o áudio;
 * sem esse codificador, cai no MPEG-4 do FFmpeg. Texto: o áudio final vai ao Whisper do app.
 */
object Exportador {
    private const val LADO_MAXIMO_DA_IMAGEM = 4096

    suspend fun exportar(
        contexto: Context,
        projeto: Projeto,
        modeloWhisper: String,
        idioma: String = "pt",
        aoAvancar: (Int) -> Unit,
    ): Resultado {
        val extensao = Estudio.saida(projeto.saida)?.extensao ?: "mp4"
        val nome = "estudio-${projeto.destino}-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.$extensao"
        if (projeto.saida == "texto") {
            val wav = File.createTempFile("estudio-", ".wav", contexto.cacheDir)
            try {
                gerar(contexto, projeto, wav) { aoAvancar(it / 4) }
                val transcricao = Midia.transcrever(
                    contexto,
                    Entrada(Uri.fromFile(wav), "estudio.wav", Tipo.AUDIO, wav.length()),
                    Opcoes("txt", modelo = modeloWhisper, idioma = idioma),
                    aoAvancar = { aoAvancar(25 + it * 3 / 4) },
                )
                return Saida.gravar(contexto, nome, Mimes.de("txt")) { it.write(transcricao.texto.toByteArray()) }.also { aoAvancar(100) }
            } finally {
                wav.delete()
            }
        }
        val arquivo = File.createTempFile("estudio-", ".$extensao", contexto.cacheDir)
        try {
            gerar(contexto, projeto, arquivo, aoAvancar)
            return Saida.gravarArquivo(contexto, nome, Mimes.de(extensao), arquivo).also { aoAvancar(100) }
        } finally {
            arquivo.delete()
        }
    }

    /**
     * Uma prévia rápida em vídeo, pequena (lado maior de 480 px), para assistir antes de exportar.
     * Fica no cache; quem chama apaga.
     */
    suspend fun previa(contexto: Context, projeto: Projeto, aoAvancar: (Int) -> Unit): File {
        val escala = minOf(1.0, 480.0 / maxOf(projeto.largura, projeto.altura))
        val pequeno = projeto.copy(
            largura = Estudio.par(projeto.largura * escala),
            altura = Estudio.par(projeto.altura * escala),
            saida = "video",
        )
        val arquivo = File(contexto.cacheDir, "estudio-previa.mp4").apply { delete() }
        gerar(contexto, pequeno, arquivo, aoAvancar)
        return arquivo
    }

    /**
     * Gera o resultado em `destino`: o vídeo, o GIF, o PNG, o MP3, ou o WAV de 16 kHz (para
     * transcrever) quando a saída é texto.
     */
    suspend fun gerar(contexto: Context, projeto: Projeto, destino: File, aoAvancar: (Int) -> Unit) {
        Estudio.problemas(projeto).takeIf { it.isNotEmpty() }?.let { throw ErroDeConversao(it.joinToString(" ")) }
        val pasta = File(contexto.cacheDir, "estudio-exportar-${System.nanoTime()}").apply { mkdirs() }
        try {
            aoAvancar(0)
            val clipes = projeto.clipes.mapIndexed { i, clipe ->
                val arquivo = File(clipe.arquivo)
                if (!arquivo.isFile) throw ErroDeConversao("Um arquivo do projeto sumiu do celular; adicione-o de novo.")
                if (clipe.ehImagem) EntradaDeClipe(prepararImagem(arquivo, File(pasta, "imagem-$i.png")).absolutePath, false)
                else {
                    val sondagem = Ffmpeg.sondar(contexto, arquivo)
                    if (!sondagem.temVideo) throw ErroDeConversao("O clipe ${i + 1} não tem imagem de vídeo.")
                    EntradaDeClipe(arquivo.absolutePath, sondagem.temAudio)
                }
            }
            val audios = projeto.audios.map { audio ->
                File(audio.arquivo).takeIf { it.isFile }?.absolutePath ?: throw ErroDeConversao("Uma música do projeto sumiu do celular; adicione-a de novo.")
            }
            val textos = if (projeto.saida == "audio" || projeto.saida == "texto") emptyList() else {
                val desenho = DesenhoDoTexto(contexto)
                projeto.textos.mapIndexed { j, texto ->
                    File(pasta, "texto-$j.png").also { png -> salvarPng(desenho.imagem(texto, projeto.largura, projeto.altura), png) }.absolutePath
                }
            }
            val receita = Estudio.receita(projeto, EntradasDaReceita(clipes, textos, audios))
            when (projeto.saida) {
                "video" -> video(contexto, projeto, receita, pasta, destino, aoAvancar)
                else -> {
                    val fim = when (projeto.saida) {
                        "gif" -> listOf("-loop", "0")
                        "imagem" -> listOf("-ss", Estudio.numero(projeto.quadro), "-frames:v", "1", "-update", "1")
                        "texto" -> listOf("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le")
                        else -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
                    }
                    val total = if (projeto.saida == "imagem") max(0.1, projeto.quadro) else receita.duracao
                    Ffmpeg.executar(contexto, comProgresso(receita) + fim + destino.absolutePath, total, aoAvancar = aoAvancar)
                }
            }
            if (destino.length() == 0L) throw ErroDeConversao("O estúdio não produziu nenhum arquivo.")
        } finally {
            pasta.deleteRecursively()
        }
    }

    /** H.264 do celular (quadros crus pelo pipe + AAC à parte, depois juntos); senão MPEG-4 do FFmpeg. */
    private suspend fun video(contexto: Context, projeto: Projeto, receita: Receita, pasta: File, saida: File, aoAvancar: (Int) -> Unit) {
        val quadros = Quadros(projeto.largura, projeto.altura, projeto.fps)
        val (mapaDoVideo, mapaDoAudio) = receita.mapas[1] to receita.mapas[3]
        if (CodificadorDoCelular.aceita(quadros)) {
            val video = File(pasta, "video.mp4")
            val audio = File(pasta, "audio.m4a")
            val argumentos = inicio() + receita.args + listOf("-filter_complex", receita.filtro) +
                listOf("-map", mapaDoVideo, "-r", projeto.fps.toString(), "-fps_mode", "cfr", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1") +
                listOf("-map", mapaDoAudio, "-c:a", "aac", "-b:a", "192k", audio.absolutePath)
            val bitrate = Comandos.bitrateDoVideo(quadros.largura, quadros.altura, quadros.porSegundo.toDouble(), 80)
            val total = (receita.duracao * projeto.fps).roundToInt()
            CodificadorDoCelular.codificar(contexto, argumentos, quadros, bitrate, video, total) { aoAvancar(it * 95 / 100) }
            Ffmpeg.executar(contexto, Comandos.juntar(video.absolutePath, audio.takeIf { it.length() > 0 }?.absolutePath, saida.absolutePath, "mp4"), null)
        } else {
            Ffmpeg.executar(
                contexto,
                comProgresso(receita) + listOf("-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p", "-r", projeto.fps.toString(),
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", saida.absolutePath),
                receita.duracao,
                aoAvancar = aoAvancar,
            )
        }
    }

    private fun inicio() = listOf("-hide_banner", "-nostdin", "-v", "error", "-y")

    private fun comProgresso(receita: Receita) =
        inicio() + listOf("-progress", "pipe:1", "-nostats") + receita.args + listOf("-filter_complex", receita.filtro) + receita.mapas

    /** Foto do celular em pé, HEIC ou AVIF: o ImageDecoder endireita; sai um PNG que o FFmpeg lê. */
    private suspend fun prepararImagem(arquivo: File, destino: File): File = withContext(Dispatchers.IO) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(arquivo)) { decodificador, informacao, _ ->
                decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maior = max(informacao.size.width, informacao.size.height)
                if (maior > LADO_MAXIMO_DA_IMAGEM) {
                    val escala = LADO_MAXIMO_DA_IMAGEM.toDouble() / maior
                    decodificador.setTargetSize((informacao.size.width * escala).roundToInt(), (informacao.size.height * escala).roundToInt())
                }
            }
        } catch (erro: Exception) {
            throw ErroDeConversao("Não deu para abrir uma das imagens do projeto.", erro)
        }
        salvarPng(bitmap, destino)
        destino
    }

    private fun salvarPng(bitmap: Bitmap, destino: File) {
        destino.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
