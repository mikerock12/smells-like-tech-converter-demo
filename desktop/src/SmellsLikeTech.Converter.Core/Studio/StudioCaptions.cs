using System.Globalization;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>Tempos do áudio final viram textos editáveis e são queimados pelo mesmo compositor.</summary>
public static class StudioCaptions
{
    public static IReadOnlyList<StudioText> ParseSrt(string srt, double duration)
    {
        var result = new List<StudioText>();
        foreach (var block in Regex.Split(srt.Replace("\r", "").Trim('\uFEFF'), "\n\\s*\n"))
        {
            var lines = block.Split('\n');
            var at = Array.FindIndex(lines, l => l.Contains("-->"));
            if (at < 0) continue;
            var times = lines[at].Split("-->");
            if (times.Length != 2 || !TimeSpan.TryParse(times[0].Trim().Replace(',', '.'), CultureInfo.InvariantCulture, out var start)
                || !TimeSpan.TryParse(times[1].Trim().Replace(',', '.'), CultureInfo.InvariantCulture, out var end)) continue;
            var text = Regex.Replace(string.Join("\n", lines.Skip(at + 1)), "<[^>]+>", "").Trim();
            var from = Math.Max(0, start.TotalSeconds); var to = Math.Min(duration, end.TotalSeconds);
            if (to <= from || text.Length == 0) continue;
            if (result.Count >= 1000) throw new ConversionException("too_many_captions", "Use até 1000 trechos de legenda por projeto.");
            result.Add(new StudioText {Id=$"legenda-{result.Count}", Text=text, Start=from, End=to,
                Y=.85, Size=.055, Font="anton", Color="#FFFFFF", Outline="#000000"});
        }
        if(result.Count==0) throw new ConversionException("empty_captions", "O Whisper não encontrou fala para legendar.");
        return result;
    }
}
