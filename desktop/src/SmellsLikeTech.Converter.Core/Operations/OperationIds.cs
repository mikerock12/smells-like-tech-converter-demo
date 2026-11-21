namespace SmellsLikeTech.Converter.Core.Operations;

/// <summary>
/// Identificadores estaveis de capacidade. Sao os mesmos nomes que o worker de nuvem
/// usara no contrato de job, entao nao devem mudar sem versionamento.
/// </summary>
public static class OperationIds
{
    public const string ImageConvert = "image.convert";
    public const string ImageProjectRender = "image.renderProject";

    public const string VideoConvert = "video.convert";
    public const string VideoExtractAudio = "video.extractAudio";
    public const string VideoToGif = "video.toGif";
    public const string VideoToFrames = "video.toFrames";

    public const string AudioConvert = "audio.convert";

    public const string SpeechTranscribe = "speech.transcribe";
    public const string SpeechSynthesize = "speech.synthesize";

    public const string PdfToDocument = "pdf.toDocument";
    public const string PdfToImage = "pdf.toImage";
    public const string OcrImageToText = "ocr.imageToText";

    public const string ImageToPdf = "pdf.fromImage";
    public const string PdfMerge = "pdf.merge";
    public const string PdfSplit = "pdf.split";

    /// <summary>Exportar um projeto do estudio (video, GIF, imagem, audio ou texto).</summary>
    public const string StudioExport = "studio.export";

    /// <summary>Separar um audio em faixas (voz, bateria, baixo, outros) pela IA local.</summary>
    public const string MusicSeparate = "music.separate";

    // Separar faixas no conversor (experimental): áudio vira .zip, vídeo perde ou mantém só a voz.
    public const string MusicStemsPackage = "music.stemsPackage";
    public const string VideoVoice = "video.voice";

    /// <summary>Restaurar áudio com IA (experimental): diagnóstico e reparo conservador no núcleo nativo.</summary>
    public const string AudioRestore = "audio.restore";

    // Ainda sem engine local.
    public const string DocumentConvert = "document.convert";

    /// <summary>Familia usada para limitar concorrencia na fila.</summary>
    public static string FamilyOf(string operation)
    {
        var separator = operation.IndexOf('.');
        return separator <= 0 ? operation : operation[..separator];
    }
}
