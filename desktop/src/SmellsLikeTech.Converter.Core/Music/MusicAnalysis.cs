namespace SmellsLikeTech.Converter.Core.Music;

public sealed record MusicAnalysis(int? Bpm, string? Key, double KeyConfidence);

/// <summary>Estimativas de andamento e tonalidade por ataques, autocorrelacao e croma.</summary>
public static class MusicAnalyzer
{
    private static readonly string[] Notes = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
    private static readonly double[] Major = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
    private static readonly double[] Minor = [6.33, 2.68, 3.52, 5.38, 2.6, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

    private static float[] Mono(float[][] channels)
    {
        var length = channels.FirstOrDefault()?.Length ?? 0;
        var output = new float[length];
        if (channels.Length == 0) return output;
        for (var i = 0; i < length; i++)
        {
            double value = 0;
            foreach (var channel in channels) if (i < channel.Length) value += channel[i];
            output[i] = (float)(value / channels.Length);
        }
        return output;
    }

    private static int? DetectBpm(float[] signal, int rate)
    {
        var hop = Math.Max(128, (int)MusicRules.Round(rate / 100.0));
        var window = hop * 2;
        var count = signal.Length / hop;
        if (count < 16) return null;
        var energy = new double[count];
        for (var i = 0; i < count; i++)
        {
            double sum = 0;
            var start = i * hop;
            for (var j = 0; j < window && start + j < signal.Length; j += 2) sum += signal[start + j] * signal[start + j];
            energy[i] = Math.Sqrt(sum / (window / 2));
        }
        var flux = new double[count];
        for (var i = 2; i < count; i++) flux[i] = Math.Max(0, energy[i] - (energy[i - 1] + energy[i - 2]) / 2);
        var mean = flux.Average();
        for (var i = 0; i < count; i++) flux[i] = Math.Max(0, flux[i] - mean);
        int? bestBpm = null;
        var best = 0.0;
        for (var bpm = 40; bpm <= 240; bpm++)
        {
            var delay = Math.Max(2, 60.0 * rate / (bpm * hop));
            var whole = (int)Math.Floor(delay);
            var fraction = delay - whole;
            double sum = 0;
            var weight = 0;
            for (var i = whole + 1; i < count; i++)
            {
                var previous = flux[i - whole] * (1 - fraction) + flux[i - whole - 1] * fraction;
                sum += flux[i] * previous;
                weight++;
            }
            var score = weight > 0 ? sum / weight : 0;
            if (bpm is >= 70 and <= 180) score *= 1.03;
            if (score > best) { best = score; bestBpm = bpm; }
        }
        return bestBpm;
    }

    private static double Correlation(double[] a, double[] b)
    {
        var ma = a.Average();
        var mb = b.Average();
        double ab = 0, aa = 0, bb = 0;
        for (var i = 0; i < a.Length; i++)
        {
            var x = a[i] - ma;
            var y = b[i] - mb;
            ab += x * y; aa += x * x; bb += y * y;
        }
        return ab / Math.Sqrt(Math.Max(1e-12, aa * bb));
    }

    private static (string? Key, double Confidence) DetectKey(float[] signal, int rate)
    {
        const int n = 2048;
        if (signal.Length < n * 4) return (null, 0);
        var fft = new Fft(n);
        var re = new double[n];
        var im = new double[n];
        var chroma = new double[12];
        var step = Math.Max(n, (signal.Length - n) / 2400);
        var used = 0;
        for (var start = 0; start + n <= signal.Length; start += step)
        {
            for (var i = 0; i < n; i++) { re[i] = signal[start + i] * (0.5 - 0.5 * Math.Cos(2 * Math.PI * i / n)); im[i] = 0; }
            fft.Transform(re, im, -1);
            for (var k = 2; k < n / 2; k++)
            {
                var frequency = k * (double)rate / n;
                if (frequency < 55 || frequency > 4000) continue;
                var note = (int)MusicRules.Round(12 * Math.Log2(frequency / 440)) + 69;
                chroma[((note % 12) + 12) % 12] += Math.Sqrt(re[k] * re[k] + im[k] * im[k]);
            }
            used++;
        }
        if (used == 0 || chroma.All(v => v < 1e-9)) return (null, 0);
        var best = double.NegativeInfinity;
        string? result = null;
        foreach (var (mode, profile) in new[] { ("maior", Major), ("menor", Minor) })
        for (var note = 0; note < 12; note++)
        {
            var rotated = Enumerable.Range(0, 12).Select(i => chroma[(i + note) % 12]).ToArray();
            var value = Correlation(rotated, profile);
            if (value > best) { best = value; result = $"{Notes[note]} {mode}"; }
        }
        return (result, Math.Clamp(best, 0, 1));
    }

    public static MusicAnalysis Analyze(float[][] channels, int rate)
    {
        var signal = Mono(channels);
        var key = DetectKey(signal, rate);
        return new MusicAnalysis(DetectBpm(signal, rate), key.Key, key.Confidence);
    }

    private sealed class Fft
    {
        private readonly double[] cos;
        private readonly double[] sin;
        private readonly int[] reverse;
        public Fft(int n)
        {
            cos = new double[n / 2]; sin = new double[n / 2]; reverse = new int[n];
            for (var i = 0; i < n / 2; i++) { cos[i] = Math.Cos(2 * Math.PI * i / n); sin[i] = Math.Sin(2 * Math.PI * i / n); }
            var bits = (int)Math.Log2(n);
            for (var i = 0; i < n; i++)
            {
                var r = 0;
                for (var b = 0; b < bits; b++) r |= ((i >> b) & 1) << (bits - 1 - b);
                reverse[i] = r;
            }
        }
        public void Transform(double[] re, double[] im, int sign)
        {
            var n = re.Length;
            for (var i = 0; i < n; i++) if (reverse[i] > i) { (re[i], re[reverse[i]]) = (re[reverse[i]], re[i]); (im[i], im[reverse[i]]) = (im[reverse[i]], im[i]); }
            for (var size = 2; size <= n; size *= 2)
            {
                var half = size / 2;
                var step = n / size;
                for (var start = 0; start < n; start += size)
                for (var k = 0; k < half; k++)
                {
                    var wr = cos[k * step]; var wi = sign * sin[k * step];
                    var a = start + k; var b = a + half;
                    var tr = re[b] * wr - im[b] * wi; var ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
