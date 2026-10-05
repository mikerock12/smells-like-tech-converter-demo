using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>
/// Allowlist unica de parametros de audio. Nenhum valor digitado pelo usuario chega
/// a linha de comando de um engine sem passar por aqui.
/// </summary>
public static class AudioAllowlist
{
    public static IReadOnlyList<int> Bitrates { get; } = [64, 96, 128, 160, 192, 256, 320];

    public static IReadOnlyList<int> SampleRates { get; } = [8_000, 16_000, 22_050, 24_000, 44_100, 48_000];

    public static bool IsLossless(string format) => format is "wav" or "flac";

    public static void ValidateFormat(string format)
    {
        if (!FormatCatalog.AudioOutputFormats.Contains(format))
        {
            throw new ConversionException("unsupported_output_format", $"Formato de áudio não suportado: '{format}'.");
        }
    }

    public static void Validate(string format, int? bitrateKbps, int? sampleRateHz, int? channels)
    {
        ValidateFormat(format);

        if (bitrateKbps is not null)
        {
            if (!Bitrates.Contains(bitrateKbps.Value))
            {
                throw new ConversionException("invalid_audio_bitrate", "Bitrate de áudio fora da lista permitida.");
            }

            if (IsLossless(format))
            {
                throw new ConversionException("invalid_combination", "Bitrate não se aplica a formatos sem perda (WAV/FLAC).");
            }
        }

        if (sampleRateHz is not null && !SampleRates.Contains(sampleRateHz.Value))
        {
            throw new ConversionException("invalid_sample_rate", "Sample rate fora da lista permitida.");
        }

        if (channels is not null and not (1 or 2))
        {
            throw new ConversionException("invalid_channels", "Canais deve ser 1 (mono) ou 2 (estéreo).");
        }
    }

    public static void ValidateTrim(TimeSpan? start, TimeSpan? duration)
    {
        if (start is not null && (start.Value < TimeSpan.Zero || start.Value > TimeSpan.FromHours(24)))
        {
            throw new ConversionException("invalid_trim", "Início do corte inválido.");
        }

        if (duration is not null && (duration.Value <= TimeSpan.Zero || duration.Value > TimeSpan.FromHours(24)))
        {
            throw new ConversionException("invalid_trim", "Duração do corte inválida.");
        }
    }
}
