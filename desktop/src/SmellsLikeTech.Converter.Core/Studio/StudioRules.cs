using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>
/// Regras do estudio, espelho de packages/converter-core/estudio.mjs. Os textos e os numeros
/// tem de sair identicos aos do JS: os casos de packages/converter-core/estudio-casos sao
/// conferidos aqui tambem (StudioTests).
/// </summary>
public static class StudioRules
{
    public const int ProjectVersion = 4;
    public const double DefaultImageDuration = 3;
    public const double MinimumDuration = 0.1;
    public const int GifShortSide = 480;
    public const int GifFps = 15;

    public static IReadOnlyList<StudioPreset> Presets { get; } = LoadPresets();

    public static IReadOnlyList<StudioOutput> Outputs { get; } =
    [
        new("video", "Vídeo", "MP4 pronto para postar, com som.", "mp4"),
        new("gif", "GIF", "Animação leve, sem som, que toca sozinha.", "gif"),
        new("imagem", "Imagem", "Um quadro em PNG: capa, post ou miniatura.", "png"),
        new("audio", "Só o áudio", "MP3 com a mistura final do som.", "mp3"),
        new("texto", "Texto", "A fala transcrita, sem enviar nada para a nuvem.", "txt")
    ];

    public static IReadOnlyList<StudioFit> Fits { get; } =
    [
        new("preencher", "Preencher", "Ocupa o quadro todo; corta as sobras."),
        new("caber", "Caber", "Mostra tudo, com faixas pretas."),
        new("caber-desfocado", "Caber com fundo desfocado", "Mostra tudo; as sobras viram a própria imagem desfocada.")
    ];

    public static IReadOnlyList<StudioFont> Fonts { get; } =
    [
        new("padrao", "Padrão", null),
        new("anton", "Impacto", "Anton-Regular.ttf"),
        new("bangers", "Quadrinho", "Bangers-Regular.ttf"),
        new("pacifico", "Cursiva", "Pacifico-Regular.ttf"),
        new("marcador", "Marcador", "PermanentMarker-Regular.ttf")
    ];

    // Layout do texto (fracoes do tamanho da letra), igual em todas as plataformas.
    public const double TextMaxWidth = 0.9;
    public const double TextLineHeight = 1.15;
    public const double TextOutline = 1.0 / 8;
    public const double TextBoxPadding = 0.3;
    public const double TextBoxRadius = 0.25;

    public static StudioPreset? FindPreset(string id) => Presets.FirstOrDefault(p => p.Id == id);

    public static StudioOutput? FindOutput(string id) => Outputs.FirstOrDefault(o => o.Id == id);

    public static StudioProject NewProject(string destination, string output = "video", int? width = null, int? height = null)
    {
        var preset = FindPreset(destination) ?? FindPreset("personalizado")!;
        return new StudioProject
        {
            Version = ProjectVersion,
            Destination = preset.Id,
            Width = Even(width ?? preset.Width),
            Height = Even(height ?? preset.Height),
            Fps = preset.Fps,
            Output = FindOutput(output) is null ? "video" : output
        };
    }

    public static double SourceDuration(StudioClip clip) => Math.Max(MinimumDuration, (clip.End ?? 0) - (clip.Start ?? 0));
    public static double ClipSpeed(StudioClip clip) => clip.IsImage || !double.IsFinite(clip.Speed) || clip.Speed<.25 || clip.Speed>4 ? 1 : clip.Speed;
    public static double TransitionDuration(StudioProject p,int i) {
        if(i<0 || i+1>=p.Clips.Count || p.Clips[i].Transition is not {} t || t.Kind=="corte") return 0;
        return Round(Math.Max(0,Math.Min(t.Duration,Math.Min(ClipDuration(p.Clips[i])/2,ClipDuration(p.Clips[i+1])/2))));
    }
    public static bool HasTransitions(StudioProject p) => p.Clips.Select((_,i)=>TransitionDuration(p,i)).Any(d=>d>0);
    public static double ClipDuration(StudioClip clip) => clip.IsImage
        ? Math.Max(MinimumDuration, clip.Duration ?? DefaultImageDuration)
        : SourceDuration(clip) / ClipSpeed(clip);

    public static IReadOnlyList<StudioSegment> Segments(StudioProject project)
    {
        var list = new List<StudioSegment>();
        var now = 0.0;
        for (var i = 0; i < project.Clips.Count; i++)
        {
            if(i>0) now-=TransitionDuration(project,i-1);
            var duration = ClipDuration(project.Clips[i]);
            list.Add(new StudioSegment(i, project.Clips[i].Id, Round(now), Round(now + duration), Round(duration)));
            now += duration;
        }

        return list;
    }

