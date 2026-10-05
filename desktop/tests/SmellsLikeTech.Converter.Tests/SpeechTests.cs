using System.Text;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Engine.Speech;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class TextChunkerTests
{
    [Fact]
    public void Split_TextoCurto_DevolveUmBloco()
    {
        var chunks = TextChunker.Split("Olá, mundo.");
        Assert.Single(chunks);
        Assert.Equal("Olá, mundo.", chunks[0]);
    }

    [Fact]
    public void Split_TextoLongo_NaoPerdeNemCortaPalavras()
    {
        var sentence = "O Smells Like Tech Converter processa arquivos localmente com segurança. ";
        var text = string.Concat(Enumerable.Repeat(sentence, 60));

        var chunks = TextChunker.Split(text, 400);

        Assert.True(chunks.Count > 1);
        Assert.All(chunks, chunk => Assert.True(chunk.Length <= 400));

        var rebuilt = string.Concat(chunks.Select(chunk => chunk.Replace(" ", string.Empty)));
        var original = text.Replace(" ", string.Empty).Trim();
        Assert.Equal(original, rebuilt);
    }

    [Fact]
    public void Split_PalavraUnicaMaiorQueOLimite_EhFatiada()
    {
        var chunks = TextChunker.Split(new string('a', 900), 300);
        Assert.True(chunks.Count >= 3);
        Assert.All(chunks, chunk => Assert.True(chunk.Length <= 300));
    }

    [Fact]
    public void Split_TextoVazio_DevolveListaVazia() => Assert.Empty(TextChunker.Split("   "));
}

public class SubtitleConverterTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "slt-srt-" + Guid.NewGuid().ToString("N"));

    public SubtitleConverterTests() => Directory.CreateDirectory(directory);

    private const string ValidSrt = """
        1
        00:00:00,000 --> 00:00:02,500
        Bem-vindo ao Smells Like Tech Converter.

        2
        00:00:02,500 --> 00:00:05,000
        Converta arquivos com qualidade.
        """;

    [Fact]
    public async Task ConvertSrtToVtt_GeraCabecalhoEPontoNosTempos()
    {
        var srt = Write("ok.srt", ValidSrt);
        var vtt = Path.Combine(directory, "ok.vtt");

        await SubtitleConverter.ConvertSrtToVttAsync(srt, vtt, CancellationToken.None);
        var content = await File.ReadAllTextAsync(vtt);

        Assert.StartsWith("WEBVTT", content);
        Assert.Contains("00:00:00.000 --> 00:00:02.500", content);
        Assert.DoesNotContain(",500 -->", content);
    }

    [Fact]
    public async Task ValidateSrt_TimestampsCrescentes_Passam()
    {
        var srt = Write("ok.srt", ValidSrt);
        var blocks = await SubtitleConverter.ValidateSrtAsync(srt, TimeSpan.FromSeconds(10), CancellationToken.None);
        Assert.Equal(2, blocks);
    }

    [Fact]
    public async Task ValidateSrt_AlemDaDuracaoDaMidia_EhRejeitado()
    {
        var srt = Write("longo.srt", ValidSrt);
        var exception = await Assert.ThrowsAsync<ConversionException>(() =>
            SubtitleConverter.ValidateSrtAsync(srt, TimeSpan.FromSeconds(1), CancellationToken.None));

        Assert.Equal("invalid_srt", exception.Code);
    }

    [Fact]
    public async Task ValidateSrt_FimAntesDoInicio_EhRejeitado()
    {
        var srt = Write("invertido.srt", """
            1
            00:00:05,000 --> 00:00:02,000
            Tempo invertido.
            """);

        var exception = await Assert.ThrowsAsync<ConversionException>(() =>
            SubtitleConverter.ValidateSrtAsync(srt, TimeSpan.FromMinutes(1), CancellationToken.None));

        Assert.Equal("invalid_srt", exception.Code);
    }

    [Fact]
    public async Task NormalizeSrt_RenumeraAPartirDeUm()
    {
        // O whisper.cpp numera os blocos a partir de zero; players esperam a partir de um.
        var srt = Write("cru.srt", """
            0
            00:00:00,000 --> 00:00:02,000
            Primeira fala.

            1
            00:00:02,000 --> 00:00:04,000
            Segunda fala.
            """);

        var blocos = await SubtitleConverter.NormalizeSrtAsync(srt, TimeSpan.FromSeconds(10), CancellationToken.None);
        var linhas = await File.ReadAllLinesAsync(srt);

        Assert.Equal(2, blocos);
        Assert.Equal("1", linhas[0]);
        Assert.Equal("2", linhas[4]);
    }

    [Fact]
    public async Task NormalizeSrt_DescartaMarcadoresDeRuidoInventados()
    {
        var srt = Write("ruido.srt", """
            0
            00:00:00,000 --> 00:00:02,000
            Uma fala de verdade.

            1
            00:00:05,000 --> 00:00:07,000
            [MÚSICA DE FUNDO]

            2
            00:00:07,000 --> 00:00:08,000
            (risos)
            """);

        var blocos = await SubtitleConverter.NormalizeSrtAsync(srt, TimeSpan.FromSeconds(10), CancellationToken.None);
        var conteudo = await File.ReadAllTextAsync(srt);

        Assert.Equal(1, blocos);
        Assert.Contains("Uma fala de verdade.", conteudo);
        Assert.DoesNotContain("MÚSICA", conteudo);
        Assert.DoesNotContain("risos", conteudo);
    }

    [Fact]
    public async Task NormalizeSrt_NaoDeixaTrechoPassarDaDuracaoDaMidia()
    {
        var srt = Write("estourado.srt", """
            0
            00:00:00,000 --> 00:00:03,000
            Fala dentro do áudio.

            1
            00:00:05,000 --> 00:00:09,000
            Fim que passa do arquivo.

            2
            00:00:20,000 --> 00:00:22,000
            Bloco inteiro fora da mídia.
            """);

        var blocos = await SubtitleConverter.NormalizeSrtAsync(srt, TimeSpan.FromSeconds(6), CancellationToken.None);
        var conteudo = await File.ReadAllTextAsync(srt);

        Assert.Equal(2, blocos);
        Assert.Contains("00:00:05,000 --> 00:00:06,000", conteudo);
        Assert.DoesNotContain("Bloco inteiro fora", conteudo);

        // E o resultado continua válido para o validador.
        Assert.Equal(2, await SubtitleConverter.ValidateSrtAsync(srt, TimeSpan.FromSeconds(6), CancellationToken.None));
    }

    [Fact]
    public async Task NormalizeSrt_SemDuracaoConhecida_MantemOsTempos()
    {
        var srt = Write("sem-duracao.srt", """
            0
            00:00:00,000 --> 00:00:02,500
            Fala única.
            """);

        var blocos = await SubtitleConverter.NormalizeSrtAsync(srt, null, CancellationToken.None);

        Assert.Equal(1, blocos);
        Assert.Contains("00:00:00,000 --> 00:00:02,500", await File.ReadAllTextAsync(srt));
    }

    [Fact]
    public async Task NormalizeSrt_SomenteRuido_ResultaEmZeroBlocos()
    {
        var srt = Write("so-ruido.srt", """
            0
            00:00:00,000 --> 00:00:02,000
            [BLANK_AUDIO]
            """);

        Assert.Equal(0, await SubtitleConverter.NormalizeSrtAsync(srt, TimeSpan.FromSeconds(5), CancellationToken.None));
    }

    [Fact]
    public async Task WriteTextFromSrt_MantemSomenteAsFalas()
    {
        var srt = Write("ok.srt", ValidSrt);
        var txt = Path.Combine(directory, "ok.txt");

        await SubtitleConverter.WriteTextFromSrtAsync(srt, txt, CancellationToken.None);
        var content = await File.ReadAllTextAsync(txt);

        Assert.Contains("Bem-vindo ao Smells Like Tech Converter.", content);
        Assert.DoesNotContain("-->", content);
        Assert.DoesNotContain("00:00", content);
    }

    private string Write(string name, string content)
    {
        var path = Path.Combine(directory, name);
        File.WriteAllText(path, content, new UTF8Encoding(false));
        return path;
    }

    public void Dispose()
    {
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }

        GC.SuppressFinalize(this);
    }
}

