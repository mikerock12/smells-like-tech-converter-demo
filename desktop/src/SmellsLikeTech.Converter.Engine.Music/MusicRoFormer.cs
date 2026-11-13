using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Music;

namespace SmellsLikeTech.Converter.Engine.Music;

internal static class MusicRoFormer
{
    public const string Id = "mvsep-mega53-v1-onnx-5s";
    public const string FileName = "mega53-espectral-fp32-5s.onnx";
    public const long Bytes = 357_162_852;
    public const string Sha256 = "308283a38fe4112aa19b8b3c86f032aac299f5523b18bbbb219ecc9feba8b7da";
    private const int Window = 2048;
    private const int Hop = 512;
    private const int Samples = 220_500;
    private const int Step = Samples / 2;
    private const int Fade = Samples / 10;
    private const int Outputs = 5;
    private const int Frames = Samples / Hop + 1;
    private const int SpectrumWidth = (Window / 2 + 1) * 4;

    public static readonly IReadOnlyList<MusicStemDefinition> Stems =
    [
        new("vozes", "Voz", ["vozes"]),
        new("bateria", "Bateria", ["bateria"]),
        new("baixo", "Baixo", ["baixo"]),
        new("guitarra", "Guitarra", ["guitarra"]),
        new("piano", "Piano", ["piano"]),
        new("outros", "Outros (resíduo)", ["outros"]),
    ];

    public static void Separate(IMusicSource source, string model, IReadOnlyList<StreamingWavWriter> writers,
        IProgress<(string Stage, double Fraction)>? progress, CancellationToken token)
    {
        if (source.Frames <= 0) throw new ConversionException("invalid_wav", "O áudio está vazio.");
        using var stft = new MusicNative.ComplexStft(Window, Hop, Samples);
        using var options = new SessionOptions
        {
            GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL,
            IntraOpNumThreads = Math.Clamp(Environment.ProcessorCount - 1, 1, 4),
            EnableCpuMemArena = false,
            EnableMemoryPattern = false,
        };
        using var session = new InferenceSession(model, options);
        using var runOptions = new RunOptions();
        using var cancellation = token.Register(() => runOptions.Terminate = true);
        var spectrum = new float[stft.SpectrumSize];
        var input = NamedOnnxValue.CreateFromTensor("espectro", new DenseTensor<float>(spectrum, [1, Frames, SpectrumWidth]));
        float[][] pcm = [new float[Samples], new float[Samples]];
        float[][] original = [new float[Samples], new float[Samples]];
        float[][] decoded = [new float[Samples], new float[Samples]];
        var sums = Enumerable.Range(0, Outputs).Select(_ => new[] { new float[Samples], new float[Samples] }).ToArray();
        var weights = new float[Samples];
        var positions = new long[Samples];
        long margin = Math.Min(Step, source.Frames - 1);
        long end = source.Frames + margin;
        for (long start = -margin; start < end; start += Step)
        {
            token.ThrowIfCancellationRequested();
            int valid = (int)Math.Min(Samples, end - start);
            ReadBlock(source, start, valid, positions, original, pcm);
            stft.Analyze(pcm[0], pcm[1], spectrum);
            try
            {
                using var outputs = session.Run([input], ["mascaras"], runOptions);
                var masks = outputs.Single().AsTensor<float>();
                if (masks is not DenseTensor<float> dense || !masks.Dimensions.SequenceEqual([1, Outputs, Frames, SpectrumWidth]))
                    throw new ConversionException("model_mismatch", "O formato de saída do RoFormer não confere.");
                for (int stem = 0; stem < Outputs; ++stem)
                {
                    token.ThrowIfCancellationRequested();
                    stft.Synthesize(spectrum, dense.Buffer.Span.Slice(stem * spectrum.Length, spectrum.Length),
                        decoded[0], decoded[1], zeroDc: true);
                    for (int i = 0; i < valid; ++i)
                    {
                        float weight = Weight(i, start > -margin, start + valid < end);
                        sums[stem][0][i] += decoded[0][i] * weight;
                        sums[stem][1][i] += decoded[1][i] * weight;
                    }
                }
            }
            catch (OnnxRuntimeException) when (token.IsCancellationRequested)
            {
                token.ThrowIfCancellationRequested();
                throw;
            }
            for (int i = 0; i < valid; ++i) weights[i] += Weight(i, start > -margin, start + valid < end);
            int first = (int)Math.Max(0, -start);
            int last = (int)Math.Clamp(source.Frames - start, 0, Step);
            if (last > first)
            {
                token.ThrowIfCancellationRequested();
                for (int channel = 0; channel < 2; ++channel)
                {
                    for (int i = first; i < last; ++i)
                    {
                        float residual = pcm[channel][i];
                        foreach (var stem in sums)
                        {
                            stem[channel][i] /= weights[i];
                            if (!float.IsFinite(stem[channel][i]))
                                throw new ConversionException("model_output", "O RoFormer devolveu uma amostra inválida.");
                            residual -= stem[channel][i];
                        }
                        decoded[channel][i] = residual;
                    }
                }
                for (int stem = 0; stem < Outputs; ++stem) writers[stem].Write(sums[stem], first, last - first);
                writers[Outputs].Write(decoded, first, last - first);
            }
            Shift(weights);
            foreach (var stem in sums)
            {
                Shift(stem[0]);
                Shift(stem[1]);
            }
            progress?.Report(("Separando com o RoFormer", Math.Clamp((start + Step) / (double)source.Frames, 0, 1)));
        }
    }

    private static void ReadBlock(IMusicSource source, long start, int valid, long[] positions, float[][] original, float[][] pcm)
    {
        long min = source.Frames - 1;
        long max = 0;
        for (int i = 0; i < valid; ++i)
        {
            long index = start + i;
            if (index < 0) index = -index;
            if (index >= source.Frames) index = 2 * (source.Frames - 1) - index;
            positions[i] = index;
            min = Math.Min(min, index);
            max = Math.Max(max, index);
        }
        for (int channel = 0; channel < 2; ++channel)
        {
            source.Copy(channel, min, (int)(max - min + 1), original[channel]);
            for (int i = 0; i < valid; ++i) pcm[channel][i] = original[channel][(int)(positions[i] - min)];
            if (valid > Samples - valid)
                for (int i = valid; i < Samples; ++i) pcm[channel][i] = pcm[channel][2 * (valid - 1) - i];
            else
                Array.Clear(pcm[channel], valid, Samples - valid);
        }
    }

    private static float Weight(int i, bool fadeIn, bool fadeOut)
    {
        if (fadeIn && i < Fade) return i / (float)(Fade - 1);
        if (fadeOut && i >= Samples - Fade) return (Samples - 1 - i) / (float)(Fade - 1);
        return 1;
    }

    private static void Shift(float[] values)
    {
        Array.Copy(values, Step, values, 0, Step);
        Array.Clear(values, Step, Step);
    }
}
