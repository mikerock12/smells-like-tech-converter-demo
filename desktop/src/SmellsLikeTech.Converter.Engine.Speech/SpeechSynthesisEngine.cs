using System.Text;
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
/// Text-to-Speech. O texto e conteudo temporario privado: fica na pasta do job,
/// nunca aparece em log, historico ou banco.
/// </summary>
public sealed class SpeechSynthesisEngine(
    ITtsEngine ttsEngine,
    Func<FfmpegOptions> optionsAccessor,
    ProcessRunner processRunner,
    Func<int> maximumCharactersAccessor,
    IConverterLog log,
    IDocumentTextReader? documentReader = null) : IConversionEngine
{
    private const long MaxTextFileBytes = 8L * 1024 * 1024;

    public string Name => ttsEngine.Name;

    public bool CanExecute(string operation) => operation == OperationIds.SpeechSynthesize;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not SynthesizeOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para síntese de voz.");
        }

        options.Validate();

        context.Progress.Report(JobProgress.Step(JobStage.PreparingInput));
        var text = await ReadTextAsync(context, cancellationToken);

        var limit = Math.Clamp(maximumCharactersAccessor(), 1, SynthesizeOptions.MaxCharacters);
        if (text.Length > limit)
        {
            throw new ConversionException(
                "text_too_long",
                $"O texto tem {text.Length:N0} caracteres e o limite atual é {limit:N0}.");
        }

        var chunks = TextChunker.Split(text);
        if (chunks.Count == 0)
        {
            throw new ConversionException("empty_text", "Não há texto para sintetizar.");
        }

        // Quando o texto veio de PDF, imagem ou documento, a leitura ocupou os primeiros 30%.
        var inicio = context.Job.InputText is null && context.Job.InputPath is { } entrada
            && FormatCatalog.KindOfExtension(Path.GetExtension(entrada).TrimStart('.')) != MediaKind.Text ? 0.3 : 0;
        var waves = new List<string>(chunks.Count);
        context.Progress.Report(JobProgress.Step(JobStage.Synthesizing));

        for (var index = 0; index < chunks.Count; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var wavePath = context.Workspace.WorkPath($"chunk_{index:000}.wav");
            var chunkIndex = index;
            var chunkProgress = new Progress<double>(value => context.Progress.Report(new JobProgress
            {
                Percent = inicio + (0.9 - inicio) * (chunkIndex + Math.Clamp(value, 0, 1)) / chunks.Count,
                Stage = JobStage.Synthesizing,
                Message = chunks.Count > 1 ? $"bloco {chunkIndex + 1} de {chunks.Count}" : null
            }));

            await ttsEngine.SynthesizeToWaveAsync(
                chunks[index],
                options.VoiceId,
                options.Rate,
                options.Volume,
                wavePath,
                chunkProgress,
                cancellationToken);

            if (!File.Exists(wavePath) || new FileInfo(wavePath).Length <= 44)
            {
                throw new ConversionException("synthesis_failed", "O motor de voz não produziu áudio.");
            }

            waves.Add(wavePath);
        }

        var engine = optionsAccessor();
        var merged = waves.Count == 1
            ? waves[0]
            : await ConcatenateAsync(context, engine, waves, cancellationToken);

        context.Progress.Report(new JobProgress { Percent = 0.92, Stage = JobStage.Encoding });
        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        await RunAsync(
            context,
            FfmpegCommandPlanner.EncodeSynthesizedAudio(engine, merged, output, options),
            cancellationToken);

        context.Progress.Report(new JobProgress { Percent = 1, Stage = JobStage.WritingOutput });

        // Somente metricas: quantidade de caracteres e blocos, nunca o texto.
        log.Write(
            LogChannel.Speech,
            $"{context.Job.Id:N} · síntese concluída · {text.Length} caractere(s) · {chunks.Count} bloco(s) · {options.OutputFormat}");

        return new EngineResult(
            [output],
            $"{text.Length:N0} caracteres · {chunks.Count} bloco(s) · {options.OutputFormat.ToUpperInvariant()}");
    }

    private async Task<string> ReadTextAsync(EngineContext context, CancellationToken cancellationToken)
    {
        var job = context.Job;
        if (job.InputText is { } inline)
        {
            return inline.Trim();
        }

        if (job.InputPath is not { } path)
        {
            throw new ConversionException("missing_input", "Informe um texto ou selecione um arquivo TXT.");
        }

        var file = new FileInfo(path);
        if (!file.Exists)
        {
            throw new ConversionException("input_not_found", "O arquivo de texto não foi encontrado.");
        }

        if (FormatCatalog.KindOfExtension(file.Extension.TrimStart('.')) == MediaKind.Text && file.Length > MaxTextFileBytes)
        {
            throw new ConversionException("text_too_long", "O arquivo de texto é grande demais para a síntese.");
        }

        // PDF, imagem e documento: o texto vem do leitor de documentos (com OCR quando precisa).
        var extension = file.Extension.TrimStart('.');
        var kind = FormatCatalog.KindOfExtension(extension);
        if (kind != MediaKind.Text)
        {
            if (documentReader is null || !documentReader.CanRead(kind, extension))
            {
                throw new ConversionException("unsupported_document", $"A narração não lê arquivos .{extension.ToLowerInvariant()}.");
            }

            var reading = new Progress<double>(value => context.Progress.Report(new JobProgress
            {
                Percent = 0.3 * Math.Clamp(value, 0, 1),
                Stage = JobStage.PreparingInput,
                Message = "lendo o texto do arquivo"
            }));
            var extracted = (await documentReader.ReadAsync(path, kind, reading, cancellationToken)).Trim();
            if (extracted.Length == 0)
            {
                throw new ConversionException("empty_text", "Não foi encontrado texto para narrar neste arquivo.");
            }

            return extracted;
        }

        var content = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
        return content.Trim();
    }

    private async Task<string> ConcatenateAsync(
        EngineContext context,
        FfmpegOptions engine,
        IReadOnlyList<string> waves,
        CancellationToken cancellationToken)
    {
        var listPath = context.Workspace.WorkPath("chunks.txt");
        var lines = waves.Select(path => $"file '{Path.GetFileName(path)}'");
        await File.WriteAllLinesAsync(listPath, lines, new UTF8Encoding(false), cancellationToken);

        var merged = context.Workspace.WorkPath("merged.wav");
        await RunAsync(context, FfmpegCommandPlanner.ConcatWaves(engine, listPath, merged), cancellationToken);
        return merged;
    }

    private async Task RunAsync(EngineContext context, CommandSpec command, CancellationToken cancellationToken)
    {
        var result = await processRunner.RunAsync(
            command,
            workingDirectory: context.Workspace.WorkDirectory,
            temporaryDirectory: context.Workspace.TempDirectory,
            cancellationToken: cancellationToken);

        if (result.ExitCode == 0)
        {
            return;
        }

        var detail = SafeDiagnostics.FromEngine(result.StandardError, context.Workspace.JobDirectory);
        log.Error("tts_encode_failed", $"{context.Job.Id:N} · saída {result.ExitCode} · {detail}");
        throw new ConversionException("tts_encode_failed", $"Falha ao gerar o áudio final ({result.ExitCode}).");
    }
}
