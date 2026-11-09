namespace SmellsLikeTech.Converter.Core.Music;

public sealed record MusicMasterPreset(string Id, string Label, double? TargetLufs, double CeilingDbtp);

public sealed record MusicExportFormat(string Id, string Label, string Extension, int Bits);

public readonly record struct MusicMeasures(double Lufs, double SamplePeakDb, double TruePeakDb);

public sealed record MusicMasterResult(float[] Left, float[] Right, double GainDb, MusicMeasures Before, MusicMeasures After);

/// <summary>
/// Master e WAV, espelho de musica-master.mjs: loudness BS.1770-4, pico real 4x, presets e
/// WAV 16/24 com dither TPDF (xorshift32) ou float32.
/// </summary>
public static class MusicMastering
{
    public static readonly IReadOnlyList<MusicMasterPreset> Presets =
    [
        new("sem-alvo", "Sem alvo (só proteção contra clipping)", null, -1),
        new("streaming", "Streaming (−14 LUFS)", -14, -1),
        new("podcast", "Podcast e voz (−16 LUFS)", -16, -1),
        new("video", "Vídeo para redes (−14 LUFS, teto −2 dBTP)", -14, -2),
        new("alto", "Alto, estilo CD (−9 LUFS)", -9, -0.5),
    ];

    public static readonly IReadOnlyList<MusicExportFormat> Formats =
    [
        new("wav-24", "WAV 24 bits", "wav", 24),
        new("wav-16", "WAV 16 bits (CD)", "wav", 16),
        new("wav-32f", "WAV 32 bits float", "wav", 32),
        new("flac", "FLAC 24 bits", "flac", 24),
        new("mp3", "MP3 320 kbps", "mp3", 16),
        new("aac", "AAC 256 kbps (M4A)", "m4a", 16),
        new("opus", "Opus 192 kbps", "opus", 16),
    ];

