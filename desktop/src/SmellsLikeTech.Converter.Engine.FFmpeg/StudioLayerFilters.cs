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

    public static string Transform(StudioTransform? value,int w,int h,double offset=0,bool optimize=false,string label="transformacao",int fps=30)
    {
        var t=value??new StudioTransform();
        if(optimize&&IsIdentity(t)) return "format=rgba";
        if(optimize&&t.Rotation==0&&t.RotationX==0&&t.RotationY==0&&!t.MirrorX&&!t.MirrorY&&
            t.Mask=="nenhuma"&&t.Opacity==1&&t.Filter=="original"&&t.Brightness==0&&t.Contrast==0&&t.Saturation==0) {
            var time=offset!=0?$"(n/{fps}+{D(offset)})":$"(n/{fps})";
            var ax=StudioAnimation.Expression(t,"x",time);var ay=StudioAnimation.Expression(t,"y",time);var az=StudioAnimation.Expression(t,"escala",time);
            return $"format=rgba,scale=w='max(2,trunc(iw*({az})/2)*2)':h='max(2,trunc(ih*({az})/2)*2)':eval=frame[{label}zoom];"
                +$"color=c=black@0:s={w}x{h}:r={fps},format=rgba[{label}base];"
                +$"[{label}base][{label}zoom]overlay=x='(W-w)/2+({ax}-0.5)*W':y='(H-h)/2+({ay}-0.5)*H':eval=frame:shortest=1,format=rgba";
        }
        if (optimize && t.Keyframes.Count == 0 && (t.RotationX != 0 || t.RotationY != 0))
        {
            // Giro 3D estático: cor e máscara no espaço da mídia, como no geq, e a perspectiva
            // por um mapa de coordenadas calculado num quadro só. Cada quadro vira uma consulta
            // por pixel (remap, LGPL), e não mais uma expressão por pixel e canal.
            var projection = Inverse(Projection(t, w, h));
            if (projection is null) return "format=rgba,colorchannelmixer=aa=0";
            var parts = new List<string>();
            parts.Add($"{SourceColorAndMask(t, w, h, fps, label, parts)}[{label}src]");
            string MapTerm(int j) => $"({D(projection[j])}*(X+0.5)/{w}+{D(projection[j + 1])}*(Y+0.5)/{h}+{D(projection[j + 2])})";
            var source = $"st(0,{w}*{MapTerm(0)}/{MapTerm(6)}-0.5);st(1,{h}*{MapTerm(3)}/{MapTerm(6)}-0.5);";
            var inside = $"between(ld(0),-0.5,{w}-0.5)*between(ld(1),-0.5,{h}-0.5)";
            string Map(int axis, int last, string name) =>
                $"color=c=black:s={w}x{h}:r={fps}:d={D(1.0 / fps)},format=gray16le,geq=lum='{source}if({inside},clip(round(ld({axis})),0,{last}),65535)',loop=loop=-1:size=1[{label}{name}]";
            parts.Add(Map(0, w - 1, "mx"));
            parts.Add(Map(1, h - 1, "my"));
            parts.Add($"[{label}src][{label}mx][{label}my]remap=fill=black@0,format=rgba");
            return string.Join(";", parts);
        }
        if (optimize && t.Keyframes.Count == 0 && t.RotationX == 0 && t.RotationY == 0)
        {
            // Transformação estática sem 3D: cor por mixer/LUT, máscara por um quadro +
            // alphamerge, giro planar pelo rotate e posição por overlay — sem geq por pixel.
            var parts = new List<string>();
            var current = SourceColorAndMask(t, w, h, fps, label, parts);
            // O rotate já escreve no tamanho da tela (ow/oh); sem giro, o pad recoloca o
            // conteúdo redimensionado no centro da tela. O overlay só entra fora do centro.
            var tail = current;
            if (t.Scale != 1) tail += $",scale=w='max(2,trunc(iw*({D(t.Scale)})/2)*2)':h='max(2,trunc(ih*({D(t.Scale)})/2)*2)'";
            if (t.MirrorX) tail += ",hflip";
            if (t.MirrorY) tail += ",vflip";
            if (t.Rotation != 0) tail += $",rotate=a={D(t.Rotation*Math.PI/180)}:ow={w}:oh={h}:fillcolor=black@0.0";
            else if (t.Scale != 1) tail += $",pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black@0";
            if (t.X == .5 && t.Y == .5)
            {
                parts.Add($"{tail},format=rgba");
                return string.Join(";", parts);
            }
            parts.Add($"{tail}[{label}z]");
            parts.Add($"color=c=black@0:s={w}x{h}:r={fps},format=rgba[{label}b]");
            parts.Add($"[{label}b][{label}z]overlay=x={D((t.X-.5)*w)}:y={D((t.Y-.5)*h)}:shortest=1,format=rgba");
            return string.Join(";", parts);
        }
        var m=Inverse(Projection(t,w,h));
        if (m is null) return "format=rgba,colorchannelmixer=aa=0";
        var u="(X+0.5)/W";var v="(Y+0.5)/H";var prefix="";
        if(t.Keyframes.Count>0) {
            var time=offset!=0?$"(T+{D(offset)})":"T";
            var ax=StudioAnimation.Expression(t,"x",time);var ay=StudioAnimation.Expression(t,"y",time);var az=StudioAnimation.Expression(t,"escala",time);
            prefix=$"st(2,{ax});st(3,{ay});st(4,{D(t.Scale)}/({az}));st(5,({u}-ld(2))*ld(4)+{D(t.X)});st(6,({v}-ld(3))*ld(4)+{D(t.Y)});";
            u="ld(5)";v="ld(6)";
        }
        string Term(int j) => $"({D(m[j])}*{u}+{D(m[j+1])}*{v}+{D(m[j+2])})";
        var animated=t.Keyframes.Count>0;var divisor=animated?"ld(7)":Term(6);
        if(animated) prefix+=$"st(7,{Term(6)});";
        var x=$"(W*{Term(0)}/{divisor}-0.5)"; var y=$"(H*{Term(3)}/{divisor}-0.5)";
        var color=ColorMatrix(t); string[] channels=["r","g","b"];
        var position=$"{prefix}st(0,{x});st(1,{y});";
        var rgb=channels.Select((c,i) => $"{c}='{position}{string.Join("+",channels.Select((source,j) => !animated||color[i*4+j]!=0?$"{D(color[i*4+j])}*{source}(ld(0),ld(1))":"").Where(s=>s.Length>0).DefaultIfEmpty("0"))}+{D(color[i*4+3])}'");
        var mask=t.Mask switch {
            "circulo"=>"lte(pow((ld(0)+0.5-W/2)/(min(W,H)/2),2)+pow((ld(1)+0.5-H/2)/(min(W,H)/2),2),1)",
            "oval"=>"lte(pow((ld(0)+0.5-W/2)/(W/2),2)+pow((ld(1)+0.5-H/2)/(H/2),2),1)",
            "arredondada"=>"lte(pow(max(abs(ld(0)+0.5-W/2)-(W/2-min(W,H)*0.15),0),2)+pow(max(abs(ld(1)+0.5-H/2)-(H/2-min(W,H)*0.15),0),2),pow(min(W,H)*0.15,2))",
            _=>"1"};
        var alpha=t.Mask!="nenhuma"?$"*{mask}":"";
        return $"format=gbrap,geq={string.Join(":",rgb)}:a='{position}if(between(ld(0),-0.5,W-0.5)*between(ld(1),-0.5,H-0.5),alpha(ld(0),ld(1))*{D(t.Opacity)}{alpha},0)':interpolation=bilinear,format=rgba";
    }

    /// <summary>
    /// Cor, opacidade e máscara no espaço da própria mídia (antes de girar), sem geq por pixel:
    /// mixer/LUT para a cor e um quadro de máscara + alphamerge. Devolve o trecho da cadeia que
    /// continua a entrada; os segmentos auxiliares vão para <paramref name="parts"/>.
    /// </summary>
    private static string SourceColorAndMask(StudioTransform t, int w, int h, int fps, string label, List<string> parts)
    {
        var matrix = ColorMatrix(t);
        var cross = new[] { 1, 2, 4, 6, 8, 9 }.Any(i => matrix[i] != 0);
        var current = "format=rgba";
        if (cross)
        {
            current += $",colorchannelmixer=rr={D(matrix[0])}:rg={D(matrix[1])}:rb={D(matrix[2])}:gr={D(matrix[4])}:gg={D(matrix[5])}:gb={D(matrix[6])}:br={D(matrix[8])}:bg={D(matrix[9])}:bb={D(matrix[10])}:aa={D(t.Opacity)}";
            if (matrix[3] != 0 || matrix[7] != 0 || matrix[11] != 0) current += $",lutrgb=r='clip(val+{D(matrix[3])},0,255)':g='clip(val+{D(matrix[7])},0,255)':b='clip(val+{D(matrix[11])},0,255)'";
        }
        else if (matrix[0] != 1 || matrix[5] != 1 || matrix[10] != 1 || matrix[3] != 0 || matrix[7] != 0 || matrix[11] != 0 || t.Opacity != 1)
        {
            var ganho = t.Opacity != 1 ? $":a='clip(val*{D(t.Opacity)},0,255)'" : "";
            current += $",lutrgb=r='clip({D(matrix[0])}*val+{D(matrix[3])},0,255)':g='clip({D(matrix[5])}*val+{D(matrix[7])},0,255)':b='clip({D(matrix[10])}*val+{D(matrix[11])},0,255)'{ganho}";
        }
        if (t.Mask != "nenhuma")
        {
            var expr = t.Mask switch {
                "circulo" => "lte(pow((X+0.5-W/2)/(min(W,H)/2),2)+pow((Y+0.5-H/2)/(min(W,H)/2),2),1)",
                "oval" => "lte(pow((X+0.5-W/2)/(W/2),2)+pow((Y+0.5-H/2)/(H/2),2),1)",
                _ => "lte(pow(max(abs(X+0.5-W/2)-(W/2-min(W,H)*0.15),0),2)+pow(max(abs(Y+0.5-H/2)-(H/2-min(W,H)*0.15),0),2),pow(min(W,H)*0.15,2))"
            };
            parts.Add($"{current}[{label}pre]");
            // Um quadro de máscara basta: ao fim dele, o alphamerge repete o último.
            parts.Add($"color=c=black:s={w}x{h}:r={fps}:d={D(1.0/fps)},format=gray,geq=lum='255*({expr})',format=gray[{label}m]");
            current = $"[{label}pre][{label}m]alphamerge";
        }
        return current;
    }

    private static bool IsIdentity(StudioTransform t) =>
        t.X==.5&&t.Y==.5&&t.Scale==1&&t.Rotation==0&&t.RotationX==0&&t.RotationY==0&&
        !t.MirrorX&&!t.MirrorY&&t.Opacity==1&&t.Blend=="normal"&&t.Filter=="original"&&
        t.Brightness==0&&t.Contrast==0&&t.Saturation==0&&!t.RemoveBackground&&t.Mask=="nenhuma"&&
        t.Keyframes.Count==0&&t.AnimationTime==0;

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

    public static string Background(StudioBackground? background,int w,int h,int fps,double duration,bool optimize = false)
    {
        var a=background?.Color??"#000000"; var b=background?.EndColor??a;
        static int[] Rgba(string c) => new[]{0,2,4,6}.Select(i => i==6&&c.Length==7?255:int.Parse(c.Substring(i+1,2),NumberStyles.HexNumber,CultureInfo.InvariantCulture)).ToArray();
        var start=Rgba(a); var end=Rgba(b);
        if(optimize&&start.SequenceEqual(end)) return $"color=c=black:s={w}x{h}:r={fps}:d={D(duration)},format=rgba,lutrgb=r={start[0]}:g={start[1]}:b={start[2]}:a={start[3]}[cfundo]";
        var channels=new[]{"r","g","b","a"}.Select((c,i) => $"{c}='{start[i]}+({end[i]-start[i]})*clip(((X+0.5)*W+(Y+0.5)*H)/(W*W+H*H),0,1)'");
        return $"color=c=black:s={w}x{h}:r={fps}:d={D(duration)},format=rgba,geq={string.Join(":",channels)},format=rgba[cfundo]";
    }
}