public class WhisperCatalogTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "slt-models-" + Guid.NewGuid().ToString("N"));

    public WhisperCatalogTests() => Directory.CreateDirectory(directory);

    [Fact]
    public void ListModels_SemArquivos_MarcaTodosComoNaoInstalados()
    {
        var catalog = new WhisperModelCatalog(directory);
        Assert.All(catalog.ListModels(), model => Assert.False(model.IsInstalled));
    }

    [Fact]
    public void Find_ComArquivoPresente_MarcaComoInstalado()
    {
        File.WriteAllBytes(Path.Combine(directory, "ggml-base.bin"), new byte[2 * 1024 * 1024]);
        var catalog = new WhisperModelCatalog(directory);

        var model = catalog.Find("base");
        Assert.NotNull(model);
        Assert.True(model!.IsInstalled);
    }

    [Fact]
    public void Find_ModeloDesconhecido_DevolveNulo() =>
        Assert.Null(new WhisperModelCatalog(directory).Find("enorme"));

    [Fact]
    public void BestInstalled_EscolheOModeloDeMaiorQualidadePresente()
    {
        Instalar("ggml-base.bin");
        Instalar("ggml-medium.bin");
        var catalog = new WhisperModelCatalog(directory);

        Assert.Equal("medium", catalog.BestInstalled()?.Id);
    }

    [Fact]
    public void ResolvePreferred_ComOPreferidoInstalado_MantemAEscolha()
    {
        Instalar("ggml-small.bin");
        Instalar("ggml-large-v3.bin");
        var catalog = new WhisperModelCatalog(directory);

        Assert.Equal("small", catalog.ResolvePreferred("small"));
    }

    [Fact]
    public void ResolvePreferred_ComOPreferidoAusente_CaiNoMelhorInstalado()
    {
        // O usuário pediu "large" mas só existe "small" no disco: em vez de falhar,
        // a transcrição usa o que dá para usar.
        Instalar("ggml-small.bin");
        var catalog = new WhisperModelCatalog(directory);

        Assert.Equal("small", catalog.ResolvePreferred("large"));
    }

    [Fact]
    public void ResolvePreferred_SemNenhumModelo_MantemOPedidoParaOErroOrientar()
    {
        var catalog = new WhisperModelCatalog(directory);
        Assert.Equal("large", catalog.ResolvePreferred("large"));
    }

    private void Instalar(string arquivo) =>
        File.WriteAllBytes(Path.Combine(directory, arquivo), new byte[2 * 1024 * 1024]);

    public void Dispose()
    {
        if (Directory.Exists(directory))
        {
            Directory.Delete(directory, recursive: true);
        }

        GC.SuppressFinalize(this);
    }
}
