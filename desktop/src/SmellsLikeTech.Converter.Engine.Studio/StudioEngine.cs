using ImageMagick;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>
/// Exporta um projeto do estudio. Fotos passam pelo Magick (orientacao do celular, HEIC,
/// AVIF), textos viram PNG, e uma unica passada do FFmpeg monta tudo pela receita comum
/// (StudioRecipe). Na saida "texto", o audio final vai para o motor de transcricao.
/// </summary>
public sealed class StudioEngine(
    Func<FfmpegOptions> optionsAccessor,
    ProcessRunner processRunner,
    FfprobeInspector inspector,
    IConversionEngine transcriber,
    IConverterLog log,
    string fontsDirectory) : IConversionEngine
{
    private const int MaxImageSide = 4096;

    public string Name => "Estúdio";

    public bool CanExecute(string operation) => operation == OperationIds.StudioExport;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not StudioExportOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para o estúdio.");
        }

        options.Validate();
        // Baixa/média/alta: o quadro, o fps, o CRF e o esforço do x264 saem da qualidade.
        var level = options.QualityLevel is null ? null : StudioQuality.Find(options.QualityLevel);
        var project = level?.Apply(options.Project) ?? options.Project;
        var job = context.Job;
        var inputs = new List<string>();
        if (job.InputPath is not null)
        {
            inputs.Add(job.InputPath);
        }

        inputs.AddRange(job.ExtraInputPaths);

        var extension = StudioRules.FindOutput(project.Output)?.Extension ?? "mp4";
        var output = project.Output == "texto"
            ? context.Workspace.WorkPath("estudio-audio.wav")
            : context.Workspace.OutputPath($"result.{extension}");
        var ceiling = project.Output == "texto" ? 0.25 : 1.0;
        await RenderAsync(
            project,
            inputs,
            context.Workspace.WorkDirectory,
            context.Workspace.TempDirectory,
            output,
            level?.VideoQuality ?? options.Quality,
            context.Progress.Report,
            ceiling,
            $"{job.Id:N}",
            cancellationToken,
            level: level);

        if (project.Output != "texto")
        {
            return new EngineResult([output], options.Summary());
        }

        // O audio final vai ao Whisper do proprio aplicativo, no mesmo workspace.
        var transcription = new ConversionJob
        {
            Options = new TranscribeOptions
            {
                Model = options.TranscriptionModel,
                Language = options.TranscriptionLanguage,
                OutputFormat = options.TranscriptionFormat
            },
            InputPath = output,
            OutputDirectory = job.OutputDirectory,
            OutputBaseName = job.OutputBaseName
        };
        var progress = new MappedProgress(context.Progress, 0.25, 1.0);
        var result = await transcriber.ExecuteAsync(new EngineContext(transcription, context.Workspace, progress), cancellationToken);
        return result with { Note = $"{options.Summary()} · {result.Note}" };
    }

    public async Task<IReadOnlyList<StudioText>> GenerateCaptionsAsync(StudioProject project, IReadOnlyList<string> inputs,
        string temporaryRoot, string model, IProgress<JobProgress> progress, CancellationToken token) {
        using var workspace=SmellsLikeTech.Converter.Infrastructure.Storage.JobWorkspace.Create(temporaryRoot,Guid.NewGuid());
        try {
            var job=new ConversionJob {InputPath=inputs[0],ExtraInputPaths=inputs.Skip(1).ToList(),
                OutputDirectory=workspace.OutputDirectory,OutputBaseName="legendas",
                Options=new StudioExportOptions {Project=project with {Output="texto"},TranscriptionModel=model,TranscriptionFormat="srt"}};
            var result=await ExecuteAsync(new EngineContext(job,workspace,progress),token);
            return StudioCaptions.ParseSrt(await File.ReadAllTextAsync(result.OutputFiles[0],token),StudioRules.TotalDuration(project));
        } finally { workspace.TryDelete(); }
    }

    /// <summary>Repassa o progresso de uma etapa dentro de uma faixa da barra, na mesma thread.</summary>
    private sealed class MappedProgress(IProgress<JobProgress> target, double floor, double ceiling) : IProgress<JobProgress>
    {
        public void Report(JobProgress value) => target.Report(value with
        {
            Percent = value.Percent is null ? null : floor + ((ceiling - floor) * value.Percent.Value)
        });
    }

    /// <summary>
    /// "#n" e a n-esima entrada do job (o plugin recebe os arquivos em ordem); um caminho tem
    /// de ser uma das entradas. O projeto nunca le arquivo que nao veio no job.
    /// </summary>
    public static string Resolve(IReadOnlyList<string> inputs, string reference)
    {
        if (reference.StartsWith('#') && int.TryParse(reference.AsSpan(1), out var index) && index >= 0 && index < inputs.Count)
        {
            return inputs[index];
        }

        if (Path.IsPathFullyQualified(reference))
        {
            var full = Path.GetFullPath(reference);
            var found = inputs.FirstOrDefault(p => string.Equals(Path.GetFullPath(p), full, StringComparison.OrdinalIgnoreCase));
            if (found is not null)
            {
                return found;
            }
        }

        throw new ConversionException("missing_input", $"O arquivo “{Path.GetFileName(reference)}” do projeto não veio junto.");
    }

    /// <summary>Quantos quadros de vídeo vão passar pelo recorte do fundo nesta montagem.</summary>
    private static double RemovalFrames(StudioProject project, bool singleFrame)
    {
        var frames = project.Clips.Where(c => !c.IsImage && c.Transform?.RemoveBackground == true)
            .Sum(c => Math.Ceiling(StudioRules.SourceDuration(c) * project.Fps));
        frames += project.Layers.Where(l => l.Visible && l.Kind != "imagem" && l.Transform?.RemoveBackground == true)
            .Sum(l => Math.Ceiling((singleFrame ? .1 : l.Offset + l.End - l.Start) * project.Fps));
        return frames;
    }

    /// <summary>Foto do celular em pe, HEIC ou AVIF: o Magick endireita e entrega um PNG que o FFmpeg le.</summary>
    private static string PrepareImage(string path, string output)
    {
        try
        {
            using var image = new MagickImage(path);
            image.AutoOrient();
            if (image.Width > MaxImageSide || image.Height > MaxImageSide)
            {
                image.Resize(new MagickGeometry(MaxImageSide, MaxImageSide));
            }

            image.Write(output, MagickFormat.Png32);
            return output;
        }
        catch (MagickException)
        {
            throw new ConversionException("invalid_image", $"Não foi possível abrir a imagem “{Path.GetFileName(path)}”.");
        }
    }

    /// <summary>
    /// Monta o projeto em <paramref name="output"/>, sem fila e sem transcrever: a exportacao
    /// usa no workspace do job, e o aplicativo usa direto para a previa (um quadro pequeno ou um
    /// video de baixa resolucao). Os arquivos do projeto tem de estar em <paramref name="inputs"/>.
    /// </summary>
    public async Task RenderAsync(
        StudioProject project,
        IReadOnlyList<string> inputs,
        string workDirectory,
        string tempDirectory,
        string output,
        int quality,
        Action<JobProgress> progress,
        double ceiling = 1.0,
        string label = "previa",
        CancellationToken cancellationToken = default,
        string? cacheDirectory = null,
        StudioQuality? level = null)
    {
        Directory.CreateDirectory(workDirectory);
        var started = System.Diagnostics.Stopwatch.StartNew();
        double removalSeconds = 0, removalMasks = 0;
        var maskEvery = level?.MaskEvery(project.Fps) ?? 1;
        progress(JobProgress.Step(JobStage.Probing));
        // Um PNG precisa só do instante escolhido, inclusive ao segmentar um vídeo longo.
        var singleFrame = project.Output == "imagem" && project.Clips.Count > 0 && !StudioRules.HasTransitions(project) &&
            StudioRules.TotalDuration(project)<=(StudioRules.Segments(project).LastOrDefault()?.End??0) &&
            !project.Clips.Any(c=>c.Transform?.Keyframes.Count>0) && !project.Layers.Any(c=>c.Transform?.Keyframes.Count>0) && !project.Texts.Any(c=>c.Transform?.Keyframes.Count>0);
        if (singleFrame)
        {
            var time = Math.Clamp(project.Frame, 0, Math.Max(0, StudioRules.TotalDuration(project) - 1.0 / project.Fps));
            var at = StudioRules.ClipAt(project, time)!.Value;
            var clip = at.Clip;
            var layersAtFrame = new List<StudioLayer>();
            foreach (var layer in StudioRules.LayersAt(project, time))
            {
                var offset = time - layer.Start + layer.Offset;
                if (layer.Kind != "imagem")
                {
                    var info = await inspector.InspectAsync(Resolve(inputs, layer.File), cancellationToken);
                    if (info.Duration?.TotalSeconds is > 0 and var duration)
                        offset = layer.Repeat ? offset % duration : Math.Min(offset, Math.Max(0, duration - 1.0 / project.Fps));
                }
                layersAtFrame.Add(layer with { Start = 0, End = .1, Offset = offset, Repeat = false });
            }
            project = project with
            {
                Frame = 0,
                Clips = [clip.IsImage ? clip with { Duration = .1, Transition = null } : clip with { Start = at.TimeInFile, End = at.TimeInFile + .1, Speed = 1, Transition = null }],
                Layers = layersAtFrame,
                Texts = project.Texts.Where(t => time >= t.Start && ((t.Id.StartsWith("legenda-") || t.Id.StartsWith("contagem-")) ? time < t.End : time <= t.End)).Select(t => t with { Start = 0, End = .1 }).ToList(),
                Audios = []
            };
        }
        var visual = project.Output is not ("audio" or "texto");
        // O recorte do fundo vem antes da composição e era a etapa "parada" da barra: agora
        // cada quadro recortado conta, num trecho da barra proporcional ao custo dele.
        var removalFrames = visual ? RemovalFrames(project, singleFrame) : 0;
        var outputFrames = Math.Max(1, (project.Output == "imagem" ? .1 : StudioRules.TotalDuration(project)) * project.Fps);
        // A fatia da barra de cada etapa sai da velocidade medida nesta máquina.
        var removalCost = removalFrames * ((Speed.SecondsPerMask / maskEvery) + .01);
        var compositionCost = outputFrames * (StudioSpeed.SecondsPerFrame + ((project.Width * (double)project.Height / 1e6) * Speed.SecondsPerMegapixelFrame * StudioEstimate.CompositionWeight(project) * (level?.EncodeCost ?? 1)));
        var removalShare = removalFrames == 0 ? 0 : removalCost / (removalCost + compositionCost);
        var removedFrames = 0;
        async Task<string> RemoveVideoAsync(string path, MediaInfo info, double start, double duration, string name)
        {
            // Nunca maior que o quadro do projeto: o modelo enxerga 320×320 de qualquer jeito.
            var sourceWidth = info.Width ?? project.Width;
            var sourceHeight = info.Height ?? project.Height;
            var scale = Math.Min(1.0, Math.Min((double)project.Width / sourceWidth, (double)project.Height / sourceHeight));
            var width = StudioRules.Even(sourceWidth * scale);
            var height = StudioRules.Even(sourceHeight * scale);
            var cached = cacheDirectory is null ? null : RemovalCache.PathFor(cacheDirectory, path, start, duration, width, height, project.Fps, maskEvery);
            var prepared = cached ?? Path.Combine(workDirectory, name);
            var before = removedFrames;
            if (cached is null || !File.Exists(cached))
            {
                var removing = System.Diagnostics.Stopwatch.StartNew();
                var partial = prepared + ".parcial.mkv";
                using var cleanup = cancellationToken.Register(() => { try { File.Delete(partial); } catch (IOException) { } });
                await BackgroundVideo.RemoveAsync(optionsAccessor().FfmpegPath, path, partial, width, height, project.Fps, start, duration, cancellationToken, maskEvery: maskEvery, onFrame:
                    count =>
                    {
                        removedFrames = before + count;
                        progress(new JobProgress
                        {
                            Stage = JobStage.RemovingBackground,
                            Percent = Math.Min(removalShare, removalShare * removedFrames / Math.Max(1.0, removalFrames)) * ceiling
                        });
                    });
                File.Move(partial, prepared, overwrite: true);
                removalSeconds += removing.Elapsed.TotalSeconds;
                removalMasks += Math.Ceiling(Math.Ceiling(duration * project.Fps) / maskEvery);
                if (cacheDirectory is not null) RemovalCache.Trim(cacheDirectory, prepared);
            }

            removedFrames = before + (int)Math.Ceiling(duration * project.Fps);
            return prepared;
        }

        var preparedClips = project.Clips.ToArray();
        var clips = new List<StudioClipInput>();
        for (var i = 0; i < project.Clips.Count; i++)
        {
            var clip = project.Clips[i];
            var path = Resolve(inputs, clip.File);
            if (clip.IsImage)
            {
                var png = PrepareImage(path, Path.Combine(workDirectory, $"imagem-{i}.png"));
                if (visual && clip.Transform?.RemoveBackground == true)
                    await BackgroundRemover.RemoveImageAsync(png, png, cancellationToken);
                clips.Add(new StudioClipInput(png, false));
                continue;
            }

            var info = await inspector.InspectAsync(path, cancellationToken);
            if (!info.HasVideo)
            {
                throw new ConversionException("video_stream_not_found", $"“{Path.GetFileName(path)}” não tem imagem de vídeo.");
            }

            if (visual && clip.Transform?.RemoveBackground == true)
            {
                path = await RemoveVideoAsync(path, info, clip.Start ?? 0, StudioRules.SourceDuration(clip), $"recorte-clipe-{i}.mkv");
                preparedClips[i] = clip with { Start = 0, End = StudioRules.SourceDuration(clip) };
            }
            clips.Add(new StudioClipInput(path, info.HasAudio));
        }

        var audios = project.Audios.Select(audio => Resolve(inputs, audio.File)).ToList();

        progress(JobProgress.Step(JobStage.PreparingInput));
        var texts = new List<string>();
        if (project.Output is not ("audio" or "texto"))
        {
            var renderer = new StudioTextRenderer(fontsDirectory);
            for (var j = 0; j < project.Texts.Count; j++)
            {
                var png = Path.Combine(workDirectory, $"texto-{j}.png");
                renderer.Render(project.Texts[j], project.Width, project.Height, png);
                texts.Add(png);
            }
        }

        var layers = new List<string>();
        var preparedLayers = project.Layers.ToArray();
        if (project.Output is not ("audio" or "texto"))
        {
            for (var i = 0; i < project.Layers.Count; i++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var layer = project.Layers[i];
                if (!layer.Visible) { layers.Add(""); continue; }
                var path = Resolve(inputs, layer.File);
                if (layer.Kind == "imagem")
                {
                    path = PrepareImage(path, Path.Combine(workDirectory, $"camada-{i}.png"));
                    if (layer.Transform?.RemoveBackground == true) await BackgroundRemover.RemoveImageAsync(path, path, cancellationToken);
                }
                else if (layer.Transform?.RemoveBackground == true)
                {
                    var info = await inspector.InspectAsync(path, cancellationToken);
                    var start = singleFrame ? layer.Offset : 0;
                    var duration = singleFrame ? .1 : Math.Min(info.Duration?.TotalSeconds ?? layer.Offset + layer.End - layer.Start, layer.Offset + layer.End - layer.Start);
                    path = await RemoveVideoAsync(path, info, start, duration, $"recorte-camada-{i}.mkv");
                    preparedLayers[i] = layer with { Kind = "video", Offset = singleFrame ? 0 : layer.Offset };
                }
                layers.Add(path);
            }
        }
        var preparedProject = project with { Clips = preparedClips, Layers = preparedLayers };
        var composing = started.Elapsed;
        var command = StudioRecipe.ToCommand(optionsAccessor(), preparedProject, new StudioInputs(clips, texts, audios, layers), output, quality, optimize: true, preset: level?.Preset);
        // Perspectiva em várias camadas supera o limite de 32 KiB do CreateProcess.
        var arguments = command.Arguments.ToList();
        var filterIndex = arguments.IndexOf("-filter_complex");
        if (filterIndex >= 0)
        {
            var filterPath = Path.Combine(workDirectory, "composicao.fffilter");
            await File.WriteAllTextAsync(filterPath, arguments[filterIndex + 1], new System.Text.UTF8Encoding(false), cancellationToken);
            arguments[filterIndex] = "-/filter_complex";
            arguments[filterIndex + 1] = filterPath;
            command = command with { Arguments = arguments };
        }
        var total = project.Output == "imagem" ? Math.Max(0.1, project.Frame) : StudioRules.TotalDuration(project);
        progress(JobProgress.Step(JobStage.Encoding));
        log.Write(LogChannel.Ffmpeg, $"{label} · estúdio · {SafeDiagnostics.FromEngine(command.Describe(), workDirectory, string.Empty)}");
        var reader = new FfmpegProgressReader(TimeSpan.FromSeconds(total), (percent, speed) => progress(new JobProgress
        {
            Percent = percent is null ? null : (removalShare + ((1 - removalShare) * percent.Value)) * ceiling,
            Stage = JobStage.Encoding,
            SpeedFactor = speed
        }));

        var result = await processRunner.RunAsync(
            command,
            workingDirectory: workDirectory,
            temporaryDirectory: tempDirectory,
            onStandardOutputLine: reader.OnLine,
            cancellationToken: cancellationToken);

        if (result.ExitCode != 0)
        {
            var detail = SafeDiagnostics.FromEngine(result.StandardError, workDirectory, string.Empty);
            log.Error("ffmpeg_failed", $"{label} · estúdio · saída {result.ExitCode} · {detail}");
            throw new ConversionException("ffmpeg_failed", $"O FFmpeg terminou com erro ({result.ExitCode}). Detalhe: {detail}");
        }

        if (!File.Exists(output) || new FileInfo(output).Length == 0)
        {
            throw new ConversionException("empty_output", "O estúdio não produziu nenhum arquivo.");
        }

        // Aprende a velocidade desta máquina para o tempo estimado das próximas montagens.
        var composedFrames = project.Output is "video" or "gif" ? total * project.Fps : 0;
        var megapixels = project.Width * (double)project.Height / 1e6;
        var composed = (started.Elapsed - composing).TotalSeconds;
        Speed = Speed.Learn(composedFrames >= 30 ? ((composed / composedFrames) - StudioSpeed.SecondsPerFrame) / (megapixels * StudioEstimate.CompositionWeight(project) * (level?.EncodeCost ?? 1)) : null,
            removalMasks >= 10 ? removalSeconds / removalMasks : null);
    }

    /// <summary>A velocidade medida nas últimas montagens (alimenta o tempo estimado).</summary>
    public StudioSpeed Speed { get; set; } = StudioSpeed.Default;
}
