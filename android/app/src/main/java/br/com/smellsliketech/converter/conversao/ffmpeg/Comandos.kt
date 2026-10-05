package br.com.smellsliketech.converter.conversao.ffmpeg

import br.com.smellsliketech.converter.conversao.Encaixe
import br.com.smellsliketech.converter.conversao.ErroDeConversao
import br.com.smellsliketech.converter.conversao.Listas
import br.com.smellsliketech.converter.conversao.Opcoes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** O que o FFmpeg disse do arquivo de entrada (ver [Sondagem.ler]). */
data class Sondagem(
    val duracaoSegundos: Double? = null,
    val temVideo: Boolean = false,
    val temAudio: Boolean = false,
    /** Já com a rotação da câmera aplicada: é o tamanho que a pessoa vê. */
    val largura: Int? = null,
    val altura: Int? = null,
    val quadrosPorSegundo: Double? = null,
) {
    companion object {
        private val DURACAO = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
        private val TAMANHO = Regex(""",\s*(\d{2,5})x(\d{2,5})[\s,\[]""")
        private val FPS = Regex("""(\d+(?:\.\d+)?)\s*fps""")
        private val ROTACAO = Regex("""rotation of (-?\d+(?:\.\d+)?) degrees""")

        /**
         * Lê o que `ffmpeg -i arquivo` escreve no stderr. A capa de um MP3 aparece como
         * "Video: ... (attached pic)" e não conta como vídeo.
         */
        fun ler(saida: String): Sondagem {
            val duracao = DURACAO.find(saida)?.destructured?.let { (h, m, s) ->
                h.toDouble() * 3600 + m.toDouble() * 60 + s.toDouble()
            }
            val linhas = saida.lines()
            val video = linhas.firstOrNull { "Stream #" in it && "Video:" in it && "attached pic" !in it }
            val audio = linhas.any { "Stream #" in it && "Audio:" in it }
            var largura: Int? = null
            var altura: Int? = null
            video?.let { linha ->
                TAMANHO.find(linha)?.destructured?.let { (l, a) -> largura = l.toInt(); altura = a.toInt() }
            }
            val rotacao = ROTACAO.find(saida)?.groupValues?.get(1)?.toDoubleOrNull()?.let { abs(it.roundToInt()) % 180 }
            if (rotacao == 90) {
                val l = largura
                largura = altura
                altura = l
            }
            return Sondagem(
                duracaoSegundos = duracao,
                temVideo = video != null,
                temAudio = audio,
                largura = largura,
                altura = altura,
                quadrosPorSegundo = video?.let { FPS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
            )
        }
    }
}

/**
 * Codificador de vídeo. O H.264 sai do codificador do próprio celular (MediaCodec, pela API
 * do Android: o FFmpeg decodifica e filtra, e manda os quadros por um pipe), sem nada de GPL;
 * quando o aparelho recusa, o app tenta de novo com o MPEG-4 do FFmpeg.
 */
enum class Codificador(val nome: String) {
    H264_CELULAR("MediaCodec"), MPEG4("mpeg4"), VP9("libvpx-vp9");

    companion object {
        /** O primeiro que tentar para o contêiner, e o de reserva. */
        fun para(formato: String): List<Codificador> = when (formato) {
            "webm" -> listOf(VP9)
            "avi" -> listOf(MPEG4)
            else -> listOf(H264_CELULAR, MPEG4)
        }
    }
}

/** Os quadros que o FFmpeg entrega ao codificador do celular: tamanho exato e ritmo constante. */
data class Quadros(val largura: Int, val altura: Int, val porSegundo: Int) {
    /** YUV 4:2:0 planar: Y inteiro, U e V com um quarto cada. */
    val bytes: Int get() = largura * altura * 3 / 2
}

/**
 * Monta as linhas de comando do FFmpeg, como o FfmpegCommandPlanner do aplicativo para
 * Windows. Todo argumento sai daqui, de valores já conferidos contra as listas: nenhum texto
 * da pessoa vira comando. Kotlin puro: os testes rodam no computador.
 */
object Comandos {
    /** Janela entregue ao Whisper de cada vez: a nativa do modelo (ver o desktop). */
    const val JANELA_DO_WHISPER = 30

    private fun numero(valor: Double): String = String.format(Locale.ROOT, "%.3f", valor).trimEnd('0').trimEnd('.')

    /** `progresso` = false quando a saída padrão leva outra coisa (os quadros do celular). */
    fun prologo(entrada: String, inicio: Double, fim: Double, progresso: Boolean = true): MutableList<String> {
        val argumentos = mutableListOf("-hide_banner", "-nostdin", "-v", "error", "-y")
        if (progresso) argumentos += listOf("-progress", "pipe:1", "-nostats")
        argumentos += listOf("-protocol_whitelist", "file,pipe")
        if (inicio > 0) argumentos += listOf("-ss", numero(inicio))
        argumentos += listOf("-i", entrada)
        if (fim > 0 && fim > inicio) argumentos += listOf("-t", numero(fim - inicio))
        return argumentos
    }

    /** Converter áudio, e vídeo → áudio: a primeira trilha de áudio, no formato pedido. */
    fun audio(entrada: String, saida: String, opcoes: Opcoes, duracaoEntrada: Double? = null): List<String> {
        validarAudio(opcoes)
        val argumentos = prologo(entrada, opcoes.inicioSegundos, opcoes.fimSegundos)
        argumentos += listOf("-map", "0:a:0", "-vn", "-sn", "-dn")
        val filtros = mutableListOf<String>()
        filtroDeVelocidadeDoAudio(opcoes.velocidade)?.let { filtros += it }
        if (opcoes.normalizar) filtros += "loudnorm=I=-16:LRA=11:TP=-1.5"
        if (opcoes.volume != 1.0) filtros += "volume=${numero(opcoes.volume)}"
        if (opcoes.fadeEntrada > 0 || opcoes.fadeSaida > 0) filtros += "asetpts=PTS-STARTPTS"
        if (opcoes.fadeEntrada > 0) filtros += "afade=t=in:st=0:d=${numero(opcoes.fadeEntrada)}"
        if (opcoes.fadeSaida > 0) {
            val total = duracaoEntrada?.takeIf { it.isFinite() && it > 0 }
                ?: throw ErroDeConversao("Não foi possível determinar a duração para aplicar fade de saída.")
            val fim = if (opcoes.fimSegundos > 0) minOf(total, opcoes.fimSegundos) else total
            val duracao = ((fim - opcoes.inicioSegundos) / opcoes.velocidade).coerceAtLeast(0.0)
            val fade = minOf(opcoes.fadeSaida, duracao)
            filtros += "afade=t=out:st=${numero((duracao - fade).coerceAtLeast(0.0))}:d=${numero(fade)}"
        }
        if (filtros.isNotEmpty()) argumentos += listOf("-af", filtros.joinToString(","))
        argumentos += codificacaoDeAudio(opcoes.formato, opcoes.bitrate)
        argumentos += formaDoAudio(opcoes)
        argumentos += listOf("-map_metadata", "-1", "-map_chapters", "-1", saida)
        return argumentos
    }

    /** O WAV da narração, codificado no formato escolhido. */
    fun codificarNarracao(wav: String, saida: String, opcoes: Opcoes): List<String> {
        validarAudio(opcoes)
        val argumentos = prologo(wav, 0.0, 0.0)
        argumentos += listOf("-vn", "-sn", "-dn")
        argumentos += codificacaoDeAudio(opcoes.formato, opcoes.bitrate)
        argumentos += formaDoAudio(opcoes)
        argumentos += listOf("-map_metadata", "-1", saida)
        return argumentos
    }

    fun validarAudio(opcoes: Opcoes) {
        if (opcoes.formato !in listOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma")) {
            throw ErroDeConversao("Formato de áudio não suportado: ${opcoes.formato}.")
        }
        if (opcoes.bitrate != 0 && opcoes.bitrate !in Listas.BITRATES) throw ErroDeConversao("Bitrate fora da lista.")
        if (opcoes.taxaDeAmostragem != 0 && opcoes.taxaDeAmostragem !in Listas.TAXAS) throw ErroDeConversao("Taxa de amostragem fora da lista.")
        if (opcoes.formato == "opus" && opcoes.taxaDeAmostragem != 0 && opcoes.taxaDeAmostragem !in Listas.TAXAS_DO_OPUS) {
            throw ErroDeConversao("O Opus só grava em 8, 16, 24 ou 48 kHz.")
        }
        if (opcoes.canais !in listOf(0, 1, 2)) throw ErroDeConversao("Canais deve ser mono ou estéreo.")
        if (!opcoes.volume.isFinite() || opcoes.volume !in 0.0..4.0) throw ErroDeConversao("Volume deve ficar entre 0 e 400%.")
        if (!opcoes.velocidade.isFinite() || opcoes.velocidade !in 0.25..4.0) throw ErroDeConversao("Velocidade deve ficar entre 0,25x e 4x.")
        if (!opcoes.fadeEntrada.isFinite() || !opcoes.fadeSaida.isFinite() || opcoes.fadeEntrada !in 0.0..60.0 || opcoes.fadeSaida !in 0.0..60.0) {
            throw ErroDeConversao("Fade deve ficar entre 0 e 60 segundos.")
        }
        if (!opcoes.inicioSegundos.isFinite() || !opcoes.fimSegundos.isFinite() || opcoes.inicioSegundos < 0 || opcoes.fimSegundos < 0 ||
            opcoes.fimSegundos > 0 && opcoes.fimSegundos <= opcoes.inicioSegundos) throw ErroDeConversao("O fim do trecho deve ser maior que o início.")
    }

    private fun formaDoAudio(opcoes: Opcoes): List<String> = buildList {
        if (opcoes.taxaDeAmostragem != 0) addAll(listOf("-ar", opcoes.taxaDeAmostragem.toString()))
        if (opcoes.canais != 0) addAll(listOf("-ac", opcoes.canais.toString()))
    }

    fun codificacaoDeAudio(formato: String, bitrateKbps: Int): List<String> {
        val padrao = when (formato) {
            "opus" -> 128
            "ogg" -> 160
            else -> 192
        }
        val bitrate = "${if (bitrateKbps == 0) padrao else bitrateKbps}k"
        return when (formato) {
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", bitrate)
            "aac", "m4a" -> listOf("-c:a", "aac", "-b:a", bitrate)
            "opus" -> listOf("-c:a", "libopus", "-b:a", bitrate, "-vbr", "on")
            "ogg" -> listOf("-c:a", "libvorbis", "-b:a", bitrate)
            "wma" -> listOf("-c:a", "wmav2", "-b:a", bitrate)
            "wav" -> listOf("-c:a", "pcm_s16le")
            "flac" -> listOf("-c:a", "flac")
            else -> throw ErroDeConversao("Formato de áudio não suportado: $formato.")
        }
    }

    /** Converter, comprimir, cortar ou tirar o áudio de um vídeo, com o MPEG-4 ou o VP9 do FFmpeg. */
    fun video(entrada: String, saida: String, opcoes: Opcoes, sondagem: Sondagem, codificador: Codificador): List<String> {
        validarVideo(opcoes)
        require(codificador != Codificador.H264_CELULAR) { "O H.264 do celular usa videoParaOCelular." }
        val argumentos = prologo(entrada, opcoes.inicioSegundos, opcoes.fimSegundos)
        filtrosDeVideo(opcoes, sondagem, codificador)?.let { argumentos += listOf("-vf", it) }
        repetirGif(argumentos, opcoes)
        argumentos += listOf("-map", "0:v:0")
        val comAudio = !opcoes.removerAudio && sondagem.temAudio
        if (comAudio) argumentos += listOf("-map", "0:a:0?")

        argumentos += codificacaoDeVideo(opcoes, sondagem, codificador)

        if (!comAudio) {
            argumentos += "-an"
        } else {
            filtroDeVelocidadeDoAudio(opcoes.velocidade)?.let { argumentos += listOf("-af", it) }
            // O áudio sempre é refeito: copiar Opus para um MOV ou Vorbis para um MP4 quebra o arquivo.
            argumentos += when (opcoes.formato) {
                "webm" -> listOf("-c:a", "libopus", "-b:a", "128k")
                "avi" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
                else -> listOf("-c:a", "aac", "-b:a", "160k")
            }
        }
        if (opcoes.formato == "mp4" || opcoes.formato == "mov") argumentos += listOf("-movflags", "+faststart")
        argumentos += listOf("-map_metadata", "-1", saida)
        return argumentos
    }

    /** O tamanho e o ritmo dos quadros para o codificador do celular; null se o vídeo não disse o tamanho. */
    fun quadrosDoCelular(opcoes: Opcoes, sondagem: Sondagem): Quadros? {
        val virado = opcoes.graus == 90 || opcoes.graus == 270
        val fonte = if (virado) sondagem.copy(largura = sondagem.altura, altura = sondagem.largura) else sondagem
        val (largura, altura) = tamanhoFinal(opcoes, fonte)
            ?: (fonte.largura ?: return null) to (fonte.altura ?: return null)
        val porSegundo = if (opcoes.quadrosPorSegundo != 0) opcoes.quadrosPorSegundo
        else (sondagem.quadrosPorSegundo ?: 30.0).roundToInt().coerceIn(1, 60)
        return Quadros(par(largura), par(altura), porSegundo)
    }

    /**
     * O FFmpeg decodifica e aplica os filtros, e manda os quadros crus (YUV 4:2:0) pela saída
     * padrão, em ritmo constante, para o codificador H.264 do celular. O áudio, quando há, vai
     * para `audio` (AAC), e depois os dois se juntam (ver [juntar]).
     */
    fun videoParaOCelular(entrada: String, audio: String?, opcoes: Opcoes, sondagem: Sondagem, quadros: Quadros): List<String> {
        validarVideo(opcoes)
        val argumentos = prologo(entrada, opcoes.inicioSegundos, opcoes.fimSegundos, progresso = false)
        repetirGif(argumentos, opcoes)
        val filtros = listOfNotNull(filtrosDeVideo(opcoes, sondagem, Codificador.H264_CELULAR), "scale=${quadros.largura}:${quadros.altura}", "setsar=1", "format=yuv420p")
        argumentos += listOf(
            "-map", "0:v:0", "-vf", filtros.joinToString(","), "-an", "-sn", "-dn",
            "-r", quadros.porSegundo.toString(), "-fps_mode", "cfr",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1",
        )
        if (audio != null) {
            argumentos += listOf("-map", "0:a:0", "-vn", "-sn", "-dn")
            filtroDeVelocidadeDoAudio(opcoes.velocidade)?.let { argumentos += listOf("-af", it) }
            argumentos += listOf("-c:a", "aac", "-b:a", "160k", audio)
        }
        return argumentos
    }

    /** O vídeo do celular e o áudio num contêiner só, sem recodificar. */
    fun juntar(video: String, audio: String?, saida: String, formato: String): List<String> {
        val argumentos = mutableListOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-i", video)
        if (audio != null) argumentos += listOf("-i", audio)
        argumentos += listOf("-map", "0:v:0")
        if (audio != null) argumentos += listOf("-map", "1:a:0")
        argumentos += listOf("-c", "copy")
        if (formato == "mp4" || formato == "mov") argumentos += listOf("-movflags", "+faststart")
        argumentos += listOf("-map_metadata", "-1", saida)
        return argumentos
    }

    fun validarVideo(opcoes: Opcoes) {
        if (opcoes.repeticoesDoGif !in 1..20) throw ErroDeConversao("Escolha entre 1 e 20 repetições do GIF.")
        if (opcoes.formato !in listOf("mp4", "mkv", "mov", "webm", "avi")) throw ErroDeConversao("Contêiner de vídeo não suportado: ${opcoes.formato}.")
        if (opcoes.qualidadeDoVideo !in 0..100) throw ErroDeConversao("Qualidade deve estar entre 0 e 100.")
        if (opcoes.alturaDoVideo != 0 && opcoes.alturaDoVideo !in Listas.ALTURAS) throw ErroDeConversao("Resolução fora da lista.")
        if (opcoes.quadrosPorSegundo != 0 && opcoes.quadrosPorSegundo !in Listas.FPS) throw ErroDeConversao("FPS fora da lista.")
        if (opcoes.velocidade < 0.25 || opcoes.velocidade > 4) throw ErroDeConversao("Velocidade deve ficar entre 0,25x e 4x.")
        if (opcoes.graus !in listOf(0, 90, 180, 270)) throw ErroDeConversao("Rotação deve ser 0, 90, 180 ou 270 graus.")
    }

    private fun repetirGif(argumentos: MutableList<String>, opcoes: Opcoes) {
        if (opcoes.repeticoesDoGif > 1) argumentos.addAll(argumentos.indexOf("-i"), listOf("-stream_loop", (opcoes.repeticoesDoGif - 1).toString()))
    }

    fun filtrosDeVideo(opcoes: Opcoes, sondagem: Sondagem, codificador: Codificador): String? {
        val filtros = mutableListOf<String>()
        when (opcoes.graus) {
            90 -> filtros += "transpose=1"
            180 -> filtros += listOf("transpose=1", "transpose=1")
            270 -> filtros += "transpose=2"
        }
        if (opcoes.espelharHorizontal) filtros += "hflip"
        if (opcoes.espelharVertical) filtros += "vflip"
        if (opcoes.quadrosPorSegundo != 0) filtros += "fps=${opcoes.quadrosPorSegundo}"
        if (abs(opcoes.velocidade - 1.0) > 0.001) filtros += "setpts=${numero(1 / opcoes.velocidade)}*PTS"
        geometria(opcoes, sondagem)?.let { filtros += it }
        // Para o celular, o formato final (e o tamanho exato) entra depois, em videoParaOCelular.
        if (codificador != Codificador.H264_CELULAR) filtros += "format=yuv420p"
        return filtros.joinToString(",").ifEmpty { null }
    }

    private fun geometria(opcoes: Opcoes, sondagem: Sondagem): String? {
        // A rotação troca largura e altura antes da geometria.
        val virado = opcoes.graus == 90 || opcoes.graus == 270
        val fonte = if (virado) sondagem.copy(largura = sondagem.altura, altura = sondagem.largura) else sondagem
        val tamanho = tamanhoFinal(opcoes, fonte)
            ?: return if (opcoes.alturaDoVideo != 0) "scale=-2:${par(opcoes.alturaDoVideo)}" else null
        val (l, a) = tamanho
        return when (opcoes.encaixe) {
            Encaixe.RECORTAR -> "scale=$l:$a:force_original_aspect_ratio=increase,crop=$l:$a"
            Encaixe.AJUSTAR -> "scale=$l:$a:force_original_aspect_ratio=decrease,pad=$l:$a:(ow-iw)/2:(oh-ih)/2:color=0x000000"
            Encaixe.DESFOCAR ->
                "split[fundo][frente];" +
                    "[fundo]scale=$l:$a:force_original_aspect_ratio=increase,crop=$l:$a,gblur=sigma=24[desfocado];" +
                    "[frente]scale=$l:$a:force_original_aspect_ratio=decrease[principal];" +
                    "[desfocado][principal]overlay=(W-w)/2:(H-h)/2"
            Encaixe.ESTICAR -> "scale=$l:$a"
        }
    }

    /** O tamanho final em pixels pares, quando há proporção nova ou altura escolhida. */
    fun tamanhoFinal(opcoes: Opcoes, sondagem: Sondagem): Pair<Int, Int>? {
        val razao = opcoes.proporcao.razao
        val largura = sondagem.largura ?: 0
        val altura = sondagem.altura ?: 0
        if (opcoes.alturaDoVideo != 0) {
            if (razao != null) return par(opcoes.alturaDoVideo * razao.first / razao.second) to par(opcoes.alturaDoVideo)
            if (largura > 0 && altura > 0) return par((largura.toDouble() * opcoes.alturaDoVideo / altura).roundToInt()) to par(opcoes.alturaDoVideo)
            return null
        }
        if (razao == null || largura <= 0 || altura <= 0) return null
        // Mantém a maior dimensão do original dentro da nova proporção.
        val pelaAltura = altura.toDouble() * razao.first / razao.second
        return if (pelaAltura <= largura) par(pelaAltura.roundToInt()) to par(altura)
        else par(largura) to par((largura.toDouble() * razao.second / razao.first).roundToInt())
    }

    private fun par(valor: Int): Int = max(2, if (valor % 2 == 0) valor else valor - 1)

    private fun codificacaoDeVideo(opcoes: Opcoes, @Suppress("UNUSED_PARAMETER") sondagem: Sondagem, codificador: Codificador): List<String> = when (codificador) {
        Codificador.MPEG4 -> listOf("-c:v", "mpeg4", "-q:v", escala(opcoes.qualidadeDoVideo, pior = 20, melhor = 2).toString(), "-tag:v", if (opcoes.formato == "avi") "XVID" else "mp4v")
        Codificador.VP9 -> listOf(
            "-c:v", codificador.nome, "-crf", escala(opcoes.qualidadeDoVideo, pior = 63, melhor = 15).toString(), "-b:v", "0",
            // "realtime" com cpu-used 8: o VP9 em software no celular, num tempo razoável.
            "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1",
        )
        Codificador.H264_CELULAR -> error("O H.264 do celular não passa por aqui.")
    }

    /** kbps: de 0,04 bit por pixel (qualidade 0) a 0,2 (qualidade 100). */
    fun bitrateDoVideo(largura: Int, altura: Int, fps: Double, qualidade: Int): Int {
        val bitsPorPixel = 0.04 + 0.16 * (qualidade.coerceIn(0, 100) / 100.0)
        return max(300, (largura.toDouble() * altura * fps * bitsPorPixel / 1000).roundToInt())
    }

    /** Qualidade 0–100 da tela para a escala do codificador (menor = melhor), como o Crf do desktop. */
    fun escala(qualidade: Int, pior: Int, melhor: Int): Int =
        (pior - (pior - melhor) * (qualidade.coerceIn(0, 100) / 100.0)).roundToInt()

    fun filtroDeVelocidadeDoAudio(velocidade: Double): String? {
        if (abs(velocidade - 1.0) <= 0.001) return null
        // O atempo aceita de 0,5x a 2x por vez; fora disso, encadeia.
        var resto = velocidade
        val etapas = mutableListOf<String>()
        while (resto > 2.0 && etapas.size < 4) { etapas += "atempo=2.0"; resto /= 2.0 }
        while (resto < 0.5 && etapas.size < 4) { etapas += "atempo=0.5"; resto /= 0.5 }
        etapas += "atempo=${numero(resto)}"
        return etapas.joinToString(",")
    }

    fun gif(entrada: String, saida: String, opcoes: Opcoes): List<String> {
        if (opcoes.fpsDoGif !in Listas.FPS_DO_GIF || opcoes.larguraDoGif !in Listas.LARGURAS_DO_GIF) throw ErroDeConversao("GIF fora das opções.")
        // Sem corte, o GIF fica nos 10 primeiros segundos, como no desktop; no máximo 2 minutos.
        val fim = when {
            opcoes.fimSegundos > opcoes.inicioSegundos -> minOf(opcoes.fimSegundos, opcoes.inicioSegundos + 120)
            else -> opcoes.inicioSegundos + 10
        }
        val argumentos = prologo(entrada, opcoes.inicioSegundos, fim)
        argumentos += listOf(
            "-vf",
            "fps=${opcoes.fpsDoGif},scale=${opcoes.larguraDoGif}:-2:flags=lanczos,split[paleta][gif];" +
                "[paleta]palettegen=stats_mode=diff[p];" +
                "[gif][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
            "-loop", "0", "-an", "-sn", "-dn", saida,
        )
        return argumentos
    }

    /** Quadros do vídeo: `padrao` é um caminho com %04d. */
    fun quadros(entrada: String, padrao: String, opcoes: Opcoes): List<String> {
        if (opcoes.quadrosExtraidosPorSegundo !in Listas.TAXAS_DE_QUADROS) throw ErroDeConversao("Taxa de quadros fora da lista.")
        if (opcoes.maximoDeQuadros !in 1..5000) throw ErroDeConversao("Limite de quadros deve ficar entre 1 e 5000.")
        val argumentos = prologo(entrada, opcoes.inicioSegundos, opcoes.fimSegundos)
        argumentos += listOf("-vf", "fps=${numero(opcoes.quadrosExtraidosPorSegundo)}", "-frames:v", opcoes.maximoDeQuadros.toString(), "-an", "-sn", "-dn")
        // WEBP sai em PNG e o Android recodifica (o FFmpeg daqui não tem o libwebp).
        if (opcoes.formato == "jpg") argumentos += listOf("-q:v", "3")
        argumentos += padrao
        return argumentos
    }

    /**
     * Transcrição com o filtro whisper do FFmpeg (o whisper.cpp embutido), como no desktop.
     * `formatoDoMotor`: "text", "srt" ou "json". O áudio vai inteiro para o filtro, que já o
     * converte para 16 kHz mono.
     */
    fun transcrever(
        entrada: String,
        destino: String,
        modelo: String,
        idioma: String,
        formatoDoMotor: String,
        inicio: Double = 0.0,
        fim: Double = 0.0,
        linhas: Int = 4,
    ): List<String> {
        if (formatoDoMotor !in listOf("text", "srt", "json")) throw ErroDeConversao("Formato interno de transcrição inválido.")
        if (idioma !in Listas.IDIOMAS.keys) throw ErroDeConversao("Idioma fora da lista.")
        val filtro = "whisper=" + listOf(
            "model=${caminhoNoFiltro(modelo)}",
            "language=$idioma",
            "destination=${caminhoNoFiltro(destino)}",
            "format=$formatoDoMotor",
            "queue=$JANELA_DO_WHISPER",
            "use_gpu=false",
        ).joinToString(":")
        val argumentos = prologo(entrada, inicio, fim)
        // "info": o filtro avisa no log quando começa cada bloco de 30 s, e é daí que sai a porcentagem.
        argumentos[argumentos.indexOf("-v") + 1] = "info"
        // Mais de 4 linhas de processamento costuma deixar o Whisper mais lento no celular (os núcleos econômicos atrasam).
        argumentos += listOf("-filter_threads", linhas.coerceIn(1, 4).toString())
        argumentos += listOf("-vn", "-sn", "-dn", "-af", filtro, "-f", "null", "-")
        return argumentos
    }

    /** Um caminho dentro de um filtro: entre aspas, com \, : e ' escapados (FfmpegFilterEscaping). */
    fun caminhoNoFiltro(caminho: String): String =
        "'" + caminho.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'") + "'"

    /** Uma imagem (em PNG, já girada e redimensionada pelo Android) nos formatos que o Android não grava. */
    fun imagem(entrada: String, saida: String, formato: String, qualidade: Int): List<String> {
        val argumentos = mutableListOf("-hide_banner", "-nostdin", "-v", "error", "-y", "-i", entrada)
        argumentos += when (formato) {
            // AVIF: qualidade vira o CRF do libaom (63 = pior, 10 = ótimo); still-picture para foto.
            "avif" -> listOf("-c:v", "libaom-av1", "-still-picture", "1", "-crf", escala(qualidade, pior = 55, melhor = 12).toString(), "-cpu-used", "6", "-pix_fmt", "yuv420p")
            "tiff" -> listOf("-c:v", "tiff", "-compression_algo", "deflate")
            "bmp" -> listOf("-c:v", "bmp", "-pix_fmt", "bgr24")
            "gif" -> listOf("-vf", "split[a][b];[a]palettegen[p];[b][p]paletteuse")
            else -> throw ErroDeConversao("Formato de imagem não suportado pelo FFmpeg: $formato.")
        }
        argumentos += listOf("-frames:v", "1", "-update", "1", saida)
        return argumentos
    }
}
