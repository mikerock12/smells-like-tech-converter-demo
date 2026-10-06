using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Licensing;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Queue;
using SmellsLikeTech.Converter.Engine.Documents;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Engine.Image;
using SmellsLikeTech.Converter.Engine.Speech;
using SmellsLikeTech.Converter.Infrastructure.Configuration;
using SmellsLikeTech.Converter.Infrastructure.Database;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Hardware;
using SmellsLikeTech.Converter.Infrastructure.Logging;
using SmellsLikeTech.Converter.Infrastructure.Media;
using SmellsLikeTech.Converter.Infrastructure.Storage;

namespace SmellsLikeTech.Converter.Composition;

/// <summary>Etapa real da inicializacao, exibida na abertura.</summary>
public sealed record StartupStage(string Message, double Progress);

/// <summary>
/// Composicao do aplicativo. A interface nunca instancia FFmpeg, ImageMagick, Whisper
/// ou SAPI: ela pede um job a fila, e a fila escolhe o motor.
/// </summary>
public sealed class AppServices : IAsyncDisposable
{
    private FfmpegOptions ffmpeg;

    private AppServices(
        ConverterPaths paths,
        FileLog log,
        ConverterDatabase database,
        SettingsStore settingsStore,
        SqliteJobHistoryStore history,
        AppSettings settings,
        FfmpegOptions ffmpegOptions,
        HardwareInfo hardware)
    {
        Paths = paths;
        Log = log;
        Database = database;
        SettingsStore = settingsStore;
        History = history;
        Settings = settings;
        ffmpeg = ffmpegOptions;
        Hardware = hardware;

        Processes = new ProcessRunner();
        Models = new WhisperModelCatalog(paths.WhisperModels);
        ModelInstaller = new WhisperModelInstaller(Models, log);
        TtsEngine = new KokoroTtsEngine();
        TextRecognizer = new WindowsTextRecognizer();

        var probe = new FfprobeInspector(() => ffmpeg, Processes);
        MediaProbe = probe;
        Inspector = new CompositeMediaInspector([probe, new MagickImageInspector()]);

        var engines = new IConversionEngine[]
        {
            new FfmpegEngine(() => ffmpeg, Processes, probe, log),
            new MagickImageEngine(log),
            new WhisperTranscriptionEngine(
                () => ffmpeg,
                Processes,
                probe,
                Models,
                () => Settings.MaxTranscriptionMinutes,
                log),
            new SpeechSynthesisEngine(
                TtsEngine,
                () => ffmpeg,
                Processes,
                () => Settings.MaxTextToSpeechCharacters,
                log,
                new DocumentTextReader(TextRecognizer)),
            new PdfDocumentEngine(TextRecognizer, log),
            new OcrImageEngine(TextRecognizer, log),
            new PdfImageEngine(log),
            new PdfAssemblyEngine(log),
            new PlannedDocumentEngine()
        };

        Queue = new ConversionQueue(
            engines,
            new JobWorkspaceFactory(paths),
            history,
            log,
            settings.ToQueueSettings(),
            () => WindowsExecutionLease.TryAcquire(log));
    }

    public ConverterPaths Paths { get; }
    public FileLog Log { get; }
    public ConverterDatabase Database { get; }
    public SettingsStore SettingsStore { get; }
    public SqliteJobHistoryStore History { get; }
    public ProcessRunner Processes { get; }
    public ConversionQueue Queue { get; }
    public CompositeMediaInspector Inspector { get; }
    public FfprobeInspector MediaProbe { get; }
    public WhisperModelCatalog Models { get; }
    public WhisperModelInstaller ModelInstaller { get; }
    public ITtsEngine TtsEngine { get; }
    public WindowsTextRecognizer TextRecognizer { get; }
    public HardwareInfo Hardware { get; private set; }
    public AppSettings Settings { get; private set; }

    public bool FfmpegAvailable => ExecutableGuard.Locate(Settings.FfmpegPath) is not null;
    public bool FfprobeAvailable => ExecutableGuard.Locate(Settings.FfprobePath) is not null;

