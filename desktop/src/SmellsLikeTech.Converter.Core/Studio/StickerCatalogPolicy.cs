using System.Globalization;

namespace SmellsLikeTech.Converter.Core.Studio;

public sealed record StickerCatalogState(IReadOnlyList<long> Attempts, long RetryAt = 0, string? Reason = null, int Failures = 0);

/// <summary>Cota beta local; HTTP 429 continua sendo a autoridade para a chave compartilhada.</summary>
public static class StickerCatalogPolicy
{
    public const long Hour = 3_600_000;
    public static StickerCatalogState Prepare(StickerCatalogState state,long now)
    {
        // O estado vem de um JSON local: um arquivo antigo ou editado pode não ter a lista.
        var recent=(state.Attempts??[]).Where(v=>v>now-Hour&&v<=now).ToArray();
        var current=state with {Attempts=recent};
        if(current.RetryAt>now) return current;
        return recent.Length>=100?current with {RetryAt=recent.Min()+Hour,Reason="limite"}:current with {RetryAt=0,Reason=null};
    }
    public static StickerCatalogState Response(StickerCatalogState state,int status,long now,string? retryAfter=null)
    {
        if(status==200) return state with {RetryAt=0,Reason=null,Failures=0};
        var failures=Math.Clamp(state.Failures+1,1,100); var factor=1L<<Math.Min(failures-1,4);
        var wait=status switch {429=>RetryDelay(retryAfter,now,Math.Min(60_000*factor,900_000)),401 or 403=>Hour,_=>Math.Min(30_000*factor,300_000)};
        return state with {RetryAt=now+wait,Reason=status==429?"limite":"indisponivel",Failures=failures};
    }
    public static long RetryDelay(string? value,long now,long fallback=60_000)
    {
        if(double.TryParse(value,NumberStyles.AllowDecimalPoint,CultureInfo.InvariantCulture,out var seconds)&&double.IsFinite(seconds)&&seconds>=0&&seconds<86_400*365)
            return Math.Max(1000,(long)(seconds*1000));
        if(DateTimeOffset.TryParse(value,CultureInfo.InvariantCulture,DateTimeStyles.AssumeUniversal,out var date)&&date.ToUnixTimeMilliseconds()>now)
            return date.ToUnixTimeMilliseconds()-now;
        return fallback;
    }
    public static string? Notice(string? reason)=>reason switch {
        "limite"=>"O GIPHY atingiu o limite de consultas. Mostrando Google Noto por enquanto. O GIPHY voltará automaticamente quando estiver disponível.",
        "indisponivel"=>"O GIPHY está indisponível. Mostrando Google Noto enquanto tentamos restabelecer a busca.",_=>null};
}
