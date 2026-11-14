using SmellsLikeTech.Converter.Core.Music;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>Os mesmos números de tests/musica-transporte.test.mjs.</summary>
public sealed class MusicTransportTests
{
    private static MusicProject Project()
    {
        var p = MusicRules.NewProject("t", "T", 48000);
        p.Tracks = [new MusicTrack { Id = "f", Clips = [new MusicClip { Id = "c", Media = "m", Start = 48000L * 10, Duration = 48000L * 50 }] }];
        return p;
    }

    [Fact]
    public void SaltosFicamDentroDaMusica()
    {
        var p = Project();
        Assert.Equal(0, MusicRules.Seek(p, 48000L * 30, "inicio"));
        Assert.Equal(48000L * 15, MusicRules.Seek(p, 48000L * 30, "voltar"));
        Assert.Equal(0, MusicRules.Seek(p, 48000L * 10, "voltar"));
        Assert.Equal(48000L * 45, MusicRules.Seek(p, 48000L * 30, "avancar"));
        Assert.Equal(48000L * 60, MusicRules.Seek(p, 48000L * 50, "avancar"));
        Assert.Equal(48000L * 45, MusicRules.Seek(p, 0, "final"));
        Assert.Equal(48000L * 30, MusicRules.Seek(p, 0, "barra", 0.5));
        Assert.Equal(48000L * 60, MusicRules.Seek(p, 0, "barra", 2));
        Assert.Equal(0, MusicRules.Seek(MusicRules.NewProject("v"), 999, "avancar"));
        Assert.Throws<ArgumentException>(() => MusicRules.Seek(p, 0, "pular"));
    }

    [Fact]
    public void ParaNoFimSalvoEmLoopOuGravando()
    {
        var p = Project();
        Assert.False(MusicRules.ReachedEnd(p, 48000L * 60 - 1));
        Assert.True(MusicRules.ReachedEnd(p, 48000L * 60));
        Assert.False(MusicRules.ReachedEnd(p, 48000L * 60, recording: true));
        p.Loop = new MusicLoop { Enabled = true, Start = 0, End = 48000 };
        Assert.False(MusicRules.ReachedEnd(p, 48000L * 70));
        Assert.False(MusicRules.ReachedEnd(MusicRules.NewProject("v"), 100));
    }
}
