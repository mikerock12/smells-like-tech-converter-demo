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
