using System.Security.Cryptography;
using System.Text;
using SherpaOnnx;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>Kokoro-82M int8, português e síntese inteiramente local.</summary>
public sealed class KokoroTtsEngine : ITtsEngine, IDisposable
{
    private readonly string directory;
    private readonly SemaphoreSlim gate = new(1);
    private OfflineTts? engine;
    private static readonly SpeechVoice[] Voices =
    [
        new("pf_dora", "Dora · Kokoro-82M", "pt-BR", "Female"),
        new("pm_alex", "Alex · Kokoro-82M", "pt-BR", "Male"),
        new("pm_santa", "Santa · Kokoro-82M", "pt-BR", "Male")
    ];

    public KokoroTtsEngine(string? modelDirectory = null) =>
        directory = modelDirectory ?? Path.Combine(AppContext.BaseDirectory, "kokoro");
    public string Name => "Kokoro-82M";
    public string Version => "v1.0 int8 / Sherpa-ONNX 1.13.8";
    public IReadOnlyList<SpeechVoice> ListVoices() => File.Exists(Path.Combine(directory, "model.int8.onnx")) &&
        File.Exists(Path.Combine(directory, "tokens.txt")) && File.Exists(Path.Combine(directory, "voices.bin")) &&
        Directory.Exists(Path.Combine(directory, "espeak-ng-data")) ? Voices.ToArray() : [];
    public static float Speed(int rate) => (float)Math.Pow(2, Math.Clamp(rate, -10, 10) / 10d);
    public static int Speaker(string voiceId) => voiceId switch
    {
        "" or "pf_dora" => 42,
        "pm_alex" => 43,
        "pm_santa" => 44,
        _ => throw new ConversionException("voice_not_available", "Escolha Dora, Alex ou Santa do Kokoro-82M.")
    };

    private OfflineTts GetEngine()
    {
        if (engine is not null) return engine;
        Verify("model.int8.onnx", "4b86207ef680e394d8343bee22dfc4c512e5c707c6d9578e3f35ab09bffd6b36");
        Verify("voices.bin", "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29");
        if (ListVoices().Count == 0) throw new ConversionException("kokoro_invalid", "Os dados do Kokoro estão incompletos. Reinstale o aplicativo completo.");
        var config = new OfflineTtsConfig();
        config.Model.Kokoro.Model = Path.Combine(directory, "model.int8.onnx");
        config.Model.Kokoro.Voices = Path.Combine(directory, "voices.bin");
        config.Model.Kokoro.Tokens = Path.Combine(directory, "tokens.txt");
        config.Model.Kokoro.DataDir = Path.Combine(directory, "espeak-ng-data");
        config.Model.Kokoro.Lang = "pt";
        config.Model.NumThreads = 2;
        config.Model.Provider = "cpu";
        config.MaxNumSentences = 1;
        engine = new OfflineTts(config);
        return engine;
    }

    private void Verify(string name, string expected)
    {
        var path = Path.Combine(directory, name);
        if (!File.Exists(path)) throw new ConversionException("kokoro_missing", "O pacote Kokoro-82M está ausente. Reinstale o aplicativo completo.");
        using var input = File.OpenRead(path);
        if (!Convert.ToHexStringLower(SHA256.HashData(input)).Equals(expected, StringComparison.Ordinal))
            throw new ConversionException("kokoro_invalid", "O pacote Kokoro-82M está danificado. Reinstale o aplicativo completo.");
    }

    public async Task SynthesizeToWaveAsync(string text, string voiceId, int rate, int volume,
        string outputWavePath, IProgress<double>? progress, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(text)) throw new ConversionException("empty_text", "Não há texto para narrar.");
        var speaker = Speaker(voiceId);
        await gate.WaitAsync(cancellationToken);
        try
        {
            await Task.Run(() =>
            {
                cancellationToken.ThrowIfCancellationRequested();
                var tts = GetEngine();
                var chunks = TextChunker.Split(text, 200);
                using var stream = new FileStream(outputWavePath, FileMode.Create, FileAccess.Write);
                using var writer = new BinaryWriter(stream, Encoding.ASCII, leaveOpen: true);
                writer.Write(new byte[44]);
                for (var index = 0; index < chunks.Count; index++)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var audio = tts.GenerateWithCallbackProgress(chunks[index], Speed(rate), speaker,
                        (_, _, _) => cancellationToken.IsCancellationRequested ? 0 : 1);
                    try
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        if (audio.SampleRate != 24_000 || audio.NumSamples == 0)
                            throw new ConversionException("synthesis_failed", "O Kokoro não gerou áudio válido.");
                        var samples = audio.Samples;
                        if (samples.Any(sample => !float.IsFinite(sample)) || samples.Max(sample => Math.Abs(sample)) < .00001f)
                            throw new ConversionException("synthesis_failed", "O Kokoro produziu áudio inválido ou silencioso. Tente novamente.");
                        var scale = Math.Clamp(volume, 0, 100) / 100f;
                        foreach (var sample in samples)
                            writer.Write((short)Math.Round(Math.Clamp(sample * scale, -1, 1) * 32767));
                    }
                    finally { audio.Dispose(); }
                    progress?.Report((index + 1d) / chunks.Count);
                }
                var dataLength = checked((int)(stream.Length - 44));
                stream.Position = 0;
                writer.Write(Encoding.ASCII.GetBytes("RIFF")); writer.Write(dataLength + 36);
                writer.Write(Encoding.ASCII.GetBytes("WAVEfmt ")); writer.Write(16);
                writer.Write((short)1); writer.Write((short)1); writer.Write(24_000); writer.Write(48_000);
                writer.Write((short)2); writer.Write((short)16);
                writer.Write(Encoding.ASCII.GetBytes("data")); writer.Write(dataLength);
            }, cancellationToken);
        }
        finally { gate.Release(); }
    }

    public void Dispose() { engine?.Dispose(); gate.Dispose(); }
}
