using System.Security.Cryptography;
using System.Text.Json;
using SkiaSharp;

namespace SmellsLikeTech.Converter.Engine.Image;

/// <summary>Ampliador 2× criado e treinado pela SLTI. Cada fase usa um filtro de 5 × 5.</summary>
public sealed class SltiUpscaler
{
    public const string Digest = "1905ec3cc0ab29ea395326c2362f1466cb92bf8211e4aa5769308489c4e00dc3";
    private readonly double[][] kernels;

    public SltiUpscaler(byte[] weights)
    {
        if (!string.Equals(Convert.ToHexString(SHA256.HashData(weights)), Digest, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Os pesos do ampliador SLTI falharam na verificação SHA-256.");
        using var model = JsonDocument.Parse(weights);
        kernels = model.RootElement.GetProperty("kernels").Deserialize<double[][]>()!;
    }

    public SKBitmap Apply(SKBitmap image)
    {
        int width = image.Width, height = image.Height;
        if ((long)width * height > 4L * 1024 * 1024 || width > 8192 || height > 8192)
            throw new InvalidDataException("A saída 2× admite até 16 megapixels e 16.384 pixels por lado.");
        var source = image.Pixels;
        var result = new SKColor[source.Length * 4];
        Span<SKColor> window = stackalloc SKColor[25];
        for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++)
        {
            int i = 0;
            for (int dy = -2; dy <= 2; dy++)
            {
                int row = Math.Clamp(y + dy, 0, height - 1) * width;
                for (int dx = -2; dx <= 2; dx++) window[i++] = source[row + Math.Clamp(x + dx, 0, width - 1)];
            }
            for (int phase = 0; phase < 4; phase++)
            {
                double r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 25; k++)
                {
                    var color = window[k];
                    double weight = kernels[phase][k] * color.Alpha;
                    a += weight;
                    r += weight * color.Red;
                    g += weight * color.Green;
                    b += weight * color.Blue;
                }
                byte alpha = Channel(a);
                int p = (2 * y + phase / 2) * 2 * width + 2 * x + phase % 2;
                result[p] = alpha == 0 ? new SKColor(0, 0, 0, 0)
                    : new SKColor(Channel(r / a), Channel(g / a), Channel(b / a), alpha);
            }
        }
        return new SKBitmap(width * 2, height * 2, SKColorType.Rgba8888, SKAlphaType.Unpremul) { Pixels = result };
    }
    private static byte Channel(double value) => (byte)Math.Clamp(Math.Round(value), 0, 255);
}
