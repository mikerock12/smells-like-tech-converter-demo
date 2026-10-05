using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Queue;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class QueueTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-queue-" + Guid.NewGuid().ToString("N"));

    public QueueTests() => Directory.CreateDirectory(root);

    [Fact]
    public async Task Job_ExecutaEEntregaOArquivoNoDestino()
    {
        var engine = new FakeEngine();
        await using var queue = Build(engine);

        var destination = Path.Combine(root, "saida");
        var job = NewJob(destination);
        var completion = WaitFor(queue, job.Id, JobStatus.Completed);

        queue.Enqueue(job);
        var snapshot = await completion;

        Assert.Equal(JobStatus.Completed, snapshot.Status);
        var produced = Assert.Single(snapshot.OutputFiles);
        Assert.True(File.Exists(produced));
        Assert.Equal(destination, Path.GetDirectoryName(produced));
        Assert.Equal("entrada.mp3", Path.GetFileName(produced));
    }

    [Fact]
    public async Task Job_ComErroDoMotor_TerminaComoFalhaSemDerrubarAFila()
    {
        var engine = new FakeEngine
        {
            Behavior = _ => throw new ConversionException("audio_stream_not_found", "Sem faixa de áudio.")
        };
        await using var queue = Build(engine);

        var job = NewJob(Path.Combine(root, "saida"));
        var completion = WaitFor(queue, job.Id, JobStatus.Failed);

        queue.Enqueue(job);
        var snapshot = await completion;

        Assert.Equal("audio_stream_not_found", snapshot.ErrorCode);
        Assert.Equal("Sem faixa de áudio.", snapshot.ErrorMessage);

        // A fila continua utilizável depois de uma falha.
        var second = NewJob(Path.Combine(root, "saida"));
        engine.Behavior = null;
        var secondCompletion = WaitFor(queue, second.Id, JobStatus.Completed);
        queue.Enqueue(second);
        Assert.Equal(JobStatus.Completed, (await secondCompletion).Status);
    }

    [Fact]
    public async Task Cancel_EncerraOJobEmExecucao()
    {
        var started = new TaskCompletionSource();
        var engine = new FakeEngine
        {
            BehaviorAsync = async (context, token) =>
            {
                started.TrySetResult();
                await Task.Delay(TimeSpan.FromSeconds(30), token);
                return new EngineResult([]);
            }
        };

        await using var queue = Build(engine);
        var job = NewJob(Path.Combine(root, "saida"));
        var completion = WaitFor(queue, job.Id, JobStatus.Cancelled);

        queue.Enqueue(job);
        await started.Task.WaitAsync(TimeSpan.FromSeconds(30));
        Assert.True(queue.Cancel(job.Id));

        var snapshot = await completion;
        Assert.Equal(JobStatus.Cancelled, snapshot.Status);
    }

    [Fact]
    public async Task Enqueue_SemMotorParaAOperacao_EhRejeitado()
    {
        await using var queue = Build(new FakeEngine { Operation = "image.convert" });
        var job = NewJob(Path.Combine(root, "saida"));

        var exception = Assert.Throws<ConversionException>(() => queue.Enqueue(job));
        Assert.Equal("operation_not_available", exception.Code);
    }

    [Fact]
    public async Task Enqueue_ComArquivoInexistente_EhRejeitado()
    {
        await using var queue = Build(new FakeEngine());
        var job = new ConversionJob
        {
            Options = new ExtractAudioOptions(),
            InputPath = Path.Combine(root, "nao-existe.mp4"),
            OutputDirectory = root
        };

        Assert.Equal("input_not_found", Assert.Throws<ConversionException>(() => queue.Enqueue(job)).Code);
    }

    [Fact]
    public async Task Pausar_ImpedeQueNovosJobsComecem()
    {
        var engine = new FakeEngine();
        await using var queue = Build(engine);
        queue.Pause();

        var job = NewJob(Path.Combine(root, "saida"));
        queue.Enqueue(job);

        await Task.Delay(400);
        Assert.Equal(JobStatus.Queued, queue.Snapshot().Single(item => item.Id == job.Id).Status);

        var completion = WaitFor(queue, job.Id, JobStatus.Completed);
        queue.Resume();
        Assert.Equal(JobStatus.Completed, (await completion).Status);
    }

    [Fact]
    public async Task Historico_RegistraOJobConcluido()
    {
        var history = new FakeHistory();
        var engine = new FakeEngine();
        await using var queue = new ConversionQueue(
            [engine],
            new JobWorkspaceFactory(new ConverterPaths(root)),
            history,
            new FakeLog(),
            new QueueSettings { MaxConcurrentJobs = 1 });

        var job = NewJob(Path.Combine(root, "saida"));
        var completion = WaitFor(queue, job.Id, JobStatus.Completed);
        queue.Enqueue(job);
        await completion;

        var entry = Assert.Single(history.Entries);
        Assert.Equal(JobStatus.Completed, entry.Status);
        Assert.Equal("video.extractAudio", entry.Operation);
        Assert.Equal("mp3", entry.OutputFormat);
    }

    // ==================== apoio ====================

    private ConversionQueue Build(IConversionEngine engine) => new(
        [engine],
        new JobWorkspaceFactory(new ConverterPaths(root)),
        new FakeHistory(),
        new FakeLog(),
        new QueueSettings { MaxConcurrentJobs = 2, JobTimeout = TimeSpan.FromMinutes(1) });

    private ConversionJob NewJob(string destination)
    {
        var input = Path.Combine(root, "entrada.mp4");
        if (!File.Exists(input))
        {
            File.WriteAllText(input, "conteúdo falso");
        }

        return new ConversionJob
        {
            Options = new ExtractAudioOptions { OutputFormat = "mp3" },
            InputPath = input,
            OutputDirectory = destination,
            OutputBaseName = "entrada"
        };
    }

    private static Task<JobSnapshot> WaitFor(ConversionQueue queue, Guid jobId, JobStatus status)
    {
        var completion = new TaskCompletionSource<JobSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);

        void Handler(object? sender, JobSnapshot snapshot)
        {
            if (snapshot.Id == jobId && snapshot.Status == status)
            {
                queue.JobChanged -= Handler;
                completion.TrySetResult(snapshot);
            }
        }

        queue.JobChanged += Handler;
        return completion.Task.WaitAsync(TimeSpan.FromSeconds(30));
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
        catch (IOException)
        {
            // Limpeza de teste; ignorar arquivos ainda bloqueados.
        }

        GC.SuppressFinalize(this);
    }

    private sealed class FakeEngine : IConversionEngine
    {
        public string Name => "Fake";

        public string Operation { get; init; } = "video.extractAudio";

        public Func<EngineContext, EngineResult>? Behavior { get; set; }

        public Func<EngineContext, CancellationToken, Task<EngineResult>>? BehaviorAsync { get; set; }

        public bool CanExecute(string operation) => operation == Operation;

        public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
        {
            if (BehaviorAsync is not null)
            {
                return await BehaviorAsync(context, cancellationToken);
            }

            if (Behavior is not null)
            {
                return Behavior(context);
            }

            context.Progress.Report(JobProgress.At(0.5, JobStage.Encoding));
            var output = context.Workspace.OutputPath("result.mp3");
            await File.WriteAllTextAsync(output, "áudio", cancellationToken);
            return new EngineResult([output], "ok");
        }
    }

    private sealed class FakeHistory : IJobHistoryStore
    {
        public List<JobHistoryEntry> Entries { get; } = [];

        public Task RecordAsync(JobHistoryEntry entry, CancellationToken cancellationToken)
        {
            lock (Entries)
            {
                Entries.Add(entry);
            }

            return Task.CompletedTask;
        }

        public Task<IReadOnlyList<JobHistoryEntry>> RecentAsync(int limit, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<JobHistoryEntry>>(Entries);

        public Task ClearAsync(CancellationToken cancellationToken)
        {
            Entries.Clear();
            return Task.CompletedTask;
        }
    }

    private sealed class FakeLog : IConverterLog
    {
        public void Write(LogChannel channel, string message)
        {
        }

        public void Error(string code, string message, Exception? exception = null)
        {
        }
    }
}
