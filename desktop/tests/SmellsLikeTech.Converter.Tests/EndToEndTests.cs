using UglyToad.PdfPig.Writer;
using UglyToad.PdfPig.Fonts.Standard14Fonts;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Content;
using SmellsLikeTech.Converter.Engine.Documents;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Queue;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Engine.Image;
using SmellsLikeTech.Converter.Engine.Speech;
using SmellsLikeTech.Converter.Infrastructure.Database;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Logging;
using SmellsLikeTech.Converter.Infrastructure.Media;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// Pilha completa como o aplicativo monta: fila real, motores reais, workspace em disco
/// e histórico em SQLite. Só falta o clique na interface.
/// </summary>
public class EndToEndTests : IAsyncLifetime
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-e2e-" + Guid.NewGuid().ToString("N"));
    private readonly ProcessRunner runner = new();
    private readonly KokoroTtsEngine ttsEngine = new();
    private readonly string? ffmpeg = ExecutableGuard.Locate("ffmpeg");
    private readonly string? ffprobe = ExecutableGuard.Locate("ffprobe");

    private ConverterPaths paths = null!;
    private ConverterDatabase database = null!;
    private SqliteJobHistoryStore history = null!;
    private FileLog log = null!;
    private ConversionQueue queue = null!;
    private CompositeMediaInspector inspector = null!;
    private WhisperModelCatalog models = null!;
    private string destination = null!;

    private bool FfmpegAvailable => ffmpeg is not null && ffprobe is not null;

    public async Task InitializeAsync()
    {
        paths = new ConverterPaths(root);
        paths.EnsureCreated();
        destination = Path.Combine(root, "Saida");

        database = new ConverterDatabase(paths);
        await database.InitializeAsync(CancellationToken.None);
        history = new SqliteJobHistoryStore(database);
        log = new FileLog(paths);

        var engineOptions = FfmpegOptions.Resolve(
            ffmpeg ?? "ffmpeg",
            ffprobe ?? "ffprobe",
            [],
            HardwareAcceleration.Cpu);

        var probe = new FfprobeInspector(() => engineOptions, runner);
        inspector = new CompositeMediaInspector([probe, new MagickImageInspector()]);

        // Os modelos Whisper são pesados e instalados sob demanda: o teste usa os que
        // realmente estão na máquina, não uma cópia dentro da pasta temporária.
        models = new WhisperModelCatalog(ConverterPaths.CreateDefault().WhisperModels);

        IConversionEngine[] engines =
        [
            new FfmpegEngine(() => engineOptions, runner, probe, log),
            new MagickImageEngine(log),
            new SpeechSynthesisEngine(ttsEngine, () => engineOptions, runner, () => 20_000, log, new DocumentTextReader(new WindowsTextRecognizer())),
            new WhisperTranscriptionEngine(() => engineOptions, runner, probe, models, () => 240, log)
        ];

        queue = new ConversionQueue(
            engines,
            new JobWorkspaceFactory(paths),
            history,
            log,
            new QueueSettings { MaxConcurrentJobs = 2, JobTimeout = TimeSpan.FromMinutes(3) });
    }

    [Fact]
    public async Task VideoParaMp3_ChegaNoDestinoEEntraNoHistorico()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var source = await CreateVideoAsync("show.mp4", seconds: 3, withAudio: true);
        var info = await inspector.InspectAsync(source, CancellationToken.None);

        var job = new ConversionJob
        {
            Options = new ExtractAudioOptions { OutputFormat = "mp3", AudioBitrateKbps = 192 },
            InputPath = source,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = SafeFileName.FromPath(source)
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);

        var output = Assert.Single(snapshot.OutputFiles);
        Assert.Equal(Path.Combine(destination, "show.mp3"), output);
        Assert.True(File.Exists(output));
        Assert.True(snapshot.OutputBytes > 1000);

        // O áudio produzido é válido e não carrega vídeo junto.
        var produced = await inspector.InspectAsync(output, CancellationToken.None);
        Assert.True(produced.HasAudio);
        Assert.False(produced.HasVideo);

        // A pasta temporária do job sai do disco depois do sucesso.
        Assert.Empty(Directory.GetDirectories(paths.Temp));

        var entries = await history.RecentAsync(10, CancellationToken.None);
        var entry = Assert.Single(entries);
        Assert.Equal(JobStatus.Completed, entry.Status);
        Assert.Equal("video.extractAudio", entry.Operation);
        Assert.Equal("FFmpeg", entry.Engine);
        Assert.True(entry.ElapsedSeconds > 0);
    }

    [Fact]
    public async Task VideoPara9x16_SaiNaResolucaoPedida()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var source = await CreateVideoAsync("paisagem.mp4", seconds: 2, withAudio: true);
        var info = await inspector.InspectAsync(source, CancellationToken.None);

        var job = new ConversionJob
        {
            Options = new VideoConvertOptions
            {
                OutputFormat = "mp4",
                VideoCodec = VideoCodecChoice.H264,
                Acceleration = HardwareAcceleration.Cpu,
                CustomWidth = 360,
                CustomHeight = 640,
                AspectRatio = AspectRatioMode.Vertical9x16,
                Fit = FitMode.Blur,
                Quality = 40,
                Audio = AudioTrackAction.Reencode,
                AudioBitrateKbps = 96
            },
            InputPath = source,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "reels"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var produced = await inspector.InspectAsync(snapshot.OutputFiles[0], CancellationToken.None);

        Assert.Equal(360, produced.Width);
        Assert.Equal(640, produced.Height);
        Assert.True(produced.HasAudio);
    }

    [Fact]
    public async Task ImagemParaWebp_RedimensionaEGrava()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var source = await CreateImageAsync("foto.png", 1600, 900);
        var info = await inspector.InspectAsync(source, CancellationToken.None);
        Assert.Equal(1600, info.Width);

        var job = new ConversionJob
        {
            Options = new ImageConvertOptions
            {
                OutputFormat = "webp",
                ResizeMode = ImageResizeMode.Width,
                Width = 800,
                Quality = 80
            },
            InputPath = source,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "foto"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var output = Assert.Single(snapshot.OutputFiles);

        Assert.Equal(Path.Combine(destination, "foto.webp"), output);
        var produced = await inspector.InspectAsync(output, CancellationToken.None);
        Assert.Equal(800, produced.Width);
        Assert.Equal(450, produced.Height);
    }

    [Fact]
    public async Task PdfParaMp3_LeOTextoDoPdfEGeraAudio()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var pdf = Path.Combine(root, "documento.pdf");
        var builder = new PdfDocumentBuilder();
        var fonte = builder.AddStandard14Font(Standard14Font.Helvetica);
        var pagina = builder.AddPage(PageSize.A4);
        pagina.AddText("Este documento foi lido em voz alta pelo conversor.", 12, new PdfPoint(50, 700), fonte);
        await File.WriteAllBytesAsync(pdf, builder.Build());

        var voz = ttsEngine.ListVoices()[0];
        var job = new ConversionJob
        {
            Options = new SynthesizeOptions { OutputFormat = "mp3", VoiceId = voz.Id, Rate = 0, Volume = 90, AudioBitrateKbps = 128 },
            InputPath = pdf,
            OutputDirectory = destination,
            OutputBaseName = "documento-narrado"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var output = Assert.Single(snapshot.OutputFiles);
        var produced = await inspector.InspectAsync(output, CancellationToken.None);
        Assert.True(produced.HasAudio);
        Assert.True(produced.Duration > TimeSpan.FromSeconds(1));
    }

    [Fact]
    public async Task TextoParaMp3_UsaAVozInstaladaEGeraAudio()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var voices = ttsEngine.ListVoices();
        if (voices.Count == 0)
        {
            // Nenhuma voz SAPI instalada nesta máquina.
            return;
        }

        var job = new ConversionJob
        {
            Options = new SynthesizeOptions
            {
                OutputFormat = "mp3",
                VoiceId = voices[0].Id,
                Rate = 0,
                Volume = 90,
                AudioBitrateKbps = 128
            },
            InputText = "Bem-vindo ao Smells Like Tech Converter. Este áudio foi gerado localmente.",
            OutputDirectory = destination,
            OutputBaseName = "locucao"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var output = Assert.Single(snapshot.OutputFiles);

        Assert.Equal(Path.Combine(destination, "locucao.mp3"), output);
        var produced = await inspector.InspectAsync(output, CancellationToken.None);
        Assert.True(produced.HasAudio);
        Assert.True(produced.Duration > TimeSpan.FromSeconds(1));

        // O texto sintetizado não pode aparecer em log nem no histórico.
        var entry = Assert.Single(await history.RecentAsync(10, CancellationToken.None));
        Assert.DoesNotContain("Bem-vindo", entry.OptionsSummary, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("Bem-vindo", entry.DisplayName, StringComparison.OrdinalIgnoreCase);

        var speechLog = Path.Combine(paths.Logs, "speech.log");
        if (File.Exists(speechLog))
        {
            Assert.DoesNotContain("Bem-vindo", await File.ReadAllTextAsync(speechLog), StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task VozParaTexto_TranscreveEmPortuguesComOModeloLocal()
    {
        var fala = await SintetizarFalaAsync("fala-pt.wav");
        if (fala is null)
        {
            return;
        }

        var info = await inspector.InspectAsync(fala, CancellationToken.None);
        var job = new ConversionJob
        {
            Options = new TranscribeOptions
            {
                OutputFormat = "txt",
                Language = "pt",
                Model = "base",
                UseGpu = false
            },
            InputPath = fala,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "transcricao"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var output = Assert.Single(snapshot.OutputFiles);
        Assert.Equal(Path.Combine(destination, "transcricao.txt"), output);

        var texto = Normalizar(await File.ReadAllTextAsync(output));
        Assert.NotEmpty(texto);

        // O reconhecimento nunca é perfeito: basta acertar a maior parte das palavras
        // marcantes da frase ditada para provar que o pipeline funciona de verdade.
        string[] esperadas = ["programa", "converte", "video", "audio", "imagem", "computador"];
        var acertos = esperadas.Count(palavra => texto.Contains(palavra, StringComparison.Ordinal));
        Assert.True(acertos >= 3, $"Transcrição reconheceu {acertos} de {esperadas.Length} palavras: “{texto}”");
    }

    [Fact]
    public async Task VozParaLegenda_GeraSrtComTimestampsValidos()
    {
        var fala = await SintetizarFalaAsync("fala-srt.wav");
        if (fala is null)
        {
            return;
        }

        var info = await inspector.InspectAsync(fala, CancellationToken.None);
        var job = new ConversionJob
        {
            Options = new TranscribeOptions { OutputFormat = "srt", Language = "pt", Model = "base" },
            InputPath = fala,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "legenda"
        };

        var snapshot = await RunAsync(job, JobStatus.Completed);
        var srt = Assert.Single(snapshot.OutputFiles);

        // Timestamps crescentes e dentro da duração do áudio.
        var blocos = await SubtitleConverter.ValidateSrtAsync(srt, info.Duration, CancellationToken.None);
        Assert.True(blocos > 0);

        // E o mesmo áudio em VTT sai com o cabeçalho certo.
        var vttJob = new ConversionJob
        {
            Options = new TranscribeOptions { OutputFormat = "vtt", Language = "pt", Model = "base" },
            InputPath = fala,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "legenda"
        };

        var vttSnapshot = await RunAsync(vttJob, JobStatus.Completed);
        var vtt = await File.ReadAllTextAsync(vttSnapshot.OutputFiles[0]);
        Assert.StartsWith("WEBVTT", vtt);
        Assert.Contains(" --> ", vtt, StringComparison.Ordinal);
        Assert.DoesNotContain(",", vtt.Split(" --> ")[0][^12..], StringComparison.Ordinal);
    }

    [Fact]
    public async Task Transcricao_SemModeloInstalado_FalhaComMensagemOrientando()
    {
        var vazio = new WhisperModelCatalog(Path.Combine(root, "sem-modelos"));
        Assert.False(vazio.Find("base")!.IsInstalled);

        var fala = await SintetizarFalaAsync("fala-erro.wav");
        if (fala is null)
        {
            return;
        }

        var engine = new WhisperTranscriptionEngine(
            () => FfmpegOptions.Resolve(ffmpeg ?? "ffmpeg", ffprobe ?? "ffprobe", [], HardwareAcceleration.Cpu),
            runner,
            new FfprobeInspector(
                () => FfmpegOptions.Resolve(ffmpeg ?? "ffmpeg", ffprobe ?? "ffprobe", [], HardwareAcceleration.Cpu),
                runner),
            vazio,
            () => 240,
            log);

        using var workspace = JobWorkspace.Create(Path.Combine(root, "temp-erro"), Guid.NewGuid());
        var job = new ConversionJob
        {
            Options = new TranscribeOptions { Model = "base" },
            InputPath = fala,
            OutputDirectory = destination
        };

        var erro = await Assert.ThrowsAsync<Core.ConversionException>(() =>
            engine.ExecuteAsync(new EngineContext(job, workspace, new IgnoreProgress()), CancellationToken.None));

        Assert.Equal("model_not_installed", erro.Code);
        Assert.Contains("Configurações", erro.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task CancelarConversaoEmAndamento_EncerraOProcessoSemDerrubarAFila()
    {
        if (!FfmpegAvailable)
        {
            return;
        }

        var source = await CreateVideoAsync("longo.mp4", seconds: 40, withAudio: true, width: 1280, height: 720);
        var info = await inspector.InspectAsync(source, CancellationToken.None);

        var job = new ConversionJob
        {
            Options = new VideoConvertOptions
            {
                OutputFormat = "mp4",
                VideoCodec = VideoCodecChoice.H265,
                Acceleration = HardwareAcceleration.Cpu,
                TargetHeight = 720,
                Quality = 90
            },
            InputPath = source,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "cancelado"
        };

        var processing = WaitFor(job.Id, JobStatus.Processing);
        var cancelled = WaitFor(job.Id, JobStatus.Cancelled);

        queue.Enqueue(job);
        await processing;
        await Task.Delay(1200);
        Assert.True(queue.Cancel(job.Id));

        var snapshot = await cancelled;
        Assert.Equal(JobStatus.Cancelled, snapshot.Status);
        Assert.Empty(snapshot.OutputFiles);

        // Converter 40 s de 720p em H.265 com CRF baixo leva minutos nesta CPU; encerrar
        // em menos de um minuto prova que o processo foi morto, não aguardado até o fim.
        // A margem é larga de propósito: o teste mede cancelamento, não desempenho.
        Assert.NotNull(snapshot.Elapsed);
        Assert.True(
            snapshot.Elapsed!.Value < TimeSpan.FromSeconds(60),
            $"O cancelamento demorou {snapshot.Elapsed.Value.TotalSeconds:0.0}s.");

        // A fila só marca Cancelled depois que o processo filho encerra, então
        // nesse ponto a pasta temporária do job já saiu do disco.
        Assert.DoesNotContain(
            Directory.GetDirectories(paths.Temp),
            directory => Path.GetFileName(directory) == job.Id.ToString("N"));

        // E nada parcial ficou no destino.
        Assert.False(File.Exists(Path.Combine(destination, "cancelado.mp4")));

        // A fila segue funcionando: um job simples depois do cancelamento conclui.
        var second = new ConversionJob
        {
            Options = new ExtractAudioOptions { OutputFormat = "wav" },
            InputPath = source,
            Input = info,
            OutputDirectory = destination,
            OutputBaseName = "depois"
        };
        var after = await RunAsync(second, JobStatus.Completed);
        Assert.True(File.Exists(after.OutputFiles[0]));
    }

    // ==================== apoio ====================

    /// <summary>Frase ditada pela voz do Windows, usada como entrada real da transcrição.</summary>
    private const string FraseDitada =
        "O programa converte vídeo, áudio e imagem direto no seu computador.";

    /// <summary>
    /// Gera um WAV falado com uma voz pt-BR instalada. Devolve nulo quando a máquina
    /// não tem voz pt-BR ou o modelo Whisper não foi baixado.
    /// </summary>
    private async Task<string?> SintetizarFalaAsync(string nomeArquivo)
    {
        if (!FfmpegAvailable || models.Find("base")?.IsInstalled != true)
        {
            return null;
        }

        var voz = ttsEngine.ListVoices().FirstOrDefault(voice => voice.IsBrazilianPortuguese);
        if (voz is null)
        {
            return null;
        }

        var path = Path.Combine(root, nomeArquivo);
        if (File.Exists(path))
        {
            return path;
        }

        await ttsEngine.SynthesizeToWaveAsync(
            FraseDitada,
            voz.Id,
            rate: 0,
            volume: 100,
            path,
            null,
            CancellationToken.None);

        return path;
    }

    /// <summary>Minúsculas e sem acentos, para comparar texto reconhecido sem falso negativo.</summary>
    private static string Normalizar(string texto)
    {
        var decomposto = texto.ToLowerInvariant().Normalize(System.Text.NormalizationForm.FormD);
        var builder = new System.Text.StringBuilder(decomposto.Length);
        foreach (var caractere in decomposto)
        {
            if (System.Globalization.CharUnicodeInfo.GetUnicodeCategory(caractere)
                != System.Globalization.UnicodeCategory.NonSpacingMark)
            {
                builder.Append(caractere);
            }
        }

        return builder.ToString().Normalize(System.Text.NormalizationForm.FormC).Trim();
    }

    private sealed class IgnoreProgress : IProgress<JobProgress>
    {
        public void Report(JobProgress value)
        {
        }
    }

    private async Task<JobSnapshot> RunAsync(ConversionJob job, JobStatus expected)
    {
        var completion = WaitFor(job.Id, expected);
        queue.Enqueue(job);
        return await completion;
    }

    private Task<JobSnapshot> WaitFor(Guid jobId, JobStatus status)
    {
        var completion = new TaskCompletionSource<JobSnapshot>(TaskCreationOptions.RunContinuationsAsynchronously);

        void Handler(object? sender, JobSnapshot snapshot)
        {
            if (snapshot.Id != jobId)
            {
                return;
            }

            if (snapshot.Status == status)
            {
                queue.JobChanged -= Handler;
                completion.TrySetResult(snapshot);
            }
            else if (snapshot.Status == JobStatus.Failed && status != JobStatus.Failed)
            {
                queue.JobChanged -= Handler;
                completion.TrySetException(new Xunit.Sdk.XunitException(
                    $"Job falhou: {snapshot.ErrorCode} · {snapshot.ErrorMessage}"));
            }
        }

        queue.JobChanged += Handler;
        return completion.Task.WaitAsync(TimeSpan.FromMinutes(3));
    }

    private async Task<string> CreateVideoAsync(
        string name,
        int seconds,
        bool withAudio,
        int width = 640,
        int height = 480)
    {
        var path = Path.Combine(root, name);
        List<string> arguments =
        [
            "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", $"testsrc=size={width}x{height}:rate=30:duration={seconds}"
        ];

        if (withAudio)
        {
            arguments.AddRange(["-f", "lavfi", "-i", $"sine=frequency=440:duration={seconds}", "-c:a", "aac"]);
        }

        arguments.AddRange(["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-t", $"{seconds}", path]);

        var result = await runner.RunAsync(new CommandSpec(ffmpeg!, arguments), root, root, cancellationToken: CancellationToken.None);
        Assert.Equal(0, result.ExitCode);
        return path;
    }

    private async Task<string> CreateImageAsync(string name, int width, int height)
    {
        var path = Path.Combine(root, name);
        var result = await runner.RunAsync(
            new CommandSpec(ffmpeg!,
            [
                "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", $"testsrc=size={width}x{height}:rate=1:duration=1",
                "-frames:v", "1", path
            ]),
            root,
            root,
            cancellationToken: CancellationToken.None);

        Assert.Equal(0, result.ExitCode);
        return path;
    }

    public async Task DisposeAsync()
    {
        await queue.DisposeAsync();
        ttsEngine.Dispose();
        log.Dispose();
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();

        try
        {
            if (Directory.Exists(root))
            {
                Directory.Delete(root, recursive: true);
            }
        }
        catch (IOException)
        {
            // Arquivo ainda bloqueado; irrelevante para o resultado do teste.
        }
    }
}
