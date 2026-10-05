using ImageMagick;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Media;

namespace SmellsLikeTech.Converter.Engine.Image;

/// <summary>Le dimensoes, formato real e transparencia sem carregar a imagem inteira.</summary>
public sealed class MagickImageInspector : IMediaInspector
{
    public bool CanInspect(MediaKind kind) => kind == MediaKind.Image;

    public Task<MediaInfo> InspectAsync(string path, CancellationToken cancellationToken)
    {
        var file = new FileInfo(path);
        if (!file.Exists)
        {
            throw new ConversionException("input_not_found", "O arquivo não foi encontrado.");
        }

        cancellationToken.ThrowIfCancellationRequested();

        try
        {
            var info = new MagickImageInfo(path);
            return Task.FromResult(new MediaInfo
            {
                Path = path,
                FileName = file.Name,
                Extension = FormatCatalog.Normalize(file.Extension),
                Kind = MediaKind.Image,
                SizeBytes = file.Length,
                // O formato real vem do conteudo, nao da extensao.
                Container = info.Format.ToString().ToLowerInvariant(),
                Width = (int)info.Width,
                Height = (int)info.Height,
                HasAlpha = HasAlpha(path),
                PageCount = 1
            });
        }
        catch (MagickException exception)
        {
            throw new ConversionException("invalid_image", "O arquivo não pôde ser lido como imagem válida.", exception);
        }
    }

    private static bool HasAlpha(string path)
    {
        try
        {
            using var image = new MagickImage(path);
            return image.HasAlpha;
        }
        catch (MagickException)
        {
            return false;
        }
    }
}
