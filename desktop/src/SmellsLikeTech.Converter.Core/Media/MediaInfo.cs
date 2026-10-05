namespace SmellsLikeTech.Converter.Core.Media;

/// <summary>Faixa de video detectada no arquivo.</summary>
public sealed record VideoStreamInfo(
    int Index,
    string Codec,
    int Width,
    int Height,
    double? FrameRate,
    long? BitrateBps,
    string? PixelFormat,
    int RotationDegrees);

/// <summary>Faixa de audio detectada no arquivo.</summary>
public sealed record AudioStreamInfo(
    int Index,
    string Codec,
    int? SampleRateHz,
    int? Channels,
    long? BitrateBps,
    string? Language);

/// <summary>
/// Tudo o que o aplicativo conseguiu descobrir sobre o arquivo arrastado.
/// A interface usa isto para oferecer somente operacoes compativeis.
/// </summary>
public sealed record MediaInfo
{
    public required string Path { get; init; }
    public required string FileName { get; init; }
    public required string Extension { get; init; }
    public required MediaKind Kind { get; init; }
    public long SizeBytes { get; init; }
    public string? Container { get; init; }
    public TimeSpan? Duration { get; init; }
    public IReadOnlyList<VideoStreamInfo> VideoStreams { get; init; } = [];
    public IReadOnlyList<AudioStreamInfo> AudioStreams { get; init; } = [];
    public int? Width { get; init; }
    public int? Height { get; init; }
    public bool HasAlpha { get; init; }
    public int? PageCount { get; init; }
    public string? ProbeError { get; init; }

    public bool HasVideo => VideoStreams.Count > 0;
    public bool HasAudio => AudioStreams.Count > 0;

    public double? AspectRatio => Width is > 0 && Height is > 0
        ? (double)Width.Value / Height.Value
        : null;

    public string SizeDisplay => FormatBytes(SizeBytes);

    public static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return unit == 0
            ? $"{bytes} {units[unit]}"
            : $"{value:0.##} {units[unit]}";
    }

    public static string FormatDuration(TimeSpan duration) =>
        duration.TotalHours >= 1
            ? $"{(int)duration.TotalHours:00}:{duration.Minutes:00}:{duration.Seconds:00}"
            : $"{duration.Minutes:00}:{duration.Seconds:00}";
}
