namespace SmellsLikeTech.Converter.Core.Music;

public sealed record MusicStemDefinition(string Id, string Label, string[] From);

public sealed record MusicSeparationMode(string Id, string Label, IReadOnlyList<MusicStemDefinition> Stems);

/// <summary>
/// Separacao de faixas e reducao de ruido, espelho de musica-separacao.mjs. O modelo
/// (Spleeter 4stems, MIT) roda fora daqui; esta classe faz STFT, mascaras e reconstrucao em
/// trechos de 512 quadros, com memoria limitada.
/// </summary>
public static class MusicSeparation
{
    public const string ModelId = "spleeter-4stems-v1";
    public const string ModelFile = "spleeter-4stems-v1.onnx";
    public const string ModelUrl = "https://converter.smellsliketech.com.br/modelos/spleeter-4stems-v1.onnx";
    public const string ModelSha256 = "c5fae1215e5636a911ef4ced6eb056b129562d2661a186fddfa321afc2dc95a1";
    public const long ModelBytes = 78614199;
    public const string ModelLicense = "MIT — github.com/deezer/spleeter (código e modelos pré-treinados)";
    public const int ModelRate = 44100;
    public const int Window = 4096;
    public const int Hop = 1024;
    public const int FramesPerChunk = 512;
    public const int Bands = 1024;
    public static readonly string[] Instruments = ["vozes", "bateria", "baixo", "outros"];

    public static readonly IReadOnlyList<MusicSeparationMode> Modes =
    [
        new("2", "Voz e instrumental", [new("vozes", "Voz", ["vozes"]), new("instrumental", "Instrumental", ["bateria", "baixo", "outros"])]),
        new("4", "Voz, bateria, baixo e outros", [new("vozes", "Voz", ["vozes"]), new("bateria", "Bateria", ["bateria"]), new("baixo", "Baixo", ["baixo"]), new("outros", "Outros", ["outros"])]),
    ];

    public const string Warning =
        "A separação por IA nunca é perfeita: pode sobrar um pouco de um instrumento no outro, principalmente quando tocam juntos na mesma região. " +
        "Guitarra, piano, segunda voz ou backing vocal separados não estão disponíveis neste modelo: eles ficam em “outros” ou junto da voz principal. " +
        "A faixa original continua no projeto, para comparar.";

    public static MusicSeparationMode? Mode(string id) => Modes.FirstOrDefault(m => m.Id == id);

    public delegate void Reader(int channel, long start, int n, float[] destination);

    public delegate void Emitter(int channel, long start, float[] samples, int n);

    /// <summary>
    /// Separa: `run` recebe a magnitude [1,2,512,1024] e devolve as estimativas [1,4,2,512,1024].
    /// `emit(stem, canal, inicio, amostras, n)` recebe o resultado em pedacos e copia na hora.
    /// </summary>
    public static async Task<IReadOnlyList<MusicStemDefinition>> SeparateAsync(
        long length,
        int channels,
        Reader read,
        Func<float[], CancellationToken, Task<float[]>> run,
        Action<int, int, long, float[], int> emit,
        string mode,
        IProgress<double>? progress,
        CancellationToken cancellationToken,
        bool refined = false)
    {
        var definition = Mode(mode) ?? throw new ArgumentException($"Modo de separação desconhecido: {mode}");
        if (refined || Environment.GetEnvironmentVariable("SLT_MUSIC_NATIVE") == "1" && MusicNative.IsAvailable)
        {
            if (!MusicNative.IsAvailable)
                throw new ConversionException("native_missing", "O núcleo nativo é necessário para refinar os agudos.");
            return await SeparateNativeAsync(length, channels, read, run, emit, definition, progress, cancellationToken, refined).ConfigureAwait(false);
        }
        var processor = new MusicSpectralProcessor(length, Window, Hop, FramesPerChunk, definition.Stems.Count);
        var input = new float[2 * FramesPerChunk * Bands];
        float[][] mask = [new float[FramesPerChunk * processor.B], new float[FramesPerChunk * processor.B]];
        var indices = definition.Stems.Select(s => s.From.Select(name => Array.IndexOf(Instruments, name)).ToArray()).ToArray();
        for (var s = 0; s < processor.Chunks; s++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var q = processor.Analyze(s, read, channels);
            processor.Magnitude(q, Bands, input);
            var estimates = await run(input, cancellationToken).ConfigureAwait(false);
            for (var f = 0; f < definition.Stems.Count; f++)
            {
                StemMask(estimates, Instruments.Length, indices[f], q, FramesPerChunk, Bands, processor.B, mask);
                var stem = f;
                processor.Synthesize(s, f, mask, (c, start, samples, n) => emit(stem, c, start, samples, n));
            }

            progress?.Report((s + 1) / (double)processor.Chunks);
        }

        return definition.Stems;
    }

