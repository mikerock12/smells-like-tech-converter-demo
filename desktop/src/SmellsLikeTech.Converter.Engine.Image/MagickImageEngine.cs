using ImageMagick;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;
using SmellsLikeTech.Converter.Core.Options;

namespace SmellsLikeTech.Converter.Engine.Image;

/// <summary>
/// Motor de imagens sobre ImageMagick: converter, redimensionar, recortar, mudar proporcao,
/// girar, espelhar, comprimir, tratar transparencia e limpar metadados.
/// </summary>
public sealed class MagickImageEngine(IConverterLog log) : IConversionEngine
{
    public string Name => "ImageMagick";

    public bool CanExecute(string operation) => operation == OperationIds.ImageConvert;

    public Task<EngineResult> ExecuteAsync(EngineContext context, CancellationToken cancellationToken)
    {
        if (context.Job.Options is not ImageConvertOptions options)
        {
            throw new ConversionException("operation_not_available", "Opções inválidas para conversão de imagem.");
        }

        var inputPath = context.Job.InputPath
            ?? throw new ConversionException("missing_input", "Esta operação precisa de um arquivo de imagem.");

        options.Validate();
        cancellationToken.ThrowIfCancellationRequested();

        // O processamento e sincrono e rapido; roda fora da thread da interface.
        return Task.Run(() => Convert(context, inputPath, options, cancellationToken), cancellationToken);
    }

    private EngineResult Convert(
        EngineContext context,
        string inputPath,
        ImageConvertOptions options,
        CancellationToken cancellationToken)
    {
        context.Progress.Report(JobProgress.At(0.05, JobStage.PreparingInput));

        using var image = ReadImage(inputPath);
        cancellationToken.ThrowIfCancellationRequested();

        context.Progress.Report(JobProgress.At(0.25, JobStage.Encoding));
        ApplyOrientation(image, options);
        cancellationToken.ThrowIfCancellationRequested();

        context.Progress.Report(JobProgress.At(0.45, JobStage.Encoding));
        ApplyGeometry(image, options);
        cancellationToken.ThrowIfCancellationRequested();

        context.Progress.Report(JobProgress.At(0.7, JobStage.Encoding));
        ApplyOutputSettings(image, options);

        var output = context.Workspace.OutputPath($"result.{options.OutputExtension}");
        context.Progress.Report(JobProgress.At(0.85, JobStage.WritingOutput));

        try
        {
            image.Write(output, ResolveFormat(options.OutputFormat));
        }
        catch (MagickException exception)
        {
            log.Error("image_write_failed", $"{context.Job.Id:N} · falha ao gravar {options.OutputFormat}", exception);
            throw new ConversionException("image_write_failed", "Não foi possível gravar a imagem convertida.", exception);
        }

        context.Progress.Report(JobProgress.At(1, JobStage.WritingOutput));

        var inputSize = new FileInfo(inputPath).Length;
        var outputSize = new FileInfo(output).Length;
        return new EngineResult(
            [output],
            $"{image.Width}x{image.Height} · {MediaInfo.FormatBytes(inputSize)} → {MediaInfo.FormatBytes(outputSize)}");
    }

    private static MagickImage ReadImage(string path)
    {
        try
        {
            return new MagickImage(path);
        }
        catch (MagickException exception)
        {
            throw new ConversionException(
                "invalid_image",
                "O arquivo não pôde ser lido como imagem válida.",
                exception);
        }
    }

    private static void ApplyOrientation(MagickImage image, ImageConvertOptions options)
    {
        image.AutoOrient();

        switch (options.Rotation)
        {
            case RotationDegrees.Clockwise90:
                image.Rotate(90);
                break;
            case RotationDegrees.Half180:
                image.Rotate(180);
                break;
            case RotationDegrees.CounterClockwise270:
                image.Rotate(270);
                break;
        }

        if (options.FlipHorizontal)
        {
            image.Flop();
        }

        if (options.FlipVertical)
        {
            image.Flip();
        }
    }

    private static void ApplyGeometry(MagickImage image, ImageConvertOptions options)
    {
        var target = ResolveTargetSize(image, options);
        if (target is null)
        {
            return;
        }

        var (width, height) = target.Value;
        var background = new MagickColor(options.BackgroundColor);

        switch (options.Fit)
        {
            case FitMode.Crop:
                image.Resize(new MagickGeometry((uint)width, (uint)height) { FillArea = true });
                image.Extent(
                    new MagickGeometry((uint)width, (uint)height),
                    Gravity.Center,
                    background);
                break;

            case FitMode.Contain:
                image.Resize(new MagickGeometry((uint)width, (uint)height));
                image.BackgroundColor = background;
                image.Extent(new MagickGeometry((uint)width, (uint)height), Gravity.Center, background);
                break;

            case FitMode.Blur:
                ApplyBlurredBackground(image, width, height);
                break;

            default:
                image.Resize(new MagickGeometry((uint)width, (uint)height) { IgnoreAspectRatio = true });
                break;
        }
    }

