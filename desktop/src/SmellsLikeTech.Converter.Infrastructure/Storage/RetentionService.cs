using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Infrastructure.Storage;

/// <summary>
/// Politica de limpeza: temporarios somem depois do job, jobs com falha ficam algumas horas
/// para diagnostico e o cache tem validade propria.
/// </summary>
public sealed class RetentionService(ConverterPaths paths, IConverterLog log)
{
    public int CleanupTemp(TimeSpan maximumAge) => CleanupDirectory(paths.Temp, maximumAge);

    public int CleanupFailedJobs(TimeSpan maximumAge) => CleanupDirectory(paths.FailedJobs, maximumAge);

    public int CleanupCache(TimeSpan maximumAge) => CleanupDirectory(paths.Cache, maximumAge);

    /// <summary>Executa a limpeza completa na abertura do aplicativo.</summary>
    public void RunStartupCleanup(TimeSpan failedJobRetention)
    {
        var removed = CleanupTemp(TimeSpan.FromHours(12))
            + CleanupFailedJobs(failedJobRetention)
            + CleanupCache(TimeSpan.FromDays(7));

        if (removed > 0)
        {
            log.Write(LogChannel.App, $"Limpeza inicial removeu {removed} pasta(s) temporária(s).");
        }
    }

    private int CleanupDirectory(string root, TimeSpan maximumAge)
    {
        if (!Directory.Exists(root))
        {
            return 0;
        }

        var cutoff = DateTime.UtcNow - maximumAge;
        var removed = 0;

        foreach (var directory in SafeEnumerate(root))
        {
            try
            {
                var info = new DirectoryInfo(directory);
                if (info.LastWriteTimeUtc > cutoff || (info.Attributes & FileAttributes.ReparsePoint) != 0)
                {
                    continue;
                }

                if (FileAccessGuard.TreeContainsReparsePoint(directory))
                {
                    continue;
                }

                Directory.Delete(directory, recursive: true);
                removed++;
            }
            catch (IOException)
            {
                // Pasta em uso: a proxima execucao tenta de novo.
            }
            catch (UnauthorizedAccessException)
            {
                // Sem permissao: nao elevar privilegios.
            }
        }

        return removed;
    }

    private IEnumerable<string> SafeEnumerate(string root)
    {
        try
        {
            return Directory.EnumerateDirectories(root, "*", SearchOption.TopDirectoryOnly).ToArray();
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            log.Error("cleanup_enumeration_failed", $"Não foi possível listar '{root}'.", exception);
            return [];
        }
    }
}
