using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Core.Operations;

/// <summary>Uma capacidade oferecida pelo produto e as condicoes em que ela aparece.</summary>
public sealed record OperationDescriptor
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public required string Description { get; init; }
    public required IReadOnlyList<MediaKind> InputKinds { get; init; }
    public required Func<MediaInfo?, JobOptions> DefaultOptions { get; init; }

    public bool RequiresAudioTrack { get; init; }
    public bool RequiresVideoTrack { get; init; }

    /// <summary>Falso enquanto o motor local do marco correspondente nao existir.</summary>
    public bool Available { get; init; } = true;

    public string? Milestone { get; init; }

    public IReadOnlyList<string> OutputFormats { get; init; } = [];

    /// <summary>Quando definido, so estes documentos sao aceitos (os que o motor sabe ler).</summary>
    public IReadOnlyList<string>? DocumentExtensions { get; init; }

    public bool Accepts(MediaInfo info)
    {
        if (!InputKinds.Contains(info.Kind))
        {
            return false;
        }

        if (info.Kind == MediaKind.Document && DocumentExtensions is { } documentos
            && !documentos.Contains(info.Extension.TrimStart('.'), StringComparer.OrdinalIgnoreCase))
        {
            return false;
        }

        if (RequiresAudioTrack && info.Kind != MediaKind.Audio && !info.HasAudio)
        {
            return false;
        }

        return !RequiresVideoTrack || info.HasVideo;
    }
}

/// <summary>
/// Catalogo unico de operacoes. A interface consulta daqui o que oferecer para o arquivo
/// detectado - nunca oferece combinacoes sem sentido so para dizer que converte tudo.
/// </summary>
public static class OperationCatalog
{
    public static IReadOnlyList<OperationDescriptor> All { get; } = Build();

    public static OperationDescriptor? Find(string id) =>
        All.FirstOrDefault(descriptor => string.Equals(descriptor.Id, id, StringComparison.Ordinal));

    /// <summary>Operacoes compativeis com o arquivo detectado, disponiveis primeiro.</summary>
    public static IReadOnlyList<OperationDescriptor> For(MediaInfo info) =>
        [.. All.Where(descriptor => descriptor.Accepts(info)).OrderByDescending(descriptor => descriptor.Available)];

