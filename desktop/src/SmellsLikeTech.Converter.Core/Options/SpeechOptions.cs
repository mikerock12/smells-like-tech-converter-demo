using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>Speech-to-Text local (whisper.cpp).</summary>
public sealed record TranscribeOptions : JobOptions
{
    public override string Operation => OperationIds.SpeechTranscribe;
    public override string OutputExtension => OutputFormat;

    public string OutputFormat { get; init; } = "txt";

    /// <summary>"auto" ou codigo de idioma da allowlist.</summary>
    public string Language { get; init; } = "auto";

    /// <summary>tiny, base, small, medium ou large.</summary>
    public string Model { get; init; } = "base";

    public bool UseGpu { get; init; }

    /// <summary>Faixa de audio de origem quando a midia tem mais de uma.</summary>
    public int AudioStreamIndex { get; init; }

    public static IReadOnlyList<string> OutputFormats { get; } = ["txt", "srt", "vtt"];

    public static IReadOnlyList<string> Models { get; } = ["tiny", "base", "small", "medium", "large"];

    public static IReadOnlyDictionary<string, string> Languages { get; } = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["auto"] = "Detectar automaticamente",
        ["pt"] = "Português",
        ["en"] = "Inglês",
        ["es"] = "Espanhol",
        ["fr"] = "Francês",
        ["de"] = "Alemão",
        ["it"] = "Italiano",
        ["ja"] = "Japonês"
    };

    public override void Validate()
    {
        if (!OutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException("unsupported_output_format", "Saída da transcrição deve ser TXT, SRT ou VTT.");
        }

        if (!Languages.ContainsKey(Language))
        {
            throw new ConversionException("unsupported_language", "Idioma fora da lista permitida.");
        }

        if (!Models.Contains(Model))
        {
            throw new ConversionException("unsupported_model", "Modelo Whisper fora da lista permitida.");
        }

        if (AudioStreamIndex is < 0 or > 15)
        {
            throw new ConversionException("invalid_audio_stream", "Faixa de áudio inválida.");
        }
    }

    public override string Summary()
    {
        var language = Languages.TryGetValue(Language, out var display) ? display : Language;
        return $"{OutputFormat.ToUpperInvariant()} · {language} · modelo {Model}";
    }
}

/// <summary>Text-to-Speech local (Windows SAPI na primeira versao).</summary>
public sealed record SynthesizeOptions : JobOptions
{
    public override string Operation => OperationIds.SpeechSynthesize;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "mp3";

    /// <summary>ID tecnico da voz anunciada pelo engine. Nunca um nome livre digitado pelo usuario.</summary>
    public string VoiceId { get; init; } = string.Empty;

    /// <summary>-10 a 10.</summary>
    public int Rate { get; init; }

    /// <summary>0 a 100.</summary>
    public int Volume { get; init; } = 100;

    public int? AudioBitrateKbps { get; init; }
    public int? SampleRateHz { get; init; }
    public int? Channels { get; init; }

    /// <summary>Limite defensivo por job; o valor efetivo vem das configuracoes.</summary>
    public const int MaxCharacters = 200_000;

    public override void Validate()
    {
        AudioAllowlist.Validate(OutputFormat, AudioBitrateKbps, SampleRateHz, Channels);

        // Voz vazia usa Dora no Kokoro; o plugin anuncia também os IDs técnicos.
        if (VoiceId.Length > 256)
        {
            throw new ConversionException("unsupported_voice", "Identificador de voz inválido.");
        }

        if (Rate is < -10 or > 10)
        {
            throw new ConversionException("unsupported_rate", "Velocidade deve estar entre -10 e 10.");
        }

        if (Volume is < 0 or > 100)
        {
            throw new ConversionException("unsupported_volume", "Volume deve estar entre 0 e 100.");
        }
    }

    public override string Summary()
    {
        // Nunca inclui o texto sintetizado.
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        if (Rate != 0)
        {
            parts.Add($"velocidade {Rate:+0;-0}");
        }

        if (Volume != 100)
        {
            parts.Add($"volume {Volume}%");
        }

        return string.Join(" · ", parts);
    }
}
