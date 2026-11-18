using System.Text.Json;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Engine.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// Restaurador de áudio: as mesmas regras do site e do Android (restaurador-casos.json, gerado
/// por node scripts/restaurador-casos.mjs), o tratamento do pacote de faixas e o caminho real
/// pela DLL (diagnóstico, plano e reparo de um WAV com zumbido, chiado e estalos).
/// </summary>
public sealed class AudioRestorationTests
{
    private static Dictionary<string, double> Numbers(JsonElement e) =>
        e.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetDouble());

    [Fact]
    public void PlanoBateComOJs()
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "restaurador-casos.json")));
        foreach (var caso in document.RootElement.EnumerateArray())
        {
            var nome = caso.GetProperty("nome").GetString();
            var plan = AudioRestoration.Build(Numbers(caso.GetProperty("diagnostico")), AudioRestoration.ReadOptions(caso.GetProperty("opcoes").GetRawText()));
            var esperado = caso.GetProperty("esperado");
            Assert.Equal(esperado.GetProperty("perfil").GetString(), plan.Profile);
            Assert.Equal(esperado.GetProperty("etapas").EnumerateArray().Select(x => x.GetString()), plan.Steps.Select(s => s.Id));
            Assert.Equal(esperado.GetProperty("avisos").EnumerateArray().Select(x => x.GetString()), plan.Warnings);
            Assert.Equal(esperado.GetProperty("semMudanca").GetBoolean(), plan.NoChange);
            Assert.Equal(esperado.GetProperty("recomendacoes").EnumerateArray().Select(x => x.GetString()), plan.Evaluation.Recommendations);
            if (esperado.GetProperty("ia").ValueKind == JsonValueKind.Null) Assert.Null(plan.Ai);
            else
            {
                Assert.Equal(esperado.GetProperty("ia").GetProperty("mistura").GetDouble(), plan.Ai!.Mix, 4);
                Assert.Equal(esperado.GetProperty("ia").GetProperty("corteHz").GetDouble(), plan.Ai.CutoffHz, 4);
            }
            foreach (var (chave, valor) in Numbers(esperado.GetProperty("parametros")))
                Assert.True(Math.Abs(plan.Parameters[chave] - valor) < 1e-4, $"{nome}: {chave} = {plan.Parameters[chave]}, esperado {valor}");
        }
    }

    [Fact]
    public void TratamentoDoPacoteBateComOJs()
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "musica-pacote-casos.json")));
        var entradas = document.RootElement.GetProperty("entrada").GetProperty("tratamentos").EnumerateArray().ToList();
        var esperados = document.RootElement.GetProperty("esperado").GetProperty("tratamentos").EnumerateArray().ToList();
        Assert.Equal(entradas.Count, esperados.Count);
        for (var i = 0; i < entradas.Count; i++)
        {
            var t = MusicStemTreatment.Read(System.Text.Json.Nodes.JsonNode.Parse(entradas[i].GetRawText()));
            var e = esperados[i];
            Assert.Equal(e.GetProperty("refinarAgudos").GetBoolean(), t.RefineHighs);
            Assert.Equal(e.GetProperty("reduzirRuido").GetBoolean(), t.ReduceNoise);
            Assert.Equal(e.GetProperty("restauracao").GetInt32(), t.Restoration);
            Assert.Equal(e.GetProperty("aceleracao").GetBoolean(), t.Acceleration);
        }
        // O pedido inteiro carrega o tratamento ida e volta.
        var pedido = MusicStemPackage.ReadRequest("""{"modelo":"roformer","tratamento":{"refinarAgudos":true,"restauracao":40}}""");
        Assert.True(pedido.Treatment.RefineHighs);
        Assert.Equal(40, MusicStemPackage.ReadRequest(MusicStemPackage.ToJson(pedido)).Treatment.Restoration);
    }

    [Fact]
    public async Task RestauraWavComZumbidoChiadoEEstalosPelaDll()
    {
        if (!File.Exists(Path.Combine(AppContext.BaseDirectory, "slt_native.dll"))) return;
        Assert.True(MusicNative.HasAudioRestoration && MusicNative.HasStemRefine);
        var pasta = Path.Combine(Path.GetTempPath(), $"slt-restaurador-{Guid.NewGuid():N}");
        Directory.CreateDirectory(pasta);
        try
        {
            const int taxa = 44100;
            var entrada = Path.Combine(pasta, "fita.wav");
            var random = new Random(3);
            using (var writer = new StreamingWavWriter(entrada, 2, taxa))
            {
                var n = taxa * 8;
                var l = new float[n];
                for (var i = 0; i < n; i++)
                {
                    var t = i / (double)taxa;
                    var musica = t is > 2 and < 6 ? 0.3 * Math.Sin(2 * Math.PI * 523.25 * t) : 0;
                    l[i] = (float)(musica + 0.03 * Math.Sin(2 * Math.PI * 60 * t) + 0.012 * (random.NextDouble() * 2 - 1));
                }
                for (var i = taxa; i < n; i += taxa / 2) l[i] += 0.5f;
                writer.Write([l, (float[])l.Clone()], 0, n);
            }
            if (ExecutableGuard.Locate("ffmpeg") is null) return;
            var tools = new MusicTools(() => "ffmpeg", new ProcessRunner(), pasta, pasta);
            var saida = Path.Combine(pasta, "fita-restaurado.wav");
            var opcoes = AudioRestoration.ReadOptions("""{"perfil":"archive","intensidade":60,"formato":"wav-32f"}""");
            var r = await tools.RestoreAudioAsync(entrada, saida, opcoes, null, CancellationToken.None);
            Assert.Equal(60, r.Diagnosis["zumbidoHz"]);
            Assert.Contains("zumbido", r.Plan.Steps.Select(s => s.Id));
            Assert.Contains("ruido", r.Plan.Steps.Select(s => s.Id));
            Assert.True(r.Stats!.Value.Clicks >= 10);
            using var antes = new MappedWavSource(entrada);
            using var depois = new MappedWavSource(saida);
            Assert.Equal(antes.Frames, depois.Frames);
            double Rms(MappedWavSource s, double de, double ate)
            {
                var n = (int)((ate - de) * taxa);
                var b = new float[n];
                s.Copy(0, (long)(de * taxa), n, b);
                return 20 * Math.Log10(Math.Sqrt(b.Sum(x => (double)x * x) / n));
            }
            Assert.True(Rms(depois, 6.5, 7.5) - Rms(antes, 6.5, 7.5) < -10, "ruído e zumbido caem mais de 10 dB");
            Assert.True(Math.Abs(Rms(depois, 3, 5) - Rms(antes, 3, 5)) < 1.5, "a música fica");
        }
        finally
        {
            try { Directory.Delete(pasta, true); } catch (IOException) { }
        }
    }
    [Fact]
    public void BlocosDoApolloCobremOAudio()
    {
        Assert.Equal([0L], MusicApollo.Starts(1000));
        Assert.Equal([0L, 110_250L, 220_500L], MusicApollo.Starts(441_000));
        var n = 3 * 220_500L + 1234;
        Assert.True(MusicApollo.Starts(n).Last() + 220_500 >= n);
    }

    private sealed class Imediato<T>(Action<T> acao) : IProgress<T>
    {
        public void Report(T value) => acao(value);
    }

    private static async Task Ffmpeg(params string[] argumentos)
    {
        var info = new System.Diagnostics.ProcessStartInfo(ExecutableGuard.Locate("ffmpeg")!) { UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in argumentos) info.ArgumentList.Add(a);
        using var processo = System.Diagnostics.Process.Start(info)!;
        await processo.WaitForExitAsync();
        Assert.Equal(0, processo.ExitCode);
    }

    /// <summary>O Apollo de verdade (ONNX do laboratório ou SLT_APOLLO_ONNX), num MP3 de 64 kbps: o plano chama a IA e a saída confere.</summary>
    [Fact]
    public async Task RestauraMp3ComOApollo()
    {
        if (!File.Exists(Path.Combine(AppContext.BaseDirectory, "slt_native.dll")) || ExecutableGuard.Locate("ffmpeg") is null) return;
        var modelo = Environment.GetEnvironmentVariable("SLT_APOLLO_ONNX");
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); modelo is null && d is not null; d = d.Parent)
        {
            var c = Path.Combine(d.FullName, "build", "nativo", "laboratorio-stems", "pesos", "apollo", MusicApollo.FileName);
            if (File.Exists(c)) modelo = c;
        }
        if (modelo is null) return;
        var pasta = Path.Combine(Path.GetTempPath(), $"slt-apollo-{Guid.NewGuid():N}");
        Directory.CreateDirectory(Path.Combine(pasta, "musica"));
        try
        {
            File.Copy(modelo, Path.Combine(pasta, "musica", MusicApollo.FileName));
            const int taxa = 44100;
            var limpo = Path.Combine(pasta, "limpo.wav");
            var random = new Random(5);
            var n = taxa * 7;
            var l = new float[n];
            var r = new float[n];
            for (var i = 0; i < n; i++)
            {
                var t = i / (double)taxa;
                var envelope = Math.Exp(-3 * (t % 0.5));
                var tom = 0.25 * Math.Sin(2 * Math.PI * 220 * t) + 0.1 * Math.Sin(2 * Math.PI * 659.25 * t);
                var chiado = 0.08 * envelope * (random.NextDouble() * 2 - 1);
                l[i] = (float)(tom * envelope + chiado);
                r[i] = (float)(tom * envelope * 0.9 - chiado);
            }
            using (var writer = new StreamingWavWriter(limpo, 2, taxa)) writer.Write([l, r], 0, n);
            var mp3 = Path.Combine(pasta, "comprimido.mp3");
            var tools = new MusicTools(() => "ffmpeg", new ProcessRunner(), pasta, pasta);
            Assert.True(tools.ApolloInstalled);
            await Ffmpeg("-v", "error", "-y", "-i", limpo, "-c:a", "libmp3lame", "-b:a", "64k", mp3);
            var saida = Path.Combine(pasta, "restaurado.wav");
            var opcoes = AudioRestoration.ReadOptions("""{"perfil":"lossy","intensidade":35,"formato":"wav-32f","ia":true}""");
            var etapas = new List<string>();
            var res = await tools.RestoreAudioAsync(mp3, saida, opcoes, new Imediato<(string Stage, double Fraction)>(p => { lock (etapas) if (!etapas.Contains(p.Stage)) etapas.Add(p.Stage); }), CancellationToken.None);
            Assert.NotNull(res.Plan.Ai);
            Assert.Equal("compressao", res.Plan.Steps[0].Id);
            Assert.Contains("Reparando a compressão (Apollo)", etapas);
            var decodificado = Path.Combine(pasta, "mp3.wav");
            await Ffmpeg("-v", "error", "-y", "-i", mp3, "-c:a", "pcm_f32le", decodificado);
            using var antes = new MappedWavSource(decodificado);
            using var depois = new MappedWavSource(saida);
            Assert.Equal(antes.Frames, depois.Frames);
            var a = new float[antes.Frames];
            var b = new float[depois.Frames];
            antes.Copy(0, 0, a.Length, a);
            depois.Copy(0, 0, b.Length, b);
            double ab = 0, aa = 0, bb = 0;
            for (var i = 0; i < a.Length; i++) { ab += a[i] * (double)b[i]; aa += a[i] * (double)a[i]; bb += b[i] * (double)b[i]; }
            Assert.True(ab / Math.Sqrt(aa * bb) > 0.9, "abaixo do corte, o original continua");
        }
        finally
        {
            try { Directory.Delete(pasta, true); } catch (IOException) { }
        }
    }
}
