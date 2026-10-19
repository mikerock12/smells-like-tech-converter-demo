using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Core.Options;

/// <summary>
/// Exportar um projeto do estudio. Os arquivos do projeto chegam como entradas do job
/// (<see cref="Jobs.ConversionJob.InputPath"/> e ExtraInputPaths); no projeto, cada
/// <c>arquivo</c> e o caminho completo (aplicativo) ou "#n", a n-esima entrada do job (plugin).
/// </summary>
public sealed record StudioExportOptions : JobOptions
{
    public override string Operation => OperationIds.StudioExport;

    public override string OutputExtension => StudioRules.FindOutput(Project.Output)?.Extension ?? "mp4";

    public required StudioProject Project { get; init; }

    /// <summary>Qualidade do video, 0 a 100 (vira CRF/CQ como no conversor).</summary>
    public int Quality { get; init; } = 80;

    /// <summary>Modelo Whisper da saida "texto".</summary>
    public string TranscriptionModel { get; init; } = "base";

    public string TranscriptionLanguage { get; init; } = "auto";

    public override void Validate()
    {
        var problems = StudioRules.Problems(Project);
        if (problems.Count > 0)
        {
            throw new ConversionException("invalid_project", string.Join(" ", problems));
        }

        if (Project.Clips.Count > 100 || Project.Texts.Count > 100 || Project.Audios.Count > 20)
        {
            throw new ConversionException("project_too_large", "O projeto passou do limite de 100 clipes, 100 textos e 20 áudios.");
        }

        if (Quality is < 0 or > 100)
        {
            throw new ConversionException("invalid_quality", "A qualidade vai de 0 a 100.");
        }

        if (Project.Output == "texto")
        {
            new TranscribeOptions { Model = TranscriptionModel, Language = TranscriptionLanguage }.Validate();
        }
    }

    public override string Summary()
    {
        var preset = StudioRules.FindPreset(Project.Destination);
        var output = StudioRules.FindOutput(Project.Output)?.Title ?? Project.Output;
        var destination = preset is null || preset.Id == "personalizado"
            ? $"{Project.Width}×{Project.Height}"
            : preset.Title;
        return $"Estúdio · {destination} · {output} · {StudioRules.ReadableDuration(StudioRules.TotalDuration(Project))}";
    }
}
