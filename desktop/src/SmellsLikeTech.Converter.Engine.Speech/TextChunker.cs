using System.Text;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>
/// Divide textos longos em blocos por limites naturais (fim de frase, quebra de linha).
/// Nunca corta uma palavra ao meio: os blocos sao sintetizados separadamente e unidos depois.
/// </summary>
public static class TextChunker
{
    public const int DefaultChunkSize = 1_200;

    public static IReadOnlyList<string> Split(string text, int maximumCharacters = DefaultChunkSize)
    {
        var limit = Math.Clamp(maximumCharacters, 200, 20_000);
        var normalized = text.Replace("\r\n", "\n", StringComparison.Ordinal).Trim();
        if (normalized.Length == 0)
        {
            return [];
        }

        if (normalized.Length <= limit)
        {
            return [normalized];
        }

        var chunks = new List<string>();
        var current = new StringBuilder(limit);

        foreach (var sentence in Sentences(normalized))
        {
            if (current.Length > 0 && current.Length + sentence.Length > limit)
            {
                chunks.Add(current.ToString().Trim());
                current.Clear();
            }

            if (sentence.Length > limit)
            {
                foreach (var piece in SplitByWords(sentence, limit))
                {
                    if (current.Length > 0)
                    {
                        chunks.Add(current.ToString().Trim());
                        current.Clear();
                    }

                    chunks.Add(piece);
                }

                continue;
            }

            current.Append(sentence);
        }

        if (current.Length > 0)
        {
            chunks.Add(current.ToString().Trim());
        }

        return [.. chunks.Where(chunk => chunk.Length > 0)];
    }

    private static IEnumerable<string> Sentences(string text)
    {
        var start = 0;
        for (var index = 0; index < text.Length; index++)
        {
            var character = text[index];
            var isBreak = character is '.' or '!' or '?' or '\n' or ';' or ':';
            if (!isBreak)
            {
                continue;
            }

            // Consome pontuacao e espacos seguintes para nao iniciar bloco com espaco.
            var end = index + 1;
            while (end < text.Length && (text[end] is '.' or '!' or '?' or '"' or '\'' or ')' or ']' or ' ' or '\n'))
            {
                end++;
            }

            yield return text[start..end];
            start = end;
            index = end - 1;
        }

        if (start < text.Length)
        {
            yield return text[start..];
        }
    }

    private static IEnumerable<string> SplitByWords(string sentence, int limit)
    {
        var builder = new StringBuilder(limit);
        foreach (var word in sentence.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            if (builder.Length > 0 && builder.Length + word.Length + 1 > limit)
            {
                yield return builder.ToString().Trim();
                builder.Clear();
            }

            // Palavra maior que um bloco inteiro: fatiada em pedaços, sem descartar nada.
            var remaining = word.AsMemory();
            while (remaining.Length > limit)
            {
                if (builder.Length > 0)
                {
                    yield return builder.ToString().Trim();
                    builder.Clear();
                }

                yield return remaining[..limit].ToString();
                remaining = remaining[limit..];
            }

            if (remaining.Length == 0)
            {
                continue;
            }

            if (builder.Length > 0)
            {
                builder.Append(' ');
            }

            builder.Append(remaining);
        }

        if (builder.Length > 0)
        {
            yield return builder.ToString().Trim();
        }
    }
}
