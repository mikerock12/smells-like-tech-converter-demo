package br.com.smellsliketech.converter.conversao

import android.net.Uri

/**
 * O tipo de um arquivo, pelo MIME que o Android informa e pela extensão. A extensão ganha
 * quando o MIME é genérico: o WhatsApp manda documento como application/octet-stream, e
 * legenda chega como text/plain.
 */
enum class Tipo(val rotulo: String) {
    IMAGEM("Imagem"), VIDEO("Vídeo"), AUDIO("Áudio"), PDF("PDF"), DOCUMENTO("Documento"), TEXTO("Texto"), LEGENDA("Legenda");

    companion object {
        /** Os mesmos formatos de entrada do aplicativo para Windows (FormatCatalog), mais os do celular. */
        val IMAGENS = setOf("jpg", "jpeg", "png", "webp", "avif", "tiff", "tif", "bmp", "gif", "ico", "heic", "heif")
        val VIDEOS = setOf("mp4", "mkv", "mov", "avi", "webm", "mpeg", "mpg", "m4v", "ts", "wmv", "flv", "3gp")
        val AUDIOS = setOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma", "amr", "3ga", "aiff", "aif")
        val DOCUMENTOS = setOf(
            "doc", "docx", "odt", "rtf", "md", "markdown", "html", "htm", "epub",
            "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp",
        )
        val LEGENDAS = setOf("srt", "vtt")

        fun de(mime: String?, nome: String): Tipo? {
            val extensao = nome.substringAfterLast('.', "").lowercase()
            porExtensao(extensao)?.let { return it }
            val tipo = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return when {
                tipo.startsWith("image/") -> IMAGEM
                tipo.startsWith("video/") -> VIDEO
                tipo.startsWith("audio/") -> AUDIO
                tipo == "application/pdf" -> PDF
                tipo == "application/x-subrip" || tipo == "text/vtt" -> LEGENDA
                tipo == "application/msword" || tipo == "application/rtf" || tipo == "text/rtf" ||
                    tipo == "text/html" || tipo == "text/markdown" || tipo == "text/csv" || tipo == "application/epub+zip" ||
                    tipo.startsWith("application/vnd.openxmlformats-officedocument.") ||
                    tipo.startsWith("application/vnd.oasis.opendocument.") -> DOCUMENTO
                tipo.startsWith("text/") -> TEXTO
                else -> null
            }
        }

        private fun porExtensao(extensao: String): Tipo? = when (extensao) {
            in IMAGENS -> IMAGEM
            in VIDEOS -> VIDEO
            in AUDIOS -> AUDIO
            "pdf" -> PDF
            in DOCUMENTOS -> DOCUMENTO
            in LEGENDAS -> LEGENDA
            "txt" -> TEXTO
            else -> null
        }
    }
}

data class Entrada(val uri: Uri, val nome: String, val tipo: Tipo, val bytes: Long) {
    val extensao: String get() = nome.substringAfterLast('.', "").lowercase()
}

private val AUDIO = listOf("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma")
private val VIDEO = listOf("mp4", "mkv", "mov", "webm", "avi")

/**
 * O que o app sabe fazer: as operações do aplicativo para Windows (OperationCatalog), mais
 * as que o celular ganhou (documentos, legenda). `formatos` são as saídas oferecidas, na
 * ordem da tela; a primeira é a padrão. `juntaTudo` = uma saída só para todos os arquivos.
 */
