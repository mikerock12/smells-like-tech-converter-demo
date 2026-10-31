using System.IO.Compression;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Studio;
using Xunit;

namespace SmellsLikeTech.Converter.Tests;

public sealed class StudioCreationTests
{
    private static StudioProject Project(string file)=>new() {Width=64,Height=64,Fps=10,Clips=[new() {Id="c",File=file,Kind="imagem",Duration=5,Fit="caber"}]};
    [Fact] public void AnimationKeepsEndpointsAndSmoothCurve()
    {
        var t=new StudioTransform {Keyframes=[new(1,0,.25,1,"suave"),new(3,1,.75,2)]};
        Assert.Equal((0.0,.25,1.0),StudioAnimation.At(t,0));Assert.Equal((.5,.5,1.5),StudioAnimation.At(t,2));
        Assert.Equal(.15625,StudioAnimation.At(t,1.5).X);Assert.Null(t.Problem());
        var moved=StudioAnimation.ChangeBase(t,t with {X=.6,Y=.4,Scale=2});
        var at=StudioAnimation.At(moved,2);Assert.Equal(.6,at.X,8);Assert.Equal(.4,at.Y,8);Assert.Equal(3,at.Scale,8);
        Assert.Equal("suave",moved.Keyframes[0].Curve);
        Assert.NotNull((t with {Keyframes=[new(0,0,0,0)]}).Problem());
    }
    [Fact] public void CountdownMigrationAndSplitKeepTimeline()
    {
        var p=Project("a");var c=StudioCreation.Countdown(p,1,3);
        Assert.Equal(new[]{"3","2","1"},c.Texts.Select(t=>t.Text));
        Assert.Equal(2,StudioCreation.Apply(p,"fotos").Clips[0].Transform!.Keyframes.Count);
        p=p with {Layers=[new() {Id="l",File="b",Kind="video",Start=1,End=6,Offset=2,Transform=StudioAnimation.KenBurns(null,5)}]};
        var split=StudioCreation.SplitLayer(p,"l",2,"l2");Assert.Equal(3,split.Layers[1].Offset);
        Assert.Equal(StudioAnimation.At(p.Layers[0].Transform!,1.5),StudioAnimation.At(split.Layers[1].Transform!,.5));
        var old=p with {Version=3,Audios=[new() {Id="a",File="m",At=1,Start=2,End=20}]};
        var upgraded=StudioCreation.Upgrade(old);Assert.Equal(5,StudioRules.TotalDuration(upgraded));Assert.Equal(6,upgraded.Audios[0].End);
    }
    [Fact] public void SplitClipKeepsSourceSpeedAndAnimation()
    {
        var animation=StudioAnimation.KenBurns(null,5);
        var original=Project("a") with {Clips=[new() {Id="v",File="video.mp4",Kind="video",Start=2,End=12,Speed=2,Fit="caber",Transform=animation}]};
        var split=StudioCreation.SplitClip(original,"v",2,"v2");
        Assert.Equal(new[]{"v","v2"},split.Clips.Select(c=>c.Id));Assert.Equal(6,split.Clips[0].End);Assert.Equal(6,split.Clips[1].Start);Assert.Equal(12,split.Clips[1].End);
        Assert.Equal(2,StudioRules.ClipDuration(split.Clips[0]));Assert.Equal(3,StudioRules.ClipDuration(split.Clips[1]));Assert.Equal(StudioRules.TotalDuration(original),StudioRules.TotalDuration(split));
        Assert.Equal(StudioAnimation.At(animation,3),StudioAnimation.At(split.Clips[1].Transform!,1));
        Assert.Throws<ConversionException>(()=>StudioCreation.SplitClip(original,"v",0,"outside"));
        var transitioned=original with {Clips=[original.Clips[0] with {Transition=new StudioTransition("fade")},original.Clips[0] with {Id="next"}]};
        Assert.Throws<ConversionException>(()=>StudioCreation.SplitClip(transitioned,"v",2,"v2"));
    }
    [Fact] public void PortableArchiveIncludesMediaAndRejectsPathsOutsideTheProject()
    {
        var directory=Path.Combine(Path.GetTempPath(),"studio-archive-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
        try {
            var file=Path.Combine(directory,"original.png");File.WriteAllBytes(file,[1,2,3,4]);
            using var memory=new MemoryStream();StudioArchive.Save(Project(file),memory);memory.Position=0;
            var opened=StudioArchive.Open(memory,Path.Combine(directory,"reaberto"));Assert.Equal(new byte[]{1,2,3,4},File.ReadAllBytes(opened.Clips[0].File));
            Assert.Equal(4,opened.Version);
            using var bad=new MemoryStream();using(var zip=new ZipArchive(bad,ZipArchiveMode.Create,true)) {using var writer=new StreamWriter(zip.CreateEntry("../fora.txt").Open());writer.Write("fora");}
            bad.Position=0;Assert.Throws<IOException>(()=>StudioArchive.Open(bad,Path.Combine(directory,"recusado")));
            Assert.False(Directory.Exists(Path.Combine(directory,"recusado")));Assert.False(File.Exists(Path.Combine(directory,"fora.txt")));
        } finally {Directory.Delete(directory,true);}
    }
}
