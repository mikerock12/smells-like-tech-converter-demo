using System.IO.Compression;
using System.Net.Http;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using SmellsLikeTech.Converter.Infrastructure.Storage;
using Xunit;
using Xunit.Abstractions;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// As ferramentas experimentais do conversor de ponta a ponta, com o Spleeter real (quando
/// SLT_MODELO_SEPARACAO aponta para o .onnx) e o FFmpeg real: o .zip com as combinações e
/// os efeitos, e o vídeo sem voz com a imagem copiada.
/// </summary>
public sealed class MusicStemsEngineTests(ITestOutputHelper output) : IDisposable
{
    private readonly string folder = Directory.CreateTempSubdirectory("pacote-teste").FullName;

    public void Dispose()
    {
        try { Directory.Delete(folder, true); } catch (IOException) { }
    }

    private static float[] Mix(int frames, int rate)
    {
        var x = new float[frames];
        for (var i = 0; i < frames; i++)
        {
            var t = i / (double)rate;
            x[i] = (float)(0.4 * Math.Sin(2 * Math.PI * 55 * t) + 0.15 * Math.Sin(2 * Math.PI * 440 * t) * (0.5 + 0.5 * Math.Sin(2 * Math.PI * 3 * t))
                + (i % (rate / 2) < 400 ? 0.5 * Math.Sin(i * 1.7) : 0));
        }

        return x;
    }

    private (MusicEngine Engine, string Input)? Prepare()
    {
        var model = Environment.GetEnvironmentVariable("SLT_MODELO_SEPARACAO");
        if (string.IsNullOrEmpty(model) || !File.Exists(model) || ExecutableGuard.Locate("ffmpeg") is null)
        {
            output.WriteLine("Sem SLT_MODELO_SEPARACAO ou FFmpeg: teste de ponta a ponta não executado.");
            return null;
        }

        var tools = new MusicTools(() => "ffmpeg", new ProcessRunner(), Path.Combine(folder, "temp"), Path.Combine(folder, "modelos"));
        Directory.CreateDirectory(Path.GetDirectoryName(tools.ModelPath)!);
        File.Copy(model, tools.ModelPath, true);
        const int rate = 44100;
        var mix = Mix(rate * 6, rate);
        var input = Path.Combine(folder, "musica.wav");
        File.WriteAllBytes(input, MusicMastering.EncodeWav([mix, mix], rate, 16));
        return (new MusicEngine(tools, () => new HttpClient()), input);
    }

    private async Task<(EngineResult Result, List<double> Progress)> RunAsync(MusicEngine engine, JobOptions options, string input)
    {
        var job = new ConversionJob { Options = options, InputPath = input, OutputDirectory = Path.Combine(folder, "saida") };
        using var workspace = JobWorkspace.Create(Path.Combine(folder, "jobs"), job.Id);
        var steps = new List<double>();
        var progress = new SyncProgress(p => { lock (steps) steps.Add(p.Percent ?? 0); });
        var result = await engine.ExecuteAsync(new EngineContext(job, workspace, progress), CancellationToken.None);
        var kept = result.OutputFiles.Select(f =>
        {
            var copy = Path.Combine(folder, Path.GetFileName(f));
            File.Copy(f, copy, true);
            return copy;
        }).ToList();
        return (result with { OutputFiles = kept }, steps);
    }

    private sealed class SyncProgress(Action<JobProgress> report) : IProgress<JobProgress>
    {
        public void Report(JobProgress value) => report(value);
    }

    [Fact]
    public async Task GeraOZipComAsCombinacoesEOsEfeitosDeCadaFaixa()
    {
        if (Prepare() is not var (engine, input)) return;
        var configuration = MusicStemPackage.Configuration("spleeter");
        configuration.Tracks.Single(t => t.Id == "vozes").Effects = [new MusicEffect { Id = "fx-comp", Kind = "compressor", Enabled = true }];
        configuration.Tracks.Single(t => t.Id == "baixo").GainDb = -6;
        var package = MusicStemPackage.ToJson(new MusicStemPackageRequest
        {
            Model = "spleeter",
            Configuration = configuration,
            Groups = [new() { Name = "Voz e baixo", Stems = ["vozes", "baixo"] }, new() { Name = "Sem voz", Stems = ["bateria", "baixo", "outros"] }],
            Format = "wav-16",
        });
        var started = DateTime.UtcNow;
        var (result, steps) = await RunAsync(engine, new MusicStemsPackageOptions { Package = package }, input);
        output.WriteLine($"Pacote de 6 s em {(DateTime.UtcNow - started).TotalSeconds:0.0} s; {steps.Count} avisos de progresso");

        var zip = Assert.Single(result.OutputFiles);
        Assert.EndsWith("musica-faixas.zip", zip);
        using var archive = ZipFile.OpenRead(zip);
        Assert.Equal(["musica-voz-e-baixo.wav", "musica-sem-voz.wav"], archive.Entries.Select(e => e.Name));
        foreach (var entry in archive.Entries)
        {
            var path = Path.Combine(folder, entry.Name);
            entry.ExtractToFile(path, true);
            var wav = MusicMastering.ReadWav(File.ReadAllBytes(path));
            Assert.Equal(44100, wav.SampleRate);
            Assert.InRange(wav.Channels[0].Length, 44100 * 6 - 10, 44100 * 6 + 44100 * 10);
            Assert.True(wav.Channels[0].Max(Math.Abs) > 0.01f, $"{entry.Name} saiu em silêncio");
        }

        Assert.True(steps.Count > 5);
        Assert.True(steps.Max() > 0.95, $"o progresso parou em {steps.Max():0.00}");
    }

    [Fact]
    public async Task TiraAVozDoVideoSemRecodificarAImagem()
    {
        if (Prepare() is not var (engine, input)) return;
        var video = Path.Combine(folder, "video.mp4");
        var runner = new ProcessRunner();
        var made = await runner.RunAsync(new CommandSpec("ffmpeg", ["-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25",
            "-i", input, "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", video]), folder, folder);
        Assert.Equal(0, made.ExitCode);
        var (result, _) = await RunAsync(engine, new VideoVoiceOptions { Package = """{"modelo":"spleeter","manter":"resto"}""", OutputFormat = "mp4" }, video);
        var file = Assert.Single(result.OutputFiles);
        Assert.EndsWith("video-sem-voz.mp4", file);
        var probe = await runner.RunAsync(new CommandSpec("ffprobe", ["-v", "error", "-show_entries", "stream=codec_type,codec_name,nb_frames:format=duration", "-of", "compact", file]), folder, folder);
        output.WriteLine(probe.StandardOutput);
        Assert.Contains("codec_name=h264", probe.StandardOutput);
        Assert.Contains("codec_name=aac", probe.StandardOutput);
        Assert.Contains("nb_frames=150", probe.StandardOutput);
    }
}
