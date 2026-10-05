namespace SmellsLikeTech.Converter.Core.Media;

/// <summary>
/// Allowlist de extensoes conhecidas. Nada fora desta tabela vira job:
/// a extensao sozinha nunca decide a conversao, mas decide o que sequer e aceito.
/// </summary>
public static class FormatCatalog
{
    public static IReadOnlyList<string> ImageFormats { get; } =
        ["jpg", "jpeg", "png", "webp", "avif", "tiff", "tif", "bmp", "gif", "ico", "heic", "heif"];

    public static IReadOnlyList<string> VideoFormats { get; } =
        ["mp4", "mkv", "mov", "avi", "webm", "mpeg", "mpg", "m4v", "ts", "wmv", "flv"];

    public static IReadOnlyList<string> AudioFormats { get; } =
        ["mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "wma"];

    public static IReadOnlyList<string> DocumentFormats { get; } =
        ["doc", "docx", "odt", "rtf", "md", "markdown", "html", "htm", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub"];

    public static IReadOnlyList<string> TextFormats { get; } = ["txt"];

    public static IReadOnlyList<string> PdfFormats { get; } = ["pdf"];

    /// <summary>Formatos de saida de audio aceitos pelos engines locais.</summary>
    public static IReadOnlyList<string> AudioOutputFormats { get; } =
        ["mp3", "wav", "flac", "aac", "m4a", "ogg", "opus"];

    /// <summary>Containers de video aceitos como saida.</summary>
    public static IReadOnlyList<string> VideoOutputFormats { get; } =
        ["mp4", "mkv", "mov", "webm", "avi"];

    /// <summary>Formatos de imagem aceitos como saida.</summary>
    public static IReadOnlyList<string> ImageOutputFormats { get; } =
        ["jpg", "png", "webp", "avif", "tiff", "bmp", "gif", "ico"];

    private static readonly Dictionary<string, MediaKind> KindByExtension = Build();

    public static MediaKind KindOfExtension(string extension)
    {
        var normalized = Normalize(extension);
        return KindByExtension.TryGetValue(normalized, out var kind) ? kind : MediaKind.Unknown;
    }

    public static MediaKind KindOfPath(string path) => KindOfExtension(Path.GetExtension(path));

    public static bool IsSupportedInput(string path) => KindOfPath(path) != MediaKind.Unknown;

    /// <summary>
    /// Extensao em minusculas, sem ponto e validada. Impede que nome de arquivo do usuario
    /// vire argumento arbitrario para um processo externo.
    /// </summary>
    public static string Normalize(string extension)
    {
        var value = extension.Trim().TrimStart('.').ToLowerInvariant();
        if (value.Length is < 1 or > 8 || !value.All(char.IsAsciiLetterOrDigit))
        {
            throw new ConversionException("invalid_extension", $"Extensão de arquivo inválida: '{extension}'.");
        }

        return value;
    }

    /// <summary>Extensao real gravada no disco para um formato logico.</summary>
    public static string FileExtension(string format) => Normalize(format) switch
    {
        "jpeg" => "jpg",
        "markdown" => "md",
        var other => other
    };

    private static Dictionary<string, MediaKind> Build()
    {
        var map = new Dictionary<string, MediaKind>(StringComparer.OrdinalIgnoreCase);
        Add(map, ImageFormats, MediaKind.Image);
        Add(map, VideoFormats, MediaKind.Video);
        Add(map, AudioFormats, MediaKind.Audio);
        Add(map, PdfFormats, MediaKind.Pdf);
        Add(map, DocumentFormats, MediaKind.Document);
        Add(map, TextFormats, MediaKind.Text);
        return map;
    }

    private static void Add(Dictionary<string, MediaKind> map, IReadOnlyList<string> formats, MediaKind kind)
    {
        foreach (var format in formats)
        {
            map[format] = kind;
        }
    }
}