    private static void ApplyBlurredBackground(MagickImage image, int width, int height)
    {
        using var background = (MagickImage)image.Clone();
        background.Resize(new MagickGeometry((uint)width, (uint)height) { FillArea = true });
        background.Extent(new MagickGeometry((uint)width, (uint)height), Gravity.Center);
        background.Blur(0, 18);

        image.Resize(new MagickGeometry((uint)width, (uint)height));
        background.Composite(image, Gravity.Center, CompositeOperator.Over);

        image.Read(background.ToByteArray(MagickFormat.Png));
    }

    /// <summary>Tamanho final considerando redimensionamento e proporcao escolhidos.</summary>
    private static (int Width, int Height)? ResolveTargetSize(MagickImage image, ImageConvertOptions options)
    {
        var sourceWidth = (int)image.Width;
        var sourceHeight = (int)image.Height;
        var ratio = options.AspectRatio.Ratio();

        int width;
        int height;

        switch (options.ResizeMode)
        {
            case ImageResizeMode.Exact when options.Width is int exactWidth && options.Height is int exactHeight:
                return (exactWidth, exactHeight);

            case ImageResizeMode.Width when options.Width is int targetWidth:
                width = targetWidth;
                height = ratio is null
                    ? (int)Math.Round((double)sourceHeight * targetWidth / sourceWidth)
                    : (int)Math.Round((double)targetWidth * ratio.Value.Height / ratio.Value.Width);
                return (Math.Max(1, width), Math.Max(1, height));

            case ImageResizeMode.Height when options.Height is int targetHeight:
                height = targetHeight;
                width = ratio is null
                    ? (int)Math.Round((double)sourceWidth * targetHeight / sourceHeight)
                    : (int)Math.Round((double)targetHeight * ratio.Value.Width / ratio.Value.Height);
                return (Math.Max(1, width), Math.Max(1, height));

            case ImageResizeMode.Percent when options.Percent is int percent:
                width = Math.Max(1, (int)Math.Round(sourceWidth * percent / 100.0));
                height = Math.Max(1, (int)Math.Round(sourceHeight * percent / 100.0));
                return ratio is null ? (width, height) : FitToRatio(width, height, ratio.Value);

            default:
                return ratio is null ? null : FitToRatio(sourceWidth, sourceHeight, ratio.Value);
        }
    }

    private static (int Width, int Height) FitToRatio(int width, int height, (int Width, int Height) ratio)
    {
        var byHeight = (double)height * ratio.Width / ratio.Height;
        return byHeight <= width
            ? (Math.Max(1, (int)Math.Round(byHeight)), height)
            : (width, Math.Max(1, (int)Math.Round((double)width * ratio.Height / ratio.Width)));
    }

    private static void ApplyOutputSettings(MagickImage image, ImageConvertOptions options)
    {
        var format = ResolveFormat(options.OutputFormat);
        var supportsAlpha = format is MagickFormat.Png or MagickFormat.WebP or MagickFormat.Avif
            or MagickFormat.Gif or MagickFormat.Tiff or MagickFormat.Ico;

        if (!options.KeepTransparency || !supportsAlpha)
        {
            image.BackgroundColor = new MagickColor(options.BackgroundColor);
            image.Alpha(AlphaOption.Remove);
            image.Alpha(AlphaOption.Off);
        }

        if (options.StripMetadata)
        {
            image.Strip();
        }

        if (format is not (MagickFormat.Png or MagickFormat.Bmp or MagickFormat.Tiff))
        {
            image.Quality = (uint)Math.Clamp(options.Quality, 1, 100);
        }

        if (format == MagickFormat.Ico)
        {
            // ICO nao aceita dimensoes acima de 256 pixels.
            if (image.Width > 256 || image.Height > 256)
            {
                image.Resize(new MagickGeometry(256, 256));
            }
        }
    }

    private static MagickFormat ResolveFormat(string outputFormat) => outputFormat switch
    {
        "jpg" or "jpeg" => MagickFormat.Jpeg,
        "png" => MagickFormat.Png,
        "webp" => MagickFormat.WebP,
        "avif" => MagickFormat.Avif,
        "tiff" or "tif" => MagickFormat.Tiff,
        "bmp" => MagickFormat.Bmp,
        "gif" => MagickFormat.Gif,
        "ico" => MagickFormat.Ico,
        _ => throw new ConversionException("unsupported_output_format", $"Formato de imagem não suportado: '{outputFormat}'.")
    };
}
