using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class StudioOptimizationTests
{
    [Fact] public void SolidBackgroundAndIdentitySkipGeqOnlyWhenOptimized()
    {
        var background=StudioLayerFilters.Background(new StudioBackground {Color="#01020380"},32,32,10,2,true);
        Assert.DoesNotContain("geq",background);Assert.Contains("lutrgb=r=1:g=2:b=3:a=128",background);
        Assert.Contains("geq",StudioLayerFilters.Background(new StudioBackground {Color="#01020380"},32,32,10,2));
        Assert.Equal("format=rgba",StudioLayerFilters.Transform(new StudioTransform(),32,32,optimize:true));
        Assert.Contains("geq",StudioLayerFilters.Transform(new StudioTransform(),32,32));
        Assert.Contains("geq",StudioLayerFilters.Transform(new StudioTransform {Brightness=.1},32,32,optimize:true));
    }

    [Fact] public void ImmediatePreviewOnlyAcceptsProjectsItCanRepresent()
    {
        var clip=new StudioClip {Id="c",File="video.mp4",Kind="video",Start=0,End=60,Fit="caber",Volume=1};
        var project=new StudioProject {Width=1080,Height=1080,Background=new StudioBackground {Color="#FF7A18"},Clips=[clip]};
        Assert.True(StudioImmediatePreview.Accept(project));
        Assert.True(StudioImmediatePreview.Accept(project with {Clips=[clip with {Transform=new StudioTransform()}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Clips=[clip with {Fit="caber-desfocado"}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Clips=[clip with {Volume=1.5}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Clips=[clip with {Speed=2}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Clips=[clip with {Transform=new StudioTransform {Brightness=.1}}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Background=new StudioBackground {Color="#7536E8",EndColor="#FF7A18"}}));
        Assert.False(StudioImmediatePreview.Accept(project with {Texts=[new StudioText {Id="t",Text="texto",End=1}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Audios=[new StudioAudio {Id="a",File="a.mp3",End=1}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Layers=[new StudioLayer {Id="l",File="foto.png"}]}));
        Assert.False(StudioImmediatePreview.Accept(project with {Clips=[clip,clip with {Id="c2"}]}));
    }
}
