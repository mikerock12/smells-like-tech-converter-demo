using System.Globalization;
using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Studio;

public sealed record StudioKeyframe(
    [property: JsonPropertyName("tempo")] double Time,
    [property: JsonPropertyName("x")] double X,
    [property: JsonPropertyName("y")] double Y,
    [property: JsonPropertyName("escala")] double Scale,
    [property: JsonPropertyName("curva")] string Curve = "linear");

/// <summary>Posição e zoom após a perspectiva, em segundos locais da mídia.</summary>
public static class StudioAnimation
{
    public static string? Problem(StudioTransform t)
    {
        if(!double.IsFinite(t.AnimationTime)||t.AnimationTime<0||t.AnimationTime>86400) return "Tempo da animação inválido.";
        if(t.Mask is not ("nenhuma" or "circulo" or "oval" or "arredondada")) return "Máscara desconhecida.";
        if(t.Keyframes.Count>100) return "Use até 100 quadros-chave por camada.";
        var previous=-1.0;
        foreach(var q in t.Keyframes) {
            if(!double.IsFinite(q.Time)||q.Time<0||q.Time<=previous||!double.IsFinite(q.X)||q.X< -2||q.X>3
                ||!double.IsFinite(q.Y)||q.Y< -2||q.Y>3||!double.IsFinite(q.Scale)||q.Scale<.05||q.Scale>6
                ||q.Curve is not ("linear" or "suave")) return "Quadros-chave inválidos: use tempos crescentes e posição/zoom dentro dos limites.";
            previous=q.Time;
        }
        return null;
    }

    public static (double X,double Y,double Scale) At(StudioTransform t,double time)
    {
        time+=t.AnimationTime;
        if(t.Keyframes.Count==0) return(t.X,t.Y,t.Scale);
        var a=t.Keyframes[0]; if(time<=a.Time) return(a.X,a.Y,a.Scale);
        for(var i=1;i<t.Keyframes.Count;i++) {
            var b=t.Keyframes[i];
            if(time<b.Time) {
                var p=(time-a.Time)/(b.Time-a.Time);if(a.Curve=="suave") p=p*p*(3-2*p);
                return(a.X+(b.X-a.X)*p,a.Y+(b.Y-a.Y)*p,a.Scale+(b.Scale-a.Scale)*p);
            }
            a=b;
        }
        return(a.X,a.Y,a.Scale);
    }

    public static string Expression(StudioTransform t,string field,string time="T")
    {
        static string N(double v)=>(Math.Floor(v*1e9+.5)/1e9).ToString("0.#########",CultureInfo.InvariantCulture);
        if(t.AnimationTime!=0) time=$"({time}+{N(t.AnimationTime)})";
        double Value(StudioKeyframe q)=>field switch {"x"=>q.X,"y"=>q.Y,_=>q.Scale};
        if(t.Keyframes.Count==0) return N(field switch {"x"=>t.X,"y"=>t.Y,_=>t.Scale});
        var result=N(Value(t.Keyframes[^1]));
        for(var i=t.Keyframes.Count-2;i>=0;i--) {
            var a=t.Keyframes[i];var b=t.Keyframes[i+1];
            var p=$"clip(({time}-{N(a.Time)})/{N(b.Time-a.Time)},0,1)";
            var curve=a.Curve=="suave"?$"({p}*{p}*(3-2*{p}))":p;
            result=$"if(lt({time},{N(b.Time)}),{N(Value(a))}+({N(Value(b)-Value(a))})*{curve},{result})";
        }
        return result;
    }

    public static StudioTransform ChangeBase(StudioTransform before,StudioTransform after)
    {
        if(before.Keyframes.Count==0) return after;
        var dx=after.X-before.X;var dy=after.Y-before.Y;var z=after.Scale/before.Scale;
        if(dx==0&&dy==0&&z==1) return after;
        return after with {Keyframes=before.Keyframes.Select(q=>q with {X=Math.Clamp(q.X+dx,-2,3),Y=Math.Clamp(q.Y+dy,-2,3),Scale=Math.Clamp(q.Scale*z,.05,6)}).ToList()};
    }

    public static StudioTransform KenBurns(StudioTransform? value,double duration,bool reverse=false)
    {
        var t=value??new();var a=new StudioKeyframe(0,t.X,t.Y,t.Scale,"suave");
        var b=a with {Time=Math.Max(.1,duration),Scale=Math.Min(6,t.Scale*1.2)};
        return t with {AnimationTime=0,Keyframes=reverse?[b with {Time=0},a with {Time=b.Time}]:[a,b]};
    }
}
