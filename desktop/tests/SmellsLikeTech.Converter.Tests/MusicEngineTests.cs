using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.Music;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;
using Xunit.Abstractions;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// A criacao musical do Windows de ponta a ponta: FFmpeg real na importacao e exportacao, o
/// modelo real de separacao (quando SLT_MODELO_SEPARACAO aponta para o .onnx) e o WASAPI de
/// verdade, gravando o som do computador (loopback) enquanto o projeto toca.
/// </summary>
public sealed class MusicEngineTests(ITestOutputHelper output) : IDisposable
{
    private readonly string folder = Directory.CreateTempSubdirectory("musica-teste").FullName;

    public void Dispose()
    {
        try { Directory.Delete(folder, true); } catch (IOException) { }
    }

    private MusicTools Tools() => new(() => "ffmpeg", new ProcessRunner(), Path.Combine(folder, "temp"), Path.Combine(folder, "modelos"));

    private static float[] Sine(double frequency, int frames, int rate, double amplitude = 0.4)
    {
        var x = new float[frames];
        for (var i = 0; i < frames; i++) x[i] = (float)(amplitude * Math.Sin(2 * Math.PI * frequency * i / rate));
        return x;
    }

    private static bool HasFfmpeg()
    {
        try { return ExecutableGuard.Locate("ffmpeg") is not null; }
        catch (Exception) { return false; }
    }

    [Fact]
    public async Task RejeitaCacheComTamanhoCorretoEShaInvalido()
    {
        var tools = Tools();
        Directory.CreateDirectory(Path.GetDirectoryName(tools.ModelPath)!);
        using (var model = File.Create(tools.ModelPath)) model.SetLength(MusicSeparation.ModelBytes);
        var falha = await Assert.ThrowsAsync<SmellsLikeTech.Converter.Core.ConversionException>(() =>
            tools.SeparateAsync("entrada.wav", folder, "2", 44_100, null, default));
        Assert.Equal("model_mismatch", falha.Code);
    }

    [Fact]
    public async Task ImportsAnyRateAndExportsEveryFormat()
    {
        if (!HasFfmpeg()) { output.WriteLine("FFmpeg ausente: teste não executado."); return; }
        var source = Path.Combine(folder, "violao-44k.wav");
        await File.WriteAllBytesAsync(source, MusicMastering.EncodeWav([Sine(220, 44100 * 3, 44100)], 44100, 16));
        var library = new MusicLibrary(folder);
        var project = MusicRules.FromTemplate("violao-e-voz", "teste-exportacao", "Teste de exportação");
        var tools = Tools();
        var (frames, channels) = await tools.ImportAsync(source, library.MediaPath(project.Id, "midia-1"), 48000, CancellationToken.None);
        Assert.InRange(frames, 143_900, 144_100);
        Assert.Equal(1, channels);
        project.Media.Add(new MusicMedia { Id = "midia-1", Name = "violao.wav", SampleRate = 48000, Channels = 1, Frames = frames });
        project.Tracks[0].Clips.Add(new MusicClip { Id = "c1", Media = "midia-1", Duration = frames });
        project.Tracks[1].Clips.Add(new MusicClip { Id = "c2", Media = "midia-1", Start = 24000, Duration = frames - 24000, GainDb = -6 });
        Assert.Empty(MusicRules.Problems(project));
        library.Save(project);
        Assert.Equal(project.ToJson(), library.Load(project.Id).ToJson());
        foreach (var format in MusicMastering.Formats)
        {
            var files = await tools.ExportAsync(project, m => library.MediaPath(project.Id, m.Id), new MusicExportRequest(format.Id, "streaming", false, false, true), Path.Combine(folder, "saida"), null, CancellationToken.None);
            var file = Assert.Single(files);
            Assert.True(new FileInfo(file.Path).Length > 10_000, $"{format.Id}: {new FileInfo(file.Path).Length} bytes");
            Assert.InRange(file.Measures!.Value.Lufs, -14.6, -13.4);
            Assert.True(file.Measures.Value.TruePeakDb <= -0.9, $"pico real {file.Measures.Value.TruePeakDb}");
            output.WriteLine($"{format.Id}: {new FileInfo(file.Path).Length} bytes, {file.Measures.Value.Lufs:0.00} LUFS, {file.Measures.Value.TruePeakDb:0.00} dBTP");
        }

        var stems = await tools.ExportAsync(project, m => library.MediaPath(project.Id, m.Id), new MusicExportRequest("wav-24", "sem-alvo", false, true, false), Path.Combine(folder, "faixas"), null, CancellationToken.None);
        Assert.Equal(2, stems.Count);
        var archive = Path.Combine(folder, "teste.sltmusic");
        MusicArchive.Save(project, m => library.MediaPath(project.Id, m.Id), archive);
        var reopened = MusicArchive.Open(archive, Path.Combine(folder, "reaberto"));
        Assert.Equal(project.ToJson(), reopened.ToJson());
    }

