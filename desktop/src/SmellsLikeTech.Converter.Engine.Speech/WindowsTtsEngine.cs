using System.Runtime.Versioning;
using System.Speech.AudioFormat;
using System.Speech.Synthesis;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>
/// Primeira implementacao de <see cref="ITtsEngine"/>: SAPI/System.Speech do Windows.
/// Gera WAV local; a codificacao final fica com o FFmpeg. Motores neurais futuros
/// entram pela mesma interface, sem mexer no nucleo nem na interface grafica.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsTtsEngine : ITtsEngine
{
    /// <summary>Formato fixo do WAV intermediario: permite juntar blocos sem recodificar.</summary>
    private static readonly SpeechAudioFormatInfo WaveFormat = new(
        samplesPerSecond: 22_050,
        bitsPerSample: AudioBitsPerSample.Sixteen,
        channel: AudioChannel.Mono);

    public string Name => "Windows SAPI";

    public string Version => Environment.OSVersion.Version.ToString();

    public IReadOnlyList<SpeechVoice> ListVoices()
    {
        try
        {
            using var synthesizer = new SpeechSynthesizer();
            return
            [
                .. synthesizer.GetInstalledVoices()
                    .Where(voice => voice.Enabled)
                    .Select(voice => voice.VoiceInfo)
                    .Select(info => new SpeechVoice(
                        info.Id,
                        info.Description ?? info.Name,
                        info.Culture?.Name ?? "desconhecido",
                        info.Gender.ToString()))
                    // pt-BR primeiro: e o idioma alvo da primeira versao.
                    .OrderByDescending(voice => voice.IsBrazilianPortuguese)
                    .ThenBy(voice => voice.DisplayName, StringComparer.CurrentCulture)
            ];
        }
        catch (PlatformNotSupportedException)
        {
            return [];
        }
    }

    public async Task SynthesizeToWaveAsync(
        string text,
        string voiceId,
        int rate,
        int volume,
        string outputWavePath,
        IProgress<double>? progress,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            throw new ConversionException("empty_text", "Não há texto para sintetizar.");
        }

        using var synthesizer = new SpeechSynthesizer();
        SelectVoice(synthesizer, voiceId);
        synthesizer.Rate = Math.Clamp(rate, -10, 10);
        synthesizer.Volume = Math.Clamp(volume, 0, 100);
        synthesizer.SetOutputToWaveFile(outputWavePath, WaveFormat);

        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var totalCharacters = Math.Max(1, text.Length);

        void OnProgress(object? sender, SpeakProgressEventArgs args) =>
            progress?.Report(Math.Clamp((double)args.CharacterPosition / totalCharacters, 0, 1));

        void OnCompleted(object? sender, SpeakCompletedEventArgs args)
        {
            if (args.Cancelled)
            {
                completion.TrySetCanceled(cancellationToken);
            }
            else if (args.Error is not null)
            {
                completion.TrySetException(args.Error);
            }
            else
            {
                completion.TrySetResult(true);
            }
        }

        synthesizer.SpeakProgress += OnProgress;
        synthesizer.SpeakCompleted += OnCompleted;

        // Cancelar a sintese nao derruba o aplicativo: apenas encerra a fala em andamento.
        await using var registration = cancellationToken.Register(() => synthesizer.SpeakAsyncCancelAll());

        try
        {
            synthesizer.SpeakAsync(new Prompt(text));
            await completion.Task;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception exception) when (exception is InvalidOperationException or FormatException)
        {
            throw new ConversionException("synthesis_failed", "A síntese de voz falhou.", exception);
        }
        finally
        {
            synthesizer.SpeakProgress -= OnProgress;
            synthesizer.SpeakCompleted -= OnCompleted;
            synthesizer.SetOutputToNull();
        }

        cancellationToken.ThrowIfCancellationRequested();
    }

    /// <summary>
    /// A voz e escolhida pelo ID anunciado pelo proprio motor. Nome livre enviado
    /// pela interface nunca chega ao SAPI.
    /// </summary>
    private static void SelectVoice(SpeechSynthesizer synthesizer, string voiceId)
    {
        var instaladas = synthesizer.GetInstalledVoices()
            .Where(voice => voice.Enabled)
            .Select(voice => voice.VoiceInfo)
            .ToList();

        // Sem voz escolhida, o produto decide por conta propria: portugues do Brasil se
        // houver, senao a que o Windows ja usa por padrao. Quem chama sem interface - o
        // plugin, a partir do site - nao tem como saber os IDs tecnicos das vozes.
        if (string.IsNullOrWhiteSpace(voiceId))
        {
            var padrao = instaladas.FirstOrDefault(info =>
                info.Culture.Name.StartsWith("pt-BR", StringComparison.OrdinalIgnoreCase));

            if (padrao is not null)
            {
                synthesizer.SelectVoice(padrao.Name);
            }

            return;
        }

        var match = instaladas.FirstOrDefault(info => string.Equals(info.Id, voiceId, StringComparison.Ordinal));

        if (match is null)
        {
            throw new ConversionException(
                "voice_not_available",
                "A voz selecionada não está mais instalada neste computador.");
        }

        try
        {
            synthesizer.SelectVoice(match.Name);
        }
        catch (ArgumentException exception)
        {
            throw new ConversionException("voice_not_available", "Não foi possível usar a voz selecionada.", exception);
        }
    }
}
