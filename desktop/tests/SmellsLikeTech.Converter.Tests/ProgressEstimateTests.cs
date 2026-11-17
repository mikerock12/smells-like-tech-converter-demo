using SmellsLikeTech.Converter.Core.Jobs;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>Os mesmos números e textos de packages/converter-core/andamento.mjs (tests/musica-pacote.test.mjs).</summary>
public sealed class ProgressEstimateTests
{
    [Fact]
    public void EstimaPeloRitmoESemPular()
    {
        var now = 0.0;
        var estimate = new ProgressEstimate(() => now);
        Assert.Null(estimate.Register(0.01));
        now = 1;
        Assert.Null(estimate.Register(0.02));
        now = 10;
        var first = estimate.Register(0.2)!.Value;
        Assert.InRange(first, 31, 49);
        now = 11;
        var second = estimate.Register(0.21)!.Value;
        Assert.True(Math.Abs(second - first) < 6, $"{first} → {second}");
        Assert.Null(estimate.Register(0.05));
    }

    [Fact]
    public void NaoSobeEntreBlocosDoRoFormer()
    {
        // 72 avisos de 40 s (3 min de música em blocos de 2,5 s), consultando a cada segundo.
        var now = 0.0;
        var estimate = new ProgressEstimate(() => now);
        double? previous = null;
        for (var t = 0; t < 72 * 40; t++)
        {
            now = t;
            var fraction = Math.Floor(t / 40.0) / 72;
            var remaining = estimate.Register(fraction);
            if (remaining is { } r && previous is { } p && r > p)
                Assert.True(t % 40 == 0, $"subiu sem avanço em {t} s: {p} → {r}");
            if (remaining is { } atBlock && t % 40 == 0 && fraction > 0.1)
            {
                var truth = 72 * 40 - t;
                Assert.InRange(atBlock, truth * 0.8 - 5, truth * 1.2 + 5);
            }
            if (remaining is not null) previous = remaining;
        }
    }

    [Fact]
    public void TextosIguaisAosDoSite()
    {
        Assert.Equal("37% · faltam cerca de 3 min", ProgressEstimate.Describe(0.37, 125));
        Assert.Equal("Preparando…", ProgressEstimate.Describe(0, null));
        Assert.Equal("50% · quase pronto", ProgressEstimate.Describe(0.5, 3));
        Assert.Equal("90% · faltam cerca de 1 h 7 min", ProgressEstimate.Describe(0.9, 4000));
        Assert.Equal("10% · faltam cerca de 45 s", ProgressEstimate.Describe(0.1, 42));
    }
}