    private static async Task<IReadOnlyList<MusicStemDefinition>> SeparateNativeAsync(
        long length, int channels, Reader read, Func<float[], CancellationToken, Task<float[]>> run,
        Action<int, int, long, float[], int> emit, MusicSeparationMode definition,
        IProgress<double>? progress, CancellationToken token, bool refined)
    {
        using var processor = new MusicNative.Separator((ulong)length, Window, Hop, FramesPerChunk, Bands,
            (uint)Instruments.Length, (uint)definition.Stems.Count, refined);
        var input = new float[2 * FramesPerChunk * Bands];
        var left = new float[FramesPerChunk * Hop + Window];
        var right = new float[left.Length];
        var source = new float[left.Length];
        var indices = definition.Stems.Select(s => s.From.Select(name => (uint)Array.IndexOf(Instruments, name)).ToArray()).ToArray();
        var chunks = processor.Chunks;
        for (uint chunk = 0; chunk < chunks; chunk++)
        {
            token.ThrowIfCancellationRequested();
            var (start, count, _) = processor.Window(chunk);
            var first = Math.Max(0, start);
            var n = (int)Math.Max(0, Math.Min(length, start + count) - first);
            var offset = (int)(first - start);
            for (var c = 0; c < 2; c++)
            {
                var destination = c == 0 ? left : right;
                Array.Clear(destination, 0, count);
                if (n == 0) continue;
                read(Math.Min(c, channels - 1), first, n, source);
                Array.Copy(source, 0, destination, offset, n);
            }
            processor.Analyze(chunk, left.AsSpan(0, count), right.AsSpan(0, count), input);
            var estimates = await run(input, token).ConfigureAwait(false);
            for (var stem = 0; stem < indices.Length; stem++)
            {
                var (position, frames) = processor.Synthesize(chunk, (uint)stem, estimates, indices[stem], left, right);
                if (frames == 0) continue;
                emit(stem, 0, position, left, frames);
                emit(stem, 1, position, right, frames);
            }
            progress?.Report((chunk + 1) / (double)chunks);
        }
        return definition.Stems;
    }

    /// <summary>Mascara de Wiener (expoente 2) da faixa; acima das bandas do modelo, a media do quadro.</summary>
    public static void StemMask(float[] estimates, int instruments, int[] indices, int q, int T, int bands, int B, float[][] destination)
    {
        const double eps = 1e-10;
        var plane = 2 * T * bands;
        for (var c = 0; c < 2; c++)
        {
            var m = destination[c];
            for (var f = 0; f < q; f++)
            {
                double rowSum = 0;
                var baseIndex = (c * T + f) * bands;
                for (var k = 0; k < bands; k++)
                {
                    var total = eps;
                    double part = 0;
                    for (var i = 0; i < instruments; i++)
                    {
                        double e = estimates[i * plane + baseIndex + k];
                        total += e * e;
                    }

                    foreach (var i in indices)
                    {
                        double e = estimates[i * plane + baseIndex + k];
                        part += e * e + eps / instruments;
                    }

                    var value = part / total;
                    m[f * B + k] = (float)value;
                    rowSum += value;
                }

                var mean = rowSum / bands;
                for (var k = bands; k < B; k++) m[f * B + k] = (float)mean;
            }
        }
    }

