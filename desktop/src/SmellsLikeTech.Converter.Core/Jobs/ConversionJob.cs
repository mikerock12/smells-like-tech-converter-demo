using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Core.Jobs;

/// <summary>
/// Job universal. Imagem, video, audio, transcricao e sintese usam esta mesma estrutura
/// e a mesma fila - nao existem filas separadas por tipo de midia.
/// </summary>
public sealed class ConversionJob
{
    public Guid Id { get; init; } = Guid.NewGuid();

    public required JobOptions Options { get; init; }

    /// <summary>Arquivo de origem. Nulo quando a entrada e texto digitado (Text-to-Speech).</summary>
    public string? InputPath { get; init; }

    /// <summary>Metadados detectados na entrada, quando houver arquivo.</summary>
    public MediaInfo? Input { get; init; }

    /// <summary>
    /// Arquivos alem do primeiro, para operacoes que juntam varios numa saida so (juntar
    /// PDF, imagens para PDF). Vazio para todo o resto. Cada caminho ja passou pela mesma
    /// inspecao que <see cref="InputPath"/>.
    /// </summary>
    public IReadOnlyList<string> ExtraInputPaths { get; init; } = [];

    /// <summary>
    /// Conteudo textual privado da sintese de voz. Fica somente em memoria e no workspace
    /// temporario do job: nunca vai para o banco, para o historico nem para os logs.
    /// </summary>
    public string? InputText { get; init; }

    public required string OutputDirectory { get; init; }

    /// <summary>Nome desejado do arquivo final, sem extensao. Sempre sanitizado antes do uso.</summary>
    public string? OutputBaseName { get; init; }

    public string Operation => Options.Operation;

    public string DisplayName => InputPath is not null
        ? Path.GetFileName(InputPath)
        : OutputBaseName ?? "texto";

    public DateTimeOffset CreatedAt { get; } = DateTimeOffset.Now;

    // --- Estado de execucao. Alterado somente pela fila. ---

    public JobStatus Status { get; internal set; } = JobStatus.Queued;
    public JobStage Stage { get; internal set; } = JobStage.Waiting;

    /// <summary>0 a 1, ou nulo quando a etapa nao tem progresso mensuravel.</summary>
    public double? Progress { get; internal set; }

    public string? Message { get; internal set; }
    public string? ErrorCode { get; internal set; }
    public string? ErrorMessage { get; internal set; }
    public TimeSpan? Eta { get; internal set; }
    public double? SpeedFactor { get; internal set; }

    public DateTimeOffset? StartedAt { get; internal set; }
    public DateTimeOffset? CompletedAt { get; internal set; }

    public IReadOnlyList<string> OutputFiles { get; internal set; } = [];
    public long OutputBytes { get; internal set; }
    public long InputBytes => Input?.SizeBytes ?? 0;

    public JobSnapshot Snapshot() => new()
    {
        Id = Id,
        Operation = Operation,
        DisplayName = DisplayName,
        OptionsSummary = Options.Summary(),
        Status = Status,
        Stage = Stage,
        Progress = Progress,
        Message = Message,
        ErrorCode = ErrorCode,
        ErrorMessage = ErrorMessage,
        Eta = Eta,
        SpeedFactor = SpeedFactor,
        CreatedAt = CreatedAt,
        StartedAt = StartedAt,
        CompletedAt = CompletedAt,
        OutputFiles = OutputFiles,
        InputBytes = InputBytes,
        OutputBytes = OutputBytes
    };
}

/// <summary>Retrato imutavel do job, seguro para atravessar threads ate a interface.</summary>
public sealed record JobSnapshot
{
    public required Guid Id { get; init; }
    public required string Operation { get; init; }
    public required string DisplayName { get; init; }
    public required string OptionsSummary { get; init; }
    public required JobStatus Status { get; init; }
    public required JobStage Stage { get; init; }
    public double? Progress { get; init; }
    public string? Message { get; init; }
    public string? ErrorCode { get; init; }
    public string? ErrorMessage { get; init; }
    public TimeSpan? Eta { get; init; }
    public double? SpeedFactor { get; init; }
    public DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset? StartedAt { get; init; }
    public DateTimeOffset? CompletedAt { get; init; }
    public IReadOnlyList<string> OutputFiles { get; init; } = [];
    public long InputBytes { get; init; }
    public long OutputBytes { get; init; }

    public TimeSpan? Elapsed => StartedAt is null ? null : (CompletedAt ?? DateTimeOffset.Now) - StartedAt.Value;
}

/// <summary>Atualizacao de progresso publicada por um engine.</summary>
public sealed record JobProgress
{
    /// <summary>0 a 1. Nulo quando indeterminado.</summary>
    public double? Percent { get; init; }

    public JobStage? Stage { get; init; }
    public string? Message { get; init; }

    /// <summary>Velocidade relativa ao tempo real, quando o engine informa (ex.: 3.4x).</summary>
    public double? SpeedFactor { get; init; }

    public TimeSpan? Eta { get; init; }

    public static JobProgress At(double percent, JobStage stage) =>
        new() { Percent = Math.Clamp(percent, 0, 1), Stage = stage };

    public static JobProgress Step(JobStage stage, string? message = null) =>
        new() { Stage = stage, Message = message };
}
