using ImageMagick;
using SmellsLikeTech.Converter.Engine.Image;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class ImageProjectTests
{
    [Theory]
    [InlineData("editor-navegador.sltimage", "A")]
    [InlineData("Fixtures/editor-android.sltimage", "Android")]
    public void AbreProjetoEditaCamadaESalvaPixels(string arquivo, string texto)
    {
        using var input = File.OpenRead(Path.Combine(AppContext.BaseDirectory, arquivo));
        var project = ImageProject.Open(input);
        var doc = project.Document;
        Assert.Equal(texto, doc.Layers[1].Texto);
        project.Apply(doc with { Layers = [doc.Layers[0], doc.Layers[1] with { Texto = "Windows", Visivel = false }] });
        using var saved = new MemoryStream();
        project.Save(saved);
        saved.Position = 0;
        var reopened = ImageProject.Open(saved);
        Assert.Equal("Windows", reopened.Document.Layers[1].Texto);
        using var decoded = new MagickImage(reopened.Render(MagickFormat.Jxl));
        Assert.Equal(32u, decoded.Width);
        Assert.Equal(24u, decoded.Height);
        using var pixels = decoded.GetPixels();
        var blue = new byte[] { 36, 156, 214, 255 };
        var transparent = new byte[4];
        for (int y = 0; y < 24; y++)
            for (int x = 0; x < 32; x++)
                Assert.Equal(x < 16 ? blue : transparent, pixels.GetPixel(x, y).ToArray());
    }
}
