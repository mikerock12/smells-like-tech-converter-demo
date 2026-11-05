using System.Text.Json;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class StudioMaskTests
{
    private static JsonElement Casos()
    {
        using var documento = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "recorte-casos.json")));
        return documento.RootElement.Clone();
    }

    [Fact]
    public void CurvaBateComAReferencia()
    {
        foreach (var par in Casos().GetProperty("curva").EnumerateArray())
        {
            Assert.Equal(par[1].GetSingle(), StudioMask.Curve(par[0].GetSingle()), 3);
        }
    }

    [Fact]
    public void IlhasSaemComoNaReferencia()
    {
        var caso = Casos().GetProperty("ilhas");
        var mascara = caso.GetProperty("mascara").EnumerateArray().Select(v => v.GetSingle()).ToArray();

        var tiradas = StudioMask.RemoveIslands(mascara, caso.GetProperty("largura").GetInt32(), caso.GetProperty("altura").GetInt32());

        Assert.Equal(caso.GetProperty("tiradas").GetInt32(), tiradas);
        Assert.Equal(caso.GetProperty("esperado").EnumerateArray().Select(v => v.GetSingle()), mascara);
    }

    [Fact]
    public void MascaraVaziaNaoQuebra()
    {
        var mascara = new float[16];
        Assert.Equal(0, StudioMask.RemoveIslands(mascara, 4, 4));
    }

    [Fact]
    public void AplicarAMascaraNaoDeixaNevoaNemHaloNoFundo()
    {
        const int lado = 64;
        var rgba = Enumerable.Repeat((byte)255, lado * lado * 4).ToArray();
        // Confiança baixa em toda parte, como a névoa que o modelo deixa em volta da figura.
        var mascara = Enumerable.Repeat(.3f, BackgroundRemover.Side * BackgroundRemover.Side).ToArray();
        for (var y = 100; y < 220; y++)
        for (var x = 100; x < 220; x++) mascara[(y * BackgroundRemover.Side) + x] = 1f;

        BackgroundRemover.ApplyMask(rgba, lado, lado, mascara, null, 0, CancellationToken.None);

        Assert.Equal(0, rgba[3]);
        Assert.Equal(255, rgba[(((lado / 2) * lado) + (lado / 2)) * 4 + 3]);
    }

    [Fact]
    public void RepeticoesDoProjetoVoltamDoJsonEForaDaListaViramProblema()
    {
        var projeto = StudioRules.NewProject("personalizado", "gif", 480, 480) with { Repeats = 5 };
        Assert.Equal(5, StudioProject.Parse(projeto.ToJson()).Repeats);
        Assert.Contains("Escolha quantas vezes a animação repete.", StudioRules.Problems(projeto with { Repeats = 4 }));
        Assert.Equal([0, 3, 5, 10], AnimationRepeats.Allowed);
        Assert.Equal("Sem fim", AnimationRepeats.Label(0));
    }
}
