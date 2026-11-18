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

    /// <summary>Refinar agudos: no RoFormer, o Wiener do núcleo; no Spleeter, a máscara refinada.</summary>
    public bool? RefineHighs { get; init; }

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

/// <summary>
/// Separar um áudio em faixas e entregar um .zip com os arquivos pedidos, cada um misturado com
/// os efeitos das faixas (experimental). <see cref="Package"/> é o pedido em JSON, o mesmo do
/// site e do Android (<see cref="MusicStemPackage.ReadRequest"/>).
/// </summary>
public sealed record MusicStemsPackageOptions : JobOptions
{
    public override string Operation => OperationIds.MusicStemsPackage;

    public override string OutputExtension => "zip";

    public string Package { get; init; } = "";

    public override void Validate()
    {
        if (Package.Length > 1_000_000) throw new ConversionException("invalid_package", "O pedido de separação é grande demais.");
        MusicStemPackage.ReadRequest(Package);
    }

    public override string Summary()
    {
        var request = MusicStemPackage.ReadRequest(Package);
        return $"Separar em {request.Groups.Count} arquivo(s) · {MusicStemPackage.Model(request.Model)!.Label}";
    }
}

/// <summary>
/// Restaurar áudio com IA (experimental). <see cref="Restoration"/> é o pedido em JSON, o mesmo
/// do site e do Android (<see cref="AudioRestoration.ReadOptions"/>).
/// </summary>
public sealed record AudioRestoreOptions : JobOptions
{
    public override string Operation => OperationIds.AudioRestore;

    public override string OutputExtension => AudioRestoration.ReadOptions(Restoration).Format switch { "flac" => "flac", "mp3" => "mp3", _ => "wav" };

    public string Restoration { get; init; } = "";

    public override void Validate()
    {
        if (Restoration.Length > 100_000) throw new ConversionException("invalid_options", "O pedido de restauração é grande demais.");
        AudioRestoration.ReadOptions(Restoration);
    }

    public override string Summary()
    {
        var o = AudioRestoration.ReadOptions(Restoration);
        var profile = AudioRestoration.Profiles.First(p => p.Id == o.Profile).Label;
        return o.Profile == "manual" ? $"Restaurar · {profile}" : $"Restaurar · {profile} · {o.Intensity}%";
    }
}

/// <summary>Tirar a voz de um vídeo, ou ficar só com ela, sem recodificar a imagem (experimental).</summary>
public sealed record VideoVoiceOptions : JobOptions
{
    public override string Operation => OperationIds.VideoVoice;

    public override string OutputExtension => OutputFormat is "webm" or "mkv" or "mov" ? OutputFormat : "mp4";

    public string Package { get; init; } = "";

    /// <summary>"mp4", "webm", "mkv" ou "mov"; o vídeo é copiado, só o áudio é refeito.</summary>
    public string OutputFormat { get; init; } = "mp4";

    public override void Validate()
    {
        if (Package.Length > 1_000_000) throw new ConversionException("invalid_package", "O pedido de separação é grande demais.");
        if (OutputFormat is not ("mp4" or "webm" or "mkv" or "mov")) throw new ConversionException("invalid_format", "Formato de vídeo não suportado.");
        MusicStemPackage.ReadRequest(Package, defaultKeep: "resto");
    }

    public override string Summary() =>
        MusicStemPackage.ReadRequest(Package, defaultKeep: "resto").Keep == "voz" ? "Manter só a voz do vídeo" : "Tirar a voz do vídeo";
}
