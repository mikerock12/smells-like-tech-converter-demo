using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>Baixa/média/alta e a prévia leve: os mesmos números de estudio-qualidade.mjs e Qualidade.kt.</summary>
public sealed class StudioQualityTests
{
    private static StudioProject Reels() => StudioRules.NewProject("instagram-reels", "video", null, null) with
    {
        Clips = [new StudioClip { Id = "c", File = "v.mp4", Kind = "video", Start = 0, End = 60, Fit = "caber" }]
    };

    [Fact]
    public void BaixaVaiAte720pEAPreviaFicaLeve()
    {
        var p = Reels();
        Assert.Equal(["baixa", "media", "alta"], StudioQuality.Levels.Select(q => q.Id));
        var baixa = StudioQuality.Find("baixa").Apply(p);
        Assert.Equal(1280, Math.Max(baixa.Width, baixa.Height));
        Assert.Equal(0, baixa.Width % 2);
        Assert.Equal((p.Width, p.Height), (StudioQuality.Find("alta").Apply(p).Width, StudioQuality.Find("media").Apply(p).Height));
        var previa = StudioQuality.Preview.Apply(p);
        Assert.Equal(480, Math.Max(previa.Width, previa.Height));
        Assert.Equal(Math.Min(p.Fps, 12), previa.Fps);
        Assert.Equal("alta", StudioQuality.Find("desconhecida").Id);
    }

    [Fact]
    public void TempoEstimadoCresceComQualidadeERecorte()
    {
        var p = Reels();
        var tempos = StudioQuality.Levels.Select(q => StudioEstimate.Estimate(p, q, StudioSpeed.Default)).ToList();
        Assert.True(tempos[0] < tempos[1] && tempos[1] < tempos[2]);
        Assert.True(StudioEstimate.Estimate(p, StudioQuality.Preview, StudioSpeed.Default) < tempos[0]);
        var recortado = p with { Clips = [p.Clips[0] with { Transform = new StudioTransform { RemoveBackground = true } }] };
        Assert.Equal(60 * p.Fps, StudioEstimate.RemovalFrames(recortado));
        Assert.True(StudioEstimate.Estimate(recortado, StudioQuality.Find("alta"), StudioSpeed.Default) > tempos[2] * 2);
        Assert.Equal("cerca de 15 s", StudioEstimate.Readable(TimeSpan.FromSeconds(12)));
        Assert.Equal("cerca de 3 min", StudioEstimate.Readable(TimeSpan.FromSeconds(130)));
        Assert.Equal("cerca de 3 h 10 min", StudioEstimate.Readable(TimeSpan.FromSeconds((3 * 3600) + 600)));
    }

    [Fact]
    public void VelocidadeAprendeSemAceitarAbsurdos()
    {
        var v = StudioSpeed.Default.Learn(.05, null);
        Assert.InRange(v.SecondsPerMegapixelFrame, StudioSpeed.Default.SecondsPerMegapixelFrame, .05);
        Assert.Equal(StudioSpeed.Default, StudioSpeed.Default.Learn(999, -1));
    }

    [Fact]
    public void QualidadeEscolhidaVaiAoX264EAoPlugin()
    {
        var engine = FfmpegOptions.Resolve("ffmpeg", "ffprobe", [], HardwareAcceleration.Cpu);
        var p = Reels();
        var inputs = new StudioInputs([new StudioClipInput("v.mp4", true)], [], []);
        var rapido = string.Join(" ", StudioRecipe.ToCommand(engine, p, inputs, "x.mp4", 55, true, "veryfast").Arguments);
        Assert.Contains("-preset veryfast", rapido);
        Assert.Contains("-preset medium", string.Join(" ", StudioRecipe.ToCommand(engine, p, inputs, "x.mp4", 82, true).Arguments));
        new StudioExportOptions { Project = p, QualityLevel = "media" }.Validate();
        Assert.Throws<ConversionException>(() => new StudioExportOptions { Project = p, QualityLevel = "ultra" }.Validate());
        var lido = (StudioExportOptions)JobOptionsJson.Deserialize(JobOptionsJson.Serialize(new StudioExportOptions { Project = p, QualityLevel = "baixa" }))!;
        Assert.Equal("baixa", lido.QualityLevel);
        Assert.Contains("qualidade baixa", lido.Summary());
    }
}
