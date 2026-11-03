using System.Diagnostics;
using ImageMagick;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;
using Xunit.Abstractions;

namespace SmellsLikeTech.Converter.Tests;

public sealed class StudioOptimizationEngineTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"slt-otimizacao-"+Guid.NewGuid().ToString("N"));
    private readonly ProcessRunner runner=new();
    private readonly string? ffmpeg=ExecutableGuard.Locate("ffmpeg");
    private readonly string? ffprobe=ExecutableGuard.Locate("ffprobe");
    private readonly ITestOutputHelper output;
    public StudioOptimizationEngineTests(ITestOutputHelper output) {this.output=output;Directory.CreateDirectory(root);}
    private bool Available=>ffmpeg is not null&&ffprobe is not null;

    [Fact] public async Task FundoSolidoPreservaAlfaNoFfmpegReal()
    {
        if(!Available) return;
        var source=Path.Combine(root,"origem.png");
        using(var image=new MagickImage(MagickColors.Transparent,64,64)) {
            using var pixels=image.GetPixels();pixels.SetPixel(32,32,[255,0,0,255]);image.Write(source,MagickFormat.Png32);
        }
        var project=StudioRules.NewProject("personalizado","imagem",64,64) with {Fps=10,Background=new StudioBackground {Color="#01020380"},
            Clips=[new StudioClip {Id="c",File=source,Kind="imagem",Duration=1,Fit="caber"}]};
        var inputs=new StudioInputs([new StudioClipInput(source,false)],[],[]);
        var before=await Render(project,inputs,"antigo.png",false);var after=await Render(project,inputs,"novo.png",true);
        using var a=new MagickImage(before);using var b=new MagickImage(after);
        var pa=a.ToByteArray(MagickFormat.Rgba);var pb=b.ToByteArray(MagickFormat.Rgba);Assert.Equal(pa.Length,pb.Length);
        for(var i=0;i<pa.Length;i++) Assert.True(Math.Abs(pa[i]-pb[i])<=2,$"canal {i}: {pa[i]} / {pb[i]}");
        Assert.Equal(128,pb[3]);Assert.Equal(255,pb[(32*64+32)*4+3]);Assert.True(pb[(32*64+32)*4]>240);
    }

    [Fact] public async Task KenBurnsOtimizadoNaoUsaGeqNoFfmpegReal()
    {
        if(!Available) return;
        var source=Path.Combine(root,"origem.png");
        using(var image=new MagickImage(new MagickColor(255,0,0,255),64,64)) image.Write(source,MagickFormat.Png32);
        var project=StudioRules.NewProject("personalizado","video",64,64) with {Fps=10,Background=new StudioBackground {Color="#204060"},
            Clips=[new StudioClip {Id="c",File=source,Kind="imagem",Duration=1,Fit="caber",Transform=StudioAnimation.KenBurns(null,1)}]};
        var inputs=new StudioInputs([new StudioClipInput(source,false)],[],[]);
        var output=Path.Combine(root,"kenburns.mp4");var options=FfmpegOptions.Resolve(ffmpeg!,ffprobe!,[],HardwareAcceleration.Cpu);
        var command=StudioRecipe.ToCommand(options,project,inputs,output,50,true);
        Assert.DoesNotContain("geq",string.Join(" ",command.Arguments));
        var result=await runner.RunAsync(command,root,root,cancellationToken:CancellationToken.None);Assert.Equal(0,result.ExitCode);
        var frame=Path.Combine(root,"kenburns.png");await Run("-ss","0","-i",output,"-frames:v","1",frame);
        using var imagem=new MagickImage(frame);var pixel=imagem.GetPixels().GetPixel(32,32);Assert.True(pixel.GetChannel(0)>240);
    }

    // A geometria é comparada com o geq de referência. A máscara fica no espaço da mídia e gira
    // junto, como no canvas do site (camadas.ts) e no JS (camadas-ffmpeg.test.mjs); o geq legado
    // do C# a deixava parada na tela, então ela é conferida à parte, pela área que sobra.
    [Theory]
    [InlineData(0,30,0,"nenhuma",1.0)]
    [InlineData(25,-20,15,"nenhuma",.8)]
    [InlineData(-35,10,-40,"nenhuma",1.0)]
    public async Task Giro3dPorRemapIgualAoGeqNoFfmpegReal(double rotationX,double rotationY,double rotation,string mask,double opacity)
    {
        if(!Available) return;
        var source=Path.Combine(root,"giro3d.png");
        if(!File.Exists(source)) await Run("-f","lavfi","-i","testsrc2=size=160x120","-frames:v","1",source);
        var transform=new StudioTransform {RotationX=rotationX,RotationY=rotationY,Rotation=rotation,Scale=.8,X=.55,Y=.45,Mask=mask,Opacity=opacity};
        var project=StudioRules.NewProject("personalizado","imagem",160,120) with {Fps=10,
            Clips=[new StudioClip {Id="c",File=source,Kind="imagem",Duration=1,Fit="caber"}],
            Layers=[new StudioLayer {Id="l",File=source,Kind="imagem",Start=0,End=1,Transform=transform}]};
        var inputs=new StudioInputs([new StudioClipInput(source,false)],[],[],[source]);
        var filter=StudioRecipe.ToCommand(FfmpegOptions.Resolve(ffmpeg!,ffprobe!,[],HardwareAcceleration.Cpu),project,inputs,"x.png",50,true).Arguments;
        Assert.Contains("remap=",string.Join(" ",filter));Assert.DoesNotContain("format=gbrap,geq",string.Join(" ",filter));
        var before=await Render(project,inputs,$"geq-{rotationX}.png",false);var after=await Render(project,inputs,$"remap-{rotationX}.png",true);
        if(Environment.GetEnvironmentVariable("SLT_AMOSTRAS") is { Length: > 0 } amostras) {Directory.CreateDirectory(amostras);File.Copy(before,Path.Combine(amostras,Path.GetFileName(before)),true);File.Copy(after,Path.Combine(amostras,Path.GetFileName(after)),true);}
        using var a=new MagickImage(before);using var b=new MagickImage(after);
        var pa=a.ToByteArray(MagickFormat.Rgba);var pb=b.ToByteArray(MagickFormat.Rgba);Assert.Equal(pa.Length,pb.Length);
        // O remap pega o pixel mais próximo e o geq interpola: numa imagem de teste cheia de
        // bordas finas a diferença fica nas transições (MSE RGBA ~60–90 em 0–255).
        var mse=pa.Zip(pb).Average(pair=>Math.Pow(pair.First-pair.Second,2));
        output.WriteLine($"giro {rotationX}/{rotationY}/{rotation} {mask}: MSE={mse:F2}");
        Assert.True(mse<200,$"MSE={mse}");
    }

    [Fact] public async Task Giro3dComMascaraGiraAMascaraJuntoNoFfmpegReal()
    {
        if(!Available) return;
        var source=Path.Combine(root,"giro3d-mascara.png");
        await Run("-f","lavfi","-i","color=c=red:s=160x120","-frames:v","1",source);
        async Task<int> Covered(string mask)
        {
            var transform=new StudioTransform {RotationY=25,Rotation=20,Scale=.8,Mask=mask};
            var project=StudioRules.NewProject("personalizado","imagem",160,120) with {Fps=10,Background=new StudioBackground {Color="#00000000"},
                Clips=[new StudioClip {Id="c",File=source,Kind="imagem",Duration=1,Fit="caber",Transform=transform}]};
            var rendered=await Render(project,new StudioInputs([new StudioClipInput(source,false)],[],[]),$"mascara-{mask}.png",true);
            using var image=new MagickImage(rendered);
            var pixels=image.ToByteArray(MagickFormat.Rgba);
            return Enumerable.Range(0,pixels.Length/4).Count(i=>pixels[i*4]>200&&pixels[i*4+3]>127);
        }
        var whole=await Covered("nenhuma");var oval=await Covered("oval");var circle=await Covered("circulo");
        output.WriteLine($"pixels vermelhos: inteira={whole} oval={oval} círculo={circle}");
        // Oval inscrito ~ π/4 da área; círculo menor ainda (lado menor).
        Assert.InRange(oval/(double)whole,.7,.86);Assert.True(circle<oval);
    }

    [Fact] public async Task Giro3dDe60SegundosEm1080pAntesEDepois()
    {
        if(!Available||Environment.GetEnvironmentVariable("SLT_BENCHMARK")!="1") return;
        var input=Path.Combine(root,"origem-1080.mp4");
        await Run("-f","lavfi","-i","testsrc2=size=1920x1080:rate=30:duration=10","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p",input);
        var project=StudioRules.NewProject("personalizado","video",1920,1080) with {Fps=30,
            Clips=[new StudioClip {Id="c",File=input,Kind="video",Start=0,End=10,Fit="caber",Volume=1,Transform=new StudioTransform {RotationY=30,Rotation=10}}]};
        var inputs=new StudioInputs([new StudioClipInput(input,false)],[],[]);
        var times=new Dictionary<bool,double>();
        foreach(var optimize in new[]{true,false}) {
            var watch=Stopwatch.StartNew();await Render(project,inputs,optimize?"remap.mp4":"geq.mp4",optimize);times[optimize]=watch.Elapsed.TotalMilliseconds;
        }
        output.WriteLine($"10 s 1920×1080/30 com giro 3D: geq={times[false]:F0} ms; remap={times[true]:F0} ms; aceleração={times[false]/times[true]:F1}x");
        Assert.True(times[true]<times[false]);
    }

    [Fact] public async Task VideoDe60SegundosAntesEDepois()
    {
        if(!Available||Environment.GetEnvironmentVariable("SLT_BENCHMARK")!="1") return;
        var input=Path.Combine(root,"origem-60.mp4");
        await Run("-f","lavfi","-i","testsrc=size=320x240:rate=10:duration=60","-f","lavfi","-i","sine=frequency=440:duration=60",
            "-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-c:a","aac","-shortest",input);
        var project=StudioRules.NewProject("personalizado","video",480,480) with {Fps=10,Background=new StudioBackground {Color="#FF7A18"},
            Clips=[new StudioClip {Id="c",File=input,Kind="video",Start=0,End=60,Fit="caber",Volume=1}]};
        var inputs=new StudioInputs([new StudioClipInput(input,true)],[],[]);
        var times=new Dictionary<bool,double>();var frames=new Dictionary<bool,string>();
        foreach(var optimize in new[]{false,true}) {
            var watch=Stopwatch.StartNew();var rendered=await Render(project,inputs,optimize?"otimizado.mp4":"original.mp4",optimize);watch.Stop();times[optimize]=watch.Elapsed.TotalMilliseconds;
            var info=await new FfprobeInspector(()=>FfmpegOptions.Resolve(ffmpeg!,ffprobe!,[],HardwareAcceleration.Cpu),runner).InspectAsync(rendered,CancellationToken.None);
            Assert.True(info.HasVideo&&info.HasAudio);Assert.InRange(info.Duration!.Value.TotalSeconds,59.8,60.2);
            var frame=Path.Combine(root,optimize?"novo.png":"antigo.png");await Run("-ss","30","-i",rendered,"-frames:v","1",frame);frames[optimize]=frame;
        }
        using var a=new MagickImage(frames[false]);using var b=new MagickImage(frames[true]);
        var pa=a.ToByteArray(MagickFormat.Rgb);var pb=b.ToByteArray(MagickFormat.Rgb);Assert.Equal(pa.Length,pb.Length);
        var mse=pa.Zip(pb).Average(pair=>Math.Pow(pair.First-pair.Second,2));
        Assert.True(mse<16,$"MSE={mse}");
        output.WriteLine($"60 s, 480×480, 10 fps, com áudio: original={times[false]:F0} ms; otimizado={times[true]:F0} ms; aceleração={times[false]/times[true]:F2}x; MSE={mse:F6}");
        Assert.True(times[true]<times[false]);
    }

    private async Task<string> Render(StudioProject project,StudioInputs inputs,string name,bool optimize)
    {
        var output=Path.Combine(root,name);var options=FfmpegOptions.Resolve(ffmpeg!,ffprobe!,[],HardwareAcceleration.Cpu);
        var command=StudioRecipe.ToCommand(options,project,inputs,output,50,optimize);
        var result=await runner.RunAsync(command,root,root,cancellationToken:CancellationToken.None);Assert.Equal(0,result.ExitCode);Assert.True(new FileInfo(output).Length>0);return output;
    }

    private async Task Run(params string[] args)
    {
        var result=await runner.RunAsync(new CommandSpec(ffmpeg!,["-hide_banner","-loglevel","error","-y",..args]),root,root,cancellationToken:CancellationToken.None);
        Assert.Equal(0,result.ExitCode);
    }

    public void Dispose() {if(Directory.Exists(root)) Directory.Delete(root,true);}
}
