using System.Diagnostics;
using SmellsLikeTech.Converter.Core;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>
/// Dois pipes, um quadro por vez: não acumula PNGs nem vídeo cru no disco. Enquanto o modelo
/// recorta um quadro, o próximo já está sendo lido e o anterior gravado; <c>onFrame</c> recebe
/// quantos quadros já saíram, para a barra andar durante o recorte. Com <c>maskEvery</c> maior
/// que 1, o modelo roda só a cada tantos quadros e os do meio misturam as máscaras vizinhas
/// (a inferência é o que pesa: ~0,3 s por quadro num PC de 4 núcleos).
/// </summary>
public static class BackgroundVideo
{
    public static async Task RemoveAsync(string ffmpeg,string input,string output,int width,int height,int fps,double start,double duration,CancellationToken token,Action<int>? onFrame=null,int maskEvery=1)
    {
        var size=$"{width}x{height}";
        var decodeArgs=new List<string>{"-v","error","-nostdin","-ss",StudioRules.Number(start),"-t",StudioRules.Number(duration),"-i",input,
            "-vf",$"fps={fps},scale={width}:{height}","-an","-f","rawvideo","-pix_fmt","rgba","pipe:1"};
        var encodeArgs=new List<string>{"-v","error","-y","-f","rawvideo","-pix_fmt","rgba","-s",size,"-framerate",fps.ToString(System.Globalization.CultureInfo.InvariantCulture),"-i","pipe:0",
            "-ss",StudioRules.Number(start),"-t",StudioRules.Number(duration),"-i",input,"-map","0:v","-map","1:a?","-c:v","ffv1","-level","3","-pix_fmt","yuva444p","-c:a","pcm_s16le","-t",StudioRules.Number(duration),output};
        using var decode=Start(ffmpeg,decodeArgs);
        Process encoder;
        try { encoder=Start(ffmpeg,encodeArgs); } catch { Kill(decode); throw; }
        using var encode=encoder;
        using var cancel=token.Register(()=>{Kill(decode);Kill(encode);});
        var decodeErrors=Drain(decode.StandardError);var encodeErrors=Drain(encode.StandardError);var unused=Drain(encode.StandardOutput);
        decode.StandardInput.Close();
        try {
            var length=checked(width*height*4);var count=0;
            var source=decode.StandardOutput.BaseStream;var target=encode.StandardInput.BaseStream;
            async Task<byte[]?> Read()
            {
                var frame=new byte[length];
                var first=await source.ReadAsync(frame.AsMemory(0,1),token);
                if(first==0) return null;
                await source.ReadExactlyAsync(frame.AsMemory(1),token);
                return frame;
            }
            var next=Read();Task writing=Task.CompletedTask;
            // Quadros entre duas máscaras esperam a seguinte e usam a mistura das duas.
            var waiting=new List<byte[]>();float[]? previous=null;var index=0;
            async Task Write(byte[] frame)
            {
                await writing;
                writing=target.WriteAsync(frame,token).AsTask();
                count++;onFrame?.Invoke(count);
            }
            async Task Flush(float[] before,float[]? after)
            {
                for(var j=0;j<waiting.Count;j++) {
                    var frame=waiting[j];var t=after is null?0:(float)(j+1)/(waiting.Count+1);
                    await Task.Run(()=>BackgroundRemover.ApplyMask(frame,width,height,before,after,t,token),token);
                    await Write(frame);
                }
                waiting.Clear();
            }
            while(await next is { } frame) {
                next=Read();
                if(index++%Math.Max(1,maskEvery)!=0&&previous is not null) {waiting.Add(frame);continue;}
                var mask=await BackgroundRemover.MaskAsync(frame,width,height,token);
                if(previous is not null) await Flush(previous,mask);
                await Task.Run(()=>BackgroundRemover.ApplyMask(frame,width,height,mask,null,0,token),token);
                await Write(frame);
                previous=mask;
            }
            if(previous is not null) await Flush(previous,null);
            await writing;
            encode.StandardInput.Close();
            await Task.WhenAll(decode.WaitForExitAsync(token),encode.WaitForExitAsync(token),decodeErrors,encodeErrors,unused);
            if(count==0||decode.ExitCode!=0||encode.ExitCode!=0) throw new ConversionException("background_failed","Não foi possível remover o fundo de um vídeo. Confira se ele pode ser reproduzido e tente novamente.");
        } catch(Exception) when(token.IsCancellationRequested) {throw new OperationCanceledException(token);}
        finally {Kill(decode);Kill(encode);}
    }
    private static Process Start(string executable,IEnumerable<string> args) {
        var info=new ProcessStartInfo(executable) {UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(var arg in args) info.ArgumentList.Add(arg);
        var process=new Process {StartInfo=info};
        try {process.Start();return process;} catch {process.Dispose();throw;}
    }
    private static void Kill(Process p) {try {if(!p.HasExited) p.Kill(true);}catch(InvalidOperationException) {}catch(System.ComponentModel.Win32Exception) {}}
    private static async Task Drain(StreamReader reader) {var buffer=new char[4096];while(await reader.ReadAsync(buffer)>0) {}}
}
