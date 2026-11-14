using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Engine.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;
using Xunit.Abstractions;

namespace SmellsLikeTech.Converter.Tests;

public sealed class InferenceProcessorsTests(ITestOutputHelper output)
{
    [Fact]
    public void UsaOsNucleosFisicosDeDesempenho()
    {
        Assert.Equal(3, InferenceProcessors.Plan(4, 4));   // i5 de 4 núcleos: um livre para a interface
        Assert.Equal(8, InferenceProcessors.Plan(8, 16));  // 8 núcleos com SMT: não 15 threads disputando
        Assert.Equal(6, InferenceProcessors.Plan(6, 20));  // Intel híbrido 6P+8E: só os P-cores
        Assert.Equal(7, InferenceProcessors.Plan(0, 8));   // sem a informação: como antes
        Assert.Equal(1, InferenceProcessors.Plan(1, 2));
        var aqui = InferenceProcessors.PerformanceCores();
        output.WriteLine($"Nesta máquina: {aqui} núcleo(s) de desempenho, {Environment.ProcessorCount} lógicos, {InferenceProcessors.InferenceThreads()} thread(s) na inferência.");
        Assert.InRange(aqui, 1, Environment.ProcessorCount);
    }

    /// <summary>Medição (SLT_MEDIR_THREADS=1 e SLT_MODELO_SEPARACAO): Spleeter real em 30 s de áudio com 1..N threads.</summary>
    [Fact]
    public async Task MedeASeparacaoPorNumeroDeThreads()
    {
        var model = Environment.GetEnvironmentVariable("SLT_MODELO_SEPARACAO");
        if (Environment.GetEnvironmentVariable("SLT_MEDIR_THREADS") != "1" || string.IsNullOrEmpty(model) || !File.Exists(model)) return;
        var folder = Directory.CreateTempSubdirectory("threads").FullName;
        try
        {
            var tools = new MusicTools(() => "ffmpeg", new ProcessRunner(), Path.Combine(folder, "temp"), Path.Combine(folder, "modelos"));
            Directory.CreateDirectory(Path.GetDirectoryName(tools.ModelPath)!);
            File.Copy(model, tools.ModelPath, true);
            const int rate = 44100;
            var n = rate * 30;
            var x = new float[n];
            for (var i = 0; i < n; i++) x[i] = (float)(0.3 * Math.Sin(2 * Math.PI * 110 * i / rate) + 0.2 * Math.Sin(2 * Math.PI * 660 * i / rate) * Math.Sin(i * 0.0003));
            var input = Path.Combine(folder, "entrada.wav");
            File.WriteAllBytes(input, MusicMastering.EncodeWav([x, x], rate, 16));
            await tools.SeparateAsync(input, Path.Combine(folder, "aquecer"), "4", rate, null, CancellationToken.None);
            for (var threads = 1; threads <= Environment.ProcessorCount; threads++)
            {
                Environment.SetEnvironmentVariable("SLT_ONNX_THREADS", threads.ToString());
                var started = DateTime.UtcNow;
                await tools.SeparateAsync(input, Path.Combine(folder, $"t{threads}"), "4", rate, null, CancellationToken.None);
                output.WriteLine($"{threads} thread(s): {(DateTime.UtcNow - started).TotalSeconds:0.00} s para 30 s de áudio");
            }
        }
        finally
        {
            Environment.SetEnvironmentVariable("SLT_ONNX_THREADS", null);
            try { Directory.Delete(folder, true); } catch (IOException) { }
        }
    }
}
