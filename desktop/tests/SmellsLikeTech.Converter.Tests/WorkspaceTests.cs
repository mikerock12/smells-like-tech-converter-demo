using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Infrastructure.Configuration;
using SmellsLikeTech.Converter.Infrastructure.Database;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class WorkspaceTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-ws-" + Guid.NewGuid().ToString("N"));

    public WorkspaceTests() => Directory.CreateDirectory(root);

    [Fact]
    public void Create_MontaAsQuatroPastasDoJob()
    {
        var jobId = Guid.NewGuid();
        using var workspace = JobWorkspace.Create(root, jobId);

        Assert.True(Directory.Exists(workspace.InputDirectory));
        Assert.True(Directory.Exists(workspace.WorkDirectory));
        Assert.True(Directory.Exists(workspace.OutputDirectory));
        Assert.True(Directory.Exists(workspace.TempDirectory));
        Assert.EndsWith(jobId.ToString("N"), workspace.JobDirectory);
    }

    [Fact]
    public void WorkPath_ComSeparador_EhRejeitado()
    {
        using var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        var exception = Assert.Throws<ConversionException>(() => workspace.WorkPath(@"..\..\senha.txt"));
        Assert.Equal("workspace_escape", exception.Code);
    }

    [Fact]
    public void EnsureContained_ForaDoJob_EhRejeitado()
    {
        using var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        Assert.Throws<ConversionException>(() => workspace.EnsureContained(Path.Combine(root, "outro", "x.mp4")));
    }

    [Fact]
    public void Dispose_RemoveApenasAPastaDoJob()
    {
        var vizinho = Path.Combine(root, "vizinho.txt");
        File.WriteAllText(vizinho, "não me apague");

        var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        var jobDirectory = workspace.JobDirectory;
        workspace.Dispose();

        Assert.False(Directory.Exists(jobDirectory));
        Assert.True(File.Exists(vizinho));
    }

    /// <summary>
    /// O caso que fazia a conversao cancelada deixar a pasta para tras: matar o ffmpeg nao
    /// devolve os handles dele no mesmo instante, e a primeira tentativa de apagar pega o
    /// arquivo parcial ainda em uso. Aqui o arquivo e solto no meio das tentativas.
    /// </summary>
    /// <remarks>
    /// A soltura acontece dentro da espera entre tentativas, e nao num Task.Delay correndo
    /// contra o relogio: no CI a continuacao do Task.Delay chegou a atrasar mais que todas
    /// as esperas somadas, e o teste falhava sem defeito no produto.
    /// </remarks>
    [Fact]
    public void TryDelete_ComArquivoPresoQueEhSoltoLogoDepois_Apaga()
    {
        var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        var jobDirectory = workspace.JobDirectory;
        using var preso = File.Create(Path.Combine(workspace.OutputDirectory, "result.mp4"));
        var esperas = new List<int>();

        // Solta o arquivo depois de a primeira tentativa ter falhado, como o Windows faz
        // quando termina de fechar a tabela de handles do processo morto.
        var apagou = workspace.TryDelete(delay =>
        {
            esperas.Add(delay);
            preso.Dispose();
            Thread.Sleep(delay);
        });

        Assert.True(apagou);
        Assert.False(Directory.Exists(jobDirectory));
        Assert.NotEmpty(esperas);
    }

    /// <summary>
    /// Acesso negado tambem pode passar sozinho. No Windows 10, enquanto existe uma secao
    /// mapeada do arquivo (processo morto que o mapeou, visualizador, gerador de
    /// miniatura), a exclusao e recusada com acesso negado, e nao com "em uso", ate o
    /// mapeamento sumir. Versoes mais novas, como a do runner do CI, ja apagam o arquivo
    /// mapeado de primeira, entao o teste provoca o mesmo ERROR_ACCESS_DENIED com o
    /// atributo de somente leitura, que barra a exclusao em qualquer versao. Quem tira o
    /// atributo e o teste, entre uma tentativa e outra: o TryDelete nunca mexe em atributo.
    /// </summary>
    [Fact]
    public void TryDelete_ComAcessoNegadoQuePassaLogoDepois_Apaga()
    {
        var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        var jobDirectory = workspace.JobDirectory;
        var arquivo = Path.Combine(workspace.OutputDirectory, "result.mp4");
        File.WriteAllText(arquivo, "parcial");
        File.SetAttributes(arquivo, FileAttributes.ReadOnly);
        var esperas = new List<int>();

        var apagou = workspace.TryDelete(delay =>
        {
            esperas.Add(delay);
            File.SetAttributes(arquivo, FileAttributes.Normal);
            Thread.Sleep(delay);
        });

        Assert.True(apagou);
        Assert.False(Directory.Exists(jobDirectory));
        Assert.NotEmpty(esperas);
    }

    [Fact]
    public void TryDelete_ComArquivoPresoOTempoTodo_DevolveFalsoSemLancar()
    {
        var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        using var preso = File.Create(Path.Combine(workspace.OutputDirectory, "result.mp4"));

        Assert.False(workspace.TryDelete());
        Assert.True(Directory.Exists(workspace.JobDirectory));
    }

    [Fact]
    public void TryDelete_SemNadaPreso_ApagaNaPrimeiraTentativa()
    {
        var workspace = JobWorkspace.Create(root, Guid.NewGuid());
        File.WriteAllText(Path.Combine(workspace.OutputDirectory, "result.mp4"), "parcial");

        var esperas = new List<int>();
        var apagou = workspace.TryDelete(esperas.Add);

        Assert.True(apagou);
        // Sem nada preso ninguem espera: as esperas so existem para o caso raro.
        Assert.Empty(esperas);
    }

    [Fact]
    public void ConverterPaths_MontaAEstruturaPrevista()
    {
        var paths = new ConverterPaths(root);
        paths.EnsureCreated();

        Assert.True(Directory.Exists(paths.Temp));
        Assert.True(Directory.Exists(paths.Cache));
        Assert.True(Directory.Exists(paths.WhisperModels));
        Assert.True(Directory.Exists(paths.Logs));
        Assert.True(Directory.Exists(paths.FailedJobs));
        Assert.True(Directory.Exists(paths.Output));
        Assert.EndsWith("converter.db", paths.DatabaseFile);
    }

    [Fact]
    public void RetentionService_RemoveSomenteOQuePassouDaValidade()
    {
        var paths = new ConverterPaths(root);
        paths.EnsureCreated();

        var antigo = Path.Combine(paths.Temp, "antigo");
        var recente = Path.Combine(paths.Temp, "recente");
        Directory.CreateDirectory(antigo);
        Directory.CreateDirectory(recente);
        Directory.SetLastWriteTimeUtc(antigo, DateTime.UtcNow.AddDays(-2));

        var removed = new RetentionService(paths, new NullLog()).CleanupTemp(TimeSpan.FromHours(12));

        Assert.Equal(1, removed);
        Assert.False(Directory.Exists(antigo));
        Assert.True(Directory.Exists(recente));
    }

    [Fact]
    public async Task Sqlite_GravaELeHistoricoEConfiguracoes()
    {
        var paths = new ConverterPaths(root);
        paths.EnsureCreated();

        var database = new ConverterDatabase(paths);
        await database.InitializeAsync(CancellationToken.None);

        var history = new SqliteJobHistoryStore(database);
        await history.RecordAsync(
            new Core.Abstractions.JobHistoryEntry
            {
                Id = Guid.NewGuid(),
                Operation = "video.extractAudio",
                DisplayName = "show.mp4",
                OptionsSummary = "MP3 · 192 kbps",
                Status = Core.Jobs.JobStatus.Completed,
                InputFormat = "mp4",
                OutputFormat = "mp3",
                InputBytes = 1000,
                OutputBytes = 300,
                CreatedAt = DateTimeOffset.Now,
                CompletedAt = DateTimeOffset.Now,
                ElapsedSeconds = 1.5
            },
            CancellationToken.None);

        var entries = await history.RecentAsync(10, CancellationToken.None);
        Assert.Single(entries);
        Assert.Equal("show.mp4", entries[0].DisplayName);

        var settingsStore = new SettingsStore(database);
        await settingsStore.SaveAsync(new AppSettings { MaxConcurrentJobs = 4 }, CancellationToken.None);
        var reloaded = await settingsStore.LoadAsync(CancellationToken.None);

        Assert.Equal(4, reloaded.MaxConcurrentJobs);
        Assert.Equal(4, reloaded.ToQueueSettings().MaxConcurrentJobs);
    }

    [Fact]
    public void QueueSettings_LimitaPorOperacaoEDepoisPorFamilia()
    {
        var settings = new QueueSettingsFixture();
        Assert.Equal(1, settings.Value.LimitFor("speech.transcribe"));
        Assert.Equal(1, settings.Value.LimitFor("video.convert"));
        Assert.Equal(4, settings.Value.LimitFor("image.convert"));
        Assert.Equal(2, settings.Value.LimitFor("desconhecido.operacao"));
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
            // Arquivo ainda bloqueado por SQLite; irrelevante para o teste.
        }

        GC.SuppressFinalize(this);
    }

    private sealed class NullLog : Core.Abstractions.IConverterLog
    {
        public void Write(Core.Abstractions.LogChannel channel, string message)
        {
        }

        public void Error(string code, string message, Exception? exception = null)
        {
        }
    }

    private sealed class QueueSettingsFixture
    {
        public Core.Queue.QueueSettings Value { get; } = new() { MaxConcurrentJobs = 2 };
    }
}
