using System.Net.Http;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>
/// A separacao de faixas pela fila: e o que o plugin oferece ao site ("music.separate").
/// Baixa o modelo na primeira vez (conferido pelo SHA-256), separa e devolve um WAV por faixa.
/// </summary>
public sealed class MusicEngine(MusicTools tools, Func<HttpClient> http) : IConversionEngine
{
    public string Name => "Criação musical";

    public bool CanExecute(string operation) => operation == OperationIds.MusicSeparate;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not MusicSeparateOptions options || context.Job.InputPath is null)
            throw new ConversionException("operation_not_available", "Opções inválidas para a separação.");
        options.Validate();
        var progress = new Progress<(string Stage, double Fraction)>(p => context.Progress.Report(new JobProgress
        {
            Percent = 0.1 + p.Fraction * 0.88, Stage = JobStage.SeparatingStems, Message = p.Stage,
        }));
        IReadOnlyList<(MusicStemDefinition Stem, string Path)> stems;
        if (options.QualityProfile == "maximum")
        {
            stems = await tools.SeparateRoFormerAsync(context.Job.InputPath, context.Workspace.WorkDirectory,
                options.SampleRate, progress, cancellationToken);
        }
        else
        {
            if (!tools.ModelReady)
                context.Progress.Report(new JobProgress { Percent = 0, Stage = JobStage.PreparingInput, Message = "Baixando o modelo de separação (78 MB, só na primeira vez)" });
            await tools.EnsureModelAsync(http(), new Progress<double>(f => context.Progress.Report(new JobProgress
            {
                Percent = f * 0.1, Stage = JobStage.PreparingInput, Message = "Baixando o modelo de separação",
            })), cancellationToken);
            stems = await tools.SeparateAsync(context.Job.InputPath, context.Workspace.WorkDirectory, options.Mode,
                options.SampleRate, progress, cancellationToken, refined: options.MaskProfile == "refinada");
        }
        var outputs = new List<string>();
        for (var i = 0; i < stems.Count; i++)
        {
            var target = context.Workspace.OutputPath($"{i:00}-{stems[i].Stem.Id}.wav");
            File.Move(stems[i].Path, target, overwrite: true);
            outputs.Add(target);
        }

        return new EngineResult(outputs, options.Summary());
    }
}
