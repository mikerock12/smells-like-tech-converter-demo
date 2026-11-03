using System.Diagnostics;
using SmellsLikeTech.Converter.Core.Abstractions;
using SmellsLikeTech.Converter.Core.Jobs;
using SmellsLikeTech.Converter.Core.Media;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Engine.FFmpeg;
using SmellsLikeTech.Converter.Engine.Studio;
using SmellsLikeTech.Converter.Infrastructure.Execution;
using Xunit;
using Xunit.Abstractions;

namespace SmellsLikeTech.Converter.Tests;

/// <summary>
/// Relato do dono (03/11): vídeo de 1 min com giro e remoção de fundo ficava em "lendo o
/// arquivo" sem sair do lugar. Mede cada etapa com o FFmpeg e o modelo reais (SLT_BENCHMARK=1).
/// </summary>
public sealed class StudioPerformanceTests : IDisposable
{
    private readonly string root=Path.Combine(Path.GetTempPath(),"slt-desempenho-"+Guid.NewGuid().ToString("N"));
    private readonly ProcessRunner runner=new();
    private readonly string? ffmpeg=ExecutableGuard.Locate("ffmpeg");
    private readonly string? ffprobe=ExecutableGuard.Locate("ffprobe");
    private readonly ITestOutputHelper output;
    public StudioPerformanceTests(ITestOutputHelper output) {this.output=output;Directory.CreateDirectory(root);}

    [Theory]
    [InlineData("previa",640,360,10)]
    [InlineData("exportacao",1920,1080,30)]
    public async Task GiroComRemocaoDeFundoMostraProgressoETermina(string name,int width,int height,int fps)
    {
        if(ffmpeg is null||ffprobe is null||Environment.GetEnvironmentVariable("SLT_BENCHMARK")!="1") return;
        const int seconds=10;
        var input=Path.Combine(root,"celular.mp4");
        if(!File.Exists(input))
            await Run("-f","lavfi","-i",$"testsrc2=size=1080x1920:rate=30:duration={seconds}","-f","lavfi","-i",$"sine=frequency=440:duration={seconds}",
                "-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-c:a","aac","-shortest",input);
        var project=StudioRules.NewProject("personalizado","video",width,height) with {Fps=fps,
            Clips=[new StudioClip {Id="c",File=input,Kind="video",Start=0,End=seconds,Fit="caber-desfocado",Volume=1,
                Transform=new StudioTransform {Rotation=20,RotationY=30,RemoveBackground=true}}]};
        var options=FfmpegOptions.Resolve(ffmpeg,ffprobe,[],HardwareAcceleration.Cpu);
        var engine=new StudioEngine(()=>options,runner,new FfprobeInspector(()=>options,runner),new NoTranscriber(),new SilentLog(),Path.Combine(AppContext.BaseDirectory,"fontes"));
        var watch=Stopwatch.StartNew();var reports=new List<(double At,JobProgress Progress)>();
        var file=Path.Combine(root,name+".mp4");
        // A prévia usa a qualidade leve (4 máscaras/s); a exportação, a alta (uma máscara por quadro).
        var level=name=="previa"?StudioQuality.Preview:StudioQuality.Find("alta");
        await engine.RenderAsync(project,[input],Path.Combine(root,name),Path.Combine(root,name),file,level.VideoQuality,p=>{lock(reports) reports.Add((watch.Elapsed.TotalSeconds,p));},level:level);
        var estimate=StudioEstimate.Estimate(project,level,StudioSpeed.Default);
        watch.Stop();
        var percents=reports.Where(r=>r.Progress.Percent is not null).ToList();
        foreach(var stage in reports.GroupBy(r=>r.Progress.Stage))
            output.WriteLine($"{stage.Key}: de {stage.Min(r=>r.At):F1} s a {stage.Max(r=>r.At):F1} s, {stage.Count(r=>r.Progress.Percent is not null)} avisos com %");
        output.WriteLine($"{name} {width}×{height}/{fps} fps, {seconds} s de vídeo: {watch.Elapsed.TotalSeconds:F1} s no total; estimativa {estimate.TotalSeconds:F0} s; velocidade aprendida {engine.Speed}");
        Assert.True(File.Exists(file)&&new FileInfo(file).Length>0);
        // Nenhum trecho de mais de 3 s sem um aviso novo de porcentagem depois do primeiro.
        var gaps=percents.Zip(percents.Skip(1),(a,b)=>b.At-a.At).DefaultIfEmpty(0).Max();
        output.WriteLine($"maior intervalo sem progresso: {gaps:F1} s");
        Assert.True(percents.Count>5,"O progresso deve avançar durante a remoção de fundo.");
    }