    public static double TotalDuration(StudioProject project) =>
        project.Version<4 ? Segments(project).LastOrDefault()?.End ?? 0 : Round(new[]{Segments(project).LastOrDefault()?.End ?? 0,project.Layers.Where(c=>c.Visible).Select(c=>c.End).DefaultIfEmpty(0).Max(),project.Audios.Select(a=>(a.At??0)+a.End-a.Start).DefaultIfEmpty(0).Max()}.Max());

    /// <summary>Clipe visivel no instante <paramref name="time"/> e o ponto dele no arquivo.</summary>
    public static (int Index, StudioClip Clip, double TimeInFile)? ClipAt(StudioProject project, double time)
    {
        var segments = Segments(project);
        if (segments.Count == 0)
        {
            return null;
        }

        var target = Math.Min(Math.Max(0, time), TotalDuration(project));
        var segment = segments.FirstOrDefault(s => target < s.End) ?? segments[^1];
        var clip = project.Clips[segment.Index];
        var inside = Math.Min(target - segment.Start, project.Version>=4 && target>=segment.End ? Math.Max(0,(Math.Ceiling(segment.Duration*project.Fps)-1)/project.Fps) : segment.Duration);
        return (segment.Index, clip, Round(clip.IsImage ? 0 : (clip.Start ?? 0) + inside * ClipSpeed(clip)));
    }

    /// <summary>Retangulo onde a origem e desenhada para preencher ou caber (pares, como o scale do FFmpeg).</summary>
    public static (double X, double Y, int W, int H) FitRect(string fit, double sourceWidth, double sourceHeight, int width, int height)
    {
        var scale = fit == "preencher"
            ? Math.Max(width / sourceWidth, height / sourceHeight)
            : Math.Min(width / sourceWidth, height / sourceHeight);
        var w = Even(sourceWidth * scale);
        var h = Even(sourceHeight * scale);
        return ((width - w) / 2.0, (height - h) / 2.0, w, h);
    }

    public static (int Width, int Height) GifSize(int width, int height)
    {
        var shorter = Math.Min(width, height);
        if (shorter <= GifShortSide)
        {
            return (Even(width), Even(height));
        }

        var scale = (double)GifShortSide / shorter;
        return (Even(width * scale), Even(height * scale));
    }

    /// <summary>Problemas que impedem exportar, nos mesmos textos do site e do Android.</summary>
    public static IReadOnlyList<string> Problems(StudioProject project)
    {
        var list = new List<string>();
        if (project.Version is not (1 or 2 or 3 or ProjectVersion))
        {
            list.Add("Projeto de uma versão que este app não conhece.");
        }

        if (!(project.Width >= 16 && project.Height >= 16 && project.Width <= 4096 && project.Height <= 4096))
        {
            list.Add("O tamanho do quadro precisa ficar entre 16 e 4096 pixels.");
        }

        if (FindOutput(project.Output) is null)
        {
            list.Add("Escolha o que exportar.");
        }

        if (project.Clips.Count == 0)
        {
            list.Add("Adicione pelo menos um vídeo ou imagem.");
        }

        for (var i = 0; i < project.Clips.Count; i++)
        {
            var clip = project.Clips[i];
            if (clip.Kind == "video" && !((clip.End ?? 0) - (clip.Start ?? 0) >= MinimumDuration))
            {
                list.Add($"O clipe {i + 1} termina antes de começar.");
            }

            if (!double.IsFinite(clip.Speed) || clip.Speed<.25 || clip.Speed>4) list.Add($"A velocidade do clipe {i+1} precisa ficar entre 0,25× e 4×.");
            if (clip.Transition is {} t && (t.Kind is not ("corte" or "fade" or "dissolver" or "deslizar" or "zoom") || !double.IsFinite(t.Duration) || t.Duration<.1 || t.Duration>5)) list.Add($"A transição do clipe {i+1} é inválida (use de 0,1 a 5 segundos).");
            if (!Fits.Any(f => f.Id == clip.Fit))
            {
                list.Add($"O clipe {i + 1} está sem enquadramento.");
            }
        }

        for (var i = 0; i < project.Texts.Count; i++)
        {
            var text = project.Texts[i];
            if (string.IsNullOrWhiteSpace(text.Text))
            {
                list.Add($"O texto {i + 1} está vazio.");
            }

            if (!(text.End > text.Start))
            {
                list.Add($"O texto {i + 1} termina antes de começar.");
            }
        }

        for (var i = 0; i < project.Audios.Count; i++)
        {
            var audio = project.Audios[i];
            if (!(audio.End - audio.Start >= MinimumDuration))
            {
                list.Add($"O áudio {i + 1} termina antes de começar.");
            }
        }

        if (project.Layers.Count > 12) list.Add("Use até 12 camadas adicionais.");
        for (var i = 0; i < project.Layers.Count; i++)
        {
            var layer = project.Layers[i];
            if (layer.Kind is not ("imagem" or "video" or "gif") || string.IsNullOrEmpty(layer.File)) list.Add($"A camada {i + 1} está sem mídia válida.");
            if (!double.IsFinite(layer.Start) || !double.IsFinite(layer.End) || layer.Start < 0 || layer.End <= layer.Start) list.Add($"A camada {i + 1} está com o intervalo inválido.");
            if (!double.IsFinite(layer.Offset) || layer.Offset < 0) list.Add($"A camada {i + 1} está com o início da mídia inválido.");
        }
        foreach (var transform in project.Clips.Select(c => c.Transform).Concat(project.Texts.Select(t => t.Transform)).Concat(project.Layers.Select(l => l.Transform)))
            if (transform?.Problem() is { } problem) list.Add(problem);
        static bool ValidColor(string color) => System.Text.RegularExpressions.Regex.IsMatch(color, "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");
        if (project.Background is { } background && (!ValidColor(background.Color) || (background.EndColor is not null && !ValidColor(background.EndColor)))) list.Add("Cor de fundo inválida.");
        var total = TotalDuration(project);
        if(project.Version>=4) {
            if(project.Fps<1||project.Fps>60) list.Add("Use uma taxa de 1 a 60 quadros por segundo.");
            if(!double.IsFinite(total)||total<0||total>86400) list.Add("A composição deve ter duração finita de até 24 horas.");
            if(project.Clips.Count>100||project.Texts.Count>1100||project.Audios.Count>20) list.Add("Use até 100 clipes, 1100 textos e 20 áudios.");
            if(project.Audios.Any(a=>!double.IsFinite(a.At??0)||(a.At??0)<0||!double.IsFinite(a.Start)||a.Start<0||!double.IsFinite(a.End))) list.Add("O intervalo de um áudio é inválido.");
        }
        if (project.Output == "imagem" && project.Clips.Count > 0 && !(project.Frame >= 0 && project.Frame <= total))
        {
            list.Add("Escolha um instante dentro do vídeo para a imagem.");
        }

        return list;
    }

