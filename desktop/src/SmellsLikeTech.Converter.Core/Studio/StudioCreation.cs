namespace SmellsLikeTech.Converter.Core.Studio;

public static class StudioCreation
{
    public static (string Id,string Title)[] Templates { get; }=[("abertura","Título de abertura"),("fotos","Fotos em movimento"),("cartao","Cartão social")];
    public static StudioProject Apply(StudioProject p,string id)
    {
        p=Upgrade(p);
        if(!Templates.Any(t=>t.Id==id)||p.Clips.Count==0) throw new ConversionException("studio_template","Adicione uma mídia e escolha um modelo.");
        if(id=="fotos") return p with {Version=4,Clips=p.Clips.Select(c=>c.IsImage?c with {Transform=StudioAnimation.KenBurns(c.Transform,StudioRules.ClipDuration(c))}:c).ToList()};
        var title=new StudioText {Id="modelo-titulo",Text=id=="cartao"?"Sua mensagem aqui":"Seu título aqui",Start=0,End=Math.Min(StudioRules.TotalDuration(p),3),X=.5,Y=id=="cartao"?.85:.2,Size=.075,Color="#FFFFFF",Outline="#000000",Box="#000000B3",Font="anton"};
        return p with {Version=4,Background=id=="cartao"?new StudioBackground {Color="#7536E8",EndColor="#FF7A18"}:p.Background,
            Clips=id=="cartao"?p.Clips.Select(c=>c with {Transform=(c.Transform??new()) with {Scale=.78,Y=.42}}).ToList():p.Clips,
            Texts=[..p.Texts.Where(t=>t.Id!="modelo-titulo"),title]};
    }
    public static StudioProject Countdown(StudioProject p,double start=0,int count=3)
    {
        p=Upgrade(p);
        if(!double.IsFinite(start)||start<0||count<1||count>10||start+count>StudioRules.TotalDuration(p)+.001)
            throw new ConversionException("studio_countdown","A contagem deve ter de 1 a 10 segundos e caber na linha do tempo.");
        var texts=Enumerable.Range(0,count).Select(i=>new StudioText {Id=$"contagem-{i}",Text=(count-i).ToString(System.Globalization.CultureInfo.InvariantCulture),Start=start+i,End=start+i+1,X=.5,Y=.5,Size=.25,Color="#FFFFFF",Outline="#000000",Font="anton"});
        return p with {Version=4,Texts=[..p.Texts.Where(t=>!t.Id.StartsWith("contagem-")),..texts]};
    }
    public static StudioProject SplitClip(StudioProject p,string id,double time,string newId)
    {
        var c=p.Clips.FirstOrDefault(c=>c.Id==id);var segment=StudioRules.Segments(p).FirstOrDefault(s=>s.Id==id);
        if(c is null||segment is null||p.Clips.Count>=100) throw new ConversionException("studio_split","Selecione um clipe e use até 100 clipes.");
        var local=time-segment.Start;var duration=StudioRules.ClipDuration(c);
        if(local<=.05||local>=duration-.05) throw new ConversionException("studio_split","Posicione o cursor dentro do clipe para dividir.");
        if(StudioRules.HasTransitions(p)) throw new ConversionException("studio_split","Troque as transições por corte seco antes de dividir o clipe.");
        var transform=c.Transform is {Keyframes.Count:>0} t?t with {AnimationTime=t.AnimationTime+local}:c.Transform;
        var cut=(c.Start??0)+local*StudioRules.ClipSpeed(c);
        var first=c.IsImage?c with {Duration=local,Transition=null}:c with {End=cut,Transition=null};
        var second=c.IsImage?c with {Id=newId,Duration=duration-local,Transform=transform}:c with {Id=newId,Start=cut,Transform=transform};
        return p with {Clips=p.Clips.SelectMany(a=>a.Id==id?new[]{first,second}:new[]{a}).ToList()};
    }
    public static StudioProject SplitLayer(StudioProject p,string id,double time,string newId)
    {
        p=Upgrade(p);
        var c=p.Layers.FirstOrDefault(c=>c.Id==id);
        if(c is null||time<=c.Start+.05||time>=c.End-.05||p.Layers.Count>=12) throw new ConversionException("studio_split","Posicione o cursor dentro da faixa e use até 12 camadas.");
        var local=time-c.Start;
        var transform=c.Transform is {Keyframes.Count:>0} t?t with {AnimationTime=t.AnimationTime+local}:c.Transform;
        return p with {Version=4,Layers=p.Layers.SelectMany(a=>a.Id==id?new[]{c with {End=time},c with {Id=newId,Start=time,Offset=c.Offset+(c.Kind=="imagem"?0:local),Transform=transform}}:new[]{a}).ToList()};
    }
    public static StudioProject Upgrade(StudioProject p)
    {
        if(p.Version>=4) return p;
        var total=StudioRules.TotalDuration(p);
        return p with {Version=4,Layers=p.Layers.Where(c=>c.Start<total).Select(c=>c with {End=Math.Min(total,c.End)}).ToList(),
            Audios=p.Audios.Where(a=>(a.At??0)<total).Select(a=>a with {End=Math.Min(a.End,a.Start+total-(a.At??0))}).ToList()};
    }
    public static IReadOnlyList<string> Files(StudioProject p)=>p.Clips.Select(c=>c.File).Concat(p.Audios.Select(c=>c.File)).Concat(p.Layers.Select(c=>c.File)).Distinct(StringComparer.Ordinal).ToList();
    public static StudioProject MapFiles(StudioProject p,Func<string,string> map)=>p with {Clips=p.Clips.Select(c=>c with {File=map(c.File)}).ToList(),Audios=p.Audios.Select(c=>c with {File=map(c.File)}).ToList(),Layers=p.Layers.Select(c=>c with {File=map(c.File)}).ToList()};
}
