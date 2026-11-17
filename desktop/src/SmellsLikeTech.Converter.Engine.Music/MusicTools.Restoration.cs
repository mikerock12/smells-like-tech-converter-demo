using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>
/// Restaurador de áudio e os tratamentos novos da separação, sobre WAV float32 em fluxo (a
/// música não precisa caber na RAM): refino dos agudos (Wiener do núcleo), recuperação da
/// bateria do resíduo (segunda separação) e restauração do arquivo inteiro (diagnóstico,
/// plano e reparo do núcleo). Mesmas regras do site e do Android.
/// </summary>
public sealed partial class MusicTools
{
    public sealed record RestorationResult(string Path, AudioRestoration.Plan Plan, IReadOnlyDictionary<string, double> Diagnosis,
        (double Clicks, double Clips, double DenoiseDb, double LimiterDb)? Stats);

    private const int Block = 1 << 15;

    /// <summary>Taxa de um WAV (cabeçalho fmt).</summary>
    internal static int RateOf(string wav)
    {
        using var stream = File.OpenRead(wav);
        var header = new byte[Math.Min(4096, stream.Length)];
        stream.ReadExactly(header);
        for (var p = 12; p + 8 <= header.Length;)
        {
            var id = System.Text.Encoding.ASCII.GetString(header, p, 4);
            var size = (int)BitConverter.ToUInt32(header, p + 4);
            if (id == "fmt ") return (int)BitConverter.ToUInt32(header, p + 12);
            p += 8 + size + (size & 1);
        }
        throw new ConversionException("invalid_wav", "WAV sem cabeçalho de formato.");
    }

    /// <summary>Refina os agudos de faixas separadas (WAV 44,1 kHz estéreo, mesma duração), trocando os arquivos.</summary>
    internal static void RefineHighs(IReadOnlyList<string> stems, int rate, CancellationToken token)
    {
        if (!MusicNative.HasStemRefine || stems.Count < 2) return;
        var sources = stems.Select(s => new MappedWavSource(s)).ToList();
        var temps = stems.Select(s => s + ".refinando").ToList();
        try
        {
            var frames = sources.Min(s => s.Frames);
            using var refiner = new MusicNative.StemRefiner((uint)rate, stems.Count, 8000, 0.8f);
            var latency = refiner.Latency;
            var writers = temps.Select(t => new StreamingWavWriter(t, 2, rate)).ToList();
            try
            {
                var planar = new float[Block * 2 * stems.Count];
                var channel = new float[Block];
                var left = new float[Block];
                var right = new float[Block];
                var total = frames + latency;
                for (long start = 0; start < total; start += Block)
                {
                    token.ThrowIfCancellationRequested();
                    var n = (int)Math.Min(Block, total - start);
                    for (var s = 0; s < stems.Count; s++)
                        for (var c = 0; c < 2; c++)
                        {
                            if (start < frames) sources[s].Copy(c, start, n, channel);
                            else Array.Clear(channel, 0, n);
                            if (start + n > frames) Array.Clear(channel, (int)Math.Max(0, frames - start), n - (int)Math.Max(0, frames - start));
                            Array.Copy(channel, 0, planar, (s * 2 + c) * n, n);
                        }
                    refiner.Process(planar, n);
                    // A saída vem atrasada: o começo (latência) é descartado e o fim completa a duração.
                    var from = (int)Math.Max(0, latency - start);
                    var count = (int)Math.Min(n - from, frames - (start + from - latency));
                    if (count <= 0) continue;
                    for (var s = 0; s < stems.Count; s++)
                    {
                        Array.Copy(planar, (s * 2) * n + from, left, 0, count);
                        Array.Copy(planar, (s * 2 + 1) * n + from, right, 0, count);
                        writers[s].Write([left, right], 0, count);
                    }
                }
            }
            finally
            {
                foreach (var w in writers) w.Dispose();
            }
        }
        finally
        {
            foreach (var s in sources) s.Dispose();
        }
        for (var i = 0; i < stems.Count; i++) File.Move(temps[i], stems[i], overwrite: true);
    }