    /// <summary>Reducao de ruido por porta espectral (perfil dos 10% de quadros mais quietos).</summary>
    public static void ReduceNoise(long length, int channels, Reader read, Emitter emit, double strength = 1.5, double floor = 0.1)
    {
        const int window = 2048, hop = 512, T = 256;
        var analysis = new MusicSpectralProcessor(length, window, hop, T, 1);
        var B = analysis.B;
        var energies = new List<double>();
        for (var s = 0; s < analysis.Chunks; s++)
        {
            var q = analysis.Analyze(s, read, channels);
            for (var f = 0; f < q; f++)
            {
                double e = 0;
                for (var c = 0; c < 2; c++)
                for (var k = 0; k < B; k++)
                {
                    double re = analysis.Re[c][f * B + k], im = analysis.Im[c][f * B + k];
                    e += re * re + im * im;
                }

                energies.Add(e);
            }
        }

        var sorted = energies.Order().ToList();
        var threshold = sorted.Count == 0 ? 0 : sorted[Math.Max(0, (int)Math.Floor(sorted.Count * 0.1) - 1)];
        double[][] profile = [new double[B], new double[B]];
        var counted = 0;
        for (var s = 0; s < analysis.Chunks; s++)
        {
            var q = analysis.Analyze(s, read, channels);
            for (var f = 0; f < q; f++)
            {
                if (energies[s * T + f] > threshold) continue;
                counted++;
                for (var c = 0; c < 2; c++)
                for (var k = 0; k < B; k++)
                    profile[c][k] += Hypot(analysis.Re[c][f * B + k], analysis.Im[c][f * B + k]);
            }
        }

        for (var c = 0; c < 2; c++)
        for (var k = 0; k < B; k++)
            profile[c][k] /= Math.Max(1, counted);

        var synthesis = new MusicSpectralProcessor(length, window, hop, T, 1);
        float[][] mask = [new float[T * B], new float[T * B]];
        for (var s = 0; s < synthesis.Chunks; s++)
        {
            var q = synthesis.Analyze(s, read, channels);
            for (var c = 0; c < 2; c++)
            for (var f = 0; f < q; f++)
            for (var k = 0; k < B; k++)
            {
                var i = f * B + k;
                var magnitude = Hypot(synthesis.Re[c][i], synthesis.Im[c][i]);
                mask[c][i] = (float)Math.Max(floor, magnitude > 0 ? 1 - strength * profile[c][k] / magnitude : 0);
            }

            synthesis.Synthesize(s, 0, mask, (c, start, samples, n) => emit(c, start, samples, n));
        }
    }

    /// <summary>Math.hypot do JS (sem estouro), para as magnitudes baterem nos tres nucleos.</summary>
    public static double Hypot(double a, double b)
    {
        a = Math.Abs(a);
        b = Math.Abs(b);
        if (a == 0) return b;
        if (b == 0) return a;
        return Math.Sqrt(a * a + b * b);
    }
}

/// <summary>FFT complexa radix-2 no lugar.</summary>
public sealed class MusicFft
{
    private readonly int n;
    private readonly double[] cos, sin;
    private readonly int[] reverse;

