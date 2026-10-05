using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.Speech;

/// <summary>
/// Speech-to-Text local. O FFmpeg extrai e normaliza o áudio em PCM mono 16 kHz e
/// o whisper.cpp embutido no FFmpeg produz TXT, SRT ou VTT.
/// </summary>
public sealed class WhisperTranscriptionEngine(
    Func<FfmpegOptions> optionsAccessor,
    ProcessRunner processRunner,
    FfprobeInspector inspector,
    WhisperModelCatalog catalog,
    Func<int> maximumMinutesAccessor,
    IConverterLog log) : IConversionEngine
{
    public string Name => "whisper.cpp";

    public bool CanExecute(string operation) => operation == OperationIds.SpeechTranscribe;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not TranscribeOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para transcrição.");
        }

        options.Validate();

        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "A transcrição precisa de um arquivo de áudio ou vídeo.");

        // O modelo pedido pode nao estar baixado. Cair no melhor instalado e o que o
        // produto promete, mas isso so acontecia na tela do aplicativo, que resolvia antes
        // de montar o job. Quem chega pelo plugin nao passa por aquela tela e recebia uma
        // recusa mesmo tendo outro modelo pronto para usar.
        var escolhido = catalog.ResolvePreferred(options.Model);
        var model = catalog.Find(escolhido)
            ?? throw new ConversionException("unsupported_model", "Modelo Whisper desconhecido.");

        if (!model.IsInstalled)
        {
            throw new ConversionException(
                "model_not_installed",
                "Nenhum modelo de transcrição está instalado. Baixe um em Configurações → Modelos.");
        }

        context.Progress.Report(JobProgress.Step(JobStage.Probing));
        var info = context.Job.Input ?? await inspector.InspectAsync(inputPath, cancellationToken);

        if (info.AudioStreams.Count == 0)
        {
            throw new ConversionException("audio_stream_not_found", "Este arquivo não possui faixa de áudio para transcrever.");
        }

        var maximumMinutes = Math.Clamp(maximumMinutesAccessor(), 1, 1440);
        if (info.Duration is { } duration && duration > TimeSpan.FromMinutes(maximumMinutes))
        {
            throw new ConversionException(
                "media_too_long",
                $"A mídia tem {MediaInfo.FormatDuration(duration)} e o limite atual é de {maximumMinutes} minutos.");
        }

        var engine = optionsAccessor();
        var streamIndex = info.AudioStreams[
            Math.Min(options.AudioStreamIndex, info.AudioStreams.Count - 1)].Index;

        // 1) Áudio previsível para o motor: PCM mono 16 kHz.
        var preparedWave = context.Workspace.WorkPath("speech-input.wav");
        context.Progress.Report(JobProgress.Step(JobStage.PreprocessingAudio));
        await RunAsync(
            context,
            FfmpegCommandPlanner.PrepareSpeechAudio(engine, inputPath, preparedWave, streamIndex),
            info.Duration,
            JobStage.PreprocessingAudio,
            0,
            0.2,
            cancellationToken);

        if (!File.Exists(preparedWave) || new FileInfo(preparedWave).Length < 1024)
        {
            throw new ConversionException("invalid_audio", "Não foi possível preparar o áudio para transcrição.");
        }

        // 2) Transcrição. O motor sempre produz SRT: TXT e VTT saem dele já validado,
        //    garantindo timestamps conferidos mesmo quando a saída final é texto puro.
        const string EngineFormat = "srt";
        var intermediateSrt = context.Workspace.WorkPath("transcript.srt");

        context.Progress.Report(JobProgress.Step(JobStage.Transcribing));
        await RunAsync(
            context,
            FfmpegCommandPlanner.Transcribe(engine, preparedWave, intermediateSrt, model.Path, options, EngineFormat),
            info.Duration,
            JobStage.Transcribing,
            0.2,
            0.97,
            cancellationToken);

        if (!File.Exists(intermediateSrt))
        {
            throw new ConversionException("transcription_failed", "O motor não produziu nenhuma legenda.");
        }

        // O motor numera a partir de zero, estende o último trecho além do fim da mídia
        // e às vezes inventa marcadores de ruído no silêncio. Isso é acertado aqui, antes
        // de qualquer saída ser gerada.
        var blocks = await SubtitleConverter.NormalizeSrtAsync(intermediateSrt, info.Duration, cancellationToken);
        if (blocks == 0)
        {
            throw new ConversionException(
                "no_speech_detected",
                "Nenhuma fala foi reconhecida neste áudio.");
        }

        await SubtitleConverter.ValidateSrtAsync(intermediateSrt, info.Duration, cancellationToken);

        context.Progress.Report(JobProgress.Step(JobStage.WritingOutput));
        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");

        switch (options.OutputFormat)
        {
            case "srt":
                File.Move(intermediateSrt, output, overwrite: true);
                break;
            case "vtt":
                await SubtitleConverter.ConvertSrtToVttAsync(intermediateSrt, output, cancellationToken);
                break;
            default:
                await SubtitleConverter.WriteTextFromSrtAsync(intermediateSrt, output, cancellationToken);
                break;
        }

        // O conteúdo transcrito nunca entra no log: apenas a contagem de blocos.
        log.Write(LogChannel.Speech, $"{context.Job.Id:N} · transcrição concluída · {blocks} bloco(s) · modelo {options.Model}");
        return new EngineResult([output], $"{blocks} trecho(s) · modelo {model.DisplayName}");
    }

    private async Task RunAsync(
        EngineContext context,
        CommandSpec command,
        TimeSpan? totalDuration,
        JobStage stage,
        double progressFloor,
        double progressCeiling,
        CancellationToken cancellationToken)
    {
        var reader = new FfmpegProgressReader(
            totalDuration,
            (percent, speed) => context.Progress.Report(new JobProgress
            {
                Percent = percent is null ? null : progressFloor + ((progressCeiling - progressFloor) * percent.Value),
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
        log.Error("transcription_failed", $"{context.Job.Id:N} · saída {result.ExitCode} · {detail}");
        throw new ConversionException("transcription_failed", $"Falha na transcrição ({result.ExitCode}). Detalhe: {detail}");
    }
}
