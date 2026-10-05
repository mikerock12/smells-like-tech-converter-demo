using System.Diagnostics;
using System.Text;
using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Infrastructure.Execution;

/// <summary>
/// Executa motores externos com TEMP/TMP apontando para a pasta do job, saida limitada
/// e cancelamento que encerra a arvore de processos sem derrubar o aplicativo.
/// </summary>
public sealed class ProcessRunner
{
    private const int OutputLimitCharacters = 64 * 1024;

    public async Task<ProcessResult> RunAsync(
        CommandSpec command,
        string workingDirectory,
        string temporaryDirectory,
        Action<string>? onStandardOutputLine = null,
        Action<string>? onStandardErrorLine = null,
        CancellationToken cancellationToken = default)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = command.Executable,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = workingDirectory
        };

        foreach (var argument in command.Arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        // Somente para os processos filhos: o TEMP global do Windows nao e alterado.
        startInfo.Environment["TEMP"] = temporaryDirectory;
        startInfo.Environment["TMP"] = temporaryDirectory;

        using var process = new Process { StartInfo = startInfo };
        var stopwatch = Stopwatch.StartNew();

        try
        {
            if (!process.Start())
            {
                throw new ConversionException("dependency_start_failed", "Não foi possível iniciar o motor local.");
            }
        }
        catch (Exception exception) when (exception is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            throw new ConversionException(
                "dependency_start_failed",
                $"Não foi possível iniciar '{Path.GetFileName(command.Executable)}'. Verifique a configuração.",
                exception);
        }

        process.StandardInput.Close();
        var stdout = PumpAsync(process.StandardOutput, onStandardOutputLine, cancellationToken);
        var stderr = PumpAsync(process.StandardError, onStandardErrorLine, cancellationToken);

        try
        {
            await process.WaitForExitAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            TryKillTree(process);
            await process.WaitForExitAsync(CancellationToken.None);
            throw;
        }

        var output = await stdout;
        var error = await stderr;
        stopwatch.Stop();
        return new ProcessResult(process.ExitCode, output, error, stopwatch.Elapsed);
    }

    private static async Task<string> PumpAsync(
        StreamReader reader,
        Action<string>? onLine,
        CancellationToken cancellationToken)
    {
        var builder = new StringBuilder();
        while (await reader.ReadLineAsync(cancellationToken) is { } line)
        {
            if (builder.Length < OutputLimitCharacters)
            {
                builder.AppendLine(line);
            }

            onLine?.Invoke(line);
        }

        return builder.ToString();
    }

    private static void TryKillTree(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (InvalidOperationException)
        {
            // O processo terminou entre a verificacao e o Kill.
        }
        catch (System.ComponentModel.Win32Exception)
        {
            // Sem privilegio para encerrar; o WaitForExit observara o estado final.
        }
    }
}
