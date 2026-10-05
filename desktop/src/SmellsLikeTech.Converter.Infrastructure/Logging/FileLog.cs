using System.Text;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Infrastructure.Storage;

namespace SmellsLikeTech.Converter.Infrastructure.Logging;

/// <summary>
/// Logs separados por canal em D:\SmellsLikeTechConverter\Logs.
/// Nunca recebem texto de sintese, transcricao ou conteudo de arquivo do usuario.
/// </summary>
public sealed class FileLog : IConverterLog, IDisposable
{
    private const long MaxFileBytes = 8L * 1024 * 1024;

    private readonly string directory;
    private readonly object gate = new();
    private bool disposed;

    public FileLog(ConverterPaths paths)
    {
        directory = paths.Logs;
        Directory.CreateDirectory(directory);
    }

    /// <summary>Ultimas linhas em memoria, para a tela de configuracoes.</summary>
    public IReadOnlyList<string> Recent
    {
        get
        {
            lock (gate)
            {
                return [.. recent];
            }
        }
    }

    private readonly Queue<string> recent = new();

    public void Write(LogChannel channel, string message)
    {
        var line = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} [{channel}] {Single(message)}";
        Append(FileFor(channel), line);

        lock (gate)
        {
            recent.Enqueue(line);
            while (recent.Count > 300)
            {
                recent.Dequeue();
            }
        }
    }

    public void Error(string code, string message, Exception? exception = null)
    {
        // O HRESULT importa: erros de COM costumam chegar com mensagem vazia.
        var detail = exception is null
            ? string.Empty
            : $" | {exception.GetType().Name} (0x{exception.HResult:X8}): {Single(exception.Message)}";
        var line = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff} [{code}] {Single(message)}{detail}";
        Append("errors.log", line);

        lock (gate)
        {
            recent.Enqueue(line);
            while (recent.Count > 300)
            {
                recent.Dequeue();
            }
        }
    }

    private static string FileFor(LogChannel channel) => channel switch
    {
        LogChannel.Jobs => "jobs.log",
        LogChannel.Ffmpeg => "ffmpeg.log",
        LogChannel.Speech => "speech.log",
        LogChannel.Errors => "errors.log",
        _ => "app.log"
    };

    private static string Single(string message) =>
        message.Replace('\r', ' ').Replace('\n', ' ');

    private void Append(string fileName, string line)
    {
        if (disposed)
        {
            return;
        }

        var path = Path.Combine(directory, fileName);
        lock (gate)
        {
            try
            {
                RotateIfNeeded(path);
                File.AppendAllText(path, line + Environment.NewLine, Encoding.UTF8);
            }
            catch (IOException)
            {
                // Log nunca derruba conversao.
            }
            catch (UnauthorizedAccessException)
            {
                // Idem.
            }
        }
    }

    private static void RotateIfNeeded(string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length < MaxFileBytes)
        {
            return;
        }

        var archive = path + ".1";
        File.Delete(archive);
        File.Move(path, archive);
    }

    public void Dispose() => disposed = true;
}
