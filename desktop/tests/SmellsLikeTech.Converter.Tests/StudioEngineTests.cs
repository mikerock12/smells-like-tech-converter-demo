using ImageMagick;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Engine.Studio;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// Exporta um projeto do estudio com o FFmpeg de verdade: video desfocado + foto + video
/// mudo, um texto e musica. Sem FFmpeg na maquina, os testes saem sem falhar.
/// </summary>
public sealed class StudioEngineTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "slt-estudio-" + Guid.NewGuid().ToString("N"));
    private readonly ProcessRunner runner = new();
    private readonly string? ffmpeg = ExecutableGuard.Locate("ffmpeg");
    private readonly string? ffprobe = ExecutableGuard.Locate("ffprobe");

    public StudioEngineTests() => Directory.CreateDirectory(root);

    private bool Available => ffmpeg is not null && ffprobe is not null;

    [Theory]
    [InlineData("video")]
    [InlineData("gif")]
    [InlineData("imagem")]
    [InlineData("audio")]
    public async Task ExportaCadaSaida(string saida)
    {
        if (!Available)
        {
            return;
        }

        var (video, foto, musica) = await CreateSamplesAsync();
        var project = Project(saida, video, foto, musica);
        var result = await ExportAsync(project, [video, foto, musica]);
        var output = Assert.Single(result.OutputFiles);
        Assert.True(new FileInfo(output).Length > 500);

        if (saida == "imagem")
        {
            using var image = new MagickImage(output);
            Assert.Equal((540u, 960u), (image.Width, image.Height));
            return;
        }

        var info = await Probe(output);
        switch (saida)
        {
            case "video":
                Assert.True(info.HasVideo && info.HasAudio);
                Assert.Equal(540, info.VideoStreams[0].Width);
                Assert.Equal(960, info.VideoStreams[0].Height);
                Assert.InRange(info.Duration!.Value.TotalSeconds, 4.8, 5.3);
                break;
            case "gif":
                Assert.True(info.HasVideo);
                Assert.Equal(480, info.VideoStreams[0].Width);
                break;
            case "audio":
                Assert.False(info.HasVideo);
                Assert.InRange(info.Duration!.Value.TotalSeconds, 4.8, 5.3);
                break;
        }
    }

    [Fact]
    public async Task ArquivoQueNaoVeioNoJobERecusado()
    {
        if (!Available)
        {
            return;
        }

        var (video, foto, musica) = await CreateSamplesAsync();
        var project = Project("video", video, foto, musica);
        var erro = await Assert.ThrowsAsync<ConversionException>(() => ExportAsync(project, [video, foto]));
        Assert.Equal("missing_input", erro.Code);
    }

    [Fact]
    public void TextoViraPngTransparenteComLetraNoLugar()
    {
        var png = Path.Combine(root, "texto.png");
        var fonts = Path.Combine(AppContext.BaseDirectory, "fontes");
        new StudioTextRenderer(fonts).Render(new StudioText
        {
            Text = "Promoção de verão com várias palavras para quebrar a linha",
            Start = 0,
            End = 1,
            X = 0.5,
            Y = 0.2,
            Size = 0.06,
            Color = "#FFFFFF",
            Outline = "#000000",
            Box = "#FF000080",
            Font = "anton"
        }, 540, 960, png);

        KeepSample(png, "estudio-texto.png");
        using var image = new MagickImage(png);
        Assert.Equal((540u, 960u), (image.Width, image.Height));
        Assert.True(image.HasAlpha);
        using var pixels = image.GetPixels();
        Assert.Equal(0, pixels.GetPixel(5, 900)!.GetChannel(3));     // longe do texto: transparente
        Assert.True(pixels.GetPixel(270, 192)!.GetChannel(3) > 0);   // centro do texto (y = 0,2): desenhado
        Assert.True(File.Exists(Path.Combine(fonts, "Anton-Regular.ttf")));
    }

    [Fact]
    public void ReferenciaPorIndiceOuCaminhoDoJob()
    {
        var entradas = new[] { @"C:\a\video.mp4", @"C:\b\foto.jpg" };
        Assert.Equal(entradas[1], StudioEngine.Resolve(entradas, "#1"));
        Assert.Equal(entradas[0], StudioEngine.Resolve(entradas, @"C:\a\video.mp4"));
        Assert.Throws<ConversionException>(() => StudioEngine.Resolve(entradas, "#2"));
        Assert.Throws<ConversionException>(() => StudioEngine.Resolve(entradas, @"C:\c\outro.mp4"));
        Assert.Throws<ConversionException>(() => StudioEngine.Resolve(entradas, "video.mp4"));
    }

    [Fact]
    public async Task RecorteDeVideoPreservaAudioAlfaEDuracao()
    {
        if (!Available) return;
        var input = Path.Combine(root, "entrada-recorte.mp4");
        var output = Path.Combine(root, "recortado.mkv");
        await Run("-f", "lavfi", "-i", "testsrc=size=96x80:rate=4:duration=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", input);
        await BackgroundVideo.RemoveAsync(ffmpeg!, input, output, 96, 80, 4, .25, .5, CancellationToken.None);
        var info = await Probe(output);
        Assert.True(info.HasVideo && info.HasAudio);
        Assert.InRange(info.Duration!.Value.TotalSeconds, .45, .65);
        var frame = Path.Combine(root, "recortado.png");
        await Run("-i", output, "-frames:v", "1", frame);
        using var image = new MagickImage(frame);
        Assert.True(image.HasAlpha);
        var rgba = image.ToByteArray(MagickFormat.Rgba);
        Assert.Contains(rgba.Where((_, i) => i % 4 == 3), a => a < 128);
        Assert.Contains(rgba.Where((_, i) => i % 4 == 3), a => a > 200);
    }

    [Fact]
    public async Task ExportaPerspectivaComCamadaRecortadaNoInstanteEscolhido()
    {
        if (!Available) return;
        var (video, foto, _) = await CreateSamplesAsync();
        var project = StudioRules.NewProject("personalizado", "imagem", 128, 128) with
        {
            Clips = [new StudioClip { Id = "base", File = foto, Kind = "imagem", Duration = 2 }],
            Layers = [new StudioLayer { Id = "extra", File = video, Kind = "video", Start = .3, End = 2, Offset = .1,
                Transform = new StudioTransform { RotationX = 15, RotationY = -20, Rotation = 17, Scale = .6, RemoveBackground = true } }],
            Frame = 1
        };
        var result = await ExportAsync(project, [foto, video]);
        using var image = new MagickImage(Assert.Single(result.OutputFiles));
        Assert.Equal((128u, 128u), (image.Width, image.Height));
    }

    private static StudioProject Project(string saida, string video, string foto, string musica) =>
        StudioRules.NewProject("personalizado", saida, 540, 960) with
        {
            Clips =
            [
                new StudioClip { Id = "c1", File = video, Kind = "video", Start = 0.5, End = 2.5, Fit = "caber-desfocado", Volume = 1 },
                new StudioClip { Id = "c2", File = foto, Kind = "imagem", Duration = 1.5, Fit = "preencher" },
                new StudioClip { Id = "c3", File = "#0", Kind = "video", Start = 0, End = 1.5, Fit = "caber", Muted = true }
            ],
            Texts =
            [
                new StudioText { Id = "t1", Text = "Olá, estúdio!", Start = 0, End = 3, Y = 0.2, Size = 0.07, Color = "#FFFF00", Outline = "#000000", Font = "bangers" }
            ],
            Audios = [new StudioAudio { Id = "m1", File = musica, At = 1, Start = 0, End = 10, Volume = 0.5, FadeIn = 0.5, FadeOut = 1 }],
            Frame = 1
        };

    private async Task<EngineResult> ExportAsync(StudioProject project, IReadOnlyList<string> inputs)
    {
        var engineOptions = FfmpegOptions.Resolve(ffmpeg!, ffprobe!, [], HardwareAcceleration.Cpu);
        var inspector = new FfprobeInspector(() => engineOptions, runner);
        var engine = new StudioEngine(() => engineOptions, runner, inspector, new NoTranscriber(), new SilentLog(),
            Path.Combine(AppContext.BaseDirectory, "fontes"));
        using var workspace = JobWorkspace.Create(Path.Combine(root, "temp"), Guid.NewGuid());
        var job = new ConversionJob
        {
            Options = new StudioExportOptions { Project = project },
            InputPath = inputs[0],
            ExtraInputPaths = [.. inputs.Skip(1)],
            OutputDirectory = Path.Combine(root, "saida")
        };
        var result = await engine.ExecuteAsync(new EngineContext(job, workspace, new IgnoreProgress()), CancellationToken.None);

        // O workspace some no Dispose: copia o resultado para conferir depois.
        var copies = result.OutputFiles.Select(file =>
        {
            var copy = Path.Combine(root, Guid.NewGuid().ToString("N") + Path.GetExtension(file));
            File.Copy(file, copy);
            KeepSample(copy, $"estudio-{project.Output}{Path.GetExtension(file)}");
            return copy;
        }).ToList();
        return result with { OutputFiles = copies };
    }

    /// <summary>Com SLT_AMOSTRAS definido, guarda o resultado para conferir a olho.</summary>
    private static void KeepSample(string path, string name)
    {
        var folder = Environment.GetEnvironmentVariable("SLT_AMOSTRAS");
        if (!string.IsNullOrWhiteSpace(folder))
        {
            Directory.CreateDirectory(folder);
            File.Copy(path, Path.Combine(folder, name), overwrite: true);
        }
    }

    private async Task<(string Video, string Foto, string Musica)> CreateSamplesAsync()
    {
        var video = Path.Combine(root, "video.mp4");
        var foto = Path.Combine(root, "foto.jpg");
        var musica = Path.Combine(root, "musica.mp3");
        if (!File.Exists(video))
        {
            await Run("-f", "lavfi", "-i", "testsrc=size=640x360:rate=30:duration=3", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", video);
            await Run("-f", "lavfi", "-i", "testsrc=size=400x600:duration=1", "-frames:v", "1", foto);
            await Run("-f", "lavfi", "-i", "sine=frequency=880:duration=12", musica);
        }

        return (video, foto, musica);
    }

    private async Task Run(params string[] args)
    {
        var result = await runner.RunAsync(new CommandSpec(ffmpeg!, ["-hide_banner", "-loglevel", "error", "-y", .. args]), root, root,
            cancellationToken: CancellationToken.None);
        Assert.Equal(0, result.ExitCode);
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
            // Arquivo ainda em uso pelo antivirus/indexador; irrelevante para o teste.
        }
    }

    private sealed class IgnoreProgress : IProgress<JobProgress>
    {
        public void Report(JobProgress value)
        {
        }
    }

    private sealed class NoTranscriber : IConversionEngine
    {
        public string Name => "nenhum";

        public bool CanExecute(string operation) => false;

        public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Sem transcrição neste teste.");
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