    [Fact]
    public async Task SeparatesWithTheRealModelAndStemsAddUpToTheMix()
    {
        var model = Environment.GetEnvironmentVariable("SLT_MODELO_SEPARACAO");
        if (model is null || !File.Exists(model) || !HasFfmpeg()) { output.WriteLine("Modelo ou FFmpeg ausente: teste não executado."); return; }
        var tools = Tools();
        Directory.CreateDirectory(Path.GetDirectoryName(tools.ModelPath)!);
        File.Copy(model, tools.ModelPath, true);
        Assert.True(tools.ModelReady);
        const int rate = 48000;
        var n = rate * 8;
        var bass = Sine(55, n, rate, 0.5);
        var lead = Sine(440, n, rate, 0.2);
        var mix = new float[n];
        for (var i = 0; i < n; i++) mix[i] = bass[i] + lead[i] * (float)(0.5 + 0.5 * Math.Sin(2 * Math.PI * 5 * i / rate));
        var input = Path.Combine(folder, "mistura.wav");
        await File.WriteAllBytesAsync(input, MusicMastering.EncodeWav([mix, mix], rate, 32));
        var started = DateTime.UtcNow;
        var stems = await tools.SeparateAsync(input, Path.Combine(folder, "faixas"), "4", rate, null, CancellationToken.None);
        output.WriteLine($"Separação de 8 s em {(DateTime.UtcNow - started).TotalSeconds:0.0} s");
        Assert.Equal(["vozes", "bateria", "baixo", "outros"], stems.Select(s => s.Stem.Id));
        var sum = new double[n];
        var energies = new Dictionary<string, double>();
        foreach (var (stem, path) in stems)
        {
            using var wav = new MappedWavSource(path);
            Assert.InRange(wav.Frames, n - 10, n + 10);
            var data = new float[n];
            wav.Copy(0, 0, n, data);
            double e = 0;
            for (var i = 0; i < n; i++) { sum[i] += data[i]; e += data[i] * (double)data[i]; }
            energies[stem.Id] = e;
        }

        double error = 0, reference = 0;
        for (var i = rate; i < n - rate; i++) { error += Math.Pow(sum[i] - mix[i], 2); reference += mix[i] * (double)mix[i]; }
        var snr = 10 * Math.Log10(reference / error);
        output.WriteLine($"Soma das faixas contra a mistura: {snr:0.0} dB; energias {string.Join(", ", energies.Select(e => $"{e.Key} {e.Value:0}"))}");
        Assert.True(snr > 25, $"as faixas não somam a mistura ({snr:0.0} dB)");
        Assert.True(energies["baixo"] > energies["bateria"], "o grave de 55 Hz devia ir para o baixo");
    }

    [Fact]
    public async Task ExportsTempoAndPitchInTheRealFile()
    {
        const int rate = 48000;
        var library = new MusicLibrary(folder);
        Directory.CreateDirectory(Path.GetDirectoryName(library.MediaPath("tempo-tom", "seno"))!);
        await File.WriteAllBytesAsync(library.MediaPath("tempo-tom", "seno"), MusicMastering.EncodeWav([Sine(440, rate * 2, rate)], rate, 32));
        var project = MusicRules.NewProject("tempo-tom", "Tempo e tom");
        project.Media.Add(new MusicMedia { Id = "seno", SampleRate = rate, Channels = 1, Frames = rate * 2 });
        project.Tracks.Add(new MusicTrack { Id = "f", Name = "Seno", Clips = [new MusicClip { Id = "c", Media = "seno", Duration = rate, Speed = 2, Semitones = 12, PreservePitch = true }] });
        Assert.Empty(MusicRules.Problems(project));
        var files = await Tools().ExportAsync(project, m => library.MediaPath(project.Id, m.Id), new MusicExportRequest("wav-32f", "sem-alvo", false, false, false), Path.Combine(folder, "tempo-saida"), null, CancellationToken.None);
        using var result = new MappedWavSource(Assert.Single(files).Path);
        Assert.Equal(rate, result.Frames);
        var data = new float[rate];
        result.Copy(0, 0, rate, data);
        Assert.Contains(data, v => Math.Abs(v) > 0.05);
    }

