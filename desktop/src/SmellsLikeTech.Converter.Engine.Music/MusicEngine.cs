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
        operation is OperationIds.MusicSeparate or OperationIds.MusicStemsPackage or OperationIds.VideoVoice or OperationIds.AudioRestore;

    public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken) => context.Job.Options switch
    {
        MusicStemsPackageOptions package => PackageAsync(context, package, cancellationToken),
        VideoVoiceOptions voice => VoiceAsync(context, voice, cancellationToken),
        AudioRestoreOptions restore => RestoreAsync(context, restore, cancellationToken),
        _ => SeparateAsync(context, cancellationToken)
    };

    private static void Report(EngineContext context, string stage, double fraction, string message, JobStage jobStage = JobStage.SeparatingStems) =>
        context.Progress.Report(new JobProgress { Percent = MusicStemPackage.Fraction(stage, fraction), Stage = jobStage, Message = message });

    /// <summary>Garante o modelo (baixa e confere na primeira vez) e separa, com o progresso nas etapas do pacote.</summary>
    private async Task<IReadOnlyList<(MusicStemDefinition Stem, string Path)>> StemsAsync(EngineContext context, string input, MusicStemPackageModel model, CancellationToken token,
        MusicStemTreatment? treatment = null)
    {
        treatment ??= new MusicStemTreatment();
        var roformer = model.Id == "roformer";
        Report(context, "modelo", 0, roformer ? "Conferindo o RoFormer" : "Conferindo o modelo de separação", JobStage.PreparingInput);
        if (!roformer || !MusicTools.HasExperimentalRoFormer || tools.ModelInstalled(true))
            await tools.EnsureModelAsync(http(), new Progress<double>(f => Report(context, "modelo", f, "Baixando o modelo de separação (só na primeira vez)", JobStage.PreparingInput)),
                token, roformer);
        var progress = new Progress<(string Stage, double Fraction)>(p => Report(context, "separando", p.Fraction, $"{model.Label}: {p.Stage}"));
        var folder = Path.Combine(context.Workspace.WorkDirectory, "faixas");
        var stems = roformer
            ? await tools.SeparateRoFormerAsync(input, folder, PackageRate, progress, token, treatment.RefineHighs)
            : await tools.SeparateAsync(input, folder, model.Mode, PackageRate, progress, token, refined: treatment.RefineHighs);
        // A mesma qualidade do estúdio musical: ruído de cada faixa e restauração por instrumento.
        if (!treatment.ReduceNoise && treatment.Restoration <= 0) return stems;
        var tratadas = new List<(MusicStemDefinition, string)>();
        foreach (var (stem, path) in stems)
        {
            token.ThrowIfCancellationRequested();
            var atual = path;
            if (treatment.ReduceNoise)
            {
                Report(context, "mixando", 0, $"Reduzindo o ruído: {stem.Label}");
                var limpo = Path.Combine(folder, $"{stem.Id}-limpo-{Guid.NewGuid():N}.wav");
                await MusicTools.ReduceNoiseAsync(atual, limpo, PackageRate, token);
                atual = limpo;
            }
            if (treatment.Restoration > 0 && MusicNative.IsAvailable)
            {
                Report(context, "mixando", 0, $"Restaurando {stem.Label}");
                var restaurada = Path.Combine(folder, $"{stem.Id}-restaurada-{Guid.NewGuid():N}.wav");
                await MusicTools.RestoreStemAsync(atual, restaurada, PackageRate, stem.Id, treatment.Restoration, token);
                atual = restaurada;
            }
            tratadas.Add((stem, atual));
        }
        return tratadas;
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
        var stems = await StemsAsync(context, context.Job.InputPath!, model, token, request.Treatment);
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
        var stems = await StemsAsync(context, audio, model, token, request.Treatment);
        var mixed = await MixAsync(context, request, request.Groups[0], stems, FramesOf(audio), "wav-32f", tail: false, 0, 1, token);
        Report(context, "juntando", 0.2, "Juntando o áudio novo ao vídeo (a imagem é copiada)", JobStage.WritingOutput);
        var suffix = keep == "voz" ? "so-voz" : "sem-voz";
        var output = context.Workspace.OutputPath($"{Path.GetFileNameWithoutExtension(context.Job.InputPath!)}-{suffix}.{options.OutputExtension}");
        await tools.ReplaceAudioAsync(context.Job.InputPath!, mixed, output, token);
        return new EngineResult([output], $"{(keep == "voz" ? "Só a voz" : "Sem a voz")} · {model.Label} · experimental");
    }

    /// <summary>Restaurar áudio com IA (experimental): diagnóstico, plano e reparo do núcleo, no formato pedido.</summary>
    private async Task<EngineResult> RestoreAsync(EngineContext context, AudioRestoreOptions options, CancellationToken token)
    {
        var o = AudioRestoration.ReadOptions(options.Restoration);
        var output = context.Workspace.OutputPath(AudioRestoration.OutputName(context.Job.InputPath!, MusicTools.RestorationExtension(o.Format)));
        var progress = new Progress<(string Stage, double Fraction)>(p => context.Progress.Report(new JobProgress
        {
            Percent = Math.Clamp(p.Fraction, 0, 1), Stage = JobStage.Encoding, Message = p.Stage,
        }));
        var result = await tools.RestoreAudioAsync(context.Job.InputPath!, output, o, progress, token, http: http());
        var outputs = new List<string> { output };
        if (o.Report)
        {
            // Só medidas e versões: nada do áudio nem caminhos do computador.
            var report = Path.ChangeExtension(output, null) + "-relatorio.json";
            var json = new System.Text.Json.Nodes.JsonObject
            {
                ["ferramenta"] = OperationIds.AudioRestore, ["versao"] = AudioRestoration.Version, ["nucleo"] = "ABI 1.4", ["perfil"] = result.Plan.Profile,
                // No Avançado não há intensidade: valem as etapas.
                ["intensidade"] = result.Plan.Profile == "manual" ? null : o.Intensity,
                ["manterVolume"] = o.KeepVolume, ["ganhoFinalDb"] = Math.Round(result.FinalGainDb, 2),
                ["diagnostico"] = new System.Text.Json.Nodes.JsonObject(result.Diagnosis.Select(kv => KeyValuePair.Create(kv.Key, (System.Text.Json.Nodes.JsonNode?)kv.Value))),
                ["depois"] = new System.Text.Json.Nodes.JsonObject(result.After.Select(kv => KeyValuePair.Create(kv.Key, (System.Text.Json.Nodes.JsonNode?)kv.Value))),
                ["ia"] = result.Plan.Ai is { } ai ? new System.Text.Json.Nodes.JsonObject { ["modelo"] = ai.Model, ["mistura"] = ai.Mix, ["corteHz"] = ai.CutoffHz } : null,
                ["etapas"] = new System.Text.Json.Nodes.JsonArray([.. result.Plan.Steps.Select(st => (System.Text.Json.Nodes.JsonNode?)new System.Text.Json.Nodes.JsonObject { ["id"] = st.Id, ["quanto"] = st.Amount })]),
                ["avisos"] = new System.Text.Json.Nodes.JsonArray([.. result.Plan.Warnings.Select(w => (System.Text.Json.Nodes.JsonNode?)w)]),
                ["motor"] = result.Plan.Ai is null ? "núcleo nativo (slt_native.dll)" : "Apollo (ONNX, CC BY-SA 4.0) e núcleo nativo (slt_native.dll)",
            };
            await File.WriteAllTextAsync(report, json.ToJsonString(new System.Text.Json.JsonSerializerOptions { WriteIndented = true }), token);
            outputs.Add(report);
        }
        var steps = string.Join(", ", result.Plan.Steps.Select(s => AudioRestoration.StepLabel(s.Id)));
        var note = result.Plan.NoChange
            ? $"{AudioRestoration.WarningText(result.Plan.Warnings[^1])} {AudioRestoration.Summary(result.Diagnosis)}"
            : $"Restaurado: {steps} · Encontrado: {AudioRestoration.Summary(result.Diagnosis)} · Antes → depois: {AudioRestoration.BeforeAfter(result.Diagnosis, result.After)}{VolumeNote(o.KeepVolume, result.FinalGainDb)} · experimental";
        return new EngineResult(outputs, note);
    }

    private static string VolumeNote(bool keep, double gainDb) => Math.Abs(gainDb) < 0.05 ? ""
        : keep ? $" · volume igualado ao original ({gainDb:+0.0;-0.0} dB; sem passar de −1 dBFS de pico)"
        : $" · volume subiu {gainDb:0.0} dB até o pico de −1 dBFS (mais alto que o original: compare no mesmo volume)";

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
                options.SampleRate, progress, cancellationToken, options.RefineHighs == true);
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
                options.SampleRate, progress, cancellationToken, refined: options.MaskProfile == "refinada" || options.RefineHighs == true);
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
