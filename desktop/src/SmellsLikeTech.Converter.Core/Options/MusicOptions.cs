using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>
/// Separar um audio em faixas pela IA local (Spleeter 4stems). Sai um WAV por faixa do modo,
/// na taxa pedida (a do projeto do site), na mesma ordem de <see cref="MusicSeparation.Modes"/>.
/// </summary>
public sealed record MusicSeparateOptions : JobOptions
{
    public override string Operation => OperationIds.MusicSeparate;

    public override string OutputExtension => "wav";

    /// <summary>"2" (voz e instrumental) ou "4" (voz, bateria, baixo e outros).</summary>
    public string Mode { get; init; } = "4";

    /// <summary>Taxa de amostragem das faixas devolvidas.</summary>
    public int SampleRate { get; init; } = MusicRules.DefaultSampleRate;

    public string? QualityProfile { get; init; }
    public string? MaskProfile { get; init; }

    public override void Validate()
    {
        if (QualityProfile == "maximum" ? Mode != "6" : MusicSeparation.Mode(Mode) is null)
            throw new ConversionException("invalid_mode", "O modo escolhido é incompatível com o perfil de separação.");
        if (!MusicRules.SampleRates.Contains(SampleRate)) throw new ConversionException("invalid_rate", "Taxa de amostragem não suportada (44,1, 48 ou 96 kHz).");
        if (QualityProfile is not (null or "legacy" or "maximum"))
            throw new ConversionException("invalid_profile", "O perfil de qualidade solicitado ainda não está disponível.");
        if (MaskProfile is not (null or "classica" or "refinada"))
            throw new ConversionException("invalid_mask", "Perfil de máscara desconhecido.");
        if (QualityProfile == "maximum" && MaskProfile is not null)
            throw new ConversionException("invalid_mask", "O RoFormer usa máscaras próprias.");
    }

    public override string Summary() => $"Separar em {Mode} faixas";
}