    public MusicFft(int n)
    {
        this.n = n;
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (var i = 0; i < n / 2; i++)
        {
            cos[i] = Math.Cos(2 * Math.PI * i / n);
            sin[i] = Math.Sin(2 * Math.PI * i / n);
        }

        reverse = new int[n];
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
        for (var i = 0; i < n; i++)
        {
            var j = reverse[i];
            if (j > i)
            {
                (re[i], re[j]) = (re[j], re[i]);
                (im[i], im[j]) = (im[j], im[i]);
            }
        }

        for (var size = 2; size <= n; size *= 2)
        {
            var half = size / 2;
            var step = n / size;
            for (var start = 0; start < n; start += size)
            {
                for (var k = 0; k < half; k++)
                {
                    var wr = cos[k * step];
                    var wi = sign * sin[k * step];
                    var a = start + k;
                    var b = a + half;
                    var tr = re[b] * wr - im[b] * wi;
                    var ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }
}

/// <summary>Analise/sintese STFT em trechos, espelho de ProcessadorEspectral.</summary>
public sealed class MusicSpectralProcessor
{
    private readonly long length;
    private readonly int N, H, T;
    private readonly MusicFft fft;
    private readonly double[] w;
    private readonly double normal;
    private readonly float[] samples;
    private readonly double[] br, bi;
    private readonly double[][][] accumulated;
    private readonly long[] emitted, offsetBase;

    public MusicSpectralProcessor(long length, int window, int hop, int framesPerChunk, int outputs)
    {
        this.length = length;
        N = window;
        H = hop;
        T = framesPerChunk;
        B = window / 2 + 1;
        Frames = (length + window) / hop + 1;
        Chunks = (int)((Frames + framesPerChunk - 1) / framesPerChunk);
        fft = new MusicFft(window);
        w = new double[window];
        double energy = 0;
        for (var i = 0; i < window; i++)
        {
            w[i] = 0.5 - 0.5 * Math.Cos(2 * Math.PI * i / window);
            energy += w[i] * w[i];
        }

        normal = energy / hop;
        Re = [new float[T * B], new float[T * B]];
        Im = [new float[T * B], new float[T * B]];
        samples = new float[(T - 1) * H + N];
        br = new double[N];
        bi = new double[N];
        accumulated = [.. Enumerable.Range(0, outputs).Select(_ => new[] { new double[T * H + N], new double[T * H + N] })];
        emitted = new long[outputs];
        offsetBase = new long[outputs];
    }

    public int B { get; }
    public long Frames { get; }
    public int Chunks { get; }
    public float[][] Re { get; }
    public float[][] Im { get; }

    public int FramesInChunk(int s) => (int)Math.Min(T, Frames - (long)s * T);

    private void ReadWithZeros(MusicSeparation.Reader read, int channel, long start, int n)
    {
        Array.Clear(samples, 0, n);
        var a = Math.Max(0, start);
        var b = Math.Min(length, start + n);
        if (b <= a) return;
        var count = (int)(b - a);
        var temp = count == n && a == start ? samples : new float[count];
        read(channel, a, count, temp);
        if (!ReferenceEquals(temp, samples)) Array.Copy(temp, 0, samples, (int)(a - start), count);
    }

    public int Analyze(int s, MusicSeparation.Reader read, int channels)
    {
        var q = FramesInChunk(s);
        var f0 = (long)s * T;
        var start = f0 * H - N;
        var n = (q - 1) * H + N;
        for (var c = 0; c < 2; c++)
        {
            ReadWithZeros(read, Math.Min(c, channels - 1), start, n);
            var re = Re[c];
            var im = Im[c];
            for (var f = 0; f < q; f++)
            {
                for (var i = 0; i < N; i++)
                {
                    br[i] = samples[f * H + i] * w[i];
                    bi[i] = 0;
                }

                fft.Transform(br, bi, -1);
                for (var k = 0; k < B; k++)
                {
                    re[f * B + k] = (float)br[k];
                    im[f * B + k] = (float)bi[k];
                }
            }
        }

        return q;
    }

    public float[] Magnitude(int q, int bands, float[] destination)
    {
        Array.Clear(destination);
        for (var c = 0; c < 2; c++)
        for (var f = 0; f < q; f++)
        for (var k = 0; k < bands; k++)
        {
            var i = f * B + k;
            destination[(c * T + f) * bands + k] = (float)MusicSeparation.Hypot(Re[c][i], Im[c][i]);
        }

        return destination;
    }

    public void Synthesize(int s, int output, float[][] mask, MusicSeparation.Emitter emit)
    {
        var q = FramesInChunk(s);
        var f0 = (long)s * T;
        var last = s == Chunks - 1;
        for (var c = 0; c < 2; c++)
        {
            var acc = accumulated[output][c];
            var m = mask[c];
            for (var f = 0; f < q; f++)
            {
                for (var k = 0; k < B; k++)
                {
                    var i = f * B + k;
                    br[k] = Re[c][i] * (double)m[i];
                    bi[k] = Im[c][i] * (double)m[i];
                }

                for (var k = 1; k < N / 2; k++)
                {
                    br[N - k] = br[k];
                    bi[N - k] = -bi[k];
                }

                fft.Transform(br, bi, 1);
                var position = (int)((f0 + f) * H - offsetBase[output]);
                for (var i = 0; i < N; i++) acc[position + i] += br[i] / N * w[i] / normal;
            }
        }

        var readyUntil = Math.Min(length, last ? length : (f0 + q) * H - N);
        var from = emitted[output];
        if (readyUntil > from)
        {
            var n = (int)(readyUntil - from);
            for (var c = 0; c < 2; c++)
            {
                var acc = accumulated[output][c];
                var offset = (int)(from + N - offsetBase[output]);
                for (var i = 0; i < n; i++) samples[i] = (float)acc[offset + i];
                emit(c, from, samples, n);
            }

            emitted[output] = readyUntil;
        }

        var newBase = (f0 + q) * H;
        var shift = (int)(newBase - offsetBase[output]);
        foreach (var acc in accumulated[output])
        {
            Array.Copy(acc, shift, acc, 0, N);
            Array.Clear(acc, N, acc.Length - N);
        }

        offsetBase[output] = newBase;
    }
}
