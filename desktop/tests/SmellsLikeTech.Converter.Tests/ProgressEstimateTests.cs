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
    public void TextosIguaisAosDoSite()
    {
        Assert.Equal("37% · faltam cerca de 3 min", ProgressEstimate.Describe(0.37, 125));
        Assert.Equal("Preparando…", ProgressEstimate.Describe(0, null));
        Assert.Equal("50% · quase pronto", ProgressEstimate.Describe(0.5, 3));
        Assert.Equal("90% · faltam cerca de 1 h 7 min", ProgressEstimate.Describe(0.9, 4000));
        Assert.Equal("10% · faltam cerca de 45 s", ProgressEstimate.Describe(0.1, 42));
    }
}