enum class Ferramenta(val titulo: String, val tipo: Tipo, val formatos: List<String> = emptyList(), val juntaTudo: Boolean = false) {
    IMAGEM_CONVERTER("Converter imagem", Tipo.IMAGEM, listOf("jpg", "png", "webp", "avif", "tiff", "bmp", "gif", "ico")),
    IMAGEM_COMPRIMIR("Comprimir imagem", Tipo.IMAGEM, listOf("jpg", "webp", "avif")),
    IMAGEM_REDIMENSIONAR("Redimensionar", Tipo.IMAGEM, listOf("jpg", "png", "webp", "avif")),
    IMAGEM_RECORTAR("Mudar a proporção", Tipo.IMAGEM, listOf("jpg", "png", "webp")),
    IMAGEM_GIRAR("Girar e espelhar", Tipo.IMAGEM, listOf("jpg", "png", "webp")),
    IMAGEM_ICONE("Criar ícone (ICO)", Tipo.IMAGEM, listOf("ico")),
    IMAGENS_PARA_PDF("Imagens em um PDF", Tipo.IMAGEM, listOf("pdf"), juntaTudo = true),
    IMAGEM_OCR("Texto da imagem (OCR)", Tipo.IMAGEM, listOf("txt", "md")),
    IMAGEM_NARRAR("Ler a imagem em voz alta", Tipo.IMAGEM, AUDIO),
    GIF_PARA_VIDEO("GIF → vídeo", Tipo.IMAGEM, VIDEO),

    VIDEO_CONVERTER("Converter vídeo", Tipo.VIDEO, VIDEO),
    VIDEO_COMPRIMIR("Comprimir vídeo", Tipo.VIDEO, listOf("mp4", "webm")),
    VIDEO_CORTAR("Cortar vídeo", Tipo.VIDEO, VIDEO),
    VIDEO_SEM_AUDIO("Tirar o áudio", Tipo.VIDEO, VIDEO),
    VIDEO_PARA_AUDIO("Vídeo → áudio", Tipo.VIDEO, AUDIO),
    VIDEO_GIF("Vídeo → GIF", Tipo.VIDEO, listOf("gif")),
    VIDEO_QUADROS("Vídeo → imagens", Tipo.VIDEO, listOf("png", "jpg", "webp")),
    // A transcrição volta como texto na tela; o formato a pessoa escolhe depois (Transcricao.FORMATOS).
    VIDEO_TRANSCREVER("Transcrever (Whisper)", Tipo.VIDEO, listOf("txt")),

    AUDIO_CONVERTER("Converter áudio", Tipo.AUDIO, AUDIO),
    AUDIO_CORTAR("Cortar áudio", Tipo.AUDIO, AUDIO),
    AUDIO_EDITAR("Editar áudio", Tipo.AUDIO, AUDIO),
    AUDIO_TRANSCREVER("Transcrever (Whisper)", Tipo.AUDIO, listOf("txt")),

    PDF_JUNTAR("Juntar PDFs", Tipo.PDF, listOf("pdf"), juntaTudo = true),
    PDF_DIVIDIR("Dividir PDF", Tipo.PDF, listOf("pdf")),
    PDF_GIRAR("Girar páginas", Tipo.PDF, listOf("pdf")),
    PDF_COMPRIMIR("Comprimir PDF", Tipo.PDF, listOf("pdf")),
    PDF_PARA_IMAGENS("PDF → imagens", Tipo.PDF, listOf("png", "jpg", "webp")),
    PDF_PARA_DOCUMENTO("PDF → documento (com OCR)", Tipo.PDF, listOf("docx", "txt", "md", "html")),
    PDF_NARRAR("Ler o PDF em voz alta", Tipo.PDF, AUDIO),

    DOCUMENTO_CONVERTER("Converter documento", Tipo.DOCUMENTO, listOf("pdf", "docx", "txt", "html", "md", "csv")),
    DOCUMENTO_NARRAR("Ler o documento em voz alta", Tipo.DOCUMENTO, AUDIO),

    TEXTO_NARRAR("Narrar (texto → áudio)", Tipo.TEXTO, AUDIO),
    TEXTO_CONVERTER("Converter texto", Tipo.TEXTO, listOf("pdf", "docx", "html", "md")),

