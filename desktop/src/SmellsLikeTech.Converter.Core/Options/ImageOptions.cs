using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Operations;

namespace SmellsLikeTech.Converter.Core.Options;

public sealed record ImageConvertOptions : JobOptions
{
    public override string Operation => OperationIds.ImageConvert;
    public override string OutputExtension => FormatCatalog.FileExtension(OutputFormat);

    public string OutputFormat { get; init; } = "webp";

    public ImageResizeMode ResizeMode { get; init; } = ImageResizeMode.None;
    public int? Width { get; init; }
    public int? Height { get; init; }
    public int? Percent { get; init; }

    public AspectRatioMode AspectRatio { get; init; } = AspectRatioMode.Original;
    public FitMode Fit { get; init; } = FitMode.Crop;
    public string BackgroundColor { get; init; } = "#000000";

    /// <summary>1 a 100. Ignorado por formatos sem perda.</summary>
    public int Quality { get; init; } = 80;

    public RotationDegrees Rotation { get; init; } = RotationDegrees.None;
    public bool FlipHorizontal { get; init; }
    public bool FlipVertical { get; init; }

    public bool KeepTransparency { get; init; } = true;
    public bool StripMetadata { get; init; } = true;

    public const int MaxDimension = 20_000;

    public override void Validate()
    {
        if (!FormatCatalog.ImageOutputFormats.Contains(OutputFormat))
        {
            throw new ConversionException("unsupported_output_format", $"Formato de imagem não suportado: '{OutputFormat}'.");
        }

        if (Quality is < 1 or > 100)
        {
            throw new ConversionException("invalid_quality", "Qualidade deve estar entre 1 e 100.");
        }

        switch (ResizeMode)
        {
            case ImageResizeMode.Width when Width is null:
            case ImageResizeMode.Height when Height is null:
                throw new ConversionException("invalid_resolution", "Informe a dimensão do redimensionamento.");
            case ImageResizeMode.Exact when Width is null || Height is null:
                throw new ConversionException("invalid_resolution", "Informe largura e altura.");
            case ImageResizeMode.Percent when Percent is null:
                throw new ConversionException("invalid_resolution", "Informe a porcentagem.");
        }

        ValidateDimension(Width, "Largura");
        ValidateDimension(Height, "Altura");

        if (Percent is not null && Percent is < 1 or > 1000)
        {
            throw new ConversionException("invalid_resolution", "Porcentagem deve ficar entre 1 e 1000.");
        }

        ColorAllowlist.Validate(BackgroundColor);
    }

    private static void ValidateDimension(int? value, string name)
    {
        if (value is not null && (value < 1 || value > MaxDimension))
        {
            throw new ConversionException("invalid_resolution", $"{name} deve ficar entre 1 e {MaxDimension} pixels.");
        }
    }

    public override string Summary()
    {
        var parts = new List<string> { OutputFormat.ToUpperInvariant() };
        switch (ResizeMode)
        {
            case ImageResizeMode.Width:
                parts.Add($"{Width}px de largura");
                break;
            case ImageResizeMode.Height:
                parts.Add($"{Height}px de altura");
                break;
            case ImageResizeMode.Exact:
                parts.Add($"{Width}x{Height}");
                break;
            case ImageResizeMode.Percent:
                parts.Add($"{Percent}%");
                break;
        }

        if (AspectRatio != AspectRatioMode.Original)
        {
            parts.Add(AspectRatio.Display());
        }

        if (!AudioAllowlist.IsLossless(OutputFormat) && OutputFormat is not ("png" or "bmp" or "tiff"))
        {
            parts.Add($"{Quality}%");
        }

        return string.Join(" · ", parts);
    }
}
