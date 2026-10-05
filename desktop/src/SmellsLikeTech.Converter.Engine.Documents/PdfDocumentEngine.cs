using System.Runtime.Versioning;
using SkiaSharp;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>
/// PDF para documento de texto. Quando a pagina nao tem camada de texto - o caso de
/// documento escaneado ou fotografado - ela e desenhada como imagem e passa pelo
/// reconhecimento de texto do Windows.
/// </summary>
[SupportedOSPlatform("windows10.0.19041.0")]
public sealed class PdfDocumentEngine(WindowsTextRecognizer recognizer, IConverterLog log) : IConversionEngine
{
    private const int OcrDpi = 300;

    public string Name => "PDF";

    public bool CanExecute(string operation) => operation == OperationIds.PdfToDocument;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not PdfToDocumentOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para conversão de PDF.");
        }

        options.Validate();
        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de um arquivo PDF.");

        context.Progress.Report(JobProgress.Step(JobStage.Probing));
        var pages = PdfReader.ExtractPages(inputPath, PdfToDocumentOptions.MaxPages, cancellationToken);
        if (pages.Count == 0)
        {
            throw new ConversionException("empty_pdf", "O PDF não tem páginas.");
        }

        var semTexto = pages.Where(page => !page.HasUsableText).Select(page => page.Number).ToHashSet();
        var reconhecidas = 0;

        if (semTexto.Count > 0 && options.UseOcrWhenNeeded)
        {
            pages = await RecognizeScannedPagesAsync(context, inputPath, pages, semTexto, options, cancellationToken);
            reconhecidas = semTexto.Count;
        }

        var comConteudo = pages.Where(page => page.Text.Trim().Length > 0).ToList();
        if (comConteudo.Count == 0)
        {
            throw new ConversionException(
                "no_text_in_pdf",
                options.UseOcrWhenNeeded
                    ? "Não foi possível extrair texto deste PDF, nem por reconhecimento de imagem."
                    : "Este PDF não tem camada de texto. Ative o reconhecimento de imagem (OCR) para convertê-lo.");
        }

        context.Progress.Report(new JobProgress { Percent = 0.92, Stage = JobStage.WritingOutput });

        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        var titulo = Path.GetFileNameWithoutExtension(inputPath);
        var sections = comConteudo
            .Select(page => new DocumentSection($"Página {page.Number}", page.Text))
            .ToList();

        await DocumentWriter.WriteAsync(
            output,
            options.OutputFormat,
            titulo,
            sections,
            options.IncludePageMarks,
            cancellationToken);

        context.Progress.Report(new JobProgress { Percent = 1, Stage = JobStage.WritingOutput });

        // Só contagens: o conteúdo do documento não vai para o log.
        log.Write(
            LogChannel.Jobs,
            $"{context.Job.Id:N} · PDF convertido · {comConteudo.Count} página(s) · {reconhecidas} por OCR · {options.OutputFormat}");

        var nota = reconhecidas > 0
            ? $"{comConteudo.Count} página(s) · {reconhecidas} reconhecida(s) por OCR"
            : $"{comConteudo.Count} página(s)";
        return new EngineResult([output], nota);
    }

    private async Task<IReadOnlyList<PdfPage>> RecognizeScannedPagesAsync(
        EngineContext context,
        string inputPath,
        IReadOnlyList<PdfPage> pages,
        IReadOnlySet<int> semTexto,
        PdfToDocumentOptions options,
        CancellationToken cancellationToken)
    {
        if (!WindowsTextRecognizer.IsAvailable)
        {
            throw new ConversionException(
                "ocr_not_available",
                "Este PDF é uma imagem e o Windows não tem reconhecimento de texto instalado.");
        }

        context.Progress.Report(JobProgress.Step(
            JobStage.Encoding,
            $"reconhecendo {semTexto.Count} página(s) escaneada(s)"));

        var pdf = await File.ReadAllBytesAsync(inputPath, cancellationToken);
        var atualizadas = new List<PdfPage>(pages.Count);
        var processadas = 0;

        foreach (var page in pages)
        {
            cancellationToken.ThrowIfCancellationRequested();

            if (!semTexto.Contains(page.Number))
            {
                atualizadas.Add(page);
                continue;
            }

            var imagem = PdfRasterizer.RenderPage(pdf, page.Number - 1, OcrDpi, SKEncodedImageFormat.Png, 100);
            var texto = await recognizer.ReadAsync(imagem, options.OcrLanguage, keepLineBreaks: true, cancellationToken);
            atualizadas.Add(page with { Text = texto });

            processadas++;
            context.Progress.Report(new JobProgress
            {
                Percent = 0.9 * processadas / semTexto.Count,
                Stage = JobStage.Encoding,
                Message = $"página {page.Number}"
            });
        }

        return atualizadas;
    }
}