    [Fact]
    public async Task AppliesTempoAndPitchToTheMasterMixOnly()
    {
        const int rate = 48000;
        var project = MusicRules.NewProject("tempo-master", "Tempo na master");
        var path = Path.Combine(folder, "seno.wav");
        await File.WriteAllBytesAsync(path, MusicMastering.EncodeWav([Sine(440, rate * 2, rate)], rate, 32));
        project.Media.Add(new MusicMedia { Id = "seno", SampleRate = rate, Channels = 1, Frames = rate * 2 });
        project.Tracks.Add(new MusicTrack { Id = "f", Name = "Seno", Clips = [new MusicClip { Id = "c", Media = "seno", Duration = rate * 2 }] });
        project.Master.Speed = 2;
        project.Master.Semitones = 12;
        project.Master.PreservePitch = true;
        var tools = Tools();
        var request = new MusicExportRequest("wav-32f", "sem-alvo", false, false, false);
        var mix = await tools.ExportAsync(project, _ => path, request, Path.Combine(folder, "master"), null, CancellationToken.None);
        using var result = new MappedWavSource(Assert.Single(mix).Path);
        Assert.Equal(rate, result.Frames);
        var data = new float[rate / 2];
        result.Copy(0, rate / 4, data.Length, data);
        var crossings = data.Zip(data.Skip(1)).Count(pair => pair.First <= 0 && pair.Second > 0);
        Assert.InRange(crossings, 425, 455);

        var stems = await tools.ExportAsync(project, _ => path, request with { EachTrack = true }, Path.Combine(folder, "faixas"), null, CancellationToken.None);
        using var stem = new MappedWavSource(Assert.Single(stems).Path);
        Assert.Equal(rate * 2, stem.Frames);
    }

    [Fact]
    public async Task RecordsWhilePlayingWithAStableOffsetOnRealWasapi()
    {
        if (Environment.GetEnvironmentVariable("SLT_TESTE_LOOPBACK_WASAPI") != "1") return;
        MusicAudioEngine engine;
        try
        {
            if (MusicAudioEngine.Outputs().Count == 0) { output.WriteLine("Sem saída de áudio ativa: teste não executado."); return; }
            engine = new MusicAudioEngine();
            engine.Start();
            engine.OpenLoopbackInput();
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException or ArgumentException)
        {
            output.WriteLine($"WASAPI indisponível ({e.Message}): teste não executado.");
            return;
        }

        using (engine)
        {
            const int rate = 48000;
            var project = MusicRules.NewProject("teste-loopback", "Loopback");
            project.CountIn = 0;
            project.Metronome.Enabled = false;
            var clicks = new float[rate * 4];
            var pulses = new List<long>();
            for (var k = 0; k < 7; k++)
            {
                var p = 6000 + k * (rate / 2);
                pulses.Add(p);
                for (var i = 0; i < 64; i++) clicks[p + i] = (float)(0.8 * Math.Exp(-i / 10.0) * (i % 2 == 0 ? 1 : -1));
            }

            project.Media.Add(new MusicMedia { Id = "cliques", SampleRate = rate, Channels = 1, Frames = clicks.Length });
            var track = MusicRules.NewTrack("faixa-cliques", "Cliques");
            track.Clips.Add(new MusicClip { Id = "c1", Media = "cliques", Duration = clicks.Length });
            project.Tracks.Add(track);
            engine.SetSource("cliques", new MemoryMusicSource([clicks]));
            engine.SetProject(project);
            var offsets = new List<long>();
            for (var take = 0; take < 3; take++)
            {
                var parts = new List<float[]>();
                var plan = MusicRules.RecordingPlan(project, 0);
                var task = engine.RecordAsync(plan, 1, false, parts.Add);
                await Task.Delay(3800);
                engine.Stop();
                var result = await task;
                var recorded = parts.SelectMany(p => p).ToArray();
                var offset = MusicRules.MeasureLatency(recorded, [.. pulses.Select(p => p - result.AlignedFrame)], rate);
                output.WriteLine($"Tomada {take + 1}: {recorded.Length} quadros, deslocamento medido {offset} quadros ({(offset ?? 0) * 1000.0 / rate:0.0} ms), saída estimada {engine.OutputLatencyFrames * 1000.0 / rate:0.0} ms");
                Assert.NotNull(offset);
                offsets.Add(offset!.Value);
            }

            // A tomada sempre cai no mesmo lugar: sem deriva entre as gravações (±5 ms).
            Assert.True(offsets.Max() - offsets.Min() <= rate * 0.005, $"deslocamentos variaram: {string.Join(", ", offsets)}");
            Assert.InRange(offsets.Average(), -rate * 0.05, rate * 0.3);
        }
    }

