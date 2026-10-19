using ImageMagick;
using ImageMagick.Drawing;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>
/// Desenha um texto do estudio num PNG transparente do tamanho do quadro, com o layout comum
/// (ver LAYOUT_DO_TEXTO em estudio.mjs): letra de <c>tamanho</c> × altura, quebra em 90% da
/// largura, linhas centralizadas em (x, y), contorno atras do preenchimento e caixa de fundo
/// arredondada. O FFmpeg so sobrepoe a imagem pronta.
/// </summary>
public sealed class StudioTextRenderer(string fontsDirectory)
{
    public void Render(StudioText text, int width, int height, string outputPath)
    {
        var size = Math.Max(4, text.Size * height);
        var font = FontPath(text.Font);
        using var image = new MagickImage(MagickColors.Transparent, (uint)width, (uint)height);
        image.Settings.Font = font;
        image.Settings.FontPointsize = size;

        var lines = Wrap(image, text.Text.Trim(), width * StudioRules.TextMaxWidth);
        var lineHeight = size * StudioRules.TextLineHeight;
        var widths = lines.Select(line => image.FontTypeMetrics(line)?.TextWidth ?? 0).ToList();
        var blockWidth = widths.Count == 0 ? 0 : widths.Max();
        var blockHeight = lineHeight * lines.Count;
        var centerX = text.X * width;
        var top = (text.Y * height) - (blockHeight / 2);
        var metrics = image.FontTypeMetrics("Ág");
        var ascent = metrics?.Ascent ?? size * 0.8;
        var descent = Math.Abs(metrics?.Descent ?? size * 0.2);

        if (Color(text.Box) is { } box)
        {
            var padding = size * StudioRules.TextBoxPadding;
            var radius = size * StudioRules.TextBoxRadius;
            new Drawables()
                .FillColor(box)
                .StrokeColor(MagickColors.Transparent)
                .RoundRectangle(centerX - (blockWidth / 2) - padding, top - padding,
                    centerX + (blockWidth / 2) + padding, top + blockHeight + padding, radius, radius)
                .Draw(image);
        }

        var fill = Color(text.Color) ?? MagickColors.White;
        var outline = Color(text.Outline);
        for (var i = 0; i < lines.Count; i++)
        {
            // Linha centralizada na sua faixa de altura: a base fica abaixo do topo pela subida da letra.
            var baseline = top + (i * lineHeight) + ((lineHeight - (ascent + descent)) / 2) + ascent;
            if (outline is not null)
            {
                Draw(image, font, size, lines[i], centerX, baseline, MagickColors.Transparent, outline, size * StudioRules.TextOutline * 2);
            }

            Draw(image, font, size, lines[i], centerX, baseline, fill, null, 0);
        }

        image.Write(outputPath, MagickFormat.Png32);
    }

    private static void Draw(MagickImage image, string font, double size, string line, double x, double y, IMagickColor<byte> fill, IMagickColor<byte>? stroke, double strokeWidth)
    {
        var drawables = new Drawables()
            .Font(font)
            .FontPointSize(size)
            .TextAlignment(TextAlignment.Center)
            .FillColor(fill);
        if (stroke is not null)
        {
            drawables = drawables.StrokeColor(stroke).StrokeWidth(strokeWidth).StrokeLineJoin(LineJoin.Round);
        }
        else
        {
            drawables = drawables.StrokeColor(MagickColors.Transparent);
        }

        drawables.Text(x, y, line).Draw(image);
    }

    /// <summary>Quebra por palavras para caber na largura; respeita as quebras de linha digitadas.</summary>
    private static List<string> Wrap(MagickImage image, string text, double maxWidth)
    {
        var lines = new List<string>();
        foreach (var paragraph in text.Replace("\r\n", "\n").Split('\n'))
        {
            var current = "";
            foreach (var word in paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            {
                var candidate = current.Length == 0 ? word : $"{current} {word}";
                if (current.Length > 0 && (image.FontTypeMetrics(candidate)?.TextWidth ?? 0) > maxWidth)
                {
                    lines.Add(current);
                    current = word;
                }
                else
                {
                    current = candidate;
                }
            }

            lines.Add(current);
        }

        return lines;
    }

    private string FontPath(string fontId)
    {
        var file = StudioRules.Fonts.FirstOrDefault(f => f.Id == fontId)?.FileName;
        if (file is not null && File.Exists(Path.Combine(fontsDirectory, file)))
        {
            return Path.Combine(fontsDirectory, file);
        }

        // "Padrão": uma fonte sem serifa em negrito do proprio Windows.
        var windows = Environment.GetFolderPath(Environment.SpecialFolder.Fonts);
        foreach (var candidate in new[] { "segoeuib.ttf", "arialbd.ttf" })
        {
            var path = Path.Combine(windows, candidate);
            if (File.Exists(path))
            {
                return path;
            }
        }

        return "Arial";
    }

    /// <summary>"#RRGGBB" ou "#RRGGBBAA" (CSS); vazio ou invalido vira nulo.</summary>
    public static IMagickColor<byte>? Color(string? hex)
    {
        if (string.IsNullOrWhiteSpace(hex) || hex[0] != '#' || (hex.Length != 7 && hex.Length != 9))
        {
            return null;
        }

        try
        {
            return new MagickColor(hex);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }
}
