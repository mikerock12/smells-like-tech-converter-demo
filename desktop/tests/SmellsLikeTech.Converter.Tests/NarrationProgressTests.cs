using SmellsLikeTech.Converter.Engine.Speech;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class NarrationProgressTests
{
    [Fact]
    public void WeightsCharacters_ExcludesPreparation_AndHidesStaleEstimate()
    {
        long now = 900_000;
        var progress = new NarrationProgress([100, 200, 100, 600], () => now);
        Assert.Null(progress.Snapshot().Eta);
        now += 2_000; progress.CompleteChunk();
        Assert.Equal(0.1, progress.Snapshot().Fraction);
        now += 4_000; progress.CompleteChunk();
        Assert.Equal(0.3, progress.Snapshot().Fraction);
        Assert.Null(progress.Snapshot().Eta);
        now += 2_000; progress.CompleteChunk();
        Assert.Equal(TimeSpan.FromSeconds(12), progress.Snapshot().Eta);
        now += 6_000;
        Assert.Equal(TimeSpan.FromSeconds(6), progress.Snapshot().Eta);
        now += 60_000;
        Assert.Null(progress.Snapshot().Eta);
        Assert.Equal(0.4, progress.Snapshot().Fraction);
        progress.CompleteChunk();
        Assert.Equal(1, progress.Snapshot().Fraction);
        Assert.Null(progress.Snapshot().Eta);
    }

    [Fact]
    public void RecentWindowTracksSlowdown()
    {
        long now = 0;
        var progress = new NarrationProgress(Enumerable.Repeat(100, 30), () => now);
        for (var n = 0; n < 3; n++) { now += 2_000; progress.CompleteChunk(); }
        Assert.Equal(TimeSpan.FromSeconds(54), progress.Snapshot().Eta);
        for (var n = 0; n < 8; n++) { now += 4_000; progress.CompleteChunk(); }
        Assert.Equal(TimeSpan.FromSeconds(76), progress.Snapshot().Eta);
    }
}
