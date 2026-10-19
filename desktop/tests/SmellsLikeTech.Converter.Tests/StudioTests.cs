using System.Text.Json;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// O estudio do Windows e do plugin tem de chegar ao mesmo resultado do site e do Android:
/// cada caso de packages/converter-core/estudio-casos traz o projeto e o esperado calculado
/// pela referencia em JS (node scripts/estudio-casos.mjs).
/// </summary>
public sealed class StudioTests
{
    public static IEnumerable<object[]> Cases() =>
        Directory.EnumerateFiles(Path.Combine(AppContext.BaseDirectory, "estudio-casos"), "*.json")
            .Order(StringComparer.Ordinal)
            .Select(path => new object[] { Path.GetFileName(path) });

    [Theory]
    [MemberData(nameof(Cases))]
    public void SharedCaseMatchesReference(string name)
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "estudio-casos", name)));
        var root = document.RootElement;
        var project = StudioProject.Parse(root.GetProperty("projeto").GetRawText());
        var expected = root.GetProperty("esperado");

        Assert.Equal(expected.GetProperty("duracao").GetDouble(), StudioRules.TotalDuration(project));

        var segments = StudioRules.Segments(project);
        var expectedSegments = expected.GetProperty("segmentos").EnumerateArray().ToList();
        Assert.Equal(expectedSegments.Count, segments.Count);
        for (var i = 0; i < segments.Count; i++)
        {
            Assert.Equal(expectedSegments[i].GetProperty("indice").GetInt32(), segments[i].Index);
            Assert.Equal(expectedSegments[i].GetProperty("id").GetString(), segments[i].Id);
            Assert.Equal(expectedSegments[i].GetProperty("inicio").GetDouble(), segments[i].Start);
            Assert.Equal(expectedSegments[i].GetProperty("fim").GetDouble(), segments[i].End);
            Assert.Equal(expectedSegments[i].GetProperty("duracao").GetDouble(), segments[i].Duration);
        }

        Assert.Equal(Strings(expected.GetProperty("problemas")), StudioRules.Problems(project));
        Assert.Equal(Strings(expected.GetProperty("avisos")), StudioRules.Warnings(project));

        var (gifWidth, gifHeight) = StudioRules.GifSize(project.Width, project.Height);
        Assert.Equal(expected.GetProperty("gif").GetProperty("largura").GetInt32(), gifWidth);
        Assert.Equal(expected.GetProperty("gif").GetProperty("altura").GetInt32(), gifHeight);

        var recipe = expected.GetProperty("receita");
        if (recipe.ValueKind == JsonValueKind.Null)
        {
            Assert.NotEmpty(StudioRules.Problems(project));
            return;
        }

        var entries = root.GetProperty("entradas");
        var inputs = new StudioInputs(
            entries.GetProperty("clipes").EnumerateArray()
                .Select(c => new StudioClipInput(c.GetProperty("caminho").GetString()!, c.GetProperty("temAudio").GetBoolean()))
                .ToList(),
            Strings(entries.GetProperty("textos")),
            Strings(entries.GetProperty("audios")));
        var command = StudioRecipe.Build(project, inputs);
        Assert.Equal(Strings(recipe.GetProperty("args")), command.Args);
        Assert.Equal(recipe.GetProperty("filtro").GetString(), command.Filter);
        Assert.Equal(Strings(recipe.GetProperty("mapas")), command.Maps);
        Assert.Equal(recipe.GetProperty("duracao").GetDouble(), command.Duration);
    }

    [Fact]
    public void PresetsComeFromTheSharedJson()
    {
        Assert.Equal(17, StudioRules.Presets.Count);
        var reels = StudioRules.FindPreset("instagram-reels")!;
        Assert.Equal((1080, 1920, 180.0), (reels.Width, reels.Height, reels.MaxDuration!.Value));
        Assert.Null(StudioRules.FindPreset("youtube")!.MaxDuration);
        Assert.All(StudioRules.Presets, p => Assert.True(p.Width % 2 == 0 && p.Height % 2 == 0, p.Id));
    }

    [Fact]
    public void NumbersAndRectanglesMatchTheJavaScript()
    {
        Assert.Equal("10", StudioRules.Number(10));
        Assert.Equal("2.5", StudioRules.Number(2.5));
        Assert.Equal("0.333", StudioRules.Number(1.0 / 3));
        Assert.Equal("7.79", StudioRules.Number(8.123 - 0.333));
        Assert.Equal("2 min 20 s", StudioRules.ReadableDuration(140));
        Assert.Equal((0.0, 656.0, 1080, 608), StudioRules.FitRect("caber", 1280, 720, 1080, 1920));
        Assert.Equal((-1167.0, 0.0, 3414, 1920), StudioRules.FitRect("preencher", 1280, 720, 1080, 1920));
        var project = StudioRules.NewProject("personalizado", "video", 721, 405);
        Assert.Equal((722, 406), (project.Width, project.Height));
    }

    /// <summary>O pedido que o site manda ao plugin (lib/estudio/plugin.ts): opcoes em PascalCase, projeto em portugues.</summary>
    [Fact]
    public void PluginAceitaOPedidoDoSite()
    {
        const string json = """
            {"operation":"studio.export","Project":{"versao":1,"destino":"instagram-reels","largura":1080,"altura":1920,"fps":30,"saida":"video",
            "clipes":[{"id":"c1","arquivo":"#0","tipo":"video","inicio":0,"fim":12,"ajuste":"caber-desfocado","volume":1},
                      {"id":"c2","arquivo":"#1","tipo":"imagem","duracao":3,"ajuste":"caber-desfocado"}],
            "textos":[{"id":"t1","texto":"Seu texto aqui","inicio":0,"fim":3,"x":0.5,"y":0.15,"tamanho":0.06,"cor":"#FFFFFF","contorno":"#000000","caixa":null,"fonte":"anton"}],
            "audios":[{"id":"m1","arquivo":"#2","entrada":0,"inicio":0,"fim":12,"volume":0.8,"fadeEntrada":0,"fadeSaida":1}],"quadro":0},
            "Quality":80,"TranscriptionModel":"base","TranscriptionLanguage":"auto"}
            """;
        var options = Assert.IsType<StudioExportOptions>(JobOptionsJson.Deserialize(json));
        options.Validate();
        Assert.Equal("#1", options.Project.Clips[1].File);
        Assert.Equal("#2", options.Project.Audios[0].File);
        Assert.Equal("mp4", options.OutputExtension);
        Assert.Equal("Estúdio · Instagram · Reels · Vídeo · 15 s", options.Summary());
        Assert.Equal("mp4", StudioRules.FindOutput(options.Project.Output)!.Extension);
    }

    private static List<string> Strings(JsonElement array) =>
        array.EnumerateArray().Select(e => e.GetString()!).ToList();
}