    private static IReadOnlyList<OperationDescriptor> Build() =>
    [
        new OperationDescriptor
        {
            Id = OperationIds.ImageConvert,
            Title = "Converter imagem",
            Description = "Formato, tamanho, proporção, qualidade, rotação e transparência.",
            InputKinds = [MediaKind.Image],
            OutputFormats = FormatCatalog.ImageOutputFormats,
            DefaultOptions = info => new ImageConvertOptions
            {
                OutputFormat = SuggestImageFormat(info)
            }
        },
        new OperationDescriptor
        {
            Id = OperationIds.VideoConvert,
            Title = "Converter vídeo",
            Description = "Formato, resolução, proporção, FPS, compressão, corte e velocidade.",
            InputKinds = [MediaKind.Video],
            RequiresVideoTrack = true,
            OutputFormats = FormatCatalog.VideoOutputFormats,
            DefaultOptions = info => new VideoConvertOptions
            {
                OutputFormat = string.Equals(info?.Extension, "mp4", StringComparison.OrdinalIgnoreCase) ? "mkv" : "mp4"
            }
        },
        new OperationDescriptor
        {
            Id = OperationIds.VideoExtractAudio,
            Title = "Vídeo → áudio",
            Description = "Extrai a trilha sonora em MP3, WAV, FLAC, AAC, M4A, OGG ou OPUS.",
            InputKinds = [MediaKind.Video],
            RequiresAudioTrack = true,
            OutputFormats = FormatCatalog.AudioOutputFormats,
            DefaultOptions = _ => new ExtractAudioOptions { OutputFormat = "mp3", AudioBitrateKbps = 192 }
        },
        new OperationDescriptor
        {
            Id = OperationIds.VideoToGif,
            Title = "Vídeo → GIF",
            Description = "Trecho curto em GIF animado com paleta otimizada.",
            InputKinds = [MediaKind.Video],
            RequiresVideoTrack = true,
            OutputFormats = ["gif"],
            DefaultOptions = _ => new VideoToGifOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.VideoToFrames,
            Title = "Vídeo → imagens",
            Description = "Extrai quadros como PNG, JPG ou WEBP.",
            InputKinds = [MediaKind.Video],
            RequiresVideoTrack = true,
            OutputFormats = ["png", "jpg", "webp"],
            DefaultOptions = _ => new VideoToFramesOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.AudioConvert,
            Title = "Converter áudio",
            Description = "Formato, bitrate, sample rate, mono/estéreo, normalização e corte.",
            InputKinds = [MediaKind.Audio],
            OutputFormats = FormatCatalog.AudioOutputFormats,
            DefaultOptions = info => new AudioConvertOptions
            {
                OutputFormat = string.Equals(info?.Extension, "mp3", StringComparison.OrdinalIgnoreCase) ? "wav" : "mp3"
            }
        },
        new OperationDescriptor
        {
            Id = OperationIds.SpeechTranscribe,
            Title = "Áudio/vídeo → texto",
            Description = "Transcrição local com Whisper em TXT, SRT ou VTT.",
            InputKinds = [MediaKind.Audio, MediaKind.Video],
            RequiresAudioTrack = true,
            OutputFormats = TranscribeOptions.OutputFormats,
            // Produto brasileiro: começa em português. A detecção automática continua
            // disponível, mas informar o idioma evita qualquer deriva no reconhecimento.
            DefaultOptions = _ => new TranscribeOptions { Language = "pt" }
        },
        new OperationDescriptor
        {
            Id = OperationIds.SpeechSynthesize,
            Title = "Texto, PDF ou imagem → áudio",
            Description = "Narração local Kokoro-82M: texto, legenda, PDF (com OCR nas páginas escaneadas), imagem, DOCX, Markdown ou HTML.",
            InputKinds = [MediaKind.Text, MediaKind.Pdf, MediaKind.Image, MediaKind.Document],
            DocumentExtensions = ["docx", "md", "markdown", "html", "htm"],
            OutputFormats = FormatCatalog.AudioOutputFormats,
            DefaultOptions = _ => new SynthesizeOptions()
        },

        new OperationDescriptor
        {
            Id = OperationIds.PdfToDocument,
            Title = "PDF → documento",
            Description = "Extrai o conteúdo para DOCX, TXT, Markdown ou HTML. PDF escaneado passa por OCR.",
            InputKinds = [MediaKind.Pdf],
            OutputFormats = PdfToDocumentOptions.OutputFormats,
            DefaultOptions = _ => new PdfToDocumentOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.OcrImageToText,
            Title = "Imagem → texto (OCR)",
            Description = "Reconhece o texto da imagem com o motor do Windows.",
            InputKinds = [MediaKind.Image],
            OutputFormats = OcrOptions.OutputFormats,
            DefaultOptions = _ => new OcrOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.PdfToImage,
            Title = "PDF → imagens",
            Description = "Uma imagem por página, na resolução escolhida.",
            InputKinds = [MediaKind.Pdf],
            OutputFormats = PdfToImageOptions.OutputFormats,
            DefaultOptions = _ => new PdfToImageOptions()
        },

        new OperationDescriptor
        {
            Id = OperationIds.PdfMerge,
            Title = "Juntar PDF",
            Description = "Vários PDFs viram um só, na ordem escolhida.",
            InputKinds = [MediaKind.Pdf],
            OutputFormats = ["pdf"],
            DefaultOptions = _ => new PdfMergeOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.PdfSplit,
            Title = "Dividir PDF",
            Description = "Extrai páginas escolhidas ou separa em um arquivo por página ou por bloco.",
            InputKinds = [MediaKind.Pdf],
            OutputFormats = ["pdf"],
            DefaultOptions = _ => new PdfSplitOptions()
        },
        new OperationDescriptor
        {
            Id = OperationIds.ImageToPdf,
            Title = "Imagens → PDF",
            Description = "Uma imagem por página, em A4 ou no tamanho da imagem.",
            InputKinds = [MediaKind.Image],
            OutputFormats = ["pdf"],
            DefaultOptions = _ => new ImageToPdfOptions()
        },

        // Marcos seguintes: aparecem na interface como indisponíveis, sem prometer o que ainda não existe.
        new OperationDescriptor
        {
            Id = OperationIds.DocumentConvert,
            Title = "Converter documento",
            Description = "DOCX, XLSX, PPTX, ODT, Markdown e HTML via LibreOffice/Pandoc.",
            InputKinds = [MediaKind.Document],
            Available = false,
            Milestone = "Marco 7",
            OutputFormats = ["pdf", "docx", "html", "txt"],
            DefaultOptions = _ => new ImageConvertOptions()
        }
    ];

    private static string SuggestImageFormat(MediaInfo? info) =>
        string.Equals(info?.Extension, "webp", StringComparison.OrdinalIgnoreCase) ? "jpg" : "webp";
}
