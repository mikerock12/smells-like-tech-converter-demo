using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Engine.Speech;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public class KokoroTests
{
    [Theory]
    [InlineData("pf_dora", 42)]
    [InlineData("pm_alex", 43)]
    [InlineData("pm_santa", 44)]
    public void VoicesArePortugueseAndShared(string id, int sid)
    {
        using var engine = new KokoroTtsEngine();
        Assert.Equal(sid, KokoroTtsEngine.Speaker(id));
        Assert.All(engine.ListVoices(), voice => Assert.True(voice.IsBrazilianPortuguese));
    }
    [Fact]
    public void UnknownVoiceIsRejected() => Assert.Throws<ConversionException>(() => KokoroTtsEngine.Speaker("voz-do-sistema"));
    [Theory]
    [InlineData(-10, .5f)]
    [InlineData(0, 1f)]
    [InlineData(10, 2f)]
    public void SpeedMatchesWebsite(int rate, float expected) => Assert.Equal(expected, KokoroTtsEngine.Speed(rate));
    [Fact]
    public async Task RealPortugueseVoicesProduceLocalPcmAndZeroVolumeIsSilent()
    {
        using var engine = new KokoroTtsEngine();
        var output = Path.Combine(Path.GetTempPath(), $"kokoro-test-{Guid.NewGuid():N}.wav");
        try
        {
            foreach (var voice in engine.ListVoices())
            {
                await engine.SynthesizeToWaveAsync("Olá. Esta narração funciona sem internet, em português do Brasil.", voice.Id, 0, 100, output, null, CancellationToken.None);
                var bytes = await File.ReadAllBytesAsync(output);
                Assert.Equal("RIFF", System.Text.Encoding.ASCII.GetString(bytes, 0, 4));
                Assert.Equal(24_000, BitConverter.ToInt32(bytes, 24));
                Assert.True(bytes.Length > 48_000);
                Assert.Contains(bytes.Skip(44), value => value != 0);
            }
            await engine.SynthesizeToWaveAsync("Teste silencioso.", "pf_dora", 0, 0, output, null, CancellationToken.None);
            Assert.All((await File.ReadAllBytesAsync(output)).Skip(44), value => Assert.Equal((byte)0, value));
        }
        finally { File.Delete(output); }
    }
    [Fact]
    public async Task MissingModelFailsWithActionableError()
    {
        using var engine = new KokoroTtsEngine(Path.Combine(Path.GetTempPath(), $"absent-{Guid.NewGuid():N}"));
        var exception = await Assert.ThrowsAsync<ConversionException>(() => engine.SynthesizeToWaveAsync("Olá", "pf_dora", 0, 100, "unused.wav", null, CancellationToken.None));
        Assert.Contains("Reinstale", exception.Message);
    }
}
