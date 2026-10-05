using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Queue;

/// <summary>
/// Limites da fila. Transcricao pesa muito mais que extrair audio, entao cada familia
/// de operacao tem seu proprio teto alem do teto global.
/// </summary>
public sealed record QueueSettings
{
    public int MaxConcurrentJobs { get; init; } = 2;

    public TimeSpan JobTimeout { get; init; } = TimeSpan.FromHours(2);

    /// <summary>Apagar a pasta temporaria do job quando ele termina bem.</summary>
    public bool DeleteWorkspaceOnSuccess { get; init; } = true;

    /// <summary>Manter a pasta do job com falha para diagnostico.</summary>
    public bool KeepWorkspaceOnFailure { get; init; } = true;

    /// <summary>Reserva minima de disco. Abaixo disso a fila nao inicia jobs novos.</summary>
    public long MinimumFreeDiskBytes { get; init; } = 20L * 1024 * 1024 * 1024;

    public IReadOnlyDictionary<string, int> ConcurrencyLimits { get; init; } = DefaultLimits;

    public static IReadOnlyDictionary<string, int> DefaultLimits { get; } = new Dictionary<string, int>(StringComparer.Ordinal)
    {
        [OperationIds.SpeechTranscribe] = 1,
        [OperationIds.SpeechSynthesize] = 1,
        ["video"] = 1,
        ["audio"] = 2,
        ["image"] = 4
    };

    /// <summary>Teto para a operacao: primeiro a regra exata, depois a familia, depois o global.</summary>
    public int LimitFor(string operation)
    {
        if (ConcurrencyLimits.TryGetValue(operation, out var exact))
        {
            return Math.Max(1, exact);
        }

        return ConcurrencyLimits.TryGetValue(OperationIds.FamilyOf(operation), out var family)
            ? Math.Max(1, family)
            : Math.Max(1, MaxConcurrentJobs);
    }
}
