using System.Diagnostics;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Queue;

/// <summary>
/// Fila universal: imagem, video, audio, transcricao e sintese passam pelo mesmo lugar.
/// Aceita pausar, cancelar, reordenar e informa progresso, etapa e ETA de cada job.
/// </summary>
public sealed class ConversionQueue : IAsyncDisposable
{
    private readonly IReadOnlyList<IConversionEngine> engines;
    private readonly IWorkspaceFactory workspaces;
    private readonly IJobHistoryStore history;
    private readonly IConverterLog log;
    private readonly object gate = new();
    private readonly List<ConversionJob> pending = [];
    private readonly Dictionary<Guid, RunningJob> running = [];
    private readonly List<ConversionJob> finished = [];
    private readonly SemaphoreSlim signal = new(0);
    private readonly CancellationTokenSource shutdown = new();
    private readonly Task pump;
    private bool paused;
    private QueueSettings settings;

    public ConversionQueue(
        IEnumerable<IConversionEngine> engines,
        IWorkspaceFactory workspaces,
        IJobHistoryStore history,
        IConverterLog log,
        QueueSettings? settings = null)
    {
        this.engines = engines.ToArray();
        this.workspaces = workspaces;
        this.history = history;
        this.log = log;
        this.settings = settings ?? new QueueSettings();
        pump = Task.Run(PumpAsync);
    }

    /// <summary>Disparado a cada mudanca relevante de um job.</summary>
    public event EventHandler<JobSnapshot>? JobChanged;

    /// <summary>Disparado quando jobs entram, saem ou trocam de posicao.</summary>
    public event EventHandler? QueueChanged;

    public QueueSettings Settings
    {
        get { lock (gate) { return settings; } }
        set { lock (gate) { settings = value; } signal.Release(); }
    }

    public bool IsPaused
    {
        get { lock (gate) { return paused; } }
    }

    public int PendingCount
    {
        get { lock (gate) { return pending.Count; } }
    }

    public int RunningCount
    {
        get { lock (gate) { return running.Count; } }
    }

    /// <summary>Valida as opcoes e coloca o job na fila.</summary>
    public Guid Enqueue(ConversionJob job)
    {
        job.Options.Validate();

        if (!engines.Any(engine => engine.CanExecute(job.Operation)))
        {
            throw new ConversionException(
                "operation_not_available",
                $"Nenhum motor local atende a operação '{job.Operation}'.");
        }

        if (job.InputPath is null && job.InputText is null)
        {
            throw new ConversionException("missing_input", "O job precisa de um arquivo ou de um texto de entrada.");
        }

        if (job.InputPath is not null && !File.Exists(job.InputPath))
        {
            throw new ConversionException("input_not_found", "O arquivo de entrada não foi encontrado.");
        }

        lock (gate)
        {
            pending.Add(job);
        }

        log.Write(LogChannel.Jobs, $"{job.Id:N} enfileirado · {job.Operation} · {job.Options.Summary()}");
        Notify(job);
        QueueChanged?.Invoke(this, EventArgs.Empty);
        signal.Release();
        return job.Id;
    }

    public void Pause()
    {
        lock (gate)
        {
            paused = true;
        }

        log.Write(LogChannel.App, "Fila pausada.");
        QueueChanged?.Invoke(this, EventArgs.Empty);
    }

    public void Resume()
    {
        lock (gate)
        {
            paused = false;
        }

        log.Write(LogChannel.App, "Fila retomada.");
        QueueChanged?.Invoke(this, EventArgs.Empty);
        signal.Release();
    }

    /// <summary>Cancela um job em execucao ou remove um job ainda aguardando.</summary>
    public bool Cancel(Guid jobId)
    {
        ConversionJob? removed = null;
        lock (gate)
        {
            if (running.TryGetValue(jobId, out var active))
            {
                active.Cancellation.Cancel();
                return true;
            }

            var index = pending.FindIndex(job => job.Id == jobId);
            if (index < 0)
            {
                return false;
            }

            removed = pending[index];
            pending.RemoveAt(index);
            removed.Status = JobStatus.Cancelled;
            removed.Stage = JobStage.Done;
            removed.CompletedAt = DateTimeOffset.Now;
            finished.Add(removed);
        }

        Notify(removed);
        QueueChanged?.Invoke(this, EventArgs.Empty);
        return true;
    }

