using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>Localizacao dos binarios e preferencia de aceleracao resolvidas uma vez.</summary>
public sealed record FfmpegOptions
{
    public required string FfmpegPath { get; init; }
    public required string FfprobePath { get; init; }

    /// <summary>Encoders de hardware realmente presentes nesta build/maquina.</summary>
    public IReadOnlyList<string> AvailableEncoders { get; init; } = [];

    public HardwareAcceleration Preference { get; init; } = HardwareAcceleration.Automatic;

    public static FfmpegOptions Resolve(
        string ffmpegPath,
        string ffprobePath,
        IReadOnlyList<string> availableEncoders,
        HardwareAcceleration preference) => new()
        {
            FfmpegPath = ExecutableGuard.Validate(ffmpegPath, "FFmpeg"),
            FfprobePath = ExecutableGuard.Validate(ffprobePath, "FFprobe"),
            AvailableEncoders = availableEncoders,
            Preference = preference
        };

    public bool Supports(string encoder) => AvailableEncoders.Contains(encoder, StringComparer.Ordinal);
}
