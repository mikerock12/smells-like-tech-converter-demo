using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>
/// Reparo de compressão por IA: Apollo (JusperLee/Apollo, CC BY-SA 4.0, código e pesos),
/// convertido para ONNX com a STFT dentro do grafo. Blocos estéreo de 5 s a 44,1 kHz,
/// sobrepostos pela metade com transição linear de 0,5 s; abaixo do corte o grafo mantém o
/// original e acima mistura o Apollo. Mesma conta de packages/converter-core/apollo.mjs.
/// </summary>
public static class MusicApollo
{
    public const string Id = "apollo-jusperlee-v1-onnx-fp32";
    public const string FileName = "apollo-restauracao-fp32-v1.onnx";
    public const long Bytes = 73_947_536;
    public const string Sha256 = "bd0e9aeb69c84056715e7bfc6386cbb8cf17e7e980add13b87177afceec2d893";
    public const string Url = "https://converter.smellsliketech.com.br/modelos/" + FileName;
    public const int Rate = 44100;
    public const string Attribution = "Apollo, de Kai Li e Yi Luo (JusperLee/Apollo), CC BY-SA 4.0. Convertido para ONNX pela Smells Like Tech, sob a mesma licença.";
    private const int Samples = 220_500;
    private const int Step = Samples / 2;
    private const int Fade = Samples / 10;

    /// <summary>Onde começa cada bloco (o último termina no fim do áudio, ou é o único).</summary>
    public static IEnumerable<long> Starts(long frames)
    {
        for (long start = 0; start < Math.Max(1, frames - Step); start += Step) yield return start;
    }

    /// <summary>Restaura um WAV estéreo a 44,1 kHz em outro, na mesma duração.</summary>
    public static void Restore(string model, string input, string output, double cutoffHz, double mix,
        IProgress<double>? progress, CancellationToken token)
    {
        using var source = new MappedWavSource(input);
        var frames = source.Frames;
        if (frames <= 0) throw new ConversionException("invalid_wav", "O áudio está vazio.");
        using var options = new SessionOptions
        {
            GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL,
            IntraOpNumThreads = Math.Min(4, Core.Jobs.InferenceProcessors.InferenceThreads()),
            EnableCpuMemArena = false,
        };
        using var session = new InferenceSession(model, options);
        using var runOptions = new RunOptions();
        using var cancellation = token.Register(() => runOptions.Terminate = true);
        var block = new float[2 * Samples];
        var audio = NamedOnnxValue.CreateFromTensor("audio", new DenseTensor<float>(block, [1, 2, Samples]));
        var corte = NamedOnnxValue.CreateFromTensor("corte", new DenseTensor<float>(new[] { (float)cutoffHz }, [1]));
        var mistura = NamedOnnxValue.CreateFromTensor("mistura", new DenseTensor<float>(new[] { (float)Math.Clamp(mix, 0, 1) }, [1]));
        // A soma ponderada fica num WAV de trabalho (dois canais e o peso): a música não precisa caber na RAM.
        float[][] sums = [new float[Samples], new float[Samples]];
        var weights = new float[Samples];
        var starts = Starts(frames).ToList();
        var channel = new float[Samples];
        using var writer = new StreamingWavWriter(output, 2, Rate);
        long written = 0;
        // Carrega o que sobrou do bloco anterior (a metade que se sobrepõe ao próximo).
        float[][] carry = [new float[Samples], new float[Samples]];
        var carryWeights = new float[Samples];
        for (var b = 0; b < starts.Count; b++)
        {
            token.ThrowIfCancellationRequested();
            var start = starts[b];
            var end = Math.Min(frames, start + Samples);
            var valid = (int)(end - start);
            Array.Clear(block);
            source.Copy(0, start, valid, channel);
            Array.Copy(channel, 0, block, 0, valid);
            source.Copy(source.Channels > 1 ? 1 : 0, start, valid, channel);
            Array.Copy(channel, 0, block, Samples, valid);
            using (var results = session.Run([audio, corte, mistura], ["restaurado"], runOptions))
            {
                var y = results.Single().AsTensor<float>();
                if (y is not DenseTensor<float> dense || !y.Dimensions.SequenceEqual([1, 2, Samples]))
                    throw new ConversionException("model_mismatch", "O formato de saída do Apollo não confere.");
                var span = dense.Buffer.Span;
                Array.Clear(sums[0]); Array.Clear(sums[1]); Array.Clear(weights);
                for (var i = 0; i < valid; i++)
                {
                    float p = 1;
                    if (start > 0 && i < Fade) p = i / (float)(Fade - 1);
                    if (end < frames && i >= valid - Fade) p = Math.Min(p, (valid - 1 - i) / (float)(Fade - 1));
                    sums[0][i] = span[i] * p;
                    sums[1][i] = span[Samples + i] * p;
                    weights[i] = p;
                }
            }
            // A primeira metade deste bloco se junta ao que sobrou do anterior e já pode ser gravada.
            var ready = b + 1 < starts.Count ? (int)(starts[b + 1] - start) : valid;
            if (b > 0)
                for (var i = 0; i < Samples - Step && i < valid; i++)
                {
                    sums[0][i] += carry[0][i]; sums[1][i] += carry[1][i]; weights[i] += carryWeights[i];
                }
            float[] left = new float[ready], right = new float[ready];
            for (var i = 0; i < ready; i++)
            {
                var w = weights[i] > 1e-9f ? weights[i] : 1;
                left[i] = sums[0][i] / w;
                right[i] = sums[1][i] / w;
                if (!float.IsFinite(left[i]) || !float.IsFinite(right[i]))
                    throw new ConversionException("restore_invalid", "O Apollo devolveu uma amostra inválida; o original não foi alterado.");
            }
            writer.Write([left, right], 0, ready);
            written += ready;
            Array.Clear(carry[0]); Array.Clear(carry[1]); Array.Clear(carryWeights);
            for (var i = ready; i < valid; i++)
            {
                carry[0][i - ready] = sums[0][i]; carry[1][i - ready] = sums[1][i]; carryWeights[i - ready] = weights[i];
            }
            progress?.Report((b + 1) / (double)starts.Count);
        }
        if (written != frames) throw new ConversionException("restore_invalid", "A duração do áudio restaurado pelo Apollo não confere.");
    }
}
