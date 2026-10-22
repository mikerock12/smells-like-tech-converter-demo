using System.Globalization;
using SmellsLikeTech.Converter.Core.Options;
using SmellsLikeTech.Converter.Core.Studio;
using SmellsLikeTech.Converter.Infrastructure.Execution;

namespace SmellsLikeTech.Converter.Engine.FFmpeg;

/// <summary>O que so o aplicativo sabe na hora de montar: caminhos e se cada clipe tem audio.</summary>
public sealed record StudioInputs(
    IReadOnlyList<StudioClipInput> Clips,
    IReadOnlyList<string> Texts,
    IReadOnlyList<string> Audios,
    IReadOnlyList<string>? Layers = null);

public sealed record StudioClipInput(string Path, bool HasAudio);

/// <summary>Argumentos de entrada, filter_complex e mapas; os codificadores ficam por conta de quem chama.</summary>
public sealed record StudioCommand(IReadOnlyList<string> Args, string Filter, IReadOnlyList<string> Maps, double Duration);

/// <summary>
/// Receita do FFmpeg do estudio, espelho de <c>receitaFfmpeg</c> em
/// packages/converter-core/estudio.mjs: o texto do filtro tem de sair identico ao do JS e
/// ao do Android, caractere por caractere (conferido pelos casos compartilhados).
/// </summary>
public static class StudioRecipe
{
    public static string SpeedFilter(StudioClip c) => StudioRules.ClipSpeed(c)==1?"":$"setpts=(PTS-STARTPTS)/{N(StudioRules.ClipSpeed(c))},";
    public static string TempoFilter(StudioClip c) {
        var v=StudioRules.ClipSpeed(c); if(v==1) return "";
        var list=new List<string>();
        while(v>2) {list.Add("atempo=2");v/=2;}
        while(v<.5) {list.Add("atempo=0.5");v*=2;}
        list.Add($"atempo={N(v)}"); return string.Join(",",list)+",";
    }
    public static StudioCommand Build(StudioProject project, StudioInputs inputs)
    {
        if (project.Output is not ("audio" or "texto") && (StudioRules.UsesLayers(project) || StudioRules.HasTransitions(project)))
            return StudioLayerRecipe.Build(project, inputs);
        int w = project.Width, h = project.Height, fps = project.Fps;
        var total = StudioRules.TotalDuration(project);
        var withVideo = project.Output is not ("audio" or "texto");
        var withAudio = project.Output is "video" or "audio" or "texto";
        var args = new List<string>();
        var filters = new List<string>();
        var inputIndex = 0;

        for (var i = 0; i < project.Clips.Count; i++)
        {
            var clip = project.Clips[i];
            var path = inputs.Clips[i].Path;
            var duration = StudioRules.ClipDuration(clip);
            if (clip.IsImage)
            {
                args.AddRange(["-loop", "1", "-framerate", fps.ToString(CultureInfo.InvariantCulture), "-t", N(duration), "-i", path]);
            }
            else
            {
                args.AddRange(["-ss", N(clip.Start ?? 0), "-t", N(StudioRules.SourceDuration(clip)), "-i", path]);
            }

            var n = inputIndex++;
            if (withVideo)
            {
                filters.Add(ClipFilter(clip.Fit, n, i, w, h, fps).Replace($"[{n}:v]", $"[{n}:v]{SpeedFilter(clip)}"));
            }

            if (withAudio)
            {
                var hasAudio = clip.Kind == "video" && inputs.Clips[i].HasAudio && clip.Muted != true && (clip.Volume ?? 1) > 0;
                filters.Add(hasAudio
                    ? $"[{n}:a]{TempoFilter(clip)}aresample=48000,aformat=channel_layouts=stereo,volume={N(clip.Volume ?? 1)},apad=whole_dur={N(duration)},atrim=duration={N(duration)},asetpts=PTS-STARTPTS[a{i}]"
                    : $"anullsrc=r=48000:cl=stereo,atrim=duration={N(duration)},asetpts=PTS-STARTPTS[a{i}]");
            }
        }

        var pairs = string.Concat(Enumerable.Range(0, project.Clips.Count)
            .Select(i => (withVideo ? $"[v{i}]" : "") + (withAudio ? $"[a{i}]" : "")));
        if(StudioRules.HasTransitions(project) && !withVideo) {
            var previous="a0";
            for(var i=1;i<project.Clips.Count;i++) {
                var d=StudioRules.TransitionDuration(project,i-1); var next=$"ax{i}";
                filters.Add(d>0?$"[{previous}][a{i}]acrossfade=d={N(d)}:c1=tri:c2=tri[{next}]":$"[{previous}][a{i}]concat=n=2:v=0:a=1[{next}]");
                previous=next;
            }
            filters.Add($"[{previous}]anull[ab]");
        } else filters.Add($"{pairs}concat=n={project.Clips.Count}:v={(withVideo ? 1 : 0)}:a={(withAudio ? 1 : 0)}{(withVideo ? "[vb]" : "")}{(withAudio ? "[ab]" : "")}");

        var video = "vb";
        if (withVideo)
        {
            for (var j = 0; j < project.Texts.Count; j++)
            {
                var text = project.Texts[j];
                args.AddRange(["-loop", "1", "-framerate", fps.ToString(CultureInfo.InvariantCulture), "-t", N(total), "-i", inputs.Texts[j]]);
                var n = inputIndex++;
                var next = $"vt{j}";
                filters.Add($"[{video}][{n}:v]overlay=0:0:enable='{(text.Id.StartsWith("legenda-")?$"gte(t,{N(text.Start)})*lt(t,{N(text.End)})":$"between(t,{N(text.Start)},{N(text.End)})")}'[{next}]");
                video = next;
            }

            if (project.Output == "gif")
            {
                var (gw, gh) = StudioRules.GifSize(w, h);
                filters.Add($"[{video}]fps={StudioRules.GifFps},scale={gw}:{gh}:flags=lanczos,split[g0][g1];[g0]palettegen=stats_mode=diff[paleta];[g1][paleta]paletteuse=dither=bayer:bayer_scale=4[gif]");
                video = "gif";
            }
        }

        var audio = "ab";
        if (withAudio && project.Audios.Count > 0)
        {
            var labels = new List<string>();
            for (var k = 0; k < project.Audios.Count; k++)
            {
                var music = project.Audios[k];
                args.AddRange(["-ss", N(music.Start), "-t", N(music.End - music.Start), "-i", inputs.Audios[k]]);
                var n = inputIndex++;
                var length = music.End - music.Start;
                var chain = new List<string> { "aresample=48000", "aformat=channel_layouts=stereo", $"volume={N(music.Volume ?? 1)}" };
                if ((music.FadeIn ?? 0) > 0)
                {
                    chain.Add($"afade=t=in:st=0:d={N(music.FadeIn!.Value)}");
                }

                if ((music.FadeOut ?? 0) > 0)
                {
                    chain.Add($"afade=t=out:st={N(Math.Max(0, length - music.FadeOut!.Value))}:d={N(music.FadeOut.Value)}");
                }

                var delay = (long)Math.Round((music.At ?? 0) * 1000, MidpointRounding.AwayFromZero);
                if (delay > 0)
                {
                    chain.Add($"adelay={delay}:all=1");
                }

                filters.Add($"[{n}:a]{string.Join(",", chain)}[m{k}]");
                labels.Add($"[m{k}]");
            }

            filters.Add($"[ab]{string.Concat(labels)}amix=inputs={labels.Count + 1}:duration=first:normalize=0[am]");
            audio = "am";
        }

        var maps = new List<string>();
        if (withVideo)
        {
            maps.AddRange(["-map", $"[{video}]"]);
        }

        if (withAudio)
        {
            maps.AddRange(["-map", $"[{audio}]"]);
        }

        return new StudioCommand(args, string.Join(";", filters), maps, total);
    }

