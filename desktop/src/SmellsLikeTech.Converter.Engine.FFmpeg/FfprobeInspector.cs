using System.Globalization;
using System.Text.Json;
using System.Text.Json.Serialization;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>
/// Deteccao completa da midia: container, codec, resolucao, FPS, bitrate, duracao,
/// faixas de audio e rotacao. E o que permite a interface oferecer so o que faz sentido.
/// </summary>
public sealed class FfprobeInspector(Func<FfmpegOptions> optionsAccessor, ProcessRunner processRunner) : IMediaInspector
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = false };

    public bool CanInspect(MediaKind kind) => kind is MediaKind.Video or MediaKind.Audio;

    public async Task<MediaInfo> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var file = new FileInfo(path);
        if (!file.Exists)
        {
            throw new ConversionException("input_not_found", "O arquivo não foi encontrado.");
        }

        var command = new CommandSpec(optionsAccessor().FfprobePath,
        [
            "-hide_banner",
            "-v", "error",
            "-print_format", "json",
            "-show_streams",
            "-show_format",
            "-protocol_whitelist", "file",
            path
        ]);

        var result = await processRunner.RunAsync(
            command,
            workingDirectory: file.DirectoryName ?? Path.GetTempPath(),
            temporaryDirectory: Path.GetTempPath(),
            cancellationToken: cancellationToken);

        if (result.ExitCode != 0)
        {
            throw new ConversionException(
                "invalid_media",
                "O FFprobe não reconheceu este arquivo como mídia válida.");
        }

        ProbeDocument document;
        try
        {
            document = JsonSerializer.Deserialize<ProbeDocument>(result.StandardOutput, Json)
                ?? throw new JsonException("Resposta vazia.");
        }
        catch (JsonException exception)
        {
            throw new ConversionException("invalid_probe_response", "O FFprobe devolveu metadados inválidos.", exception);
        }

        var streams = document.Streams ?? [];
        var videoStreams = streams
            .Where(stream => string.Equals(stream.CodecType, "video", StringComparison.Ordinal))
            .Where(stream => !IsCoverArt(stream))
            .Select(stream => new VideoStreamInfo(
                stream.Index,
                stream.CodecName ?? "desconhecido",
                stream.Width ?? 0,
                stream.Height ?? 0,
                ParseFrameRate(stream.AverageFrameRate ?? stream.RFrameRate),
                ParseLong(stream.BitRate),
                stream.PixelFormat,
                ReadRotation(stream)))
            .ToArray();

        var audioStreams = streams
            .Where(stream => string.Equals(stream.CodecType, "audio", StringComparison.Ordinal))
            .Select(stream => new AudioStreamInfo(
                stream.Index,
                stream.CodecName ?? "desconhecido",
                ParseInt(stream.SampleRate),
                stream.Channels,
                ParseLong(stream.BitRate),
                stream.Tags is not null && stream.Tags.TryGetValue("language", out var language) ? language : null))
            .ToArray();

        var duration = ParseDouble(document.Format?.Duration);
        var primaryVideo = videoStreams.FirstOrDefault();
        var rotated = primaryVideo is not null && primaryVideo.RotationDegrees is 90 or 270;

        return new MediaInfo
        {
            Path = path,
            FileName = file.Name,
            Extension = FormatCatalog.Normalize(file.Extension),
            Kind = FormatCatalog.KindOfPath(path),
            SizeBytes = file.Length,
            Container = document.Format?.FormatName,
            Duration = duration is > 0 ? TimeSpan.FromSeconds(duration.Value) : null,
            VideoStreams = videoStreams,
            AudioStreams = audioStreams,
            Width = primaryVideo is null ? null : rotated ? primaryVideo.Height : primaryVideo.Width,
            Height = primaryVideo is null ? null : rotated ? primaryVideo.Width : primaryVideo.Height,
            HasAlpha = primaryVideo?.PixelFormat?.Contains("a", StringComparison.Ordinal) == true
        };
    }

    private static bool IsCoverArt(ProbeStream stream) =>
        stream.Disposition is not null
        && stream.Disposition.TryGetValue("attached_pic", out var attached)
        && attached == 1;

    private static int ReadRotation(ProbeStream stream)
    {
        var fromSideData = stream.SideDataList?
            .Where(data => data.Rotation is not null)
            .Select(data => data.Rotation!.Value)
            .FirstOrDefault();

        var degrees = (int)Math.Round(fromSideData ?? 0);
        if (degrees == 0 && stream.Tags is not null && stream.Tags.TryGetValue("rotate", out var raw)
            && int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var tagValue))
        {
            degrees = tagValue;
        }

        return (degrees % 360 + 360) % 360;
    }

    private static double? ParseFrameRate(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        var parts = value.Split('/');
        if (parts.Length != 2
            || !double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var numerator)
            || !double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var denominator)
            || denominator == 0)
        {
            return null;
        }

        var rate = numerator / denominator;
        return rate is > 0 and < 1000 ? Math.Round(rate, 3) : null;
    }

    private static double? ParseDouble(string? value) =>
        double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;

    private static long? ParseLong(string? value) =>
        long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;

    private static int? ParseInt(string? value) =>
        int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;

    private sealed record ProbeDocument(
        [property: JsonPropertyName("streams")] IReadOnlyList<ProbeStream>? Streams,
        [property: JsonPropertyName("format")] ProbeFormat? Format);

    private sealed record ProbeStream(
        [property: JsonPropertyName("index")] int Index,
        [property: JsonPropertyName("codec_type")] string? CodecType,
        [property: JsonPropertyName("codec_name")] string? CodecName,
        [property: JsonPropertyName("width")] int? Width,
        [property: JsonPropertyName("height")] int? Height,
        [property: JsonPropertyName("avg_frame_rate")] string? AverageFrameRate,
        [property: JsonPropertyName("r_frame_rate")] string? RFrameRate,
        [property: JsonPropertyName("bit_rate")] string? BitRate,
        [property: JsonPropertyName("pix_fmt")] string? PixelFormat,
        [property: JsonPropertyName("sample_rate")] string? SampleRate,
        [property: JsonPropertyName("channels")] int? Channels,
        [property: JsonPropertyName("tags")] IReadOnlyDictionary<string, string>? Tags,
        [property: JsonPropertyName("disposition")] IReadOnlyDictionary<string, int>? Disposition,
        [property: JsonPropertyName("side_data_list")] IReadOnlyList<ProbeSideData>? SideDataList);

    private sealed record ProbeSideData(
        [property: JsonPropertyName("rotation")] double? Rotation);

    private sealed record ProbeFormat(
        [property: JsonPropertyName("format_name")] string? FormatName,
        [property: JsonPropertyName("duration")] string? Duration,
        [property: JsonPropertyName("bit_rate")] string? BitRate);
}
