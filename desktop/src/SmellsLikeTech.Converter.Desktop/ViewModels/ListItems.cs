using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Presets;

namespace SmellsLikeTech.Converter.Desktop.ViewModels;

/// <summary>Item generico de ComboBox: rotulo visivel + valor tipado.</summary>
public sealed class Choice<T>(string label, T value)
{
    public string Label { get; } = label;

    public T Value { get; } = value;

    public override string ToString() => Label;
}

public sealed class OperationItemViewModel(OperationDescriptor descriptor)
{
    public OperationDescriptor Descriptor { get; } = descriptor;

    public string Id => Descriptor.Id;

    public string Title => Descriptor.Title;

    public string Description => Descriptor.Description;

    public string Glyph => Descriptor.Id switch
    {
        OperationIds.ImageConvert => "",
        OperationIds.VideoConvert => "",
        OperationIds.VideoExtractAudio => "",
        OperationIds.VideoToGif => "",
        OperationIds.VideoToFrames => "",
        OperationIds.AudioConvert => "",
        OperationIds.SpeechTranscribe => "",
        OperationIds.SpeechSynthesize => "",
        _ => ""
    };

    public bool IsAvailable => Descriptor.Available;

    public double ContentOpacity => Descriptor.Available ? 1.0 : 0.45;

    public string BadgeText => Descriptor.Available ? string.Empty : Descriptor.Milestone ?? "em breve";

    public bool HasBadge => !Descriptor.Available;
}

public sealed class PresetItemViewModel(ConversionPreset preset)
{
    public ConversionPreset Preset { get; } = preset;

    public string Name => Preset.Name;

    public string Description => Preset.Description;
}

public sealed class HistoryItemViewModel(JobHistoryEntry entry)
{
    public JobHistoryEntry Entry { get; } = entry;

    public string DisplayName => Entry.DisplayName;

    public string Detail =>
        $"{Entry.Operation} · {Entry.OptionsSummary} · {MediaInfo.FormatBytes(Entry.InputBytes)} → {MediaInfo.FormatBytes(Entry.OutputBytes)}";

    public string When => Entry.CreatedAt.ToLocalTime().ToString("dd/MM/yyyy HH:mm");

    public string StatusText => Entry.Status switch
    {
        JobStatus.Completed => $"Concluído em {Entry.ElapsedSeconds:0.0}s",
        JobStatus.Failed => $"Falhou · {Entry.ErrorMessage ?? Entry.ErrorCode ?? "erro"}",
        JobStatus.Cancelled => "Cancelado",
        _ => Entry.Status.Display()
    };

    public string StatusGlyph => Entry.Status switch
    {
        JobStatus.Completed => "",
        JobStatus.Failed => "",
        _ => ""
    };

    public bool HasOutput => !string.IsNullOrWhiteSpace(Entry.OutputPath) && File.Exists(Entry.OutputPath);

    public string? OutputPath => Entry.OutputPath;
}

public sealed class SpeechModelItemViewModel(SpeechModel model, string notes)
{
    public SpeechModel Model { get; } = model;

    public string Id => Model.Id;

    public string DisplayName => Model.DisplayName;

    public string Notes { get; } = notes;

    public string SizeText => Model.IsInstalled
        ? $"Instalado · {MediaInfo.FormatBytes(Model.SizeBytes)}"
        : $"Não instalado · ~{MediaInfo.FormatBytes(Model.SizeBytes)}";

    public bool IsInstalled => Model.IsInstalled;

    public bool CanInstall => !Model.IsInstalled;
}