    /// <summary>
    /// A linha de comando inteira: receita + codificador da saida. Video em H.264 pelo mesmo
    /// caminho do conversor (NVENC/AMF/QSV quando houver, senao libx264) e AAC; GIF com a
    /// paleta da receita; imagem PNG do quadro escolhido; MP3; WAV 16 kHz mono para o Whisper.
    /// </summary>
    public static CommandSpec ToCommand(FfmpegOptions engine, StudioProject project, StudioInputs inputs, string outputPath, int quality)
    {
        var recipe = Build(project, inputs);
        var arguments = new List<string>
        {
            "-hide_banner", "-nostdin", "-v", "error", "-n",
            "-progress", "pipe:1", "-nostats",
            "-protocol_whitelist", "file,pipe"
        };
        arguments.AddRange(recipe.Args);
        arguments.AddRange(["-filter_complex", recipe.Filter]);
        arguments.AddRange(recipe.Maps);

        switch (project.Output)
        {
            case "video":
                var encoder = FfmpegCommandPlanner.SelectEncoder(VideoCodecChoice.H264, engine.Preference, engine);
                arguments.AddRange(["-c:v", encoder]);
                FfmpegCommandPlanner.AddQualityArguments(arguments, encoder, quality);
                arguments.AddRange(["-pix_fmt", "yuv420p", "-r", project.Fps.ToString(CultureInfo.InvariantCulture),
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"]);
                break;
            case "gif":
                arguments.AddRange(["-loop", "0"]);
                break;
            case "imagem":
                arguments.AddRange(["-ss", N(project.Frame), "-frames:v", "1", "-update", "1"]);
                break;
            case "audio":
                arguments.AddRange(["-c:a", "libmp3lame", "-b:a", "192k"]);
                break;
            case "texto":
                arguments.AddRange(["-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le"]);
                break;
        }

        arguments.Add(outputPath);
        return new CommandSpec(engine.FfmpegPath, arguments);
    }

    private static string ClipFilter(string fit, int n, int i, int w, int h, int fps)
    {
        var end = $"setsar=1,format=yuv420p,setpts=PTS-STARTPTS[v{i}]";
        return fit switch
        {
            "preencher" => $"[{n}:v]fps={fps},scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},{end}",
            "caber" => $"[{n}:v]fps={fps},scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black,{end}",
            _ => $"[{n}:v]fps={fps},split[f{i}][p{i}];"
                + $"[f{i}]scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},scale={StudioRules.Even(w / 8.0)}:{StudioRules.Even(h / 8.0)},gblur=sigma=3,scale={w}:{h}[fd{i}];"
                + $"[p{i}]scale={w}:{h}:force_original_aspect_ratio=decrease:force_divisible_by=2[pc{i}];"
                + $"[fd{i}][pc{i}]overlay=(W-w)/2:(H-h)/2,{end}"
        };
    }

    private static string N(double value) => StudioRules.Number(value);
}
