using SmellsLikeTech.Converter.Core.Music;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class MusicTransformTests
{
    private static MusicClip Clip() => new() { Id = "c", Media = "m", Duration = 48000 };
    private static MusicMedia Media => new() { Id = "m", Name = "Seno", SampleRate = 48000, Channels = 1, Frames = 48000 };

    [Fact]
    public void VolumeEPan_TemRotulosDeZeroACem()
    {
        Assert.Equal(100, MusicRules.VolumePercent(0));
        Assert.Equal(50, MusicRules.VolumePercent(-6));
        Assert.Equal(0, MusicRules.VolumeDb(100));
        Assert.Equal(-60, MusicRules.VolumeDb(0));
        Assert.Equal("0", MusicRules.PanText(0));
        Assert.Equal("+1D −1L", MusicRules.PanText(1));
        Assert.Equal("+100L −100D", MusicRules.PanText(-100));
    }

    [Fact]
    public void Velocidade_MantemTrechoEAjustaDuracao()
    {
        var fast = MusicTransform.WithSpeed(Clip(), Media, 2);
        Assert.Equal(24000, fast.Duration);
        var slow = MusicTransform.WithSpeed(fast, Media, 0.5);
        Assert.Equal(96000, slow.Duration);
        Assert.Equal(1.2, MusicTransform.SpeedToMatch(120, 100), 6);
        Assert.NotEqual("m", MusicTransform.SourceKey(fast));
    }

    [Fact]
    public void Transformacao_PreservaDuracaoNosDoisModos()
    {
        var input = Enumerable.Range(0, 48000).Select(i => (float)(Math.Sin(2 * Math.PI * 440 * i / 48000) * 0.5)).ToArray();
        foreach (var preserve in new[] { true, false })
        {
            var output = MusicTransform.Transform([input], 48000, 2, 2, preserve);
            Assert.InRange(output[0].Length, 23998, 24002);
            Assert.Contains(output[0], v => Math.Abs(v) > 0.1);
        }
    }

    [Fact]
    public void Analise_Reconhece120BpmEDoMaior()
    {
        const int rate = 48000;
        var input = new float[rate * 8];
        for (var time = 0.0; time < 8; time += 0.5)
        {
            var start = (int)Math.Round(time * rate);
            for (var i = 0; i < 1200; i++) input[start + i] += (float)(0.8 * Math.Exp(-i / 180.0));
        }
        for (var i = 0; i < input.Length; i++)
        {
            foreach (var frequency in new[] { 261.6256, 329.6276, 391.9954 }) input[i] += (float)(0.08 * Math.Sin(2 * Math.PI * frequency * i / rate));
        }
        var result = MusicAnalyzer.Analyze([input], rate);
        Assert.Equal(120, result.Bpm);
        Assert.Equal("C maior", result.Key);
    }

    [Fact]
    public void Mixer_UsaFontePreparadaEProjetoAntigoContinuaValido()
    {
        var project = new MusicProject { Id = "p" };
        project.Media.Add(Media);
        var clip = MusicTransform.WithSpeed(Clip(), Media, 2);
        project.Tracks.Add(new MusicTrack { Id = "f", Name = "F", Clips = [clip] });
        var sources = new Dictionary<string, IMusicSource>
        {
            ["m"] = new MemoryMusicSource([new float[48000].Select(_ => 0.25f).ToArray()]),
            [MusicTransform.SourceKey(clip)] = new MemoryMusicSource([new float[24000].Select(_ => 0.5f).ToArray()]),
        };
        var rendered = MusicMixer.Render(project, sources, 0, 24000);
        Assert.Equal(0.5f, rendered.Left[100]);
        Assert.Empty(MusicRules.Problems(project));
        var restored = MusicProject.Parse(project.ToJson());
        Assert.Equal(2, restored.Tracks[0].Clips[0].Speed);
        Assert.True(restored.Tracks[0].Clips[0].PreservePitch ?? true);
    }
}
