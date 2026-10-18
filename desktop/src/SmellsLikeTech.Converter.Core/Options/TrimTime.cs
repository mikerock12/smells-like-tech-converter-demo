using System.Globalization;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>
/// O corte como a tela mostra: início e fim. As opções guardam início e duração (o motor e o
/// plugin falam assim), e a conversão entre os dois fica aqui, num lugar só e testado. Os
/// tempos aceitam "1:05", "1:05.5", "1:05,5", "1:02:03" e só segundos ("90"), como no celular.
/// </summary>
public static partial class TrimTime
{
    /// <summary>Lê um tempo digitado. Vazio é nulo; o que não for tempo vira erro claro.</summary>
    public static TimeSpan? Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        var partes = Separador().Split(text.Trim());
        if (partes.Length > 3 || partes.Any(parte => parte.Length == 0))
        {
            throw Invalido(text);
        }

        double total = 0;
        for (var indice = 0; indice < partes.Length; indice++)
        {
            var ultima = indice == partes.Length - 1;
            var parte = ultima ? partes[indice].Replace(',', '.') : partes[indice];
            if ((!ultima && parte.Contains(',')) ||
                !double.TryParse(parte, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var numero) ||
                (!ultima && parte.Contains('.')) ||
                (indice > 0 && numero >= 60))
            {
                throw Invalido(text);
            }

            total = total * 60 + numero;
        }

        return TimeSpan.FromSeconds(total);
    }

    /// <summary>"m:ss" ou "h:mm:ss", com os milissegundos quando houver ("1:05.5", "1:05.135").</summary>
    public static string Format(TimeSpan value)
    {
        var milesimos = (long)Math.Round(Math.Max(0, value.TotalSeconds) * 1000, MidpointRounding.AwayFromZero);
        var inteiros = milesimos / 1000;
        var texto = inteiros >= 3600
            ? string.Create(CultureInfo.InvariantCulture, $"{inteiros / 3600}:{inteiros % 3600 / 60:00}:{inteiros % 60:00}")
            : string.Create(CultureInfo.InvariantCulture, $"{inteiros / 60}:{inteiros % 60:00}");
        var fracao = (milesimos % 1000).ToString("000", CultureInfo.InvariantCulture).TrimEnd('0');
        return fracao.Length == 0 ? texto : $"{texto}.{fracao}";
    }

    /// <summary>A duração que as opções guardam, a partir do fim mostrado na tela.</summary>
    public static TimeSpan? DurationFromEnd(TimeSpan? start, TimeSpan? end)
    {
        if (end is null)
        {
            return null;
        }

        var inicio = start ?? TimeSpan.Zero;
        if (end.Value <= inicio)
        {
            throw new ConversionException("invalid_trim", "O fim do trecho deve ser maior que o início.");
        }

        return end.Value - inicio;
    }

    /// <summary>O fim mostrado na tela, a partir do início e da duração guardados.</summary>
    public static TimeSpan? EndFromDuration(TimeSpan? start, TimeSpan? duration) =>
        duration is { } trecho ? (start ?? TimeSpan.Zero) + trecho : null;

    private static ConversionException Invalido(string text) =>
        new("invalid_trim", $"Tempo inválido: “{text}”. Use 0:30, 1:05.5 ou 1:02:15.");

    [GeneratedRegex(@"[:\- ]+")]
    private static partial Regex Separador();
}