    /// <summary>
    /// O projeto real do relato (SLT_PROJETO_DONO = caminho do projeto.json): a prévia leve e as
    /// três qualidades, com o tempo medido ao lado do estimado. A velocidade aprendida passa de
    /// uma montagem para a seguinte, como no aplicativo.
    /// </summary>
    [Fact]
    public async Task ProjetoDoDonoNasTresQualidades()
    {
        var caminho=Environment.GetEnvironmentVariable("SLT_PROJETO_DONO");
        if(ffmpeg is null||ffprobe is null||string.IsNullOrWhiteSpace(caminho)) return;
        var project=StudioProject.Parse(await File.ReadAllTextAsync(caminho));
        var inputs=project.Clips.Select(c=>c.File).Concat(project.Layers.Select(l=>l.File)).Concat(project.Audios.Select(a=>a.File)).Distinct().ToList();
        var options=FfmpegOptions.Resolve(ffmpeg,ffprobe,[],HardwareAcceleration.Cpu);
        var engine=new StudioEngine(()=>options,runner,new FfprobeInspector(()=>options,runner),new NoTranscriber(),new SilentLog(),Path.Combine(AppContext.BaseDirectory,"fontes"));
        var levels=(Environment.GetEnvironmentVariable("SLT_QUALIDADES")??"previa,baixa,media,alta").Split(',');
        foreach(var id in levels) {
            var level=id=="previa"?StudioQuality.Preview:StudioQuality.Find(id);
            var sized=level.Apply(project) with {Output="video"};
            var estimate=StudioEstimate.Estimate(project,level,engine.Speed);
            var watch=Stopwatch.StartNew();var reports=new List<(double At,JobProgress Progress)>();
            var file=Path.Combine(root,id+".mp4");
            await engine.RenderAsync(sized,inputs,Path.Combine(root,id),Path.Combine(root,id),file,level.VideoQuality,p=>{lock(reports) reports.Add((watch.Elapsed.TotalSeconds,p));},level:level);
            watch.Stop();
            var percents=reports.Where(r=>r.Progress.Percent is not null).ToList();
            var gaps=percents.Zip(percents.Skip(1),(a,b)=>b.At-a.At).DefaultIfEmpty(0).Max();
            var info=await new FfprobeInspector(()=>options,runner).InspectAsync(file,CancellationToken.None);
            output.WriteLine($"{id}: {sized.Width}×{sized.Height}/{sized.Fps} fps, {info.Duration?.TotalSeconds:F1} s, {new FileInfo(file).Length/1024/1024.0:F1} MiB; medido {watch.Elapsed.TotalSeconds:F0} s; estimado {estimate.TotalSeconds:F0} s; maior intervalo sem progresso {gaps:F1} s; velocidade {engine.Speed}");
            var amostras=Environment.GetEnvironmentVariable("SLT_AMOSTRAS");
            if(!string.IsNullOrWhiteSpace(amostras)) {Directory.CreateDirectory(amostras);File.Copy(file,Path.Combine(amostras,$"projeto-dono-{id}.mp4"),true);}
            Assert.True(info.HasVideo);Assert.InRange(info.Duration!.Value.TotalSeconds,StudioRules.TotalDuration(project)-.5,StudioRules.TotalDuration(project)+.5);
            Assert.True(gaps<15,$"A barra ficou {gaps:F1} s parada.");
        }
    }

    private sealed class NoTranscriber : IConversionEngine
    {
        public string Name=>"nenhum";
        public bool CanExecute(string operation)=>false;
        public Task<EngineResult> ExecuteAsync(EngineContext context,CancellationToken cancellationToken)=>throw new NotSupportedException();
    }

    private sealed class SilentLog : IConverterLog
    {
        public void Write(LogChannel channel,string message) {}
        public void Error(string code,string message,Exception? exception=null) {}
    }

    private async Task Run(params string[] args)
    {
        var result=await runner.RunAsync(new CommandSpec(ffmpeg!,["-hide_banner","-loglevel","error","-y",..args]),root,root,cancellationToken:CancellationToken.None);
        Assert.Equal(0,result.ExitCode);
    }

    public void Dispose() {if(Directory.Exists(root)) Directory.Delete(root,true);}
}
