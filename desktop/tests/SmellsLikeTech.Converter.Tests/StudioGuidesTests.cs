using System.Text.Json;
using SmellsLikeTech.Converter.Core.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// As guias do estudio do Windows encaixam igual ao site e ao Android: os casos de
/// packages/converter-core/guias-casos.json saem da regra em JS (node scripts/gerar-casos-das-guias.mjs).
/// </summary>
public sealed class StudioGuidesTests
{
    private static JsonElement Cases() =>
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "guias-casos.json"))).RootElement;

    private static StudioMeasures Measures(JsonElement m) => new(
        m.GetProperty("largura").GetDouble(), m.GetProperty("altura").GetDouble(),
        m.GetProperty("quadroW").GetDouble(), m.GetProperty("quadroH").GetDouble());

    private static double Number(JsonElement e, string name, double fallback) =>
        e.TryGetProperty(name, out var v) ? v.GetDouble() : fallback;

    [Fact]
    public void Encaixa_como_nos_casos_compartilhados()
    {
        foreach (var c in Cases().GetProperty("alinhar").EnumerateArray())
        {
            var name = c.GetProperty("nome").GetString();
            var raw = c.GetProperty("bruta");
            var t = new StudioTransform
            {
                X = Number(raw, "x", .5), Y = Number(raw, "y", .5), Scale = Number(raw, "escala", 1),
                Rotation = Number(raw, "giro", 0), RotationX = Number(raw, "giroX", 0), RotationY = Number(raw, "giroY", 0),
            };
            var references = c.GetProperty("referencias").EnumerateArray().Select(r => new StudioBox(
                r.GetProperty("esquerda").GetDouble(), r.GetProperty("topo").GetDouble(), r.GetProperty("direita").GetDouble(), r.GetProperty("base").GetDouble())).ToList();
            var result = StudioGuides.Align(t, Measures(c.GetProperty("medidas")), references);
            var expected = c.GetProperty("esperado");
            Assert.True(Math.Abs(result.Transform.X - expected.GetProperty("x").GetDouble()) < 1e-5, $"{name}: x {result.Transform.X}");
            Assert.True(Math.Abs(result.Transform.Y - expected.GetProperty("y").GetDouble()) < 1e-5, $"{name}: y {result.Transform.Y}");
            Assert.Equal(expected.GetProperty("giro").GetDouble(), result.Transform.Rotation);
            Assert.Equal(expected.GetProperty("reta").GetBoolean(), result.Straight);
            var guides = result.Guides.OrderBy(g => g.Vertical ? 0 : 1).ThenBy(g => g.Position)
                .Select(g => $"{g.Vertical}|{Math.Round(g.Position, 6)}|{g.Kind}").ToList();
            var wanted = expected.GetProperty("guias").EnumerateArray()
                .Select(g => $"{g.GetProperty("vertical").GetBoolean()}|{Math.Round(g.GetProperty("posicao").GetDouble(), 6)}|{g.GetProperty("tipo").GetString()}").ToList();
            Assert.True(wanted.SequenceEqual(guides), $"{name}: {string.Join(", ", guides)}");
        }
    }

    [Fact]
    public void Preencher_cobre_e_caber_e_centralizar_voltam_ao_meio()
    {
        foreach (var c in Cases().GetProperty("preencher").EnumerateArray())
        {
            var filled = StudioGuides.Fill(new StudioTransform { Rotation = 30, X = .1, Opacity = .5 }, Measures(c.GetProperty("medidas")));
            Assert.True(Math.Abs(filled.Scale - c.GetProperty("esperado").GetDouble()) < 1e-5, c.GetProperty("nome").GetString());
            Assert.Equal((.5, .5, 0.0, .5), (filled.X, filled.Y, filled.Rotation, filled.Opacity));
        }
        var whole = StudioGuides.FitWhole(new StudioTransform { Scale = 2.4, RotationX = 20 });
        Assert.Equal((1.0, 0.0), (whole.Scale, whole.RotationX));
        var centered = StudioGuides.Center(new StudioTransform { X = .1, Y = .9, Scale = 2, Rotation = 45 });
        Assert.Equal((.5, .5, 2.0, 45.0), (centered.X, centered.Y, centered.Scale, centered.Rotation));
    }
}
