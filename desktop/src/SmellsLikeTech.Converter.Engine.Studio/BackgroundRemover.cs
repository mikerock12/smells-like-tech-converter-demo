using ImageMagick;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;

namespace SmellsLikeTech.Converter.Engine.Studio;

/// <summary>U²-Net-p local; alfa de origem multiplicado pela máscara, antes dos efeitos.</summary>
public static class BackgroundRemover
{
    private const int Side=320;
    private static readonly SemaphoreSlim Gate=new(1,1);
    private static readonly Lazy<InferenceSession> Session=new(()=> {
        using var options=new SessionOptions {IntraOpNumThreads=Math.Clamp(Environment.ProcessorCount/2,1,4),GraphOptimizationLevel=GraphOptimizationLevel.ORT_ENABLE_ALL};
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
        await Gate.WaitAsync(token);
        try {
            await Task.Run(()=> {
                token.ThrowIfCancellationRequested();
                using var small=new MagickImage(rgba,new MagickReadSettings {Width=(uint)width,Height=(uint)height,Depth=8,Format=MagickFormat.Rgba});
                small.Resize(new MagickGeometry(Side,Side) {IgnoreAspectRatio=true});
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
                for(var y=0;y<height;y++) {
                    token.ThrowIfCancellationRequested();
                    var sy=Math.Clamp((y+.5)*Side/height-.5,0,Side-1); var y0=(int)sy;var y1=Math.Min(Side-1,y0+1);var fy=sy-y0;
                    for(var x=0;x<width;x++) {
                        var sx=Math.Clamp((x+.5)*Side/width-.5,0,Side-1);var x0=(int)sx;var x1=Math.Min(Side-1,x0+1);var fx=sx-x0;
                        var v=(mask[y0*Side+x0]*(1-fx)+mask[y0*Side+x1]*fx)*(1-fy)+(mask[y1*Side+x0]*(1-fx)+mask[y1*Side+x1]*fx)*fy;
                        var i=(y*width+x)*4+3;rgba[i]=(byte)Math.Clamp(Math.Round(rgba[i]*Math.Clamp((v-min)/range,0,1)),0,255);
                    }
                }
            },token);
        } catch(OnnxRuntimeException) when(token.IsCancellationRequested) {throw new OperationCanceledException(token);}
        finally {Gate.Release();}
    }
}
