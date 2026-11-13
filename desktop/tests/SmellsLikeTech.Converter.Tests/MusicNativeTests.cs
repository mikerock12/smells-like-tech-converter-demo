using System.Text.Json;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Engine.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class MusicNativeTests
{
    private static bool Presente()
    {
        if (File.Exists(Path.Combine(AppContext.BaseDirectory, "slt_native.dll")))
            Assert.True(MusicNative.IsAvailable);
        return MusicNative.IsAvailable;
    }

    [Fact]
    public void StftComplexaReconstruiCanaisComGanhosIndependentes()
    {
        if (!Presente()) return;
        Assert.True(MusicNative.HasComplexStft);
        using var stft = new MusicNative.ComplexStft(32, 11, 151);
        float[] left = Enumerable.Range(0, stft.Samples).Select(i => (float)Math.Sin(i * 0.17)).ToArray();
        float[] right = Enumerable.Range(0, stft.Samples).Select(i => (float)Math.Cos(i * 0.27)).ToArray();
        var spectrum = new float[stft.SpectrumSize];
        var mask = new float[stft.SpectrumSize];
        var outputLeft = new float[stft.Samples];
        var outputRight = new float[stft.Samples];
        for (int i = 0; i < mask.Length; i += 4)
        {
            mask[i] = 0.5f;
            mask[i + 2] = -0.25f;
        }
        stft.Analyze(left, right, spectrum);
        stft.Synthesize(spectrum, mask, outputLeft, outputRight);
        for (int i = 0; i < stft.Samples; ++i)
        {
            Assert.InRange(Math.Abs(outputLeft[i] - left[i] * 0.5f), 0, 1e-6f);
            Assert.InRange(Math.Abs(outputRight[i] + right[i] * 0.25f), 0, 1e-6f);
        }
    }

    [Fact]
    public void RenderDoMixerIgualAoMotorGerenciado()
    {
        if (!Presente()) return;
        var arquivo = Path.Combine(AppContext.BaseDirectory, "musica-casos", "01-tres-faixas.json");
        using var caso = JsonDocument.Parse(File.ReadAllText(arquivo));
        var projeto = MusicProject.Parse(caso.RootElement.GetProperty("projeto").GetRawText());
        using var nativo = new MusicNative.Mixer(projeto.SampleRate);
        var gerenciado = new MusicMixer(projeto.SampleRate);
        var pcm = Enumerable.Range(0, projeto.SampleRate * 2).Select(i => (float)(0.15 * Math.Sin(i * 0.03))).ToArray();
        foreach (var midia in projeto.Tracks.SelectMany(f => f.Clips).Select(c => c.Media).Distinct())
        {
            nativo.SetSource(midia, pcm);
            gerenciado.SetSource(midia, new MemoryMusicSource([pcm]));
        }
        nativo.SetProject(projeto.ToJson());
        gerenciado.SetProject(projeto);

        var e = new float[512];
        var d = new float[512];
        var esperadoE = new float[512];
        var esperadoD = new float[512];
        nativo.Render(0, e, d);
        gerenciado.Process(0, esperadoE.Length, esperadoE, esperadoD);
        Assert.Contains(e, v => Math.Abs(v) > 0.001f);
        for (var i = 0; i < e.Length; i++)
        {
            Assert.InRange(Math.Abs(e[i] - esperadoE[i]), 0, 2e-6f);
            Assert.InRange(Math.Abs(d[i] - esperadoD[i]), 0, 2e-6f);
        }
    }

    [Fact]
    public void RenderNativoSelecionaFaixaEFonteTransformada()
    {
        if (!Presente()) return;
        var clipe = new MusicClip { Media = "m", Duration = 128, SourceStart = 8, Speed = 2 };
        var projeto = new MusicProject
        {
            Tracks = [new MusicTrack { Id = "f1", Clips = [clipe] }, new MusicTrack { Id = "f2", Clips = [clipe] }],
        };
        using var mixer = new MusicNative.Mixer(projeto.SampleRate);
        mixer.SetSource(clipe.Media, Enumerable.Repeat(0.75f, 256).ToArray());
        mixer.SetSource(MusicTransform.SourceKey(clipe), Enumerable.Repeat(0.25f, 256).ToArray());
        mixer.SetProject(projeto);
        var e = new float[128];
        var d = new float[128];
        mixer.Render(0, e, d, onlyTrack: "f1");
        Assert.All(e, valor => Assert.Equal(0.25f, valor, 6));
        Assert.Equal(e, d);
        Assert.All(mixer.Meters("master"), valor => Assert.Equal(0.25f, valor, 6));
        Assert.All(mixer.Meters("f2"), valor => Assert.Equal(0f, valor));
    }

    [Fact]
    public async Task SaidaWasapiDoAppEmSilencio()
    {
        if (Environment.GetEnvironmentVariable("SLT_MUSIC_TEST_WASAPI") != "1") return;
        Assert.True(MusicNative.IsAvailable);
        Assert.Equal("wasapi", Environment.GetEnvironmentVariable("SLT_MUSIC_OUTPUT"));
        using var motor = new MusicAudioEngine(48_000);
        motor.SetSource("m", new MemoryMusicSource([Enumerable.Repeat(0.25f, 48_000).ToArray()]));
        motor.SetProject(new MusicProject
        {
            Master = new MusicMaster { GainDb = -60 },
            Tracks = [new MusicTrack { Id = "f", Clips = [new MusicClip { Media = "m", Duration = 48_000 }] }],
        });
        motor.Start();
        Assert.True(motor.IsUsingNativeOutput);
        motor.Play(0, withMetronome: false);
        try
        {
            var limite = DateTime.UtcNow.AddSeconds(5);
            while (motor.Position < 2400 && DateTime.UtcNow < limite) await Task.Delay(20);
            Assert.True(motor.Position >= 2400);
            var medidores = motor.Meters;
            Assert.Equal(0.25f, medidores["f"][0], 6);
            Assert.All(medidores["master"], valor => Assert.Equal(0f, valor));
        }
        finally { motor.Stop(); }
    }

    [Fact]
    public async Task ExportacaoAplicaVst3DoSdk()
    {
        if (Environment.GetEnvironmentVariable("SLT_MUSIC_TEST_VST3") != "1") return;
        Assert.True(MusicNative.HasVst3);
        var plugin = Assert.Single(MusicNative.ScanPlugins(), item =>
            item.Path.Contains("adelay.vst3", StringComparison.OrdinalIgnoreCase));
        var pasta = Directory.CreateTempSubdirectory("slt-vst3-").FullName;
        try
        {
            var arquivo = Path.Combine(pasta, "entrada.wav");
            var pcm = new float[48_000];
            pcm[0] = 0.25f;
            using (var wav = File.Create(arquivo)) MusicMastering.WriteWav(wav, [pcm, pcm], 48_000, 32);
            var projeto = new MusicProject
            {
                Title = "ComVST3",
                Media = [new MusicMedia { Id = "m", Frames = pcm.Length }],
                Tracks = [new MusicTrack { Id = "f", Clips = [new MusicClip { Media = "m", Duration = pcm.Length }] }],
            };
            var efeito = new MusicEffect { Id = "fx", Kind = "vst3", Enabled = true, Extra = new()
            {
                ["caminho"] = JsonSerializer.SerializeToElement(plugin.Path),
                ["classe"] = JsonSerializer.SerializeToElement(plugin.ClassId),
                ["parametros"] = JsonSerializer.SerializeToElement(new[] { new { id = 100, valor = 0 } }),
            } };
            projeto.Tracks[0].Effects.Add(efeito);
            var ferramentas = new MusicTools(() => throw new InvalidOperationException(), new ProcessRunner(), pasta, pasta);
            var pedido = new MusicExportRequest("wav-32f", "sem-alvo", false, false, false);
            var comVst3 = Assert.Single(await ferramentas.ExportAsync(projeto, _ => arquivo, pedido, pasta, null, default));
            using var fonte = new MappedWavSource(comVst3.Path);
            var saida = new float[pcm.Length];
            fonte.Copy(0, 0, saida.Length, saida);
            Assert.Equal(0f, saida[0], 6);
            Assert.Equal(0.25f, saida[1], 6);
        }
        finally { Directory.Delete(pasta, recursive: true); }
    }

    [Fact]
    public async Task RoFormerExperimentalProduzSeisWavsERecompoeEntrada()
    {
        if (Environment.GetEnvironmentVariable("SLT_MUSIC_TEST_ROFORMER") != "1") return;
        Assert.True(MusicTools.HasExperimentalRoFormer);
        var ffmpeg = Assert.IsType<string>(Environment.GetEnvironmentVariable("SLT_MUSIC_TEST_FFMPEG"));
        var pasta = Directory.CreateTempSubdirectory("slt-roformer-").FullName;
        try
        {
            const int taxa = 44100;
            var arquivo = Path.Combine(pasta, "entrada.wav");
            var esquerda = Enumerable.Range(0, taxa).Select(i => (float)(0.1 * Math.Sin(i * 0.061))).ToArray();
            var direita = Enumerable.Range(0, taxa).Select(i => (float)(0.1 * Math.Cos(i * 0.037))).ToArray();
            using (var wav = File.Create(arquivo)) MusicMastering.WriteWav(wav, [esquerda, direita], taxa, 32);
            var tools = new MusicTools(() => ffmpeg, new ProcessRunner(), pasta, pasta);
            var saidas = await tools.SeparateRoFormerAsync(arquivo, pasta, taxa, null, default);
            Assert.Equal(new[] { "vozes", "bateria", "baixo", "guitarra", "piano", "outros" }, saidas.Select(s => s.Stem.Id));
            var somas = new[] { new float[taxa], new float[taxa] };
            var amostras = new float[taxa];
            foreach (var (_, path) in saidas)
            {
                using var fonte = new MappedWavSource(path);
                Assert.Equal(taxa, fonte.Frames);
                for (int canal = 0; canal < 2; ++canal)
                {
                    fonte.Copy(canal, 0, taxa, amostras);
                    for (int i = 0; i < taxa; ++i) somas[canal][i] += amostras[i];
                }
            }
            for (int i = 0; i < taxa; ++i)
            {
                Assert.InRange(Math.Abs(somas[0][i] - esquerda[i]), 0, 1e-6f);
                Assert.InRange(Math.Abs(somas[1][i] - direita[i]), 0, 1e-6f);
            }
        }
        finally { Directory.Delete(pasta, recursive: true); }
    }

    [Fact]
    public void RestauradorEHandlesTemCicloDeVidaRepetivel()
    {
        if (!Presente()) return;
        var amostras = new[] { 0.1f, -0.25f, 0.2f, 0.4f };
        for (var i = 0; i < 64; i++)
        {
            using var mixer = new MusicNative.Mixer(48_000);
            mixer.SetProject("""{"taxa":48000,"faixas":[]}""");
            mixer.Play(12);
            Assert.Equal(12, mixer.Position);
            mixer.Stop();
            using var restorer = new MusicNative.Restorer(48_000, 0, 0);
            var copia = (float[])amostras.Clone();
            restorer.Process(copia);
            Assert.Equal(amostras, copia);
            Assert.Equal(1f, restorer.Gain);
        }
    }
}
