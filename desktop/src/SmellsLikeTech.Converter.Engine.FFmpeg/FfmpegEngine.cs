using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>
/// Motor de video e audio. Cobre converter, comprimir, mudar resolucao/proporcao,
/// video para audio, GIF e extracao de quadros.
/// </summary>
public sealed class FfmpegEngine(
    Func<FfmpegOptions> optionsAccessor,
    ProcessRunner processRunner,
    FfprobeInspector inspector,
    IConverterLog log) : IConversionEngine
{
    public string Name => "FFmpeg";

    public bool CanExecute(string operation) => operation is
        OperationIds.VideoConvert
        or OperationIds.VideoExtractAudio
        or OperationIds.VideoToGif
        or OperationIds.VideoToFrames
        or OperationIds.AudioConvert;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        var job = context.Job;
        var inputPath = job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de um arquivo de entrada.");

        context.Progress.Report(JobProgress.Step(JobStage.Probing));
        var info = job.Input ?? await inspector.InspectAsync(inputPath, cancellationToken);
        var engine = optionsAccessor();

        return job.Options switch
        {
            VideoConvertOptions options => await ConvertVideoAsync(context, engine, inputPath, info, options, cancellationToken),
            ExtractAudioOptions options => await ExtractAudioAsync(context, engine, inputPath, info, options, cancellationToken),
            AudioConvertOptions options => await ConvertAudioAsync(context, engine, inputPath, info, options, cancellationToken),
            VideoToGifOptions options => await ToGifAsync(context, engine, inputPath, info, options, cancellationToken),
            VideoToFramesOptions options => await ToFramesAsync(context, engine, inputPath, info, options, cancellationToken),
            _ => throw new ConversionException("operation_not_available", "Operação não suportada pelo motor FFmpeg.")
        };
    }

    private async Task<EngineResult> ConvertVideoAsync(
        EngineContext context,
        FfmpegOptions engine,
        string inputPath,
        MediaInfo info,
        VideoConvertOptions options,
        CancellationToken cancellationToken)
    {
        if (!info.HasVideo)
        {
            throw new ConversionException("video_stream_not_found", "O arquivo não contém uma faixa de vídeo.");
        }

        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        var command = FfmpegCommandPlanner.ConvertVideo(engine, inputPath, output, options, info);
        var total = EffectiveDuration(info, options.TrimStart, options.TrimDuration, options.Speed);

        await RunAsync(context, command, total, JobStage.Encoding, cancellationToken);
        return new EngineResult([output], DescribeResult(info, output, options.Summary()));
    }

    private async Task<EngineResult> ExtractAudioAsync(
        EngineContext context,
        FfmpegOptions engine,
        string inputPath,
        MediaInfo info,
        ExtractAudioOptions options,
        CancellationToken cancellationToken)
    {
        var absoluteIndex = ResolveAudioStream(info, options.AudioStreamIndex);
        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        var command = FfmpegCommandPlanner.ExtractAudio(engine, inputPath, output, options, absoluteIndex);
        var total = EffectiveDuration(info, options.TrimStart, options.TrimDuration, 1.0);

        await RunAsync(context, command, total, JobStage.Encoding, cancellationToken);
        return new EngineResult([output], DescribeResult(info, output, options.Summary()));
    }

    private async Task<EngineResult> ConvertAudioAsync(
        EngineContext context,
        FfmpegOptions engine,
        string inputPath,
        MediaInfo info,
        AudioConvertOptions options,
        CancellationToken cancellationToken)
    {
        if (!info.HasAudio)
        {
            throw new ConversionException("audio_stream_not_found", "O arquivo não contém uma faixa de áudio.");
        }

        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        var command = FfmpegCommandPlanner.ConvertAudio(engine, inputPath, output, options);
        var total = EffectiveDuration(info, options.TrimStart, options.TrimDuration, 1.0);

        await RunAsync(context, command, total, JobStage.Encoding, cancellationToken);
        return new EngineResult([output], DescribeResult(info, output, options.Summary()));
    }

    private async Task<EngineResult> ToGifAsync(
        EngineContext context,
        FfmpegOptions engine,
        string inputPath,
        MediaInfo info,
        VideoToGifOptions options,
        CancellationToken cancellationToken)
    {
        if (!info.HasVideo)
        {
            throw new ConversionException("video_stream_not_found", "O arquivo não contém uma faixa de vídeo.");
        }

        var output = context.Workspace.OutputPath("result.gif");
        var command = FfmpegCommandPlanner.ToGif(engine, inputPath, output, options);
        var total = EffectiveDuration(info, options.TrimStart, options.TrimDuration, 1.0);

        await RunAsync(context, command, total, JobStage.Encoding, cancellationToken);
        return new EngineResult([output], DescribeResult(info, output, options.Summary()));
    }

    private async Task<EngineResult> ToFramesAsync(
        EngineContext context,
        FfmpegOptions engine,
        string inputPath,
        MediaInfo info,
        VideoToFramesOptions options,
        CancellationToken cancellationToken)
    {
        if (!info.HasVideo)
        {
            throw new ConversionException("video_stream_not_found", "O arquivo não contém uma faixa de vídeo.");
        }

        var pattern = context.Workspace.OutputPath($"frame_%05d.{options.OutputExtension}");
        var command = FfmpegCommandPlanner.ToFrames(engine, inputPath, pattern, options);
        var total = EffectiveDuration(info, options.TrimStart, options.TrimDuration, 1.0);

        await RunAsync(context, command, total, JobStage.Encoding, cancellationToken);

        var frames = Directory
            .EnumerateFiles(context.Workspace.OutputDirectory, $"frame_*.{options.OutputExtension}")
            .Order(StringComparer.Ordinal)
            .ToArray();

        if (frames.Length == 0)
        {
            throw new ConversionException("empty_output", "Nenhum quadro foi extraído do vídeo.");
        }

        return new EngineResult(frames, $"{frames.Length} quadro(s) extraído(s)");
    }

    private static int ResolveAudioStream(MediaInfo info, int requestedIndex)
    {
        if (info.AudioStreams.Count == 0)
        {
            throw new ConversionException("audio_stream_not_found", "Este arquivo não possui faixa de áudio.");
        }

        if (requestedIndex >= info.AudioStreams.Count)
        {
            throw new ConversionException(
                "audio_stream_not_found",
                $"O arquivo tem {info.AudioStreams.Count} faixa(s) de áudio; a faixa {requestedIndex + 1} não existe.");
        }

        return info.AudioStreams[requestedIndex].Index;
    }

    private static TimeSpan? EffectiveDuration(MediaInfo info, TimeSpan? trimStart, TimeSpan? trimDuration, double speed)
    {
        var total = info.Duration;
        if (total is null)
        {
            return null;
        }

        var remaining = total.Value - (trimStart ?? TimeSpan.Zero);
        if (trimDuration is not null && trimDuration.Value < remaining)
        {
            remaining = trimDuration.Value;
        }

        if (remaining <= TimeSpan.Zero)
        {
            return null;
        }

        // A barra segue o tempo de saida: acelerar o video encurta o total processado.
        return speed > 0 ? remaining / speed : remaining;
    }

    private async Task RunAsync(
        EngineContext context,
        CommandSpec command,
        TimeSpan? totalDuration,
        JobStage stage,
        CancellationToken cancellationToken)
    {
        context.Progress.Report(JobProgress.Step(stage));
        log.Write(
            LogChannel.Ffmpeg,
            $"{context.Job.Id:N} · {SafeDiagnostics.FromEngine(command.Describe(), context.Workspace.JobDirectory, context.Job.InputPath ?? string.Empty)}");

        var reader = new FfmpegProgressReader(
            totalDuration,
            (percent, speed) => context.Progress.Report(new JobProgress
            {
                Percent = percent,
                Stage = stage,
                SpeedFactor = speed
            }));

        var result = await processRunner.RunAsync(
            command,
            workingDirectory: context.Workspace.WorkDirectory,
            temporaryDirectory: context.Workspace.TempDirectory,
            onStandardOutputLine: reader.OnLine,
            cancellationToken: cancellationToken);

        if (result.ExitCode == 0)
        {
            return;
        }

        var detail = SafeDiagnostics.FromEngine(
            result.StandardError,
            context.Workspace.JobDirectory,
            context.Job.InputPath ?? string.Empty);
        log.Error("ffmpeg_failed", $"{context.Job.Id:N} · saída {result.ExitCode} · {detail}");
        throw new ConversionException(
            "ffmpeg_failed",
            $"O FFmpeg terminou com erro ({result.ExitCode}). Detalhe: {detail}");
    }

    private static string DescribeResult(MediaInfo info, string outputPath, string summary)
    {
        var outputSize = new FileInfo(outputPath).Length;
        return info.SizeBytes > 0
            ? $"{summary} · {MediaInfo.FormatBytes(info.SizeBytes)} → {MediaInfo.FormatBytes(outputSize)}"
            : summary;
    }
}