    /// <summary>Soma a bateria recuperada à faixa da bateria e tira do resíduo (WAV 44,1 kHz).</summary>
    internal static void MergeRecoveredDrums(string drums, string residual, string recovered, CancellationToken token)
    {
        string newDrums = drums + ".nova", newResidual = residual + ".novo";
        using (var d = new MappedWavSource(drums))
        using (var r = new MappedWavSource(residual))
        using (var x = new MappedWavSource(recovered))
        using (var wd = new StreamingWavWriter(newDrums, 2, MusicSeparation.ModelRate))
        using (var wr = new StreamingWavWriter(newResidual, 2, MusicSeparation.ModelRate))
        {
            var dl = new float[Block]; var dr = new float[Block]; var rl = new float[Block]; var rr = new float[Block]; var xl = new float[Block]; var xr = new float[Block];
            var frames = Math.Min(d.Frames, r.Frames);
            for (long i = 0; i < frames; i += Block)
            {
                token.ThrowIfCancellationRequested();
                var n = (int)Math.Min(Block, frames - i);
                d.Copy(0, i, n, dl); d.Copy(1, i, n, dr); r.Copy(0, i, n, rl); r.Copy(1, i, n, rr); x.Copy(0, i, n, xl); x.Copy(1, i, n, xr);
                for (var k = 0; k < n; k++) { dl[k] += xl[k]; dr[k] += xr[k]; rl[k] -= xl[k]; rr[k] -= xr[k]; }
                wd.Write([dl, dr], 0, n);
                wr.Write([rl, rr], 0, n);
            }
        }
        File.Move(newDrums, drums, overwrite: true);
        File.Move(newResidual, residual, overwrite: true);
    }