    LEGENDA_CONVERTER("Converter legenda", Tipo.LEGENDA, listOf("srt", "vtt", "txt")),
    LEGENDA_NARRAR("Narrar a legenda", Tipo.LEGENDA, AUDIO);

    val transcreve: Boolean get() = this == VIDEO_TRANSCREVER || this == AUDIO_TRANSCREVER
    val narra: Boolean get() = this in setOf(TEXTO_NARRAR, LEGENDA_NARRAR, PDF_NARRAR, DOCUMENTO_NARRAR, IMAGEM_NARRAR)

    /** As escolhas com que a ferramenta abre: a saída mais útil e os ajustes de cada uma. */
    fun opcoesPadrao(): Opcoes = when (this) {
        IMAGEM_COMPRIMIR -> Opcoes(formato = "jpg", qualidade = 70, larguraMaxima = 1920)
        IMAGEM_REDIMENSIONAR -> Opcoes(formato = "jpg", larguraMaxima = 1280)
        IMAGEM_RECORTAR -> Opcoes(formato = "jpg", proporcao = Proporcao.QUADRADA)
        IMAGEM_GIRAR -> Opcoes(formato = "jpg", graus = 90)
        VIDEO_COMPRIMIR -> Opcoes(formato = "mp4", alturaDoVideo = 720, qualidadeDoVideo = 40)
        VIDEO_SEM_AUDIO -> Opcoes(formato = "mp4", removerAudio = true)
        PDF_GIRAR -> Opcoes(formato = "pdf", graus = 90)
        else -> Opcoes(formato = formatos.firstOrNull() ?: "")
    }

    companion object {
        fun para(tipo: Tipo): List<Ferramenta> = entries.filter { it.tipo == tipo }
        fun para(tipo: Tipo, extensoes: List<String>): List<Ferramenta> = para(tipo).filter {
            it != GIF_PARA_VIDEO || extensoes.isNotEmpty() && extensoes.all { extensao -> extensao == "gif" }
        }
    }
}

enum class Proporcao(val rotulo: String, val largura: Int, val altura: Int) {
    ORIGINAL("Original", 0, 0), WIDE("16:9", 16, 9), VERTICAL("9:16", 9, 16), QUADRADA("1:1", 1, 1),
    CLASSICA("4:3", 4, 3), FOTO("3:2", 3, 2), ULTRA("21:9", 21, 9);

    val razao: Pair<Int, Int>? get() = if (this == ORIGINAL) null else largura to altura
}

/** Como o conteúdo se encaixa quando a proporção muda (FitMode do desktop). */
enum class Encaixe(val rotulo: String) { RECORTAR("Recortar"), AJUSTAR("Barras"), DESFOCAR("Fundo desfocado"), ESTICAR("Esticar") }

enum class ModoDeDividir(val rotulo: String) { CADA_PAGINA("Uma por página"), PAGINAS("Páginas escolhidas"), BLOCOS("Em blocos") }

enum class CompressaoPdf(val rotulo: String, val ladoMaximo: Int, val qualidade: Int) {
    LEVE("Leve", 3000, 85), EQUILIBRADA("Equilibrada", 2000, 72), FORTE("Forte", 1400, 55);
}

/**
 * As escolhas de cada ferramenta. Cada motor lê só o que lhe interessa; os valores seguem as
 * listas permitidas do aplicativo para Windows (AudioAllowlist, VideoConvertOptions…).
 */