    public static double[][] KCoefficients(int rate)
    {
        double[] shelf;
        {
            const double G = 3.999843853973347;
            const double Q = 0.7071752369554196;
            const double fc = 1681.974450955533;
            var K = Math.Tan(Math.PI * fc / rate);
            var Vh = Math.Pow(10, G / 20);
            var Vb = Math.Pow(Vh, 0.4996667741545416);
            var a0 = 1 + K / Q + K * K;
            shelf = [(Vh + Vb * K / Q + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0];
        }

        double[] highPass;
        {
            const double Q = 0.5003270373238773;
            const double fc = 38.13547087602444;
            var K = Math.Tan(Math.PI * fc / rate);
            var a0 = 1 + K / Q + K * K;
            highPass = [1, -2, 1, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0];
        }

        return [shelf, highPass];
    }

    public static double IntegratedLoudness(float[] left, float[] right, int rate)
    {
        var k = KCoefficients(rate);
        var f1 = new MusicBiquad(k[0]);
        var f2 = new MusicBiquad(k[1]);
        var n = left.Length;
        var size = (int)MusicRules.Round(rate * 0.4);
        var step = (int)MusicRules.Round(rate * 0.1);
        if (n < size) return double.NegativeInfinity;
        var parts = n / step;
        var energy = new double[parts];
        for (var i = 0; i < parts * step; i++)
        {
            var l = f2.Sample(f1.Sample(left[i], 0), 0);
            var r = f2.Sample(f1.Sample(right[i], 1), 1);
            energy[i / step] += l * l + r * r;
        }

        var blocks = new List<double>();
        for (var b = 0; b + 4 <= parts; b++) blocks.Add((energy[b] + energy[b + 1] + energy[b + 2] + energy[b + 3]) / size);
        static double Lufs(double z) => -0.691 + 10 * Math.Log10(z);
        var absolute = blocks.Where(z => z > 0 && Lufs(z) > -70).ToList();
        if (absolute.Count == 0) return double.NegativeInfinity;
        static double Mean(List<double> list)
        {
            double s = 0;
            foreach (var z in list) s += z;
            return s / list.Count;
        }

        var relative = Lufs(Mean(absolute)) - 10;
        var final = absolute.Where(z => Lufs(z) > relative).ToList();
        return final.Count > 0 ? Lufs(Mean(final)) : double.NegativeInfinity;
    }

    public static double[][] TruePeakPhases()
    {
        var phases = new double[4][];
        const int half = 6;
        for (var f = 0; f < 4; f++)
        {
            var c = new double[12];
            for (var k = 0; k < 12; k++)
            {
                var x = k - half + 1 - f / 4.0;
                var sinc = x == 0 ? 1 : Math.Sin(Math.PI * x) / (Math.PI * x);
                var window = 0.5 + 0.5 * Math.Cos(Math.PI * x / (half + 0.5));
                c[k] = sinc * window;
            }

            phases[f] = c;
        }

        return phases;
    }

    public static (double SampleDb, double TrueDb) Peaks(float[] left, float[] right)
    {
        var phases = TruePeakPhases();
        double sample = 0, truePeak = 0;
        foreach (var channel in new[] { left, right })
        {
            for (var i = 0; i < channel.Length; i++)
            {
                var v = Math.Abs((double)channel[i]);
                if (v > sample) sample = v;
                for (var f = 1; f < 4; f++)
                {
                    var c = phases[f];
                    double sum = 0;
                    for (var k = 0; k < 12; k++)
                    {
                        var j = i + k - 5;
                        if (j >= 0 && j < channel.Length) sum += channel[j] * c[k];
                    }

                    if (Math.Abs(sum) > truePeak) truePeak = Math.Abs(sum);
                }
            }
        }

        truePeak = Math.Max(truePeak, sample);
        static double Dbfs(double v) => v > 0 ? 20 * Math.Log10(v) : double.NegativeInfinity;
        return (Dbfs(sample), Dbfs(truePeak));
    }

    public static MusicMeasures Measure(float[] left, float[] right, int rate)
    {
        var (s, t) = Peaks(left, right);
        return new MusicMeasures(IntegratedLoudness(left, right, rate), s, t);
    }

    public static MusicMasterResult Master(float[] left, float[] right, int rate, string presetId)
    {
        var preset = Presets.FirstOrDefault(p => p.Id == presetId) ?? Presets[0];
        var before = Measure(left, right, rate);
        var gainDb = preset.TargetLufs is null || !double.IsFinite(before.Lufs) ? 0 : Math.Min(24, preset.TargetLufs.Value - before.Lufs);
        var g = Math.Pow(10, gainDb / 20);
        var ceiling = Math.Pow(10, (preset.CeilingDbtp - 0.3) / 20);
        var release = Math.Exp(-1 / (rate * 0.05));
        var l = new float[left.Length];
        var r = new float[right.Length];
        double gain = 1;
        for (var i = 0; i < left.Length; i++)
        {
            var a = left[i] * g;
            var b = right[i] * g;
            var peak = Math.Max(Math.Abs(a), Math.Abs(b));
            var target = peak > ceiling ? ceiling / peak : 1;
            gain = target < gain ? target : release * gain + (1 - release) * target;
            l[i] = (float)(a * gain);
            r[i] = (float)(b * gain);
        }

        // O limitador olha a amostra; se o pico real (4x) passar do teto, tudo desce o que falta.
        var after = Measure(l, r, rate);
        if (after.TruePeakDb > preset.CeilingDbtp)
        {
            var cut = Math.Pow(10, (preset.CeilingDbtp - after.TruePeakDb - 0.05) / 20);
            for (var i = 0; i < l.Length; i++)
            {
                l[i] = (float)(l[i] * cut);
                r[i] = (float)(r[i] * cut);
            }

            after = Measure(l, r, rate);
        }

        return new MusicMasterResult(l, r, gainDb, before, after);
    }

    /// <summary>xorshift32: a mesma sequencia do JS e do Kotlin.</summary>
    public sealed class Noise(uint seed = 0x12345678)
    {
        private uint state = seed == 0 ? 1 : seed;

        public double Next()
        {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            return state / 4294967296.0;
        }
    }

    public static int Quantize(double value, int bits, Noise? noise)
    {
        var scale = bits == 16 ? 32767 : 8388607;
        var dither = noise is null ? 0 : noise.Next() - noise.Next();
        var v = (long)Math.Floor(value * scale + dither + 0.5);
        return (int)Math.Max(-scale - 1, Math.Min(scale, v));
    }

    public static byte[] EncodeWav(IReadOnlyList<float[]> channels, int rate, int bits = 24, bool dither = true, uint seed = 0x12345678)
    {
        using var stream = new MemoryStream();
        WriteWav(stream, channels, rate, bits, dither, seed);
        return stream.ToArray();
    }

    public static void WriteWavHeader(BinaryWriter w, int channels, int rate, int bits, long frames)
    {
        var bytesPerSample = bits / 8;
        var data = frames * channels * bytesPerSample;
        w.Write("RIFF"u8);
        w.Write((uint)Math.Min(uint.MaxValue, 36 + data));
        w.Write("WAVE"u8);
        w.Write("fmt "u8);
        w.Write(16);
        w.Write((ushort)(bits == 32 ? 3 : 1));
        w.Write((ushort)channels);
        w.Write(rate);
        w.Write(rate * channels * bytesPerSample);
        w.Write((ushort)(channels * bytesPerSample));
        w.Write((ushort)bits);
        w.Write("data"u8);
        w.Write((uint)Math.Min(uint.MaxValue, data));
    }

    public static void WriteWav(Stream output, IReadOnlyList<float[]> channels, int rate, int bits = 24, bool dither = true, uint seed = 0x12345678)
    {
        var n = channels[0].Length;
        var c = channels.Count;
        using var w = new BinaryWriter(output, System.Text.Encoding.ASCII, leaveOpen: true);
        WriteWavHeader(w, c, rate, bits, n);
        var noise = bits != 32 && dither ? new Noise(seed) : null;
        var buffer = new byte[Math.Min(n, 8192) * c * (bits / 8)];
        var p = 0;
        for (var i = 0; i < n; i++)
        {
            for (var k = 0; k < c; k++)
            {
                var x = channels[k][i];
                if (bits == 32)
                {
                    BitConverter.TryWriteBytes(buffer.AsSpan(p), x);
                    p += 4;
                }
                else
                {
                    var q = Quantize(x, bits, noise);
                    buffer[p++] = (byte)(q & 0xff);
                    buffer[p++] = (byte)((q >> 8) & 0xff);
                    if (bits == 24) buffer[p++] = (byte)((q >> 16) & 0xff);
                }
            }

            if (p + c * (bits / 8) > buffer.Length)
            {
                output.Write(buffer, 0, p);
                p = 0;
            }
        }

        output.Write(buffer, 0, p);
    }

    public sealed record WavData(int SampleRate, float[][] Channels, int Bits);

    /// <summary>Le WAV PCM 16/24/32 ou float32; recupera "data" zerado ou cortado (gravacao interrompida).</summary>
    public static WavData ReadWav(byte[] bytes)
    {
        string Text(int p) => System.Text.Encoding.ASCII.GetString(bytes, p, 4);
        if (bytes.Length < 12 || Text(0) != "RIFF" || Text(8) != "WAVE") throw new ConversionException("invalid_wav", "Não é um WAV.");
        var p = 12;
        (int Type, int Channels, int Rate, int Bits)? format = null;
        while (p + 8 <= bytes.Length)
        {
            var id = Text(p);
            long size = BitConverter.ToUInt32(bytes, p + 4);
            if (id == "fmt ")
            {
                int type = BitConverter.ToUInt16(bytes, p + 8);
                if (type == 0xfffe && size >= 26) type = BitConverter.ToUInt16(bytes, p + 32);
                format = (type, BitConverter.ToUInt16(bytes, p + 10), BitConverter.ToInt32(bytes, p + 12), BitConverter.ToUInt16(bytes, p + 22));
            }
            else if (id == "data")
            {
                if (format is not { } f) throw new ConversionException("invalid_wav", "WAV sem formato.");
                var available = bytes.Length - (p + 8);
                if (size == 0 || size > available) size = available;
                var bytesPerSample = f.Bits / 8;
                var n = (int)(size / (bytesPerSample * f.Channels));
                var channels = Enumerable.Range(0, f.Channels).Select(_ => new float[n]).ToArray();
                var q = p + 8;
                for (var i = 0; i < n; i++)
                {
                    for (var c = 0; c < f.Channels; c++)
                    {
                        channels[c][i] = f.Type == 3
                            ? BitConverter.ToSingle(bytes, q)
                            : f.Bits switch
                            {
                                16 => (float)(BitConverter.ToInt16(bytes, q) / 32768.0),
                                24 => (float)(((bytes[q] | (bytes[q + 1] << 8) | (bytes[q + 2] << 16)) << 8 >> 8) / 8388608.0),
                                32 => (float)(BitConverter.ToInt32(bytes, q) / 2147483648.0),
                                _ => throw new ConversionException("invalid_wav", "Profundidade não suportada.")
                            };
                        q += bytesPerSample;
                    }
                }

                return new WavData(f.Rate, channels, f.Bits);
            }

            p += 8 + (int)size + (int)(size & 1);
        }

        throw new ConversionException("invalid_wav", "WAV sem dados.");
    }
}
