using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>Converter, comprimir, mudar resolucao/proporcao/FPS, girar, cortar.</summary>
public sealed record VideoConvertOptions : JobOptions
{
    public override string Operation => OperationIds.VideoConvert;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "mp4";
    public VideoCodecChoice VideoCodec { get; init; } = VideoCodecChoice.Automatic;
    public HardwareAcceleration Acceleration { get; init; } = HardwareAcceleration.Automatic;

    /// <summary>0 = menor arquivo, 100 = melhor qualidade. Mapeado para CRF/CQ pelo engine.</summary>
    public int Quality { get; init; } = 65;

    /// <summary>Altura alvo (2160, 1440, 1080, 720, 480, 360) ou nulo para manter.</summary>
    public int? TargetHeight { get; init; }

    public int? CustomWidth { get; init; }
    public int? CustomHeight { get; init; }

    public AspectRatioMode AspectRatio { get; init; } = AspectRatioMode.Original;
    public FitMode Fit { get; init; } = FitMode.Crop;

    /// <summary>Cor de fundo em #RRGGBB para o modo Ajustar.</summary>
    public string BackgroundColor { get; init; } = "#000000";

    public int? FrameRate { get; init; }
    public int? VideoBitrateKbps { get; init; }

    public RotationDegrees Rotation { get; init; } = RotationDegrees.None;
    public bool FlipHorizontal { get; init; }
    public bool FlipVertical { get; init; }

    /// <summary>0.25x a 4x.</summary>
    public double Speed { get; init; } = 1.0;

    public AudioTrackAction Audio { get; init; } = AudioTrackAction.Keep;
    public int? AudioBitrateKbps { get; init; }

    public TimeSpan? TrimStart { get; init; }
    public TimeSpan? TrimDuration { get; init; }

    public static IReadOnlyList<int> Heights { get; } = [2160, 1440, 1080, 720, 480, 360];
    public static IReadOnlyList<int> FrameRates { get; } = [24, 25, 30, 50, 60];
    public static IReadOnlyList<int> VideoBitrates { get; } = [500, 1000, 2000, 4000, 6000, 8000, 12000, 20000, 40000];

    public override void Validate()
    {
        if (!FormatCatalog.VideoOutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException("unsupported_output_format", $"Container de vídeo não suportado: '{OutputFormat}'.");
        }

        if (Quality is < 0 or > 100)
        {
            throw new ConversionException("invalid_quality", "Qualidade deve estar entre 0 e 100.");
        }

        if (TargetHeight is not null && !Heights.Contains(TargetHeight.Value))
        {
            throw new ConversionException("invalid_resolution", "Resolução fora da lista permitida.");
        }

        ValidateCustomSize();

        if (FrameRate is not null && !FrameRates.Contains(FrameRate.Value))
        {
            throw new ConversionException("invalid_framerate", "FPS fora da lista permitida.");
        }

        if (VideoBitrateKbps is not null && !VideoBitrates.Contains(VideoBitrateKbps.Value))
        {
            throw new ConversionException("invalid_video_bitrate", "Bitrate de vídeo fora da lista permitida.");
        }

        if (Speed is < 0.25 or > 4)
        {
            throw new ConversionException("invalid_speed", "Velocidade deve estar entre 0,25x e 4x.");
        }

        ColorAllowlist.Validate(BackgroundColor);
        AudioAllowlist.ValidateTrim(TrimStart, TrimDuration);

        if (Audio == AudioTrackAction.Reencode && AudioBitrateKbps is not null
            && !AudioAllowlist.Bitrates.Contains(AudioBitrateKbps.Value))
        {
            throw new ConversionException("invalid_audio_bitrate", "Bitrate de áudio fora da lista permitida.");
        }

        if (VideoCodec == VideoCodecChoice.CopyStream
            && (TargetHeight is not null || CustomWidth is not null || AspectRatio != AspectRatioMode.Original
                || Rotation != RotationDegrees.None || FlipHorizontal || FlipVertical
                || FrameRate is not null || Math.Abs(Speed - 1.0) > 0.001))
        {
            throw new ConversionException(
                "invalid_combination",
                "Copiar o stream de vídeo não permite alterar imagem, resolução, FPS ou velocidade.");
        }

        if (OutputFormat == "webm" && VideoCodec is VideoCodecChoice.H264 or VideoCodecChoice.H265)
        {
            throw new ConversionException("invalid_combination", "WebM não aceita H.264/H.265. Use VP9 ou AV1.");
        }
    }

    private void ValidateCustomSize()
    {
        if (CustomWidth is null && CustomHeight is null)
        {
            return;
        }

        if (CustomWidth is null || CustomHeight is null)
        {
            throw new ConversionException("invalid_resolution", "Informe largura e altura personalizadas.");
        }

        if (CustomWidth is < 16 or > 7680 || CustomHeight is < 16 or > 7680)
        {
            throw new ConversionException("invalid_resolution", "Resolução personalizada deve ficar entre 16 e 7680 pixels.");
        }
    }

    public override string Summary()
    {
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        if (TargetHeight is not null)
        {
            parts.Add($"{TargetHeight}p");
        }
        else if (CustomWidth is not null && CustomHeight is not null)
        {
            parts.Add($"{CustomWidth}x{CustomHeight}");
        }

        if (AspectRatio != AspectRatioMode.Original)
        {
            parts.Add(AspectRatio.Display());
        }

        if (Audio == AudioTrackAction.Remove)
        {
            parts.Add("sem áudio");
        }

        return string.Join(" · ", parts);
    }
}

