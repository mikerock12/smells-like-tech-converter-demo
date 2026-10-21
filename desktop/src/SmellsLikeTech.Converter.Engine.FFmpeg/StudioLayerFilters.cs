using System.Globalization;
using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>Perspectiva e cor com filtros LGPL. Espelho de camadas-ffmpeg.mjs.</summary>
public static class StudioLayerFilters
{
    public static string D(double v) => (Math.Floor(v * 1e9 + .5) / 1e9).ToString("0.#########", CultureInfo.InvariantCulture);

    public static double[]? Inverse(double[]? m)
    {
        if (m is null) return null;
        var (a,b,c,d,e,f,g,h,i) = (m[0],m[1],m[2],m[3],m[4],m[5],m[6],m[7],m[8]);
        double[] cof = [e*i-f*h,c*h-b*i,b*f-c*e,f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d];
        var det = a*cof[0]+b*cof[3]+c*cof[6];
        return Math.Abs(det) < 1e-8 ? null : cof.Select(v => v/det).ToArray();
    }

    private static double[]? Projection(StudioTransform t, int w, int h)
    {
        var a=t.Rotation*Math.PI/180; var ax=t.RotationX*Math.PI/180; var ay=t.RotationY*Math.PI/180;
        var camera=Math.Max(w,h)*3.0;
        (double X,double Y)[] uv=[(0,0),(w,0),(w,h),(0,h)];
        var p=uv.Select(v => {
            var x=(v.X-w/2.0)*t.Scale; var y=(v.Y-h/2.0)*t.Scale;
            var y1=y*Math.Cos(ax); var z1=y*Math.Sin(ax);
            var x2=x*Math.Cos(ay)+z1*Math.Sin(ay); var z2=-x*Math.Sin(ay)+z1*Math.Cos(ay);
            var factor=camera/Math.Max(camera*.1,camera-z2);
            return (X:(w*t.X+(x2*Math.Cos(a)-y1*Math.Sin(a))*factor*(t.MirrorX?-1:1))/w,
                Y:(h*t.Y+(x2*Math.Sin(a)+y1*Math.Cos(a))*factor*(t.MirrorY?-1:1))/h);
        }).ToArray();
        var dx1=p[1].X-p[2].X; var dx2=p[3].X-p[2].X; var dx3=p[0].X-p[1].X+p[2].X-p[3].X;
        var dy1=p[1].Y-p[2].Y; var dy2=p[3].Y-p[2].Y; var dy3=p[0].Y-p[1].Y+p[2].Y-p[3].Y;
        var det=dx1*dy2-dx2*dy1;
        if (Math.Abs(det)<1e-8) return null;
        var g=(dx3*dy2-dx2*dy3)/det; var j=(dx1*dy3-dx3*dy1)/det;
        return [p[1].X-p[0].X+g*p[1].X,p[3].X-p[0].X+j*p[3].X,p[0].X,
            p[1].Y-p[0].Y+g*p[1].Y,p[3].Y-p[0].Y+j*p[3].Y,p[0].Y,g,j,1];
    }

    public static double[] ColorMatrix(StudioTransform t)
    {
        static double[] Saturate(double s) {
            var r=.213*(1-s); var g=.715*(1-s); var b=.072*(1-s);
            return [r+s,g,b,0,r,g+s,b,0,r,g,b+s,0];
        }
        static double[] Compose(double[] a,double[] b) => Enumerable.Range(0,12).Select(i => {
            var row=i/4; var col=i%4;
            return a[row*4]*b[col]+a[row*4+1]*b[4+col]+a[row*4+2]*b[8+col]+(col==3?a[i]:0);
        }).ToArray();
        double[] m=t.Filter switch {
            "vivido" => Saturate(1.6), "pb" => Saturate(0),
            "sepia" => Compose([1.07,0,0,0,0,.74,0,0,0,0,.43,0],Saturate(0)),
            "vintage" => Compose([1,0,0,30,0,.95,0,20,0,0,.8,0],Saturate(.6)),
            "frio" => [.9,0,0,0,0,1,0,0,0,0,1.15,15],
            "quente" => [1.15,0,0,15,0,1.02,0,5,0,0,.85,0],
            "negativo" => [-1,0,0,255,0,-1,0,255,0,0,-1,255],
            _ => [1,0,0,0,0,1,0,0,0,0,1,0]
        };
        m=Compose(Saturate(1+t.Saturation),m);
        var c=1+t.Contrast; var d=128*(1-c)+t.Brightness*100;
        return Compose([c,0,0,d,0,c,0,d,0,0,c,d],m);
    }

