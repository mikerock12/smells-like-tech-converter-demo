using SmellsLikeTech.Converter.Core.Jobs;

namespace SmellsLikeTech.Converter.Core.Abstractions;

/// <summary>
/// Registro de um job encerrado. Guarda somente metadados: nunca o conteudo do arquivo,
/// o texto enviado para sintese ou o resultado da transcricao.
/// </summary>
public sealed record JobHistoryEntry
{
    public required Guid Id { get; init; }
    public required string Operation { get; init; }
    public required string DisplayName { get; init; }
    public required string OptionsSummary { get; init; }
    public required JobStatus Status { get; init; }
    public string? InputFormat { get; init; }
    public string? OutputFormat { get; init; }
    public long InputBytes { get; init; }
    public long OutputBytes { get; init; }
    public double? DurationSeconds { get; init; }
    public string? OutputPath { get; init; }
    public string? ErrorCode { get; init; }
    public string? ErrorMessage { get; init; }
    public string? Engine { get; init; }
    public required DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset? CompletedAt { get; init; }
    public double ElapsedSeconds { get; init; }
}

public interface IJobHistoryStore
{
    Task RecordAsync(JobHistoryEntry entry, CancellationToken cancellationToken);

    Task<IReadOnlyList<JobHistoryEntry>> RecentAsync(int limit, CancellationToken cancellationToken);

    Task ClearAsync(CancellationToken cancellationToken);
}
