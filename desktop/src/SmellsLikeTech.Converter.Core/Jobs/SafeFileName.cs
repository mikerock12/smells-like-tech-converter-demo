using System.Text;

namespace SmellsLikeTech.Converter.Core.Jobs;

/// <summary>
/// Nome de arquivo enviado pelo usuario nunca vira caminho diretamente.
/// Aqui ele e reduzido a um nome simples, sem separadores nem caracteres reservados.
/// </summary>
public static class SafeFileName
{
    private const int MaxLength = 96;

    private static readonly string[] ReservedNames =
    [
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    ];

    public static string FromPath(string path) => Sanitize(Path.GetFileNameWithoutExtension(path));

    public static string Sanitize(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "arquivo";
        }

        var invalid = Path.GetInvalidFileNameChars();
        var builder = new StringBuilder(Math.Min(value.Length, MaxLength));
        foreach (var character in value.Trim())
        {
            if (builder.Length == MaxLength)
            {
                break;
            }

            if (Array.IndexOf(invalid, character) >= 0 || character is '\\' or '/' or ':')
            {
                builder.Append('_');
            }
            else if (!char.IsControl(character))
            {
                builder.Append(character);
            }
        }

        var result = builder.ToString().Trim().TrimEnd('.');
        if (result.Length == 0)
        {
            return "arquivo";
        }

        var withoutExtension = Path.GetFileNameWithoutExtension(result);
        return ReservedNames.Contains(withoutExtension, StringComparer.OrdinalIgnoreCase)
            ? "_" + result
            : result;
    }

    /// <summary>Caminho livre dentro do diretorio de destino, sem sobrescrever nada.</summary>
    public static string UniquePath(string directory, string baseName, string extension)
    {
        var safeBase = Sanitize(baseName);
        var safeExtension = Media.FormatCatalog.Normalize(extension);
        var candidate = Path.Combine(directory, $"{safeBase}.{safeExtension}");
        var attempt = 2;
        while (File.Exists(candidate) && attempt < 1_000)
        {
            candidate = Path.Combine(directory, $"{safeBase} ({attempt}).{safeExtension}");
            attempt++;
        }

        return candidate;
    }
}