    public static IReadOnlyList<StudioLayer> LayersAt(StudioProject project, double time) =>
        project.Layers.Where(l => l.Visible && time >= l.Start && time < l.End).OrderBy(l => l.Order).ToList();

    public static bool UsesLayers(StudioProject project) => project.Background is not null || project.Layers.Count > 0
        || project.Clips.Any(c => c.Transform is not null) || project.Texts.Any(t => t.Transform is not null);

    /// <summary>Avisos que nao impedem exportar.</summary>
    public static IReadOnlyList<string> Warnings(StudioProject project)
    {
        var list = new List<string>();
        var preset = FindPreset(project.Destination);
        var total = TotalDuration(project);
        if (preset?.MaxDuration is { } max && max > 0 && total > max && project.Output is "video" or "gif")
        {
            list.Add($"{preset.Network} · {preset.Name} costuma aceitar até {ReadableDuration(max)}; este projeto tem {ReadableDuration(total)}.");
        }

        if (project.Output == "gif" && total > 15)
        {
            list.Add("GIF com mais de 15 segundos fica pesado; considere exportar em vídeo.");
        }

        if (total > 0 && project.Texts.Any(t => t.Start >= total))
        {
            list.Add("Algum texto começa depois do fim do vídeo e não vai aparecer.");
        }

        return list;
    }

    /// <summary>Numero para o FFmpeg: ate 3 casas, sem zeros sobrando, como o JS.</summary>
    public static string Number(double value) => Round(value).ToString(CultureInfo.InvariantCulture);

    /// <summary>"1 min 30 s", "45 s".</summary>
    public static string ReadableDuration(double seconds)
    {
        var whole = (int)Math.Round(seconds, MidpointRounding.AwayFromZero);
        var minutes = whole / 60;
        var rest = whole % 60;
        if (minutes == 0)
        {
            return $"{rest} s";
        }

        return rest == 0 ? $"{minutes} min" : $"{minutes} min {rest} s";
    }

    public static double Round(double value) => Math.Round(value * 1000, MidpointRounding.AwayFromZero) / 1000;

    public static int Even(double value) => Math.Max(2, (int)Math.Round(value / 2, MidpointRounding.AwayFromZero) * 2);

    private static IReadOnlyList<StudioPreset> LoadPresets()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("estudio-presets.json")
            ?? throw new InvalidOperationException("estudio-presets.json não foi embutido no Core.");
        var data = JsonSerializer.Deserialize<PresetFile>(stream, StudioProject.Json)
            ?? throw new InvalidOperationException("estudio-presets.json vazio.");
        return data.Presets;
    }

    private sealed record PresetFile
    {
        [JsonPropertyName("presets")] public IReadOnlyList<StudioPreset> Presets { get; init; } = [];
    }
}
