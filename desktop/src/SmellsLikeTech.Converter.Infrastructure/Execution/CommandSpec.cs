using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Infrastructure.Execution;

/// <summary>
/// Comando externo montado como executavel + lista de argumentos.
/// Nunca existe uma string de linha de comando concatenada: nada do usuario vira shell.
/// </summary>
public sealed record CommandSpec(string Executable, IReadOnlyList<string> Arguments)
{
    /// <summary>Representacao apenas para log/diagnostico.</summary>
    public string Describe() => $"{Path.GetFileName(Executable)} {string.Join(' ', Arguments)}";
}

public sealed record ProcessResult(int ExitCode, string StandardOutput, string StandardError, TimeSpan Elapsed);

public static class ExecutableGuard
{
    /// <summary>
    /// Aceita um nome simples resolvido pelo PATH ou um caminho absoluto existente.
    /// Rejeita caminhos relativos e argumentos embutidos.
    /// </summary>
    public static string Validate(string candidate, string friendlyName)
    {
        if (string.IsNullOrWhiteSpace(candidate))
        {
            throw new ConversionException("invalid_configuration", $"Caminho de {friendlyName} não configurado.");
        }

        var value = candidate.Trim().Trim('"');
        if (value.Contains('\n') || value.Contains('\r') || value.Contains('|') || value.Contains('&'))
        {
            throw new ConversionException("invalid_configuration", $"Caminho de {friendlyName} inválido.");
        }

        if (!value.Contains(Path.DirectorySeparatorChar) && !value.Contains(Path.AltDirectorySeparatorChar))
        {
            // Nome simples: resolvido pelo PATH no momento da execucao.
            return value;
        }

        var full = Path.GetFullPath(value);
        if (!File.Exists(full))
        {
            throw new ConversionException("dependency_missing", $"{friendlyName} não foi encontrado em '{full}'.");
        }

        return full;
    }

    /// <summary>Localiza um executavel no PATH, sem executa-lo.</summary>
    public static string? Locate(string executable)
    {
        if (Path.IsPathRooted(executable))
        {
            return File.Exists(executable) ? executable : null;
        }

        var withExtension = Path.HasExtension(executable) ? executable : executable + ".exe";
        var pathVariable = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
        foreach (var directory in pathVariable.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(directory.Trim('"'), withExtension);
                if (File.Exists(candidate))
                {
                    return candidate;
                }
            }
            catch (ArgumentException)
            {
                // Entrada invalida no PATH; segue para a proxima.
            }
        }

        return null;
    }
}

public static class SafeDiagnostics
{
    private const int MaxLength = 400;

    /// <summary>
    /// Reduz a saida de um motor externo a algo curto e sem caminhos completos,
    /// para poder ir ao log sem vazar conteudo do usuario.
    /// </summary>
    public static string FromEngine(string output, params string[] pathsToMask)
    {
        if (string.IsNullOrWhiteSpace(output))
        {
            return "(sem detalhes)";
        }

        var text = output;
        foreach (var path in pathsToMask.Where(path => !string.IsNullOrEmpty(path)))
        {
            text = text.Replace(path, "<job>", StringComparison.OrdinalIgnoreCase);
        }

        text = text.Replace('\r', ' ').Replace('\n', ' ').Trim();
        return text.Length <= MaxLength ? text : text[..MaxLength] + "…";
    }
}
