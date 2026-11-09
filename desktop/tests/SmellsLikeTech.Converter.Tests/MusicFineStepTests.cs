using SmellsLikeTech.Converter.Core.Music;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>Mesmos valores do teste "− e + dos ajustes" de tests/musica.test.mjs.</summary>
public sealed class MusicFineStepTests
{
    [Theory]
    [InlineData(-3.2, 1, 0.5, -60, 12, false, -2.5)]
    [InlineData(-3.2, -1, 0.5, -60, 12, false, -3.5)]
    [InlineData(11.8, 1, 0.5, -60, 12, false, 12)]
    [InlineData(0.3, 1, 0.1, 0, 1, false, 0.4)]
    [InlineData(1000, 1, 0.02, 20, 20000, true, 1020)]
    [InlineData(20, -1, 0.02, 20, 2000, true, 20)]
    [InlineData(21, -1, 0.02, 20, 2000, true, 20)]
    public void StepsOnTheGridAndInsideTheLimits(double value, int direction, double step, double min, double max, bool relative, double expected) =>
        Assert.Equal(expected, MusicRules.FineStep(value, direction, step, min, max, relative), 9);

    [Fact]
    public void StepsForEffectParameters()
    {
        Assert.Equal((0.5, false), MusicRules.StepFor(-15, 15, "dB"));
        Assert.Equal((0.02, true), MusicRules.StepFor(20, 2000, "Hz"));
        Assert.Equal(0.01, MusicRules.StepFor(0, 1, "").Step, 12);
        Assert.Equal(0.2, MusicRules.StepFor(1, 20, "").Step, 12);
        Assert.Equal(0.1, MusicRules.StepFor(0.3, 8, "").Step, 12);
        Assert.Equal(10, MusicRules.StepFor(1, 2000, "ms").Step, 12);
    }

    [Fact]
    public void SeparatedTracksStartWithWhatTheyAre()
    {
        Assert.Equal("voz_Minha música", MusicRules.SeparatedTrackName("Voz", "Minha música"));
        Assert.Equal("bateria_Teclado", MusicRules.SeparatedTrackName("Bateria", "Teclado"));
    }
}
