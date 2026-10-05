using System.Text;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Engine.Speech;

public static class KokoroText
{
    public static string Normalize(string text) => string.Join("\n", text.Normalize(NormalizationForm.FormC)
        .Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n')
        .Where(line => Regex.IsMatch(line, @"[\p{L}\p{N}]"))
        .Select(line => Regex.Replace(line, "[\t\u00a0]+", " ").Trim())).Trim();

    public static bool ValidSamples(float[] samples) =>
        samples.Length > 0 && samples.All(float.IsFinite) && samples.Any(sample => Math.Abs(sample) > .00001f);

    public static IReadOnlyList<string> SplitForRetry(string text)
    {
        var middle = text.Length / 2;
        var cuts = Enumerable.Range(1, Math.Max(0, text.Length - 1))
            .Where(i => char.IsWhiteSpace(text[i]) && i >= 8 && text.Length - i >= 8)
            .OrderBy(i => Math.Abs(i - middle)).ToArray();
        if (cuts.Length == 0) return [];
        var halves = new[] { text[..cuts[0]].Trim(), text[cuts[0]..].Trim() };
        return halves.All(part => Regex.IsMatch(part, @"[\p{L}\p{N}]")) ? halves : [];
    }
}
