using SmellsLikeTech.Converter.Core.Studio;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>Composição RGBA, espelho de estudio-camadas.mjs.</summary>
public static class StudioLayerRecipe
{
    public static StudioCommand Build(StudioProject p,StudioInputs inputs)
    {
        var audio=StudioRecipe.Build(p with {Output="audio",Texts=[]},inputs);
        var args=audio.Args.ToList(); var filters=new List<string>{audio.Filter};
        var total=StudioRules.TotalDuration(p); var w=p.Width; var h=p.Height; var fps=p.Fps;
        var index=p.Clips.Count+p.Audios.Count; var video="cfundo"; var step=0;
        filters.Add(StudioLayerFilters.Background(p.Background,w,h,fps,total));
        void Compose(string label,StudioTransform? t,double start,double end) {
            var next=$"cc{step++}";
            filters.Add(StudioLayerFilters.Overlay(video,label,next,t?.Blend,start,end)); video=next;
        }
        var layers=p.Layers.Select((c,i)=>(C:c,I:i)).Where(v=>v.C.Visible).OrderBy(v=>v.C.Order).ToList();
        void Extra((StudioLayer C,int I) value) {
            var (c,i)=value; var duration=c.End-c.Start;
            if(c.Kind=="imagem") args.AddRange(["-loop","1","-framerate",fps.ToString(System.Globalization.CultureInfo.InvariantCulture)]);
            else {
                if(c.Repeat) args.AddRange(["-stream_loop","-1"]);
                if(c.Kind=="gif") args.AddRange(["-ignore_loop","1"]);
                args.AddRange(["-ss",N(c.Offset)]);
            }
            args.AddRange(["-t",N(duration),"-i",inputs.Layers![i]]);
            var n=index++; var label=$"ce{i}";
            filters.Add($"[{n}:v]fps={fps},format=rgba,scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black@0,setsar=1,{StudioLayerFilters.Transform(c.Transform,w,h)},tpad=stop_mode=clone:stop_duration={N(duration)},trim=duration={N(duration)},setpts=PTS-STARTPTS+{N(c.Start)}/TB[{label}]");
            Compose(label,c.Transform,c.Start,c.End);
        }
        foreach(var layer in layers.Where(v=>v.C.Order<0)) Extra(layer);
        var start=0.0;
        for(var i=0;i<p.Clips.Count;i++) {
            var c=p.Clips[i]; var duration=StudioRules.ClipDuration(c); var label=$"cp{i}";
            var end=$"setsar=1,{StudioLayerFilters.Transform(c.Transform,w,h)},setpts=PTS-STARTPTS+{N(start)}/TB[{label}]";
            if(c.Fit=="preencher") filters.Add($"[{i}:v]fps={fps},format=rgba,scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},{end}");
            else if(c.Fit=="caber") filters.Add($"[{i}:v]fps={fps},format=rgba,scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black@0,{end}");
            else filters.Add($"[{i}:v]fps={fps},format=rgba,split[cb{i}][cf{i}];[cb{i}]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},scale={StudioRules.Even(w/8.0)}:{StudioRules.Even(h/8.0)},gblur=sigma=3,scale={w}:{h}[cblur{i}];[cf{i}]scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2[cfit{i}];[cblur{i}][cfit{i}]overlay=(W-w)/2:(H-h)/2:format=auto,{end}");
            Compose(label,c.Transform,start,start+duration); start+=duration;
        }
        foreach(var layer in layers.Where(v=>v.C.Order>=0)) Extra(layer);
        for(var i=0;i<p.Texts.Count;i++) {
            var t=p.Texts[i];
            args.AddRange(["-loop","1","-framerate",fps.ToString(System.Globalization.CultureInfo.InvariantCulture),"-t",N(total),"-i",inputs.Texts[i]]);
            var n=index++; var label=$"ct{i}";
            filters.Add($"[{n}:v]{StudioLayerFilters.Transform(t.Transform,w,h)}[{label}]");
            Compose(label,t.Transform,t.Start,t.End+1.0/fps);
        }
        if(p.Output=="gif") {
            var(gw,gh)=StudioRules.GifSize(w,h);
            filters.Add($"[{video}]fps={StudioRules.GifFps},scale={gw}:{gh}:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]"); video="gif";
        }
        var maps=new List<string>{"-map",$"[{video}]"};
        if(p.Output=="video") maps.AddRange(audio.Maps); else filters.Add($"{audio.Maps[1]}anullsink");
        return new StudioCommand(args,string.Join(";",filters),maps,total);
    }
    private static string N(double value)=>StudioRules.Number(value);
}