    public void CancelAll()
    {
        List<Guid> ids;
        lock (gate)
        {
            ids = [.. pending.Select(job => job.Id), .. running.Keys];
        }

        foreach (var id in ids)
        {
            Cancel(id);
        }
    }

    /// <summary>Move um job aguardando para cima ou para baixo na fila.</summary>
    public bool Move(Guid jobId, int offset)
    {
        lock (gate)
        {
            var index = pending.FindIndex(job => job.Id == jobId);
            if (index < 0)
            {
                return false;
            }

            var target = Math.Clamp(index + offset, 0, pending.Count - 1);
            if (target == index)
            {
                return false;
            }

            var job = pending[index];
            pending.RemoveAt(index);
            pending.Insert(target, job);
        }

        QueueChanged?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>Remove do painel os jobs ja encerrados.</summary>
    public void ClearFinished()
    {
        lock (gate)
        {
            finished.Clear();
        }

        QueueChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Retrato da fila: em execucao, depois aguardando, depois encerrados.</summary>
    public IReadOnlyList<JobSnapshot> Snapshot()
    {
        lock (gate)
        {
            return
            [
                .. running.Values.Select(entry => entry.Job.Snapshot()),
                .. pending.Select(job => job.Snapshot()),
                .. finished.AsEnumerable().Reverse().Select(job => job.Snapshot())
            ];
        }
    }

    private async Task PumpAsync()
    {
        while (!shutdown.IsCancellationRequested)
        {
            try
            {
                await signal.WaitAsync(TimeSpan.FromMilliseconds(200), shutdown.Token);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            StartEligibleJobs();
        }
    }

    private void StartEligibleJobs()
    {
        var starting = new List<RunningJob>();
        lock (gate)
        {
            if (paused)
            {
                return;
            }

            var index = 0;
            while (index < pending.Count && running.Count < Math.Max(1, settings.MaxConcurrentJobs))
            {
                var job = pending[index];
                var limit = settings.LimitFor(job.Operation);
                var active = running.Values.Count(entry => SharesLimit(entry.Job.Operation, job.Operation));
                if (active >= limit)
                {
                    index++;
                    continue;
                }

                pending.RemoveAt(index);
                var entry = new RunningJob(job, CancellationTokenSource.CreateLinkedTokenSource(shutdown.Token));
                entry.Cancellation.CancelAfter(settings.JobTimeout);
                running[job.Id] = entry;
                starting.Add(entry);
            }
        }

        foreach (var entry in starting)
        {
            entry.Task = Task.Run(() => ExecuteAsync(entry));
        }

        if (starting.Count > 0)
        {
            QueueChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    private bool SharesLimit(string first, string second) =>
        string.Equals(first, second, StringComparison.Ordinal)
        || string.Equals(OperationIds.FamilyOf(first), OperationIds.FamilyOf(second), StringComparison.Ordinal);

    private async Task ExecuteAsync(RunningJob entry)
    {
        var job = entry.Job;
        var token = entry.Cancellation.Token;
        var stopwatch = Stopwatch.StartNew();
        IJobWorkspace? workspace = null;
        var engineName = "desconhecido";

        job.Status = JobStatus.Preparing;
        job.Stage = JobStage.Validating;
        job.StartedAt = DateTimeOffset.Now;
        Notify(job);

        try
        {
            job.Options.Validate();

            var engine = engines.FirstOrDefault(candidate => candidate.CanExecute(job.Operation))
                ?? throw new ConversionException(
                    "operation_not_available",
                    $"Nenhum motor local atende a operação '{job.Operation}'.");
            engineName = engine.Name;

            workspace = workspaces.Create(job.Id);

            var reporter = new ProgressReporter(job, stopwatch, Notify);
            job.Status = JobStatus.Processing;
            Notify(job);

            var result = await engine.ExecuteAsync(new EngineContext(job, workspace, reporter), token);

            token.ThrowIfCancellationRequested();
            job.Status = JobStatus.Finalizing;
            job.Stage = JobStage.WritingOutput;
            job.Progress = 1;
            Notify(job);

            job.OutputFiles = MoveToDestination(job, result.OutputFiles);
            job.OutputBytes = job.OutputFiles.Sum(SizeOf);
            job.Status = JobStatus.Completed;
            job.Stage = JobStage.Done;
            job.Message = result.Note;
            log.Write(
                LogChannel.Jobs,
                $"{job.Id:N} concluído · {job.Operation} · {engineName} · {stopwatch.Elapsed.TotalSeconds:0.0}s");
        }
        catch (OperationCanceledException)
        {
            job.Status = JobStatus.Cancelled;
            job.Stage = JobStage.Done;
            job.ErrorCode = entry.Cancellation.IsCancellationRequested && !shutdown.IsCancellationRequested
                ? "cancelled"
                : "shutdown";
            log.Write(LogChannel.Jobs, $"{job.Id:N} cancelado · {job.Operation}");
        }
        catch (ConversionException exception)
        {
            job.Status = JobStatus.Failed;
            job.Stage = JobStage.Done;
            job.ErrorCode = exception.Code;
            job.ErrorMessage = exception.Message;
            log.Error(exception.Code, $"{job.Id:N} · {job.Operation} · {exception.Message}", exception);
        }
        catch (Exception exception)
        {
            job.Status = JobStatus.Failed;
            job.Stage = JobStage.Done;
            job.ErrorCode = "unexpected_error";
            job.ErrorMessage = "Erro inesperado ao executar a conversão.";
            log.Error("unexpected_error", $"{job.Id:N} · {job.Operation}", exception);
        }
        finally
        {
            stopwatch.Stop();
            job.CompletedAt = DateTimeOffset.Now;
            job.Eta = null;
            CleanupWorkspace(workspace, job.Status);

            lock (gate)
            {
                running.Remove(job.Id);
                finished.Add(job);
            }

            await RecordHistoryAsync(job, engineName, stopwatch.Elapsed);
            Notify(job);
            QueueChanged?.Invoke(this, EventArgs.Empty);
            signal.Release();
        }
    }

    private void CleanupWorkspace(IJobWorkspace? workspace, JobStatus status)
    {
        if (workspace is null)
        {
            return;
        }

        var keep = status == JobStatus.Failed && settings.KeepWorkspaceOnFailure;
        if (keep || !settings.DeleteWorkspaceOnSuccess)
        {
            return;
        }

        // A limpeza acontece antes de o job ser anunciado como encerrado: quando a fila
        // diz que terminou, o temporario ja saiu do disco. So a limpeza inicial da proxima
        // abertura passaria por aqui de novo, e ela so olha pastas com mais de 12 horas.
        if (!workspace.TryDelete())
        {
            log.Error(
                "workspace_cleanup_failed",
                $"A pasta temporária do job {workspace.JobId:N} continuou presa e não foi removida.");
        }
    }

    private IReadOnlyList<string> MoveToDestination(ConversionJob job, IReadOnlyList<string> produced)
    {
        if (produced.Count == 0)
        {
            throw new ConversionException("empty_output", "O motor não produziu nenhum arquivo.");
        }

        Directory.CreateDirectory(job.OutputDirectory);
        var baseName = job.OutputBaseName
            ?? (job.InputPath is not null ? SafeFileName.FromPath(job.InputPath) : "audio");

        var moved = new List<string>(produced.Count);
        var counter = 1;
        foreach (var source in produced)
        {
            var extension = Path.GetExtension(source);
            var name = produced.Count == 1 ? baseName : $"{baseName}_{counter:000}";
            var destination = SafeFileName.UniquePath(job.OutputDirectory, name, extension);
            File.Move(source, destination, overwrite: false);
            moved.Add(destination);
            counter++;
        }

        return moved;
    }

    private static long SizeOf(string path)
    {
        try
        {
            return new FileInfo(path).Length;
        }
        catch (IOException)
        {
            return 0;
        }
    }

    private async Task RecordHistoryAsync(ConversionJob job, string engineName, TimeSpan elapsed)
    {
        try
        {
            await history.RecordAsync(
                new JobHistoryEntry
                {
                    Id = job.Id,
                    Operation = job.Operation,
                    DisplayName = job.DisplayName,
                    OptionsSummary = job.Options.Summary(),
                    Status = job.Status,
                    InputFormat = job.InputPath is not null
                        ? Path.GetExtension(job.InputPath).TrimStart('.').ToLowerInvariant()
                        : "texto",
                    OutputFormat = job.Options.OutputExtension,
                    InputBytes = job.InputBytes,
                    OutputBytes = job.OutputBytes,
                    DurationSeconds = job.Input?.Duration?.TotalSeconds,
                    OutputPath = job.OutputFiles.FirstOrDefault(),
                    ErrorCode = job.ErrorCode,
                    ErrorMessage = job.ErrorMessage,
                    Engine = engineName,
                    CreatedAt = job.CreatedAt,
                    CompletedAt = job.CompletedAt,
                    ElapsedSeconds = elapsed.TotalSeconds
                },
                CancellationToken.None);
        }
        catch (Exception exception)
        {
            log.Error("history_write_failed", "Não foi possível gravar o histórico do job.", exception);
        }
    }

    private void Notify(ConversionJob job) => JobChanged?.Invoke(this, job.Snapshot());

    public async ValueTask DisposeAsync()
    {
        await shutdown.CancelAsync();
        try
        {
            await pump;
        }
        catch (OperationCanceledException)
        {
            // Encerramento normal.
        }

        List<Task> active;
        lock (gate)
        {
            active = [.. running.Values.Select(entry => entry.Task).OfType<Task>()];
        }

        try
        {
            await Task.WhenAll(active).WaitAsync(TimeSpan.FromSeconds(10));
        }
        catch (Exception exception) when (exception is TimeoutException or OperationCanceledException)
        {
            // Os processos filhos ja receberam kill pelo cancelamento.
        }

        shutdown.Dispose();
        signal.Dispose();
    }

    private sealed class RunningJob(ConversionJob job, CancellationTokenSource cancellation)
    {
        public ConversionJob Job { get; } = job;
        public CancellationTokenSource Cancellation { get; } = cancellation;
        public Task? Task { get; set; }
    }

    /// <summary>Traduz o progresso do engine em porcentagem, etapa e ETA, sem inundar a interface.</summary>
    private sealed class ProgressReporter(ConversionJob job, Stopwatch stopwatch, Action<ConversionJob> notify)
        : IProgress<JobProgress>
    {
        private static readonly TimeSpan MinimumInterval = TimeSpan.FromMilliseconds(120);
        private TimeSpan lastNotification = TimeSpan.MinValue;

        public void Report(JobProgress value)
        {
            var changedStage = value.Stage is not null && value.Stage.Value != job.Stage;

            if (value.Stage is not null)
            {
                job.Stage = value.Stage.Value;
            }

            if (value.Message is not null)
            {
                job.Message = value.Message;
            }

            if (value.SpeedFactor is not null)
            {
                job.SpeedFactor = value.SpeedFactor;
            }

            if (value.Percent is not null)
            {
                job.Progress = Math.Clamp(value.Percent.Value, 0, 1);
                job.Eta = value.Eta ?? EstimateEta(job.Progress.Value);
            }
            else if (value.Eta is not null)
            {
                job.Eta = value.Eta;
            }

            var elapsed = stopwatch.Elapsed;
            if (!changedStage && elapsed - lastNotification < MinimumInterval)
            {
                return;
            }

            lastNotification = elapsed;
            notify(job);
        }

        private TimeSpan? EstimateEta(double progress)
        {
            if (progress <= 0.01 || progress >= 1)
            {
                return null;
            }

            var elapsed = stopwatch.Elapsed.TotalSeconds;
            var total = elapsed / progress;
            var remaining = total - elapsed;
            return remaining is > 0 and < 86_400 ? TimeSpan.FromSeconds(remaining) : null;
        }
    }
}
