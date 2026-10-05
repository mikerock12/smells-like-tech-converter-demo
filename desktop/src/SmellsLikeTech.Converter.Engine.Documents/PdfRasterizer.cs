using PDFtoImage;
using SkiaSharp;
using SmellsLikeTech.Converter.Core;

namespace SmellsLikeTech.Converter.Engine.Documents;

/// <summary>
/// Desenha paginas de PDF como imagem (PDFium embutido). Serve tanto para "PDF em
/// imagens" quanto para reconhecer texto de PDF escaneado.
/// </summary>
public static class PdfRasterizer
{
    public static byte[] RenderPage(byte[] pdf, int pageIndex, int dpi, SKEncodedImageFormat format, int quality)
    {
        try
        {
            using var bitmap = Conversion.ToImage(pdf, page: pageIndex, options: new RenderOptions(Dpi: dpi));
            using var data = bitmap.Encode(format, quality);
            return data.ToArray();
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException or NotSupportedException)
        {
            throw new ConversionException(
                "pdf_render_failed",
                $"Não foi possível desenhar a página {pageIndex + 1} do PDF.",
                exception);
        }
    }

    public static SKEncodedImageFormat FormatOf(string extension) => extension switch
    {
        "png" => SKEncodedImageFormat.Png,
        "jpg" or "jpeg" => SKEncodedImageFormat.Jpeg,
        "webp" => SKEncodedImageFormat.Webp,
        _ => throw new ConversionException("unsupported_output_format", $"Formato de imagem não suportado: '{extension}'.")
    };
}
