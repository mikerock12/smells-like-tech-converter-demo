namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>
/// Escapa valores que entram em um filtergraph. Caminhos de arquivo do usuario nunca
/// sao concatenados crus dentro de um filtro.
/// </summary>
public static class FfmpegFilterEscaping
{
    public static string QuotePath(string path)
    {
        var escaped = path
            .Replace(@"\", @"\\", StringComparison.Ordinal)
            .Replace(":", @"\:", StringComparison.Ordinal)
            .Replace("'", @"\'", StringComparison.Ordinal);
        return $"'{escaped}'";
    }

    /// <summary>Converte #RRGGBB para o formato de cor aceito pelo FFmpeg.</summary>
    public static string Color(string hexColor) => "0x" + hexColor.TrimStart('#').ToUpperInvariant();
}