    public static string Transform(StudioTransform? value,int w,int h)
    {
        var t=value??new StudioTransform();
        var m=Inverse(Projection(t,w,h));
        if (m is null) return "format=rgba,colorchannelmixer=aa=0";
        string Term(int j) => $"({D(m[j])}*(X+0.5)/W+{D(m[j+1])}*(Y+0.5)/H+{D(m[j+2])})";
        var x=$"(W*{Term(0)}/{Term(6)}-0.5)"; var y=$"(H*{Term(3)}/{Term(6)}-0.5)";
        var color=ColorMatrix(t); string[] channels=["r","g","b"];
        var position=$"st(0,{x});st(1,{y});";
        var rgb=channels.Select((c,i) => $"{c}='{position}{string.Join("+",channels.Select((source,j) => $"{D(color[i*4+j])}*{source}(ld(0),ld(1))"))}+{D(color[i*4+3])}'");
        return $"format=gbrap,geq={string.Join(":",rgb)}:a='{position}if(between(ld(0),-0.5,W-0.5)*between(ld(1),-0.5,H-0.5),alpha(ld(0),ld(1))*{D(t.Opacity)},0)':interpolation=bilinear,format=rgba";
    }

    public static string Overlay(string background,string foreground,string output,string? blend,double start,double end)
    {
        var active=$"gte(t,{D(start)})*lt(t,{D(end)})";
        if (blend is null or "normal") return $"[{background}][{foreground}]overlay=0:0:format=auto:enable='{active}'[{output}]";
        var mode=blend switch {"multiplicar"=>"multiply","clarear"=>"screen",_=>"overlay"};
        var p=output;
        return $"[{background}]split=3[{p}b0][{p}b1][{p}b2];[{foreground}]split=3[{p}f0][{p}f1][{p}f2];"
            +$"[{p}b0][{p}f0]blend=all_mode={mode}:shortest=0[{p}rgb];"
            +$"[{p}b1]alphaextract,format=gbrp[{p}ba];[{p}f1]alphaextract[{p}fa];"
            +$"[{p}f2][{p}rgb][{p}ba]maskedmerge[{p}mix];[{p}mix][{p}fa]alphamerge[{p}fg];"
            +$"[{p}b2][{p}fg]overlay=0:0:format=auto:enable='{active}'[{output}]";
    }

    public static string Background(StudioBackground? background,int w,int h,int fps,double duration)
    {
        var a=background?.Color??"#000000"; var b=background?.EndColor??a;
        static int[] Rgba(string c) => new[]{0,2,4,6}.Select(i => i==6&&c.Length==7?255:int.Parse(c.Substring(i+1,2),NumberStyles.HexNumber,CultureInfo.InvariantCulture)).ToArray();
        var start=Rgba(a); var end=Rgba(b);
        var channels=new[]{"r","g","b","a"}.Select((c,i) => $"{c}='{start[i]}+({end[i]-start[i]})*clip(((X+0.5)*W+(Y+0.5)*H)/(W*W+H*H),0,1)'");
        return $"color=c=black:s={w}x{h}:r={fps}:d={D(duration)},format=rgba,geq={string.Join(":",channels)},format=rgba[cfundo]";
    }
}
