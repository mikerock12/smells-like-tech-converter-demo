using SkiaSharp;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Core;
using UglyToad.PdfPig.Writer;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>
/// Montagem de PDF em .NET puro (PdfPig): juntar, dividir e imagens para PDF.
///
/// Nao renderiza nada: copia paginas inteiras entre documentos, o que e rapido e
/// preserva o conteudo original. Imagens que nao sao JPEG nem PNG passam pelo Skia
/// (ja embutido para o PDFium) e viram JPEG antes de entrar.
/// </summary>
public sealed class PdfAssemblyEngine(IConverterLog log) : IConversionEngine
{
    private const double A4Width = 595.28;
    private const double A4Height = 841.89;
    private const double PointsPerMm = 2.8346;

    public string Name => "PDF (montagem)";

    public bool CanExecute(string operation) => operation is
        OperationIds.PdfMerge
        or OperationIds.PdfSplit
        or OperationIds.ImageToPdf;

    public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de um arquivo.");

        return context.Job.Options switch
        {
            PdfMergeOptions => Task.FromResult(Merge(context, inputPath, cancellationToken)),
            PdfSplitOptions options => Task.FromResult(Split(context, inputPath, options, cancellationToken)),
            ImageToPdfOptions options => Task.FromResult(FromImages(context, inputPath, options, cancellationToken)),
            _ => throw new ConversionException("operation_not_available", "Opções inválidas para montagem de PDF.")
        };
    }

    private EngineResult Merge(EngineContext context, string inputPath, CancellationToken cancellationToken)
    {
        var files = new List<string> { inputPath };
        files.AddRange(context.Job.ExtraInputPaths);
        if (files.Count < 2)
        {
            throw new ConversionException("too_few_files", "Para juntar, escolha pelo menos dois PDFs.");
        }

        if (files.Count > PdfMergeOptions.MaxFiles)
        {
            throw new ConversionException("too_many_files", $"Junte no máximo {PdfMergeOptions.MaxFiles} arquivos por vez.");
        }

        context.Progress.Report(JobProgress.Step(JobStage.Probing));
        var documents = new List<byte[]>(files.Count);
        foreach (var file in files)
        {
            cancellationToken.ThrowIfCancellationRequested();
            documents.Add(File.ReadAllBytes(file));
        }

        context.Progress.Report(new JobProgress { Percent = 0.4, Stage = JobStage.Encoding });
        byte[] merged;
        try
        {
            merged = PdfMerger.Merge(documents);
        }
        catch (Exception exception) when (exception is PdfDocumentFormatException or ArgumentException or InvalidOperationException)
        {
            throw new ConversionException("invalid_pdf", "Um dos arquivos não pôde ser lido como PDF válido.", exception);
        }

        var output = context.Workspace.OutputPath("result.pdf");
        File.WriteAllBytes(output, merged);
        context.Progress.Report(new JobProgress { Percent = 1, Stage = JobStage.WritingOutput });

        var pages = PdfReader.CountPages(output);
        log.Write(LogChannel.Jobs, $"{context.Job.Id:N} · PDF juntado · {files.Count} arquivos · {pages} páginas");
        return new EngineResult([output], $"{files.Count} arquivos · {pages} páginas");
    }

    private EngineResult Split(EngineContext context, string inputPath, PdfSplitOptions options, CancellationToken cancellationToken)
    {
        options.Validate();
        context.Progress.Report(JobProgress.Step(JobStage.Probing));

        using var source = OpenPdf(inputPath);
        var total = source.NumberOfPages;
        var groups = Groups(options, total);
        if (groups.Count == 0)
        {
            throw new ConversionException("invalid_pages", $"Nenhuma página válida. O PDF tem {total} página(s).");
        }

        var outputs = new List<string>(groups.Count);
        var baseName = Path.GetFileNameWithoutExtension(inputPath);

        for (var index = 0; index < groups.Count; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var group = groups[index];

            var builder = new PdfDocumentBuilder();
            foreach (var page in group)
            {
                builder.AddPage(source, page);
            }

            var label = group.Count == 1 ? $"p{group[0]}" : $"p{group[0]}-{group[^1]}";
            var output = context.Workspace.OutputPath($"{SafeFileName.Sanitize(baseName)}-{label}.pdf");
            File.WriteAllBytes(output, builder.Build());
            outputs.Add(output);

            context.Progress.Report(JobProgress.At((index + 1.0) / groups.Count, JobStage.Encoding));
        }

        log.Write(LogChannel.Jobs, $"{context.Job.Id:N} · PDF dividido · {total} páginas em {outputs.Count} arquivo(s)");
        return new EngineResult(outputs, $"{outputs.Count} arquivo(s) de {total} páginas");
    }

    private static List<IReadOnlyList<int>> Groups(PdfSplitOptions options, int total)
    {
        switch (options.Mode)
        {
            case "each":
                return [.. Enumerable.Range(1, total).Select(page => (IReadOnlyList<int>)[page])];
            case "blocks":
            {
                var groups = new List<IReadOnlyList<int>>();
                for (var start = 1; start <= total; start += options.PagesPerBlock)
                {
                    groups.Add([.. Enumerable.Range(start, Math.Min(options.PagesPerBlock, total - start + 1))]);
                }

                return groups;
            }
            default:
            {
                var pages = PageRanges.Parse(options.Pages, total);
                return pages.Count == 0 ? [] : [pages];
            }
        }
    }

    private EngineResult FromImages(EngineContext context, string inputPath, ImageToPdfOptions options, CancellationToken cancellationToken)
    {
        options.Validate();
        var files = new List<string> { inputPath };
        files.AddRange(context.Job.ExtraInputPaths);

        var builder = new PdfDocumentBuilder();
        var margin = options.PageSize == "image" ? 0 : options.MarginMm * PointsPerMm;

        for (var index = 0; index < files.Count; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var (bytes, kind, width, height) = LoadImage(files[index], options.Quality);

            double pageWidth;
            double pageHeight;
            switch (options.PageSize)
            {
                case "image":
                    pageWidth = width;
                    pageHeight = height;
                    break;
                case "a4-landscape":
                    pageWidth = A4Height;
                    pageHeight = A4Width;
                    break;
                default:
                    pageWidth = A4Width;
                    pageHeight = A4Height;
                    break;
            }

            var page = builder.AddPage(pageWidth, pageHeight);
            var boxWidth = pageWidth - margin * 2;
            var boxHeight = pageHeight - margin * 2;
            var scale = Math.Min(boxWidth / width, boxHeight / height);
            var drawWidth = width * scale;
            var drawHeight = height * scale;
            var x = (pageWidth - drawWidth) / 2;
            var y = (pageHeight - drawHeight) / 2;
            var rectangle = new PdfRectangle(x, y, x + drawWidth, y + drawHeight);

            if (kind == ImageKind.Png)
            {
                page.AddPng(bytes, rectangle);
            }
            else
            {
                page.AddJpeg(bytes, rectangle);
            }

            context.Progress.Report(JobProgress.At((index + 1.0) / files.Count, JobStage.Encoding));
        }

        var output = context.Workspace.OutputPath("result.pdf");
        File.WriteAllBytes(output, builder.Build());
        log.Write(LogChannel.Jobs, $"{context.Job.Id:N} · imagens para PDF · {files.Count} página(s)");
        return new EngineResult([output], $"{files.Count} página(s)");
    }

    private enum ImageKind
    {
        Jpeg,
        Png
    }

    /// <summary>JPEG e PNG entram direto; o resto vira JPEG pelo Skia. Devolve bytes e dimensoes em pontos (1 px = 0,75 pt).</summary>
    private static (byte[] Bytes, ImageKind Kind, double Width, double Height) LoadImage(string path, int quality)
    {
        var extension = Path.GetExtension(path).TrimStart('.').ToLowerInvariant();
        byte[] raw;
        try
        {
            raw = File.ReadAllBytes(path);
        }
        catch (IOException exception)
        {
            throw new ConversionException("image_unreadable", $"Não foi possível ler “{Path.GetFileName(path)}”.", exception);
        }

        using var codec = SKCodec.Create(new MemoryStream(raw));
        if (codec is null)
        {
            throw new ConversionException("image_unreadable", $"“{Path.GetFileName(path)}” não é uma imagem que sei ler. Converta para JPG ou PNG antes.");
        }

        var info = codec.Info;
        var width = info.Width * 0.75;
        var height = info.Height * 0.75;

        if (extension is "jpg" or "jpeg" && codec.EncodedFormat == SKEncodedImageFormat.Jpeg)
        {
            return (raw, ImageKind.Jpeg, width, height);
        }

        if (extension == "png" && codec.EncodedFormat == SKEncodedImageFormat.Png)
        {
            return (raw, ImageKind.Png, width, height);
        }

        using var bitmap = SKBitmap.Decode(codec);
        if (bitmap is null)
        {
            throw new ConversionException("image_unreadable", $"Não foi possível decodificar “{Path.GetFileName(path)}”.");
        }

        // Fundo branco para o que tem transparencia: JPEG nao tem canal alfa.
        using var flattened = new SKBitmap(bitmap.Width, bitmap.Height, SKColorType.Rgb888x, SKAlphaType.Opaque);
        using (var canvas = new SKCanvas(flattened))
        {
            canvas.Clear(SKColors.White);
            canvas.DrawBitmap(bitmap, 0, 0);
        }

        using var encoded = flattened.Encode(SKEncodedImageFormat.Jpeg, quality);
        return (encoded.ToArray(), ImageKind.Jpeg, width, height);
    }

    private static PdfDocument OpenPdf(string path)
    {
        try
        {
            return PdfDocument.Open(path);
        }
        catch (Exception exception) when (exception is PdfDocumentFormatException or ArgumentException)
        {
            throw new ConversionException("invalid_pdf", "O arquivo não pôde ser lido como PDF válido.", exception);
        }
    }
}
