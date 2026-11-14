using System.Text.Json;
using SmellsLikeTech.Converter.Core.Music;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// O pacote de faixas do conversor tem as mesmas regras no Windows, no site e no Android:
/// musica-pacote-casos.json traz o esperado calculado em JS (node scripts/musica-pacote-casos.mjs).
/// </summary>
public sealed class MusicStemPackageTests
{
    private static JsonElement Case()
    {
        using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "musica-pacote-casos.json")));
        return document.RootElement.Clone();
    }

    private static List<MusicStemGroup> Groups(JsonElement array) =>
        array.Deserialize<List<MusicStemGroup>>(MusicProject.Json) ?? [];

    private static List<string> Strings(JsonElement e) => [.. e.EnumerateArray().Select(x => x.GetString()!)];

    [Fact]
    public void CombinacoesNomesEProblemasBatemComOJs()
    {
        var c = Case();
        var entrada = c.GetProperty("entrada");
        var esperado = c.GetProperty("esperado");
        foreach (var modelo in entrada.GetProperty("modelos").EnumerateArray().Select(m => m.GetString()!))
        {
            var combos = MusicStemPackage.Combinations(modelo);
            var esperadas = esperado.GetProperty("combinacoes").GetProperty(modelo).EnumerateArray().ToList();
            Assert.Equal(esperadas.Count, combos.Count);
            for (var i = 0; i < combos.Count; i++)
            {
                Assert.Equal(esperadas[i].GetProperty("id").GetString(), combos[i].Id);
                var grupos = Groups(esperadas[i].GetProperty("grupos"));
                Assert.Equal(grupos.Select(g => g.Name), combos[i].Groups.Select(g => g.Name));
                Assert.Equal(grupos.Select(g => string.Join(",", g.Stems)), combos[i].Groups.Select(g => string.Join(",", g.Stems)));
            }

            var video = esperado.GetProperty("video").GetProperty(modelo);
            foreach (var manter in new[] { "voz", "resto" })
            {
                var g = MusicStemPackage.VideoGroup(modelo, manter);
                Assert.Equal(video.GetProperty(manter).GetProperty("nome").GetString(), g.Name);
                Assert.Equal(Strings(video.GetProperty(manter).GetProperty("faixas")), g.Stems);
            }
        }

        var nomesDeGrupo = Strings(esperado.GetProperty("nomesDeGrupo"));
        var k = 0;
        foreach (var caso in entrada.GetProperty("nomesDeGrupo").EnumerateArray())
            Assert.Equal(nomesDeGrupo[k++], MusicStemPackage.GroupName(Strings(caso.GetProperty("faixas")), MusicStemPackage.Model(caso.GetProperty("modelo").GetString())));

        var nomes = entrada.GetProperty("nomes");
        Assert.Equal(Strings(esperado.GetProperty("nomes")),
            MusicStemPackage.FileNames(nomes.GetProperty("titulo").GetString()!, Groups(nomes.GetProperty("grupos")), nomes.GetProperty("extensao").GetString()!));

        var problemas = esperado.GetProperty("problemas").EnumerateArray().ToList();
        k = 0;
        foreach (var caso in entrada.GetProperty("problemas").EnumerateArray())
            Assert.Equal(Strings(problemas[k++]), MusicStemPackage.Problems(caso.GetProperty("modelo").GetString(), Groups(caso.GetProperty("grupos"))));
    }

    [Fact]
    public void OArquivoMisturaIgualAoJs()
    {
        var c = Case();
        var a = c.GetProperty("entrada").GetProperty("arquivo");
        var esperado = c.GetProperty("esperado");
        var taxa = a.GetProperty("taxa").GetInt32();
        var quadros = a.GetProperty("quadros").GetInt32();
        var configuracao = MusicStemPackage.Configuration(a.GetProperty("modelo").GetString()!, taxa);
        foreach (var ajuste in a.GetProperty("ajustes").EnumerateObject())
        {
            var faixa = configuracao.Tracks.Single(t => t.Id == ajuste.Name);
            faixa.GainDb = ajuste.Value.GetProperty("ganhoDb").GetDouble();
            faixa.Pan = ajuste.Value.GetProperty("pan").GetDouble();
            faixa.Effects = ajuste.Value.GetProperty("efeitos").Deserialize<List<MusicEffect>>(MusicProject.Json)!;
        }

        configuracao.Master.Effects = a.GetProperty("master").Deserialize<List<MusicEffect>>(MusicProject.Json)!;
        var grupo = a.GetProperty("grupo").Deserialize<MusicStemGroup>(MusicProject.Json)!;
        var projeto = MusicStemPackage.FileProject(configuracao, grupo, quadros);

        var resumo = esperado.GetProperty("arquivo");
        Assert.Equal(resumo.GetProperty("faixas").EnumerateArray().Select(f => f.GetProperty("id").GetString()), projeto.Tracks.Select(t => t.Id));
        foreach (var (f, t) in resumo.GetProperty("faixas").EnumerateArray().Zip(projeto.Tracks))
        {
            Assert.Equal(f.GetProperty("nome").GetString(), t.Name);
            Assert.Equal(f.GetProperty("ganhoDb").GetDouble(), t.GainDb);
            Assert.Equal(Strings(f.GetProperty("efeitos")), t.Effects.Select(e => e.Kind));
            var clipe = f.GetProperty("clipes")[0];
            Assert.Equal(clipe[0].GetString(), t.Clips[0].Media);
            Assert.Equal(clipe[2].GetInt64(), t.Clips[0].Duration);
        }

        Assert.Equal(Strings(resumo.GetProperty("master")), projeto.Master.Effects.Select(e => e.Kind));

        var fontes = new Dictionary<string, IMusicSource>();
        foreach (var g in a.GetProperty("geradores").EnumerateObject())
            fontes[g.Name] = new MemoryMusicSource(MusicCasesTests.Generate(g.Value, g.Value.GetProperty("canais").GetInt32(), quadros, taxa));
        var (left, right) = MusicMixer.Render(projeto, fontes, 0, quadros);
        MusicCasesTests.Summary(esperado.GetProperty("render"), left, right, "pacote");
    }

    [Fact]
    public void OPedidoChegaNormalizado()
    {
        var configuracao = MusicStemPackage.Configuration("spleeter");
        var json = $$"""{"modelo":"spleeter","configuracao":{{configuracao.ToJson()}},"grupos":[{"nome":" Base ","faixas":["outros","bateria"]}]}""";
        var pedido = MusicStemPackage.ReadRequest(json);
        Assert.Equal("Base", pedido.Groups.Single().Name);
        Assert.Equal(["bateria", "outros"], pedido.Groups.Single().Stems);
        Assert.Equal("wav-24", pedido.Format);
        Assert.Equal(["vozes"], MusicStemPackage.ReadRequest("""{"modelo":"roformer","manter":"voz"}""").Groups.Single().Stems);
        Assert.Contains("corrompidas", Assert.Throws<SmellsLikeTech.Converter.Core.ConversionException>(() => MusicStemPackage.ReadRequest("{")).Message);
        Assert.Contains("não correspondem", Assert.Throws<SmellsLikeTech.Converter.Core.ConversionException>(() =>
            MusicStemPackage.ReadRequest($$"""{"modelo":"roformer","configuracao":{{configuracao.ToJson()}}}""")).Message);
    }
}