    /// <summary>
    /// Teste B: gravação longa ouvindo o projeto, sem buracos nem quadros perdidos. Por padrão
    /// 30 s; SLT_GRAVACAO_LONGA_MIN define minutos (a entrega roda 10).
    /// </summary>
    [Fact]
    public async Task LongRecordingKeepsEveryFrame()
    {
        if (Environment.GetEnvironmentVariable("SLT_TESTE_LOOPBACK_WASAPI") != "1") return;
        var minutes = double.TryParse(Environment.GetEnvironmentVariable("SLT_GRAVACAO_LONGA_MIN"), System.Globalization.CultureInfo.InvariantCulture, out var m) ? m : 0.5;
        MusicAudioEngine engine;
        try
        {
            if (MusicAudioEngine.Outputs().Count == 0) { output.WriteLine("Sem saída de áudio ativa: teste não executado."); return; }
            engine = new MusicAudioEngine();
            engine.Start();
            engine.OpenLoopbackInput();
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException or ArgumentException)
        {
            output.WriteLine($"WASAPI indisponível ({e.Message}): teste não executado.");
            return;
        }

        using (engine)
        {
            const int rate = 48000;
            var seconds = (int)(minutes * 60);
            var tone = Sine(1000, rate * (seconds + 5), rate, 0.5);
            var project = MusicRules.NewProject("teste-longo", "Longo");
            project.CountIn = 0;
            project.Metronome.Enabled = false;
            project.Media.Add(new MusicMedia { Id = "tom", SampleRate = rate, Channels = 1, Frames = tone.Length });
            var track = MusicRules.NewTrack("faixa-tom", "Tom");
            track.Clips.Add(new MusicClip { Id = "c1", Media = "tom", Duration = tone.Length });
            project.Tracks.Add(track);
            engine.SetSource("tom", new MemoryMusicSource([tone]));
            engine.SetProject(project);
            long frames = 0;
            double maxStep = 0;
            float previous = 0;
            var skipped = 0;
            var task = engine.RecordAsync(MusicRules.RecordingPlan(project, 0), 1, false, part =>
            {
                foreach (var v in part)
                {
                    // O primeiro meio segundo tem a subida do tom; depois disso o passo é sempre pequeno.
                    if (frames > rate / 2) maxStep = Math.Max(maxStep, Math.Abs(v - previous));
                    previous = v;
                    frames++;
                }
            });
            var started = DateTime.UtcNow;
            await Task.Delay(TimeSpan.FromSeconds(seconds));
            engine.Stop();
            var take = await task;
            var elapsed = (DateTime.UtcNow - started).TotalSeconds;
            output.WriteLine($"{seconds} s pedidos, {elapsed:0.0} s de relógio, {take.Frames} quadros gravados ({take.Frames / (double)rate:0.000} s), maior salto entre amostras {maxStep:0.0000}, pulos {skipped}");
            Assert.InRange(take.Frames / (double)rate, elapsed - 0.25, elapsed + 0.25);
            // Seno de 1 kHz a 0,5: o passo máximo é 0,065; um buraco (zeros ou repetição) passa de 0,2.
            Assert.True(maxStep < 0.12, $"descontinuidade na gravação: {maxStep}");
        }
    }

    [Fact]
    public void ThePluginUnderstandsTheSeparationRequest()
    {
        var options = JobOptionsJson.Deserialize("""{"operation":"music.separate","Mode":"2","SampleRate":44100}""");
        var separate = Assert.IsType<MusicSeparateOptions>(options);
        separate.Validate();
        Assert.Equal("Separar em 2 faixas", separate.Summary());
        Assert.Throws<Core.ConversionException>(() => new MusicSeparateOptions { Mode = "6" }.Validate());
    }
}
