using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>
/// Converte a legenda SRT produzida pelo motor em VTT e valida os timestamps:
/// devem ser crescentes e nao podem passar da duracao da midia.
/// </summary>
public static partial class SubtitleConverter
{
    /// <summary>Um trecho de legenda com tempo de entrada, tempo de saida e texto.</summary>
    private sealed record SubtitleCue(TimeSpan Start, TimeSpan End, IReadOnlyList<string> Lines);

    /// <summary>
    /// Arruma a legenda crua do motor antes de virar arquivo final:
    /// numera a partir de 1, descarta marcadores de ruido inventados no silencio
    /// e nao deixa nenhum trecho passar da duracao da midia.
    /// </summary>
    public static async Task<int> NormalizeSrtAsync(
        string srtPath,
        TimeSpan? mediaDuration,
        CancellationToken cancellationToken)
    {
        var lines = await File.ReadAllLinesAsync(srtPath, Encoding.UTF8, cancellationToken);
        var cues = new List<SubtitleCue>();

        foreach (var cue in ParseSrt(lines))
        {
            if (cue.Lines.All(IsNonSpeechMarker))
            {
                continue;
            }

            var start = cue.Start;
            var end = cue.End;

            if (mediaDuration is { } duration)
            {
                if (start >= duration)
                {
                    continue;
                }

                if (end > duration)
                {
                    end = duration;
                }
            }

            if (end <= start)
            {
                continue;
            }

            cues.Add(cue with { Start = start, End = end });
        }

        var output = new List<string>(cues.Count * 4);
        for (var index = 0; index < cues.Count; index++)
        {
            output.Add((index + 1).ToString(CultureInfo.InvariantCulture));
            output.Add($"{Timestamp(cues[index].Start)} --> {Timestamp(cues[index].End)}");
            output.AddRange(cues[index].Lines);
            output.Add(string.Empty);
        }

        await File.WriteAllLinesAsync(srtPath, output, new UTF8Encoding(false), cancellationToken);
        return cues.Count;
    }

    private static IEnumerable<SubtitleCue> ParseSrt(IReadOnlyList<string> lines)
    {
        var index = 0;
        while (index < lines.Count)
        {
            if (string.IsNullOrWhiteSpace(lines[index]))
            {
                index++;
                continue;
            }

            // O numero do bloco e opcional para o parser: o que importa e o par de tempos.
            if (IndexLine().IsMatch(lines[index].Trim()))
            {
                index++;
                if (index >= lines.Count)
                {
                    yield break;
                }
            }

            var match = SrtTimestampLine().Match(lines[index]);
            if (!match.Success)
            {
                throw new ConversionException("invalid_srt", "O motor produziu uma legenda SRT inválida.");
            }

            var start = ParseTimestamp(match.Groups["start"].Value);
            var end = ParseTimestamp(match.Groups["end"].Value);
            index++;

            var text = new List<string>();
            while (index < lines.Count && !string.IsNullOrWhiteSpace(lines[index]))
            {
                text.Add(lines[index].Trim());
                index++;
            }

            if (text.Count > 0)
            {
                yield return new SubtitleCue(start, end, text);
            }
        }
    }

    /// <summary>
    /// Trechos como "[MÚSICA]", "(risos)" ou "[BLANK_AUDIO]" costumam ser invencao do
    /// motor sobre silencio e nao sao fala.
    /// </summary>
    private static bool IsNonSpeechMarker(string line)
    {
        var value = line.Trim();
        return value.Length > 1
            && ((value[0] == '[' && value[^1] == ']') || (value[0] == '(' && value[^1] == ')'));
    }

    private static string Timestamp(TimeSpan value) =>
        string.Create(
            CultureInfo.InvariantCulture,
            $"{(int)value.TotalHours:00}:{value.Minutes:00}:{value.Seconds:00},{value.Milliseconds:000}");

    public static async Task ConvertSrtToVttAsync(string srtPath, string vttPath, CancellationToken cancellationToken)
    {
        var lines = await File.ReadAllLinesAsync(srtPath, Encoding.UTF8, cancellationToken);
        var output = new List<string>(lines.Length + 2) { "WEBVTT", string.Empty };

        foreach (var line in lines)
        {
            if (!line.Contains(" --> ", StringComparison.Ordinal))
            {
                output.Add(line);
                continue;
            }

            if (!SrtTimestampLine().IsMatch(line))
            {
                throw new ConversionException("invalid_srt", "O motor produziu uma legenda SRT inválida.");
            }

            output.Add(line.Replace(',', '.'));
        }

        await File.WriteAllLinesAsync(vttPath, output, new UTF8Encoding(false), cancellationToken);
    }

    /// <summary>Verifica ordem e limites dos tempos. Retorna a quantidade de blocos lidos.</summary>
    public static async Task<int> ValidateSrtAsync(
        string srtPath,
        TimeSpan? mediaDuration,
        CancellationToken cancellationToken)
    {
        var lines = await File.ReadAllLinesAsync(srtPath, Encoding.UTF8, cancellationToken);
        var previousEnd = TimeSpan.Zero;
        var blocks = 0;
        var tolerance = TimeSpan.FromSeconds(2);

        foreach (var line in lines.Where(line => line.Contains(" --> ", StringComparison.Ordinal)))
        {
            var match = SrtTimestampLine().Match(line);
            if (!match.Success)
            {
                throw new ConversionException("invalid_srt", "A legenda gerada tem timestamps inválidos.");
            }

            var start = ParseTimestamp(match.Groups["start"].Value);
            var end = ParseTimestamp(match.Groups["end"].Value);

            if (end < start || start < previousEnd - tolerance)
            {
                throw new ConversionException("invalid_srt", "A legenda gerada tem timestamps fora de ordem.");
            }

            // O fim do último trecho pode passar um pouco da duração, mas não muito.
            if (mediaDuration is not null && end > mediaDuration.Value + tolerance)
            {
                throw new ConversionException("invalid_srt", "A legenda gerada ultrapassa a duração da mídia.");
            }

            previousEnd = end;
            blocks++;
        }

        return blocks;
    }

    /// <summary>Extrai somente o texto falado de um SRT, para a saida TXT.</summary>
    public static async Task WriteTextFromSrtAsync(string srtPath, string txtPath, CancellationToken cancellationToken)
    {
        var lines = await File.ReadAllLinesAsync(srtPath, Encoding.UTF8, cancellationToken);
        var text = new List<string>();

        foreach (var line in lines)
        {
            if (line.Contains(" --> ", StringComparison.Ordinal)
                || string.IsNullOrWhiteSpace(line)
                || IndexLine().IsMatch(line))
            {
                continue;
            }

            text.Add(line.Trim());
        }

        await File.WriteAllTextAsync(
            txtPath,
            string.Join(Environment.NewLine, text),
            new UTF8Encoding(false),
            cancellationToken);
    }

    private static TimeSpan ParseTimestamp(string value) =>
        TimeSpan.ParseExact(value.Replace(',', '.'), @"hh\:mm\:ss\.fff", CultureInfo.InvariantCulture);

    [GeneratedRegex(
        @"^(?<start>\d{2}:\d{2}:\d{2},\d{3}) --> (?<end>\d{2}:\d{2}:\d{2},\d{3})(?: .*)?$",
        RegexOptions.CultureInvariant)]
    private static partial Regex SrtTimestampLine();

    [GeneratedRegex(@"^\d+$", RegexOptions.CultureInvariant)]
    private static partial Regex IndexLine();
}
