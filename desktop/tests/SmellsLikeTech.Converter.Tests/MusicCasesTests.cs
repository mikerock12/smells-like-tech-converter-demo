using System.Text.Json;
using SmellsLikeTech.Converter.Core.Music;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// A criacao musical do Windows e do plugin tem de soar igual ao site e ao Android: cada caso
/// de packages/converter-core/musica-casos traz o esperado calculado pela referencia em JS
/// (node scripts/musica-casos.mjs), e aqui tudo e recalculado em C#.
/// </summary>
public sealed class MusicCasesTests
{
    private const double Tolerance = 2e-6;

    public static IEnumerable<object[]> Cases() =>
        Directory.EnumerateFiles(Path.Combine(AppContext.BaseDirectory, "musica-casos"), "*.json")
            .Order(StringComparer.Ordinal)
            .Select(path => new object[] { Path.GetFileName(path) });

    private static float[][] Generate(JsonElement g, int channels, long frames, int rate)
    {
        var result = new float[channels][];
        var kind = g.GetProperty("tipo").GetString();
        var amplitude = g.TryGetProperty("amplitude", out var a) ? a.GetDouble() : 0.5;
        for (var c = 0; c < channels; c++)
        {
            var x = new float[frames];
            if (kind == "seno")
            {
                var f = c == 1 && g.TryGetProperty("frequencia2", out var f2) ? f2.GetDouble() : g.GetProperty("frequencia").GetDouble();
                for (var i = 0; i < frames; i++) x[i] = (float)(amplitude * Math.Sin(2 * Math.PI * f * i / rate));
            }
            else if (kind == "ruido")
            {
                var seed = (g.TryGetProperty("semente", out var s) ? s.GetInt32() : 1) + c;
                var noise = new MusicMastering.Noise((uint)seed);
                for (var i = 0; i < frames; i++) x[i] = (float)(amplitude * (noise.Next() * 2 - 1));
            }
            else if (kind == "impulsos")
            {
                var period = g.GetProperty("periodo").GetInt32();
                for (var i = g.TryGetProperty("inicio", out var st) ? st.GetInt32() : 0; i < frames; i += period) x[i] = (float)amplitude;
            }

            result[c] = x;
        }

        return result;
    }

    private static uint Fnv(byte[] bytes)
    {
        var h = 0x811c9dc5u;
        foreach (var b in bytes)
        {
            h ^= b;
            h *= 0x01000193u;
        }

        return h;
    }

    private static void Near(JsonElement expected, double actual, string what)
    {
        if (expected.ValueKind == JsonValueKind.String)
        {
            Assert.True(expected.GetString() == "-inf" && double.IsNegativeInfinity(actual), $"{what}: esperado {expected}, veio {actual}");
            return;
        }

        var e = expected.GetDouble();
        Assert.True(Math.Abs(e - actual) <= Tolerance + 1e-7 * Math.Abs(e), $"{what}: esperado {e}, veio {actual}");
    }

    private static void Summary(JsonElement expected, float[] left, float[] right, string what)
    {
        double sum = 0, energy = 0, peak = 0;
        for (var i = 0; i < left.Length; i++)
        {
            sum += (double)left[i] + right[i];
            energy += (double)left[i] * left[i] + (double)right[i] * right[i];
            peak = Math.Max(peak, Math.Max(Math.Abs((double)left[i]), Math.Abs((double)right[i])));
        }

        Near(expected.GetProperty("soma"), sum, $"{what} soma");
        Near(expected.GetProperty("energia"), energy, $"{what} energia");
        Near(expected.GetProperty("pico"), peak, $"{what} pico");
        foreach (var sample in expected.GetProperty("amostras").EnumerateArray())
        {
            var i = sample[0].GetInt32();
            Near(sample[1], left[i], $"{what} L[{i}]");
            Near(sample[2], right[i], $"{what} R[{i}]");
        }
    }

    private static List<string> Strings(JsonElement e) => [.. e.EnumerateArray().Select(x => x.GetString()!)];

