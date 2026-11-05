using ImageMagick;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>
/// U²-Net-p local; alfa de origem multiplicado pela máscara, antes dos efeitos. A máscara
/// (320×320, de 0 a 1) sai separada da aplicação: num vídeo, dá para calcular a máscara em
/// alguns quadros e misturar as vizinhas nos quadros do meio (<see cref="BackgroundVideo"/>).
/// </summary>
public static class BackgroundRemover
{
    public const int Side=320;
    private static readonly SemaphoreSlim Gate=new(1,1);
    private static readonly Lazy<InferenceSession> Session=new(()=> {
        using var options=new SessionOptions {IntraOpNumThreads=Math.Clamp(Environment.ProcessorCount-1,1,8),GraphOptimizationLevel=GraphOptimizationLevel.ORT_ENABLE_ALL};
        return new InferenceSession(Path.Combine(AppContext.BaseDirectory,"recorte","u2netp.onnx"),options);
    });

    public static async Task RemoveImageAsync(string input,string output,CancellationToken token)
    {
        using var image=new MagickImage(input);image.AutoOrient();
        var rgba=image.ToByteArray(MagickFormat.Rgba);
        await RemoveRgbaAsync(rgba,(int)image.Width,(int)image.Height,token);
        token.ThrowIfCancellationRequested();
        using var result=new MagickImage(rgba,new MagickReadSettings {Width=image.Width,Height=image.Height,Depth=8,Format=MagickFormat.Rgba});
        result.Write(output,MagickFormat.Png32);
    }

    public static async Task RemoveRgbaAsync(byte[] rgba,int width,int height,CancellationToken token)
    {
        var mask=await MaskAsync(rgba,width,height,token);
        await Task.Run(()=>ApplyMask(rgba,width,height,mask,null,0,token),token);
    }

    /// <summary>A máscara do quadro, normalizada de 0 a 1, na grade de 320×320 do modelo.</summary>
    public static async Task<float[]> MaskAsync(byte[] rgba,int width,int height,CancellationToken token)
    {
        await Gate.WaitAsync(token);
        try {
            return await Task.Run(()=> {
                token.ThrowIfCancellationRequested();
                using var small=new MagickImage(rgba,new MagickReadSettings {Width=(uint)width,Height=(uint)height,Depth=8,Format=MagickFormat.Rgba});
                small.Scale(new MagickGeometry(Side,Side) {IgnoreAspectRatio=true}); // média de área: rápida e suficiente para a entrada do modelo
                var rgb=small.ToByteArray(MagickFormat.Rgb); var max=Math.Max(1,(int)rgb.Max()); var n=Side*Side;
                float[] means=[.485f,.456f,.406f],std=[.229f,.224f,.225f];
                var input=new DenseTensor<float>(new[]{1,3,Side,Side});
                for(var i=0;i<n;i++) for(var c=0;c<3;c++) input.Buffer.Span[c*n+i]=(rgb[i*3+c]/(float)max-means[c])/std[c];
                var session=Session.Value;
                using var run=new RunOptions();
                using var cancel=token.Register(()=>run.Terminate=true);
                using var outputs=session.Run([NamedOnnxValue.CreateFromTensor(session.InputNames[0],input)],[session.OutputNames[0]],run);
                token.ThrowIfCancellationRequested();
                var mask=outputs.First().AsEnumerable<float>().ToArray(); var min=mask.Min(); var range=Math.Max(1e-6f,mask.Max()-min);
                for(var k=0;k<mask.Length;k++) mask[k]=Math.Clamp((mask[k]-min)/range,0,1);
                StudioMask.RemoveIslands(mask,Side,Side);
                return mask;
            },token);
        } catch(OnnxRuntimeException) when(token.IsCancellationRequested) {throw new OperationCanceledException(token);}
        finally {Gate.Release();}
    }

    /// <summary>
    /// Multiplica o alfa pela máscara ampliada (bilinear). Com <paramref name="next"/>, usa a
    /// mistura das duas máscaras na fração <paramref name="t"/> (0 = só a primeira). A borda é
    /// apertada depois de ampliar: apertar em 320×320 deixaria degraus na figura.
    /// </summary>
    public static void ApplyMask(byte[] rgba,int width,int height,float[] mask,float[]? next,float t,CancellationToken token)
    {
        if(next is not null&&t>0) {
            var mixed=new float[mask.Length];
            for(var k=0;k<mask.Length;k++) mixed[k]=(mask[k]*(1-t))+(next[k]*t);
            mask=mixed;
        }
        // A interpolação horizontal é a mesma em todas as linhas: calculada uma vez só.
        var x0s=new int[width];var x1s=new int[width];var fxs=new float[width];
        for(var x=0;x<width;x++) {
            var sx=Math.Clamp((x+.5)*Side/width-.5,0,Side-1);x0s[x]=(int)sx;x1s[x]=Math.Min(Side-1,x0s[x]+1);fxs[x]=(float)(sx-x0s[x]);
        }
        var source=mask;
        Parallel.For(0,height,new ParallelOptions {CancellationToken=token},y=> {
            var sy=Math.Clamp((y+.5)*Side/height-.5,0,Side-1); var y0=(int)sy*Side;var y1=Math.Min(Side-1,(int)sy+1)*Side;var fy=(float)(sy-(int)sy);
            var linha=y*width*4+3;
            for(var x=0;x<width;x++) {
                var fx=fxs[x];
                var v=StudioMask.Curve((source[y0+x0s[x]]*(1-fx)+source[y0+x1s[x]]*fx)*(1-fy)+(source[y1+x0s[x]]*(1-fx)+source[y1+x1s[x]]*fx)*fy);
                var i=linha+x*4;rgba[i]=(byte)Math.Clamp((int)(rgba[i]*v+.5f),0,255);
            }
        });
    }
}