    /// <summary>
    /// Restaura um arquivo de áudio (qualquer formato que o FFmpeg leia) e grava no formato
    /// pedido. `levelMatch`: iguala o RMS ao do original (A/B do estúdio). O original não muda.
    /// </summary>
    public async Task<RestorationResult> RestoreAudioAsync(string input, string output, AudioRestoration.Options options,
        IProgress<(string Stage, double Fraction)>? progress, CancellationToken token, bool levelMatch = false, int? rate = null,
        HttpClient? http = null)
    {
        if (!MusicNative.HasAudioRestoration) throw new ConversionException("operation_not_available", "O restaurador precisa do núcleo nativo (slt_native.dll) 1.2 ou mais novo.");
        var work = Path.Combine(tempRoot, $"restauracao-{Guid.NewGuid():N}");
        Directory.CreateDirectory(work);
        try
        {
            progress?.Report(("Lendo o áudio", 0));
            var decoded = Path.Combine(work, "entrada.wav");
            string[] taxa = rate is { } r ? ["-ar", r.ToString(System.Globalization.CultureInfo.InvariantCulture)] : [];
            await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", input, "-vn", "-af", "aformat=channel_layouts=mono|stereo", .. taxa, "-c:a", "pcm_f32le", decoded], token);
            var sampleRate = RateOf(decoded);
            var restored = Path.Combine(work, "restaurado.wav");
            var analysis = await Task.Run(() => Analyze(decoded, sampleRate, options, progress, token), token);
            var plan = analysis.Plan;
            var repairInput = decoded;
            var from = 0.25;
            if (plan.Ai is { } ai && !plan.NoChange)
            {
                // Reparo de compressão por IA (Apollo) antes dos filtros, a 44,1 kHz estéreo; depois volta à taxa e aos canais do arquivo.
                if (!await VerifyAsync(ApolloPath, MusicApollo.Bytes, MusicApollo.Sha256, token))
                {
                    if (http is null) throw new ConversionException("model_missing", "O modelo Apollo não está baixado. Baixe-o nas opções do restaurador.");
                    await EnsureApolloAsync(http, new DirectProgress<double>(f => progress?.Report(("Baixando o modelo Apollo (só na primeira vez)", f))), token);
                }
                var at44 = Path.Combine(work, "apollo-entrada.wav");
                await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", decoded, "-ac", "2", "-ar", "44100", "-c:a", "pcm_f32le", at44], token);
                var apollo = Path.Combine(work, "apollo.wav");
                await Task.Run(() => MusicApollo.Restore(ApolloPath, at44, apollo, ai.CutoffHz, ai.Mix,
                    new DirectProgress<double>(f => progress?.Report(("Reparando a compressão (Apollo)", 0.25 + 0.6 * f))), token), token);
                repairInput = Path.Combine(work, "apollo-de-volta.wav");
                await FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", apollo, "-ac", analysis.Channels.ToString(System.Globalization.CultureInfo.InvariantCulture),
                    "-ar", sampleRate.ToString(System.Globalization.CultureInfo.InvariantCulture), "-c:a", "pcm_f32le", repairInput], token);
                FitLength(repairInput, analysis.Frames, analysis.Channels, sampleRate, token);
                from = 0.85;
            }
            var (diagnosis, stats) = await Task.Run(() => Repair(repairInput, restored, sampleRate, analysis, levelMatch, progress, from, token), token);
            progress?.Report(("Gravando o arquivo", 0.97));
            await EncodeAsync(restored, output, options.Format, token);
            return new RestorationResult(output, plan, diagnosis, stats);
        }
        finally
        {
            try { Directory.Delete(work, true); } catch (IOException) { }
        }
    }

    private sealed record Analysis(AudioRestoration.Plan Plan, IReadOnlyDictionary<string, double> Diagnosis, float[] Profile,
        double InputEnergy, double InputPeak, long Frames, int Channels);

    /// <summary>Diagnóstico e plano (e o que a conferência do fim precisa do original).</summary>
    private static Analysis Analyze(string input, int rate, AudioRestoration.Options options, IProgress<(string Stage, double Fraction)>? progress, CancellationToken token)
    {
        using var source = new MappedWavSource(input);
        if (source.Frames <= 0) throw new ConversionException("invalid_wav", "O arquivo não tem áudio.");
        var stereo = source.Channels > 1;
        var left = new float[Block];
        var right = new float[Block];
        IReadOnlyDictionary<string, double> diagnosis;
        float[] profile;
        double inputEnergy = 0, inputPeak = 0;
        using (var analyzer = new MusicNative.AudioAnalyzer((uint)rate))
        {
            for (long i = 0; i < source.Frames; i += Block)
            {
                token.ThrowIfCancellationRequested();
                var n = (int)Math.Min(Block, source.Frames - i);
                source.Copy(0, i, n, left);
                source.Copy(1, i, n, right);
                for (var k = 0; k < n; k++) { inputEnergy += left[k] * left[k] + right[k] * right[k]; inputPeak = Math.Max(inputPeak, Math.Max(Math.Abs(left[k]), Math.Abs(right[k]))); }
                analyzer.Feed(left.AsSpan(0, n), stereo ? right.AsSpan(0, n) : default);
                progress?.Report(("Analisando a qualidade", 0.25 * (i + n) / source.Frames));
            }
            diagnosis = analyzer.Result();
            profile = analyzer.NoiseProfile();
        }
        return new Analysis(AudioRestoration.Build(diagnosis, options), diagnosis, profile, inputEnergy, inputPeak, source.Frames, stereo ? 2 : 1);
    }

    /// <summary>Acerta a duração depois da troca de taxa (o FFmpeg pode deixar uma amostra a mais ou a menos).</summary>
    private static void FitLength(string wav, long frames, int channels, int rate, CancellationToken token)
    {
        var temp = wav + ".ajuste";
        using (var source = new MappedWavSource(wav))
        {
            if (source.Frames == frames) return;
            using var writer = new StreamingWavWriter(temp, channels, rate);
            var buffers = Enumerable.Range(0, channels).Select(_ => new float[Block]).ToArray();
            for (long i = 0; i < frames; i += Block)
            {
                token.ThrowIfCancellationRequested();
                var n = (int)Math.Min(Block, frames - i);
                var have = (int)Math.Clamp(source.Frames - i, 0, n);
                for (var c = 0; c < channels; c++)
                {
                    if (have > 0) source.Copy(c, i, have, buffers[c]);
                    Array.Clear(buffers[c], have, n - have);
                }
                writer.Write(buffers, 0, n);
            }
        }
        File.Move(temp, wav, overwrite: true);
    }

    /// <summary>Reparo do núcleo sobre `input` (o original ou o que saiu do Apollo), com a conferência antes de devolver.</summary>
    private static (IReadOnlyDictionary<string, double>, (double, double, double, double)?) Repair(string input, string output, int rate, Analysis analysis,
        bool levelMatch, IProgress<(string Stage, double Fraction)>? progress, double from0, CancellationToken token)
    {
        var (plan, diagnosis, profile, inputEnergy, inputPeak, _, channels) = analysis;
        using var source = new MappedWavSource(input);
        var stereo = channels > 1;
        var left = new float[Block];
        var right = new float[Block];
        if (plan.NoChange)
        {
            File.Copy(input, output, overwrite: true);
            return (diagnosis, null);
        }
        var temp = output + ".parcial";
        double outputEnergy = 0, outputPeak = 0;
        (double, double, double, double) stats;
        using (var repair = new MusicNative.AudioRepair((uint)rate, plan.Parameters, profile))
        using (var writer = new StreamingWavWriter(temp, channels, rate))
        {
            var latency = repair.Latency;
            var total = source.Frames + latency;
            for (long start = 0; start < total; start += Block)
            {
                token.ThrowIfCancellationRequested();
                var n = (int)Math.Min(Block, total - start);
                if (start < source.Frames) { source.Copy(0, start, n, left); source.Copy(1, start, n, right); }
                else { Array.Clear(left, 0, n); Array.Clear(right, 0, n); }
                repair.Process(left.AsSpan(0, n), stereo ? right.AsSpan(0, n) : default);
                var from = (int)Math.Max(0, latency - start);
                var count = (int)Math.Min(n - from, source.Frames - (start + from - latency));
                if (count > 0)
                {
                    for (var k = from; k < from + count; k++)
                    {
                        if (!float.IsFinite(left[k]) || (stereo && !float.IsFinite(right[k])))
                            throw new ConversionException("restore_invalid", "A restauração produziu amostras inválidas; o original não foi alterado.");
                        outputEnergy += left[k] * left[k] + (stereo ? right[k] * right[k] : left[k] * left[k]);
                        outputPeak = Math.Max(outputPeak, Math.Max(Math.Abs(left[k]), stereo ? Math.Abs(right[k]) : 0));
                    }
                    var l = left[from..(from + count)];
                    writer.Write(stereo ? [l, right[from..(from + count)]] : [l], 0, count);
                }
                progress?.Report(("Restaurando", from0 + (0.95 - from0) * Math.Min(1, (start + n) / (double)total)));
            }
            stats = repair.Stats();
        }
        // Conferência: duração igual, som de verdade (a entrada não era silêncio).
        using (var check = new MappedWavSource(temp))
        {
            if (check.Frames != source.Frames) throw new ConversionException("restore_invalid", "A duração do áudio restaurado não confere.");
        }
        if (inputPeak > 1e-3 && outputPeak < 1e-3)
            throw new ConversionException("restore_invalid", "O áudio restaurado ficou em silêncio; o original não foi alterado.");
        var gain = levelMatch && outputEnergy > 0 ? Math.Clamp(Math.Sqrt(inputEnergy / outputEnergy), 0.5, 2) : 1;
        if (Math.Abs(gain - 1) < 1e-4)
        {
            File.Move(temp, output, overwrite: true);
            return (diagnosis, stats);
        }
        using (var restored = new MappedWavSource(temp))
        using (var final = new StreamingWavWriter(output, channels, rate))
        {
            for (long i = 0; i < restored.Frames; i += Block)
            {
                var n = (int)Math.Min(Block, restored.Frames - i);
                restored.Copy(0, i, n, left);
                restored.Copy(1, i, n, right);
                for (var k = 0; k < n; k++) { left[k] *= (float)gain; right[k] *= (float)gain; }
                final.Write(stereo ? [left, right] : [left], 0, n);
            }
        }
        File.Delete(temp);
        return (diagnosis, stats);
    }

    /// <summary>WAV float → formato do restaurador (WAV 24/32f, FLAC 24, MP3 320).</summary>
    private Task EncodeAsync(string wav, string output, string format, CancellationToken token)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        string[] codec = format switch
        {
            "flac" => ["-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24"],
            "mp3" => ["-c:a", "libmp3lame", "-b:a", "320k"],
            "wav-32f" => ["-c:a", "pcm_f32le"],
            _ => ["-c:a", "pcm_s24le"],
        };
        return FfmpegAsync(["-hide_banner", "-nostdin", "-y", "-i", wav, .. codec, output], token);
    }

    /// <summary>Extensão do arquivo de cada formato do restaurador.</summary>
    public static string RestorationExtension(string format) => format switch { "flac" => "flac", "mp3" => "mp3", _ => "wav" };
}

/// <summary>Progresso entregue na hora, na mesma thread (sem a fila do Progress&lt;T&gt;).</summary>
internal sealed class DirectProgress<T>(Action<T> report) : IProgress<T>
{
    public void Report(T value) => report(value);
}
