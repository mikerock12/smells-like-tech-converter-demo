using ImageMagick;
using SmellsLikeTech.Converter.Engine.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class BackgroundRemoverTests
{
    [Fact]
    public async Task ModeloRealPreservaCoresETransparenciaDaCamada()
    {
        const int width = 96, height = 80;
        var rgba = new byte[width * height * 4];
        for (var y = 0; y < height; y++)
        for (var x = 0; x < width; x++)
        {
            var i = (y * width + x) * 4;
            var centro = Math.Pow((x - 48) / 25.0, 2) + Math.Pow((y - 40) / 30.0, 2) < 1;
            rgba[i] = 230;
            rgba[i + 1] = centro ? (byte)45 : (byte)240;
            rgba[i + 2] = centro ? (byte)60 : (byte)250;
            rgba[i + 3] = x < 4 ? (byte)0 : (byte)180;
        }
        var original = rgba.ToArray();
        await BackgroundRemover.RemoveRgbaAsync(rgba, width, height, CancellationToken.None);
        var alterados = 0;
        for (var i = 0; i < rgba.Length; i += 4)
        {
            Assert.Equal(original.AsSpan(i, 3).ToArray(), rgba.AsSpan(i, 3).ToArray());
            Assert.InRange(rgba[i + 3], (byte)0, original[i + 3]);
            if (original[i + 3] != rgba[i + 3]) alterados++;
        }
        Assert.True(alterados > width, "A máscara deve efetivamente alterar o alfa de parte da imagem.");
        Assert.Contains(rgba.Where((_, i) => i % 4 == 3), alfa => alfa > 100);
    }

    [Fact]
    public async Task CancelamentoNaoModificaImagemNemBloqueiaProximaInferencia()
    {
        var rgba = Enumerable.Repeat((byte)255, 16 * 16 * 4).ToArray();
        using var cancelamento = new CancellationTokenSource();
        cancelamento.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            BackgroundRemover.RemoveRgbaAsync(rgba, 16, 16, cancelamento.Token));
        Assert.All(rgba, valor => Assert.Equal(255, valor));
        await BackgroundRemover.RemoveRgbaAsync(rgba, 16, 16, CancellationToken.None);
    }
}