data class Opcoes(
    /** A extensão de saída: uma das `formatos` da ferramenta. */
    val formato: String,

    // Imagem
    val qualidade: Int = 85,
    /** 0 = mantém a largura original. */
    val larguraMaxima: Int = 0,
    val proporcao: Proporcao = Proporcao.ORIGINAL,
    val encaixe: Encaixe = Encaixe.RECORTAR,
    val graus: Int = 0,
    val espelharHorizontal: Boolean = false,
    val espelharVertical: Boolean = false,

    // Áudio (também a trilha de vídeo → áudio e a narração)
    /** kbps; 0 = o padrão do formato. */
    val bitrate: Int = 0,
    /** Hz; 0 = mantém. */
    val taxaDeAmostragem: Int = 0,
    /** 1 mono, 2 estéreo; 0 = mantém. */
    val canais: Int = 0,
    val normalizar: Boolean = false,
    val volume: Double = 1.0,
    val fadeEntrada: Double = 0.0,
    val fadeSaida: Double = 0.0,
    val inicioSegundos: Double = 0.0,
    val fimSegundos: Double = 0.0,

    // Vídeo
    /** Altura em pixels; 0 = mantém. */
    val alturaDoVideo: Int = 0,
    /** Quadros por segundo; 0 = mantém. */
    val quadrosPorSegundo: Int = 0,
    /** 0 = arquivo menor, 100 = melhor imagem. */
    val qualidadeDoVideo: Int = 65,
    val velocidade: Double = 1.0,
    val removerAudio: Boolean = false,

    // GIF e quadros
    val fpsDoGif: Int = 12,
    val larguraDoGif: Int = 480,
    val quadrosExtraidosPorSegundo: Double = 1.0,
    val maximoDeQuadros: Int = 300,
    val repeticoesDoGif: Int = 1,
    val atrasoLegendaSegundos: Double = 0.0,

    // Transcrição
    val modelo: String = "small",
    val idioma: String = "pt",

    // Narração
    val velocidadeDaFala: Float = 1.0f,
    val vozDaNarracao: String = "pf_dora",

    // PDF
    val dpi: Int = 150,
    val compressaoPdf: CompressaoPdf = CompressaoPdf.EQUILIBRADA,
    val modoDeDividir: ModoDeDividir = ModoDeDividir.CADA_PAGINA,
    val paginas: String = "1",
    val paginasPorBloco: Int = 10,
)

/** As listas permitidas, iguais às do aplicativo para Windows. */
object Listas {
    val BITRATES = listOf(64, 96, 128, 160, 192, 256, 320)
    val TAXAS = listOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)
    val ALTURAS = listOf(2160, 1440, 1080, 720, 480, 360)
    val FPS = listOf(24, 25, 30, 50, 60)
    val VELOCIDADES = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 4.0)
    val FPS_DO_GIF = listOf(8, 10, 12, 15, 20, 24)
    val LARGURAS_DO_GIF = listOf(240, 320, 480, 640, 800)
    val TAXAS_DE_QUADROS = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0)
    val DPIS = listOf(72, 96, 150, 200, 300, 600)
    val IDIOMAS = linkedMapOf(
        "pt" to "Português", "auto" to "Detectar", "en" to "Inglês", "es" to "Espanhol",
        "fr" to "Francês", "de" to "Alemão", "it" to "Italiano", "ja" to "Japonês",
    )

    /** O Opus só aceita estas taxas; as outras o FFmpeg recusaria. */
    val TAXAS_DO_OPUS = setOf(8_000, 16_000, 24_000, 48_000)
    fun semPerda(formato: String) = formato == "wav" || formato == "flac"
}

/** Um arquivo pronto, já salvo em Downloads/SmellsLikeTech. */
data class Resultado(val nome: String, val uri: Uri, val mime: String, val bytes: Long)

class ErroDeConversao(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/** O MIME de cada saída, para o Android abrir com o app certo. */
object Mimes {
    fun de(extensao: String): String = when (extensao.lowercase()) {
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "opus" -> "audio/ogg"; "wma" -> "audio/x-ms-wma"
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "gif" -> "image/gif"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "avif" -> "image/avif"; "tiff", "tif" -> "image/tiff"; "bmp" -> "image/bmp"; "ico" -> "image/x-icon"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "html" -> "text/html"; "txt" -> "text/plain"; "md" -> "text/markdown"; "csv" -> "text/csv"
        "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }
}
