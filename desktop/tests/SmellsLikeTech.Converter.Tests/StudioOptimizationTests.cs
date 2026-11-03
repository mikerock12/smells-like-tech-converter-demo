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
        // Cor, giro planar, máscara e giro 3D estático saem do geq por quadro.
        var color=StudioLayerFilters.Transform(new StudioTransform {Brightness=.1},32,32,optimize:true);
        Assert.DoesNotContain("geq",color);Assert.Contains("lutrgb=",color);
        var rotate=StudioLayerFilters.Transform(new StudioTransform {Rotation=37},32,32,optimize:true);
        Assert.DoesNotContain("geq",rotate);Assert.Contains("rotate=",rotate);
        var mask=StudioLayerFilters.Transform(new StudioTransform {Mask="circulo"},32,32,optimize:true);
        Assert.Contains("alphamerge",mask);Assert.DoesNotContain("format=gbrap,geq",mask);
        var tilt=StudioLayerFilters.Transform(new StudioTransform {RotationX=20,Mask="oval"},32,32,optimize:true);
        Assert.DoesNotContain("format=gbrap,geq",tilt);Assert.Contains("remap=fill=black@0",tilt);Assert.Contains("loop=loop=-1:size=1",tilt);
        Assert.Contains("format=gbrap,geq",StudioLayerFilters.Transform(new StudioTransform {RotationX=20},32,32));
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

    [Fact] public void KenBurnsUsesScaleAndOverlayWithoutGeq()
    {
        var animation=StudioAnimation.KenBurns(null,60);
        var filter=StudioLayerFilters.Transform(animation,480,854,optimize:true,label:"cp0",fps:30);
        Assert.DoesNotContain("geq",filter);Assert.Contains("scale=",filter);Assert.Contains("overlay=",filter);
        Assert.Contains("n/30",filter);Assert.Contains("eval=frame",filter);
    }
}
