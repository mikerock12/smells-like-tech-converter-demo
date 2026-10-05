using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Hardware;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// Executa o FFmpeg de verdade sobre um vídeo gerado na hora. Se o FFmpeg não estiver
/// instalado nesta máquina, os testes saem sem falhar.
/// </summary>
public class FfmpegIntegrationTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-ffmpeg-" + Guid.NewGuid().ToString("N"));
    private readonly ProcessRunner runner = new();
    private readonly string? ffmpeg = ExecutableGuard.Locate("ffmpeg");
    private readonly string? ffprobe = ExecutableGuard.Locate("ffprobe");

    public FfmpegIntegrationTests() => Directory.CreateDirectory(root);

    private bool Available => ffmpeg is not null && ffprobe is not null;

    [Fact]
    public async Task ExtrairAudio_GeraMp3Reproduzivel()
    {
        if (!Available)
        {
            return;
        }

        var source = await CreateSampleAsync(withAudio: true);
        var (engine, workspace, job) = Setup(source, new ExtractAudioOptions
        {
            OutputFormat = "mp3",
            AudioBitrateKbps = 128
        });

        using (workspace)
        {
            var result = await engine.ExecuteAsync(
                new EngineContext(job, workspace, new IgnoreProgress()),
                CancellationToken.None);

            var output = Assert.Single(result.OutputFiles);
            Assert.True(new FileInfo(output).Length > 1000);

            var probed = await Probe(output);
            Assert.Contains("mp3", probed.AudioStreams[0].Codec, StringComparison.OrdinalIgnoreCase);
            Assert.False(probed.HasVideo);
        }
    }

    [Fact]
    public async Task ExtrairAudio_DeVideoSemAudio_FalhaComMensagemEspecifica()
    {
        if (!Available)
        {
            return;
        }

        var source = await CreateSampleAsync(withAudio: false);
        var (engine, workspace, job) = Setup(source, new ExtractAudioOptions { OutputFormat = "mp3" });

        using (workspace)
        {
            var exception = await Assert.ThrowsAsync<Core.ConversionException>(() =>
                engine.ExecuteAsync(new EngineContext(job, workspace, new IgnoreProgress()), CancellationToken.None));

            Assert.Equal("audio_stream_not_found", exception.Code);
        }
    }

    [Fact]
    public async Task Converter_Para9x16ComFundoDesfocado_GeraVideoNaProporcaoPedida()
    {
        if (!Available)
        {
            return;
        }

        var source = await CreateSampleAsync(withAudio: true);
        var (engine, workspace, job) = Setup(source, new VideoConvertOptions
        {
            OutputFormat = "mp4",
            VideoCodec = VideoCodecChoice.H264,
            Acceleration = HardwareAcceleration.Cpu,
            CustomWidth = 360,
            CustomHeight = 640,
            AspectRatio = AspectRatioMode.Vertical9x16,
            Fit = FitMode.Blur,
            Quality = 40
        });

        using (workspace)
        {
            var result = await engine.ExecuteAsync(
                new EngineContext(job, workspace, new IgnoreProgress()),
                CancellationToken.None);

            var probed = await Probe(Assert.Single(result.OutputFiles));
            Assert.Equal(360, probed.Width);
            Assert.Equal(640, probed.Height);
            Assert.True(probed.HasAudio);
        }
    }

    [Fact]
    public async Task Converter_ParaGif_GeraArquivoAnimado()
    {
        if (!Available)
        {
            return;
        }

        var source = await CreateSampleAsync(withAudio: false);
        var (engine, workspace, job) = Setup(source, new VideoToGifOptions
        {
            FrameRate = 10,
            Width = 240,
            TrimDuration = TimeSpan.FromSeconds(2)
        });

        using (workspace)
        {
            var result = await engine.ExecuteAsync(
                new EngineContext(job, workspace, new IgnoreProgress()),
                CancellationToken.None);

            var output = Assert.Single(result.OutputFiles);
            Assert.EndsWith(".gif", output);
            Assert.True(new FileInfo(output).Length > 1000);
        }
    }

    [Fact]
    public async Task Progresso_ChegaAoFimDuranteAConversao()
    {
        if (!Available)
        {
            return;
        }

        var source = await CreateSampleAsync(withAudio: true);
        var (engine, workspace, job) = Setup(source, new ExtractAudioOptions { OutputFormat = "wav" });
        var reports = new List<double>();

        using (workspace)
        {
            await engine.ExecuteAsync(
                new EngineContext(job, workspace, new CollectProgress(reports)),
                CancellationToken.None);
        }

        Assert.NotEmpty(reports);
        Assert.Equal(1, reports[^1], 3);
    }

    [Fact]
    public async Task DeteccaoDeHardware_SoAnunciaEncoderQueRealmenteFunciona()
    {
        if (!Available)
        {
            return;
        }

        var paths = new ConverterPaths(root);
        paths.EnsureCreated();

        var hardware = await new HardwareProbe(paths, runner).DescribeAsync(ffmpeg!, CancellationToken.None);

        // Uma build completa do FFmpeg lista NVENC até em máquina sem placa NVIDIA.
        // Cada encoder anunciado precisa aguentar uma codificação de verdade.
        foreach (var encoder in hardware.HardwareEncoders)
        {
            var command = new CommandSpec(ffmpeg!,
            [
                "-hide_banner", "-nostdin", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=128x128:rate=5:duration=0.2",
                "-c:v", encoder,
                "-f", "null", "-"
            ]);

            var result = await runner.RunAsync(command, root, root, cancellationToken: CancellationToken.None);
            Assert.True(result.ExitCode == 0, $"'{encoder}' foi anunciado mas falhou ao codificar.");
        }

        // E a preferência automática nunca pode apontar para algo que não funciona.
        var preferida = hardware.PreferredAcceleration;
        if (preferida != HardwareAcceleration.Cpu)
        {
            Assert.NotEmpty(hardware.HardwareEncoders);
        }
    }

    // ==================== apoio ====================

    private (FfmpegEngine Engine, JobWorkspace Workspace, ConversionJob Job) Setup(string source, JobOptions options)
    {
        var engineOptions = FfmpegOptions.Resolve(ffmpeg!, ffprobe!, [], HardwareAcceleration.Cpu);
        var inspector = new FfprobeInspector(() => engineOptions, runner);
        var log = new SilentLog();
        var engine = new FfmpegEngine(() => engineOptions, runner, inspector, log);
        var workspace = JobWorkspace.Create(Path.Combine(root, "temp"), Guid.NewGuid());

        var job = new ConversionJob
        {
            Options = options,
            InputPath = source,
            OutputDirectory = Path.Combine(root, "saida")
        };

        return (engine, workspace, job);
    }

    private async Task<string> CreateSampleAsync(bool withAudio)
    {
        var path = Path.Combine(root, $"sample_{(withAudio ? "audio" : "mudo")}.mp4");
        if (File.Exists(path))
        {
            return path;
        }

        List<string> arguments =
        [
            "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=640x480:rate=30:duration=3"
        ];

        if (withAudio)
        {
            arguments.AddRange(["-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "aac"]);
        }

        arguments.AddRange(["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-t", "3", path]);

        var result = await runner.RunAsync(
            new CommandSpec(ffmpeg!, arguments),
            root,
            root,
            cancellationToken: CancellationToken.None);

        Assert.Equal(0, result.ExitCode);
        return path;
    }

    private async Task<MediaInfo> Probe(string path)
    {
        var engineOptions = FfmpegOptions.Resolve(ffmpeg!, ffprobe!, [], HardwareAcceleration.Cpu);
        return await new FfprobeInspector(() => engineOptions, runner).InspectAsync(path, CancellationToken.None);
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
            // Arquivo ainda em uso pelo antivírus/indexador; irrelevante para o teste.
        }

        GC.SuppressFinalize(this);
    }

    private sealed class IgnoreProgress : IProgress<JobProgress>
    {
        public void Report(JobProgress value)
        {
        }
    }

    private sealed class CollectProgress(List<double> reports) : IProgress<JobProgress>
    {
        public void Report(JobProgress value)
        {
            if (value.Percent is { } percent)
            {
                lock (reports)
                {
                    reports.Add(percent);
                }
            }
        }
    }

    private sealed class SilentLog : IConverterLog
    {
        public void Write(LogChannel channel, string message)
        {
        }

        public void Error(string code, string message, Exception? exception = null)
        {
        }
    }
}
