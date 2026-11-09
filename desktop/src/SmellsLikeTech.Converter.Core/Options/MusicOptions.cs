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

    public override void Validate()
    {
        if (MusicSeparation.Mode(Mode) is null) throw new ConversionException("invalid_mode", "Modo de separação desconhecido: use 2 ou 4 faixas.");
        if (!MusicRules.SampleRates.Contains(SampleRate)) throw new ConversionException("invalid_rate", "Taxa de amostragem não suportada (44,1, 48 ou 96 kHz).");
    }

    public override string Summary() => $"Separar em {MusicSeparation.Mode(Mode)?.Stems.Count ?? 4} faixas";
}