    [Theory]
    [MemberData(nameof(Cases))]
    public void SharedCaseMatchesReference(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "musica-casos", name)));
        var root = document.RootElement;
        var expected = root.GetProperty("esperado");
        if (root.TryGetProperty("projeto", out var projectJson)) CheckProject(root, MusicProject.Parse(projectJson.GetRawText()), expected, name);
        if (root.TryGetProperty("latencia", out var latency))
        {
            var frames = latency.GetProperty("quadros").GetInt32();
            var delay = latency.GetProperty("atraso").GetInt32();
            var pulses = latency.GetProperty("pulsos").EnumerateArray().Select(p => p.GetInt64()).ToList();
            var recorded = new float[frames];
            foreach (var p in pulses)
            for (var i = 0; i < 48 && p + delay + i < frames; i++)
                recorded[p + delay + i] = (float)(0.6 * Math.Exp(-i / 8.0) * (i % 2 == 1 ? -1 : 1));
            Assert.Equal(expected.GetProperty("latencia").GetInt64(), MusicRules.MeasureLatency(recorded, pulses, latency.GetProperty("taxa").GetInt32()));
        }

        if (root.TryGetProperty("arquivo", out var archive))
        {
            var results = expected.GetProperty("arquivo").EnumerateArray().ToList();
            var lists = archive.EnumerateArray().ToList();
            for (var i = 0; i < lists.Count; i++)
            {
                var entries = lists[i].EnumerateArray().Select(e => new MusicArchiveEntry(e.GetProperty("nome").GetString()!, e.GetProperty("tamanho").GetInt64(), e.GetProperty("comprimido").GetInt64())).ToList();
                Assert.Equal(results[i].ValueKind == JsonValueKind.Null ? null : results[i].GetString(), MusicArchive.CheckEntries(entries));
            }
        }

        if (root.TryGetProperty("separacao", out var separation)) CheckSeparation(separation, expected.GetProperty("separacao"));
        if (root.TryGetProperty("reducaoDeRuido", out var noise))
        {
            var length = noise.GetProperty("comprimento").GetInt32();
            var gen = noise.GetProperty("gerador");
            var original = Generate(gen, gen.TryGetProperty("canais", out var ch) ? ch.GetInt32() : 1, length, 48000);
            var output = new[] { new float[length], new float[length] };
            MusicSeparation.ReduceNoise(length, original.Length,
                (c, start, n, d) => Array.Copy(original[Math.Min(c, original.Length - 1)], start, d, 0, n),
                (c, start, samples, n) => Array.Copy(samples, 0, output[c], start, n));
            Summary(expected.GetProperty("reducaoDeRuido"), output[0], output[1], "ruido");
        }
    }

    private static void CheckProject(JsonElement root, MusicProject project, JsonElement expected, string name)
    {
        var problems = MusicRules.Problems(project);
        Assert.Equal(Strings(expected.GetProperty("problemas")), problems);
        if (problems.Count > 0) return;
        Assert.Equal(expected.GetProperty("duracao").GetInt64(), MusicRules.DurationInFrames(project));
        Assert.Equal(expected.GetProperty("cauda").GetInt64(), MusicMixer.TailInFrames(project));
        Assert.Equal(Strings(expected.GetProperty("audiveis")), MusicRules.AudibleTracks(project));
        var gains = expected.GetProperty("ganhos").EnumerateArray().ToList();
        for (var i = 0; i < project.Tracks.Count; i++)
        {
            var t = project.Tracks[i];
            var (l, r) = MusicRules.StereoGains(t.GainDb, t.Pan, t.LeftTrimDb, t.RightTrimDb);
            Near(gains[i][0], l, $"ganho L {t.Id}");
            Near(gains[i][1], r, $"ganho R {t.Id}");
        }

        if (root.TryGetProperty("posicoes", out var positions))
        {
            Assert.Equal(Strings(expected.GetProperty("posicoes")), positions.EnumerateArray().Select(p => MusicRules.Position(project, p.GetInt64()).Text).ToList());
        }

        if (root.TryGetProperty("metronomo", out var metronome))
        {
            var clicks = MusicRules.MetronomeClicks(project, metronome.GetProperty("inicio").GetInt64(), metronome.GetProperty("fim").GetInt64());
            var expectedClicks = expected.GetProperty("metronomo").EnumerateArray().Select(c => new MusicClick(c.GetProperty("quadro").GetInt64(), c.GetProperty("forte").GetBoolean())).ToList();
            Assert.Equal(expectedClicks, clicks);
        }

        if (root.TryGetProperty("gravacao", out var recording))
        {
            var plan = MusicRules.RecordingPlan(project, recording.GetProperty("cursor").GetInt64());
            var e = expected.GetProperty("gravacao");
            var ep = e.GetProperty("plano");
            Assert.Equal(ep.GetProperty("inicioDoTransporte").GetInt64(), plan.TransportStart);
            Assert.Equal(ep.GetProperty("inicioDaTomada").GetInt64(), plan.TakeStart);
            Assert.Equal(ep.GetProperty("fimDaTomada").ValueKind == JsonValueKind.Null ? null : ep.GetProperty("fimDaTomada").GetInt64(), plan.TakeEnd);
            Assert.Equal(ep.GetProperty("preRolagem").GetInt64(), plan.PreRoll);
            Assert.Equal(ep.GetProperty("cliquesDaContagem").EnumerateArray().Select(c => new MusicClick(c.GetProperty("quadro").GetInt64(), c.GetProperty("forte").GetBoolean())).ToList(), plan.CountInClicks);
            var clip = MusicRules.ClipFromTake(project, "tomada-1", "gravacao-1", recording.GetProperty("quadroAlinhado").GetInt64(), recording.GetProperty("quadrosCapturados").GetInt64(), plan)!;
            var ec = e.GetProperty("clipe");
            Assert.Equal(ec.GetProperty("inicio").GetInt64(), clip.Start);
            Assert.Equal(ec.GetProperty("inicioNaMidia").GetInt64(), clip.SourceStart);
            Assert.Equal(ec.GetProperty("duracao").GetInt64(), clip.Duration);
        }

        if (!root.TryGetProperty("renders", out var renders)) return;
        var sources = new Dictionary<string, IMusicSource>();
        var generators = root.GetProperty("midias");
        foreach (var media in project.Media)
        {
            if (generators.TryGetProperty(media.Id, out var g)) sources[media.Id] = new MemoryMusicSource(Generate(g, media.Channels, media.Frames, project.SampleRate));
        }

        var expectedRenders = expected.GetProperty("renders").EnumerateArray().ToList();
        var index = 0;
        foreach (var request in renders.EnumerateArray())
        {
            var frames = request.GetProperty("quadros").GetInt32();
            var only = request.TryGetProperty("somente", out var s) && s.ValueKind == JsonValueKind.String ? s.GetString() : null;
            var metro = request.TryGetProperty("metronomo", out var m) && m.GetBoolean();
            var (left, right) = MusicMixer.Render(project, sources, request.TryGetProperty("inicio", out var st) ? st.GetInt64() : 0, frames, only, metronome: metro);
            var er = expectedRenders[index];
            var what = $"{name} render {index}";
            Summary(er, left, right, what);
            if (er.TryGetProperty("medidas", out var measures))
            {
                var mm = MusicMastering.Measure(left, right, project.SampleRate);
                Near(measures.GetProperty("lufs"), mm.Lufs, $"{what} lufs");
                Near(measures.GetProperty("amostraDb"), mm.SamplePeakDb, $"{what} pico");
                Near(measures.GetProperty("realDb"), mm.TruePeakDb, $"{what} pico real");
            }

            if (er.TryGetProperty("master", out var master))
            {
                var r = MusicMastering.Master(left, right, project.SampleRate, request.GetProperty("master").GetString()!);
                Near(master.GetProperty("ganhoDb"), r.GainDb, $"{what} ganho do master");
                Near(master.GetProperty("lufs"), r.After.Lufs, $"{what} lufs do master");
                Near(master.GetProperty("realDb"), r.After.TruePeakDb, $"{what} pico do master");
                Summary(master, r.Left, r.Right, $"{what} master");
            }

            if (er.TryGetProperty("wav", out var wav))
            {
                var bits = request.GetProperty("wav").EnumerateArray().Select(b => b.GetInt32()).ToList();
                var hashes = wav.EnumerateArray().Select(h => h.GetUInt32()).ToList();
                for (var i = 0; i < bits.Count; i++) Assert.Equal(hashes[i], Fnv(MusicMastering.EncodeWav([left, right], project.SampleRate, bits[i])));
            }

            index++;
        }
    }

    private static float[] FakeEstimates(float[] input, int T, int bands, int instruments)
    {
        var output = new float[instruments * input.Length];
        for (var i = 0; i < instruments; i++)
        for (var c = 0; c < 2; c++)
        for (var f = 0; f < T; f++)
        for (var k = 0; k < bands; k++)
        {
            var j = (c * T + f) * bands + k;
            var weight = 1 + (i + 1) * ((k * 7 + f * 3 + c) % 11) / 10.0;
            output[i * input.Length + j] = (float)(input[j] * weight);
        }

        return output;
    }

    private static void CheckSeparation(JsonElement separation, JsonElement expected)
    {
        var length = separation.GetProperty("comprimento").GetInt32();
        var gen = separation.GetProperty("gerador");
        var original = Generate(gen, gen.TryGetProperty("canais", out var ch) ? ch.GetInt32() : 1, length, MusicSeparation.ModelRate);
        var mode = MusicSeparation.Mode(separation.GetProperty("modo").GetString()!)!;
        var outputs = mode.Stems.Select(_ => new[] { new float[length], new float[length] }).ToArray();
        MusicSeparation.SeparateAsync(length, original.Length,
            (c, start, n, d) => Array.Copy(original[Math.Min(c, original.Length - 1)], start, d, 0, n),
            (input, _) => Task.FromResult(FakeEstimates(input, MusicSeparation.FramesPerChunk, MusicSeparation.Bands, 4)),
            (stem, c, start, samples, n) => Array.Copy(samples, 0, outputs[stem][c], start, n),
            mode.Id, null, CancellationToken.None).GetAwaiter().GetResult();
        Assert.Equal(expected.GetProperty("trechos").GetInt32(), new MusicSpectralProcessor(length, 4096, 1024, 512, 1).Chunks);
        var stems = expected.GetProperty("faixas").EnumerateArray().ToList();
        for (var i = 0; i < stems.Count; i++)
        {
            Assert.Equal(stems[i].GetProperty("id").GetString(), mode.Stems[i].Id);
            Summary(stems[i], outputs[i][0], outputs[i][1], $"separação {mode.Stems[i].Id}");
        }
    }
}
