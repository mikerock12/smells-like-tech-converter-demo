using System.IO.Compression;
using System.Net.Http;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Music;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Engine.Music;

/// <summary>
/// A separacao de faixas pela fila. "music.separate" e o que o plugin oferece ao estudio do site
/// (um WAV por faixa). "music.stemsPackage" e "video.voice" sao as ferramentas experimentais do
/// conversor: separam, misturam cada arquivo pedido com os efeitos de cada faixa (o mesmo
/// mixer do estudio, com VST3) e entregam um .zip ou o video com o audio novo.
/// </summary>
public sealed class MusicEngine(MusicTools tools, Func<HttpClient> http) : IConversionEngine
{
    /// <summary>A taxa em que as faixas separadas sao misturadas: a do modelo, sem reamostrar duas vezes.</summary>
    private const int PackageRate = 44100;

    public string Name => "Criação musical";

    public bool CanExecute(string operation) =>
        operation is OperationIds.MusicSeparate or OperationIds.MusicStemsPackage or OperationIds.VideoVoice;

    public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken) => context.Job.Options switch
    {
        MusicStemsPackageOptions package => PackageAsync(context, package, cancellationToken),
        VideoVoiceOptions voice => VoiceAsync(context, voice, cancellationToken),
        _ => SeparateAsync(context, cancellationToken)
    };

    private static void Report(EngineContext context, string stage, double fraction, string message, JobStage jobStage = JobStage.SeparatingStems) =>
        context.Progress.Report(new JobProgress { Percent = MusicStemPackage.Fraction(stage, fraction), Stage = jobStage, Message = message });

    /// <summary>Garante o modelo (baixa e confere na primeira vez) e separa, com o progresso nas etapas do pacote.</summary>
    private async Task<IReadOnlyList<(MusicStemDefinition Stem, string Path)>> StemsAsync(EngineContext context, string input, MusicStemPackageModel model, CancellationToken token)
    {
        var roformer = model.Id == "roformer";
        Report(context, "modelo", 0, roformer ? "Conferindo o RoFormer" : "Conferindo o modelo de separação", JobStage.PreparingInput);
        if (!roformer || !MusicTools.HasExperimentalRoFormer || tools.ModelInstalled(true))
            await tools.EnsureModelAsync(http(), new Progress<double>(f => Report(context, "modelo", f, "Baixando o modelo de separação (só na primeira vez)", JobStage.PreparingInput)),
                token, roformer);
        var progress = new Progress<(string Stage, double Fraction)>(p => Report(context, "separando", p.Fraction, $"{model.Label}: {p.Stage}"));
        var folder = Path.Combine(context.Workspace.WorkDirectory, "faixas");
        return roformer
            ? await tools.SeparateRoFormerAsync(input, folder, PackageRate, progress, token)
            : await tools.SeparateAsync(input, folder, model.Mode, PackageRate, progress, token);
    }

    /// <summary>Mistura um arquivo do pacote: as faixas do grupo com os ajustes da configuracao, pelo exportador do estudio.</summary>
    private async Task<string> MixAsync(EngineContext context, MusicStemPackageRequest request, MusicStemGroup group,
        IReadOnlyList<(MusicStemDefinition Stem, string Path)> stems, long frames, string format, bool tail, int index, int total, CancellationToken token)
    {
        var configuration = request.Configuration.Clone();
        configuration.SampleRate = PackageRate;
        configuration.Title = $"arquivo-{index + 1}";
        var project = MusicStemPackage.FileProject(configuration, group, frames);
        var paths = stems.ToDictionary(s => s.Stem.Id, s => s.Path);
        var folder = Path.Combine(context.Workspace.WorkDirectory, "mistura", index.ToString(System.Globalization.CultureInfo.InvariantCulture));
        var progress = new Progress<(string Stage, double Fraction)>(p => Report(context, "mixando", (index + p.Fraction) / total, $"Mixando “{group.Name}” ({index + 1} de {total})"));
        var exported = await tools.ExportAsync(project, media => paths[media.Id], new MusicExportRequest(format, request.Master, false, false, tail), folder, progress, token);
        return exported.Single().Path;
    }

    private static long FramesOf(string wav)
    {
        using var source = new MappedWavSource(wav);
        return source.Frames;
    }

    private async Task<EngineResult> PackageAsync(EngineContext context, MusicStemsPackageOptions options, CancellationToken token)
    {
        var request = MusicStemPackage.ReadRequest(options.Package);
        var model = MusicStemPackage.Model(request.Model)!;
        var format = MusicMastering.Formats.FirstOrDefault(f => f.Id == request.Format) ?? MusicMastering.Formats[0];
        Report(context, "lendo", 0, "Lendo o áudio", JobStage.PreparingInput);
        var stems = await StemsAsync(context, context.Job.InputPath!, model, token);
        var frames = FramesOf(stems[0].Path);
        var title = Path.GetFileNameWithoutExtension(context.Job.InputPath!);
        var names = MusicStemPackage.FileNames(title, request.Groups, format.Extension);
        var zip = context.Workspace.OutputPath($"{title}-faixas.zip");
        await using (var stream = File.Create(zip))
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Create))
        {
            for (var i = 0; i < request.Groups.Count; i++)
            {
                token.ThrowIfCancellationRequested();
                var mixed = await MixAsync(context, request, request.Groups[i], stems, frames, format.Id, tail: true, i, request.Groups.Count, token);
                Report(context, "juntando", (i + 1.0) / request.Groups.Count, "Juntando no .zip", JobStage.WritingOutput);
                // Áudio já vem comprimido (MP3, FLAC) ou é WAV grande: guardar sem recomprimir é mais rápido.
                await archive.CreateEntryFromFileAsync(mixed, names[i], CompressionLevel.NoCompression, token);
            }
        }

        return new EngineResult([zip], $"{request.Groups.Count} arquivo(s) · {model.Label} · experimental");
    }

    private async Task<EngineResult> VoiceAsync(EngineContext context, VideoVoiceOptions options, CancellationToken token)
    {
        var request = MusicStemPackage.ReadRequest(options.Package, defaultKeep: "resto");
        var keep = request.Keep ?? "resto";
        request.Groups = [MusicStemPackage.VideoGroup(request.Model, keep)];
        var model = MusicStemPackage.Model(request.Model)!;
        Report(context, "lendo", 0, "Tirando o áudio do vídeo", JobStage.PreparingInput);
        var audio = Path.Combine(context.Workspace.WorkDirectory, "audio-do-video.wav");
        await tools.ImportAsync(context.Job.InputPath!, audio, PackageRate, token);
        var stems = await StemsAsync(context, audio, model, token);
        var mixed = await MixAsync(context, request, request.Groups[0], stems, FramesOf(audio), "wav-32f", tail: false, 0, 1, token);
        Report(context, "juntando", 0.2, "Juntando o áudio novo ao vídeo (a imagem é copiada)", JobStage.WritingOutput);
        var suffix = keep == "voz" ? "so-voz" : "sem-voz";
        var output = context.Workspace.OutputPath($"{Path.GetFileNameWithoutExtension(context.Job.InputPath!)}-{suffix}.{options.OutputExtension}");
        await tools.ReplaceAudioAsync(context.Job.InputPath!, mixed, output, token);
        return new EngineResult([output], $"{(keep == "voz" ? "Só a voz" : "Sem a voz")} · {model.Label} · experimental");
    }

    private async Task<EngineResult> SeparateAsync(EngineContext context, CancellationToken cancellationToken)
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
            if (!MusicTools.HasExperimentalRoFormer || tools.ModelInstalled(true))
                await tools.EnsureModelAsync(http(), new Progress<double>(f => context.Progress.Report(new JobProgress
                {
                    Percent = f * 0.1, Stage = JobStage.PreparingInput, Message = "Baixando o RoFormer",
                })), cancellationToken, roformer: true);
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