/// <summary>Video para audio - funcao obrigatoria do produto.</summary>
public sealed record ExtractAudioOptions : JobOptions
{
    public override string Operation => OperationIds.VideoExtractAudio;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "mp3";

    /// <summary>Indice da faixa de audio dentro do arquivo (0 = primeira).</summary>
    public int AudioStreamIndex { get; init; }

    public int? AudioBitrateKbps { get; init; }
    public int? SampleRateHz { get; init; }
    public int? Channels { get; init; }
    public bool Normalize { get; init; }
    public TimeSpan? TrimStart { get; init; }
    public TimeSpan? TrimDuration { get; init; }

    public override void Validate()
    {
        AudioAllowlist.Validate(OutputFormat, AudioBitrateKbps, SampleRateHz, Channels);
        AudioAllowlist.ValidateTrim(TrimStart, TrimDuration);

        if (AudioStreamIndex is < 0 or > 15)
        {
            throw new ConversionException("invalid_audio_stream", "Faixa de áudio inválida.");
        }
    }

    public override string Summary()
    {
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        if (AudioBitrateKbps is not null)
        {
            parts.Add($"{AudioBitrateKbps} kbps");
        }

        if (Channels == 1)
        {
            parts.Add("mono");
        }

        if (Normalize)
        {
            parts.Add("normalizado");
        }

        return string.Join(" · ", parts);
    }
}

public sealed record VideoToGifOptions : JobOptions
{
    public override string Operation => OperationIds.VideoToGif;
    public override string OutputExtension => "gif";

    public int FrameRate { get; init; } = 12;
    public int Width { get; init; } = 480;
    public TimeSpan? TrimStart { get; init; }
    public TimeSpan? TrimDuration { get; init; } = TimeSpan.FromSeconds(10);

    public static IReadOnlyList<int> FrameRates { get; } = [8, 10, 12, 15, 20, 24];
    public static IReadOnlyList<int> Widths { get; } = [240, 320, 480, 640, 800];

    public override void Validate()
    {
        if (!FrameRates.Contains(FrameRate))
        {
            throw new ConversionException("invalid_framerate", "FPS do GIF fora da lista permitida.");
        }

        if (!Widths.Contains(Width))
        {
            throw new ConversionException("invalid_resolution", "Largura do GIF fora da lista permitida.");
        }

        AudioAllowlist.ValidateTrim(TrimStart, TrimDuration);

        if (TrimDuration is not null && TrimDuration.Value > TimeSpan.FromMinutes(2))
        {
            throw new ConversionException("invalid_trim", "GIF é limitado a 2 minutos.");
        }
    }

    public override string Summary() => $"GIF · {Width}px · {FrameRate} fps";
}

public sealed record VideoToFramesOptions : JobOptions
{
    public override string Operation => OperationIds.VideoToFrames;
    public override string OutputExtension => ImageFormat;

    public string ImageFormat { get; init; } = "png";

    /// <summary>Quantos quadros extrair por segundo de video.</summary>
    public double FramesPerSecond { get; init; } = 1;

    public int MaxFrames { get; init; } = 300;
    public TimeSpan? TrimStart { get; init; }
    public TimeSpan? TrimDuration { get; init; }

    public static IReadOnlyList<double> Rates { get; } = [0.1, 0.25, 0.5, 1, 2, 5, 10];

    public override void Validate()
    {
        if (ImageFormat is not ("png" or "jpg" or "webp"))
        {
            throw new ConversionException("unsupported_output_format", "Frames devem ser PNG, JPG ou WEBP.");
        }

        if (!Rates.Contains(FramesPerSecond))
        {
            throw new ConversionException("invalid_framerate", "Taxa de extração fora da lista permitida.");
        }

        if (MaxFrames is < 1 or > 5000)
        {
            throw new ConversionException("invalid_frame_limit", "Limite de quadros deve ficar entre 1 e 5000.");
        }

        AudioAllowlist.ValidateTrim(TrimStart, TrimDuration);
    }

    public override string Summary() => $"{ImageFormat.ToUpperInvariant()} · {FramesPerSecond} q/s · até {MaxFrames}";
}

public sealed record AudioConvertOptions : JobOptions
{
    public override string Operation => OperationIds.AudioConvert;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "mp3";
    public int? AudioBitrateKbps { get; init; }
    public int? SampleRateHz { get; init; }
    public int? Channels { get; init; }
    public bool Normalize { get; init; }
    public TimeSpan? TrimStart { get; init; }
    public TimeSpan? TrimDuration { get; init; }

    public override void Validate()
    {
        AudioAllowlist.Validate(OutputFormat, AudioBitrateKbps, SampleRateHz, Channels);
        AudioAllowlist.ValidateTrim(TrimStart, TrimDuration);
    }

    public override string Summary()
    {
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        if (AudioBitrateKbps is not null)
        {
            parts.Add($"{AudioBitrateKbps} kbps");
        }

        if (Normalize)
        {
            parts.Add("normalizado");
        }

        return string.Join(" · ", parts);
    }
}

public static class ColorAllowlist
{
    public static void Validate(string color)
    {
        if (color.Length != 7
            || color[0] != '#'
            || !color.Skip(1).All(character => Uri.IsHexDigit(character)))
        {
            throw new ConversionException("invalid_color", "Cor deve estar no formato #RRGGBB.");
        }
    }
}