    public static async Task<AppServices> StartAsync(
        IProgress<StartupStage>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var paths = ConverterPaths.CreateDefault();
        progress?.Report(new StartupStage($"Preparando pastas em {paths.Root}", 0.08));
        paths.EnsureCreated();

        var log = new FileLog(paths);

        progress?.Report(new StartupStage("Abrindo o banco local (SQLite)", 0.26));
        var database = new ConverterDatabase(paths);
        await database.InitializeAsync(cancellationToken);

        progress?.Report(new StartupStage("Lendo configurações e presets", 0.42));
        var settingsStore = new SettingsStore(database);
        var settings = await settingsStore.LoadAsync(cancellationToken);

        // A primeira abertura marca o inicio dos sete dias de teste do aplicativo.
        if (string.IsNullOrWhiteSpace(settings.FirstRunAt))
        {
            settings = settings with { FirstRunAt = DateTimeOffset.UtcNow.ToString("O") };
            await settingsStore.SaveAsync(settings, cancellationToken);
        }

        // A raiz configurada pode ser diferente da padrao.
        if (!string.IsNullOrWhiteSpace(settings.RootDirectory)
            && !string.Equals(settings.RootDirectory, paths.Root, StringComparison.OrdinalIgnoreCase))
        {
            paths = new ConverterPaths(settings.RootDirectory);
            paths.EnsureCreated();
        }

        var history = new SqliteJobHistoryStore(database);
        var processes = new ProcessRunner();

        progress?.Report(new StartupStage("Detectando CPU, GPU e aceleração de vídeo", 0.60));
        var hardware = await new HardwareProbe(paths, processes)
            .DescribeAsync(settings.FfmpegPath, cancellationToken);

        progress?.Report(new StartupStage("Registrando motores de conversão", 0.82));
        var ffmpegOptions = BuildFfmpegOptions(settings, hardware);
        var services = new AppServices(
            paths, log, database, settingsStore, history, settings, ffmpegOptions, hardware);

        progress?.Report(new StartupStage("Limpando temporários antigos", 0.93));
        new RetentionService(paths, log)
            .RunStartupCleanup(TimeSpan.FromHours(Math.Clamp(settings.FailedJobRetentionHours, 1, 168)));

        log.Write(LogChannel.App, $"Aplicativo iniciado · raiz {paths.Root} · {hardware.CpuName}");
        progress?.Report(new StartupStage("Pronto", 1));
        return services;
    }

    public async Task ApplySettingsAsync(AppSettings settings, CancellationToken cancellationToken = default)
    {
        await SettingsStore.SaveAsync(settings, cancellationToken);
        Settings = settings;

        Hardware = await new HardwareProbe(Paths, Processes)
            .DescribeAsync(settings.FfmpegPath, cancellationToken);
        ffmpeg = BuildFfmpegOptions(settings, Hardware);
        Queue.Settings = settings.ToQueueSettings();
        Log.Write(LogChannel.App, "Configurações atualizadas.");
    }

    public string OutputDirectory => Settings.ResolveOutputDirectory(Paths);

    /// <summary>
    /// Em que pe o aplicativo esta: licenciado, em teste ou com o teste vencido. So o
    /// aplicativo com janela consulta isto; o plugin e gratuito e nunca pergunta.
    /// </summary>
    public DesktopAccessState EvaluateDesktopAccess()
    {
        var firstRun = DateTimeOffset.TryParse(
            Settings.FirstRunAt,
            null,
            System.Globalization.DateTimeStyles.AssumeUniversal,
            out var parsed)
            ? parsed
            : DateTimeOffset.UtcNow;

        return TrialPolicy.Evaluate(Settings.LicenseKey, firstRun, DateTimeOffset.UtcNow);
    }

    /// <summary>Guarda a chave colada em Configuracoes, depois de verificada.</summary>
    public async Task<LicenseVerification> ActivateLicenseAsync(string key, CancellationToken cancellationToken = default)
    {
        var verification = LicenseVerifier.Verify(key);
        if (verification.Status == LicenseStatus.Valid && verification.Claims is { UnlocksDesktop: true })
        {
            Settings = Settings with { LicenseKey = key.Trim() };
            await SettingsStore.SaveAsync(Settings, cancellationToken);
            Log.Write(LogChannel.App, $"Licença ativada · pedido {verification.Claims.Id} · plano {verification.Claims.Plan}");
        }

        return verification;
    }

    public async Task RemoveLicenseAsync(CancellationToken cancellationToken = default)
    {
        Settings = Settings with { LicenseKey = string.Empty };
        await SettingsStore.SaveAsync(Settings, cancellationToken);
        Log.Write(LogChannel.App, "Licença removida deste computador.");
    }

    private static FfmpegOptions BuildFfmpegOptions(AppSettings settings, HardwareInfo hardware)
    {
        var preference = settings.Acceleration == HardwareAcceleration.Automatic
            ? hardware.PreferredAcceleration
            : settings.Acceleration;

        try
        {
            return FfmpegOptions.Resolve(
                settings.FfmpegPath,
                settings.FfprobePath,
                hardware.HardwareEncoders,
                preference);
        }
        catch (Core.ConversionException)
        {
            // Caminho configurado invalido: volta ao nome simples resolvido pelo PATH.
            return FfmpegOptions.Resolve("ffmpeg", "ffprobe", hardware.HardwareEncoders, preference);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await Queue.DisposeAsync();
        (TtsEngine as IDisposable)?.Dispose();
        Log.Dispose();
    }
}
