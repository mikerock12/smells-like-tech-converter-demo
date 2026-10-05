using SmellsLikeTech.Converter.Engine.Speech;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class KokoroTextTests
{
    [Fact]
    public void OcrPreservesWordsNumbersAndNormalizesAccents() =>
        Assert.Equal("ATENÇÃO\nDIA 02/10\n1\nNÃO TERÃO AULA.",
            KokoroText.Normalize("  ATENC\u0327A\u0303O\n)\nDIA\t02/10\n1\nNÃO TERÃO AULA.\n!!!"));
    [Fact]
    public void InvalidAndSilentSamplesAreRejected()
    {
        foreach (var samples in new[] { Array.Empty<float>(), new[] { 0f }, new[] { float.NaN }, new[] { float.PositiveInfinity } })
            Assert.False(KokoroText.ValidSamples(samples));
        Assert.True(KokoroText.ValidSamples([.2f, -.2f]));
    }
    [Fact]
    public void RetryPreservesAllWords()
    {
        const string text = "Primeira parte do aviso. Segunda parte termina aqui.";
        Assert.Equal(text, string.Join(" ", KokoroText.SplitForRetry(text)));
        Assert.Empty(KokoroText.SplitForRetry("palavraunicasemespaco"));
    }
    [Fact]
    public async Task RealBrazilianVoiceReadsOcrNoticeWithDate()
    {
        using var engine = new KokoroTtsEngine();
        Assert.Equal(3, engine.ListVoices().Count);
        const string text = "ATENÇÃO FAMILIARES\nEM FUNÇÃO DA ORGANIZAÇÃO DA ESCOLA PARA AS ELEIÇÕES, COMUNICAMOS QUE\nAMANHÃ (SEXTA, DIA 02/10),\nOS TURNOS DA TARDE E NOITE NÃO TERÃO AULA.\n)";
        var output = Path.Combine(Path.GetTempPath(), $"kokoro-ocr-{Guid.NewGuid():N}.wav");
        try {
            await engine.SynthesizeToWaveAsync(text, "pf_dora", 0, 100, output, null, CancellationToken.None);
            var bytes = await File.ReadAllBytesAsync(output);
            Assert.True(bytes.Length > 240_000);
            Assert.Contains(bytes.Skip(44), value => value != 0);
        } finally { File.Delete(output); }
    }
}