/// <summary>Reconhecimento de texto de uma imagem.</summary>
[SupportedOSPlatform("windows10.0.19041.0")]
public sealed class OcrImageEngine(WindowsTextRecognizer recognizer, IConverterLog log) : IConversionEngine
{
    public string Name => "OCR do Windows";

    public bool CanExecute(string operation) => operation == OperationIds.OcrImageToText;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not OcrOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para reconhecimento de texto.");
        }

        options.Validate();
        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de uma imagem.");

        if (!WindowsTextRecognizer.IsAvailable)
        {
            throw new ConversionException(
                "ocr_not_available",
                "Nenhum pacote de reconhecimento de texto está instalado no Windows.");
        }

        context.Progress.Report(JobProgress.At(0.2, JobStage.Encoding));
        var texto = await recognizer.ReadFileAsync(inputPath, options.Language, options.KeepLineBreaks, cancellationToken);

        if (texto.Trim().Length == 0)
        {
            throw new ConversionException("no_text_found", "Nenhum texto foi reconhecido nesta imagem.");
        }

        context.Progress.Report(JobProgress.At(0.85, JobStage.WritingOutput));

        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        await DocumentWriter.WriteAsync(
            output,
            options.OutputFormat,
            Path.GetFileNameWithoutExtension(inputPath),
            [new DocumentSection("Texto reconhecido", texto)],
            includeSectionTitles: false,
            cancellationToken);

        context.Progress.Report(JobProgress.At(1, JobStage.WritingOutput));

        // Só a contagem de caracteres: o texto reconhecido não vai para o log.
        log.Write(LogChannel.Jobs, $"{context.Job.Id:N} · OCR concluído · {texto.Length} caractere(s)");
        return new EngineResult([output], $"{texto.Length:N0} caracteres reconhecidos");
    }
}

/// <summary>PDF para imagens, uma por pagina.</summary>
public sealed class PdfImageEngine(IConverterLog log) : IConversionEngine
{
    public string Name => "PDFium";

    public bool CanExecute(string operation) => operation == OperationIds.PdfToImage;

    public async Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not PdfToImageOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para converter o PDF em imagens.");
        }

        options.Validate();
        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de um arquivo PDF.");

        context.Progress.Report(JobProgress.Step(JobStage.Probing));
        var total = Math.Min(PdfReader.CountPages(inputPath), options.MaxPages);
        if (total == 0)
        {
            throw new ConversionException("empty_pdf", "O PDF não tem páginas.");
        }

        var pdf = await File.ReadAllBytesAsync(inputPath, cancellationToken);
        var formato = PdfRasterizer.FormatOf(options.OutputExtension);
        var arquivos = new List<string>(total);

        for (var indice = 0; indice < total; indice++)
        {
            cancellationToken.ThrowIfCancellationRequested();

            var imagem = PdfRasterizer.RenderPage(pdf, indice, options.Dpi, formato, quality: 92);
            var caminho = context.Workspace.OutputPath($"pagina_{indice + 1:000}.{options.OutputExtension}");
            await File.WriteAllBytesAsync(caminho, imagem, cancellationToken);
            arquivos.Add(caminho);

            context.Progress.Report(new JobProgress
            {
                Percent = (double)(indice + 1) / total,
                Stage = JobStage.Encoding,
                Message = $"página {indice + 1} de {total}"
            });
        }

        log.Write(LogChannel.Jobs, $"{context.Job.Id:N} · PDF em imagens · {arquivos.Count} página(s) · {options.Dpi} DPI");
        return new EngineResult(arquivos, $"{arquivos.Count} página(s) · {options.Dpi} DPI");
    }
}
