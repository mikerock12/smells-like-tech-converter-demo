using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Queue;
using SmellsLikeTech.Converter.Infrastructure.Storage;

namespace SmellsLikeTech.Converter.Infrastructure.Configuration;

/// <summary>
/// Configuracao do aplicativo, gravada no SQLite. Separada do codigo, conforme a regra
/// "configuracao fica separada do codigo" definida para o projeto.
/// </summary>
public sealed record AppSettings
{
    public string RootDirectory { get; init; } = ConverterPaths.DefaultRoot;

    /// <summary>Vazio = perguntar/usar a pasta Output padrao.</summary>
    public string OutputDirectory { get; init; } = string.Empty;

    public string FfmpegPath { get; init; } = "ffmpeg";
    public string FfprobePath { get; init; } = "ffprobe";

    public int MaxConcurrentJobs { get; init; } = 2;
    public int JobTimeoutMinutes { get; init; } = 120;
    public bool DeleteWorkspaceOnSuccess { get; init; } = true;
    public bool KeepWorkspaceOnFailure { get; init; } = true;

    /// <summary>Reserva minima de disco em GB. Abaixo disso a fila para de aceitar jobs.</summary>
    public int MinimumFreeDiskGb { get; init; } = 20;

    /// <summary>Retencao das pastas de jobs com falha, para diagnostico.</summary>
    public int FailedJobRetentionHours { get; init; } = 6;

    public HardwareAcceleration Acceleration { get; init; } = HardwareAcceleration.Automatic;

    /// <summary>Limite de caracteres por job de sintese de voz.</summary>
    public int MaxTextToSpeechCharacters { get; init; } = 20_000;

    /// <summary>Limite de duracao de midia aceita para transcricao.</summary>
    public int MaxTranscriptionMinutes { get; init; } = 240;

    public string DefaultTtsVoiceId { get; init; } = string.Empty;
    /// <summary>
    /// Padrao de fabrica equilibrado: o "base" erra nomes proprios e fala regional,
    /// e o "large" exige quase 3 GB de download. Pode ser trocado em Configuracoes.
    /// </summary>
    public string DefaultWhisperModel { get; init; } = "small";

    public bool OpenOutputFolderWhenDone { get; init; }

    // --- Licenca. O aplicativo e pago, vitalicio, com sete dias de teste completo. ---

    /// <summary>A chave colada em Configuracoes. Vazia = sem licenca (teste ou vencido).</summary>
    public string LicenseKey { get; init; } = string.Empty;

    /// <summary>Primeira abertura, em ISO 8601. Define quando o teste acaba.</summary>
    public string FirstRunAt { get; init; } = string.Empty;

    // --- Estado da janela. Na primeira execucao abre maximizada; depois volta como ficou. ---

    public bool WindowMaximized { get; init; } = true;

    /// <summary>Retangulo de restauracao. Zero significa "calcular a partir do monitor".</summary>
    public int WindowWidth { get; init; }

    public int WindowHeight { get; init; }

    public int WindowLeft { get; init; }

    public int WindowTop { get; init; }

    public bool HasWindowBounds => WindowWidth > 0 && WindowHeight > 0;

    public QueueSettings ToQueueSettings() => new()
    {
        MaxConcurrentJobs = Math.Clamp(MaxConcurrentJobs, 1, 8),
        JobTimeout = TimeSpan.FromMinutes(Math.Clamp(JobTimeoutMinutes, 1, 1440)),
        DeleteWorkspaceOnSuccess = DeleteWorkspaceOnSuccess,
        KeepWorkspaceOnFailure = KeepWorkspaceOnFailure,
        MinimumFreeDiskBytes = Math.Clamp(MinimumFreeDiskGb, 1, 4096) * 1024L * 1024 * 1024
    };

    public string ResolveOutputDirectory(ConverterPaths paths) =>
        string.IsNullOrWhiteSpace(OutputDirectory) ? paths.Output : OutputDirectory;
}
