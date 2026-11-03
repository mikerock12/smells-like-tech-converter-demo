namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>
/// As três qualidades da exportação e a prévia leve. Espelho de estudio-qualidade.mjs e de
/// Qualidade.kt: a baixa reduz o quadro para até 720p e codifica depressa; a média mantém o
/// tamanho do projeto com codificação rápida; a alta mantém o tamanho com a codificação mais
/// caprichada. A prévia é só para ter uma ideia: lado maior de 480 px e no máximo 12 quadros/s.
/// <see cref="MaskFps"/> diz quantas máscaras de fundo por segundo de vídeo o modelo calcula
/// (os quadros do meio misturam as vizinhas); 0 é uma por quadro.
/// </summary>
public sealed record StudioQuality(string Id, string Title, string Description, int MaxSide, int MaxFps, int VideoQuality, string Preset, double EncodeCost, int MaskFps)
{
    public static readonly StudioQuality Preview = new("previa", "Prévia", "Só para ter uma ideia dos efeitos.", 480, 12, 40, "ultrafast", .35, 2);

    public static readonly IReadOnlyList<StudioQuality> Levels =
    [
        new("baixa", "Baixa", "Até 720p, a mais rápida e leve.", 1280, 60, 55, "veryfast", .55, 10),
        new("media", "Média", "Tamanho do projeto, codificação rápida.", 4096, 60, 70, "faster", .75, 15),
        new("alta", "Alta", "Tamanho do projeto, melhor acabamento.", 4096, 60, 82, "medium", 1.0, 0)
    ];

    public static StudioQuality Find(string? id) => Levels.FirstOrDefault(q => q.Id == id) ?? Levels[^1];

    /// <summary>O projeto no tamanho e fps desta qualidade (o lado maior nunca passa de MaxSide).</summary>
    public StudioProject Apply(StudioProject project)
    {
        var scale = Math.Min(1.0, (double)MaxSide / Math.Max(project.Width, project.Height));
        return project with
        {
            Width = StudioRules.Even(project.Width * scale),
            Height = StudioRules.Even(project.Height * scale),
            Fps = Math.Min(project.Fps, MaxFps)
        };
    }

    /// <summary>De quantos em quantos quadros o modelo calcula uma máscara nova.</summary>
    public int MaskEvery(int fps) => MaskFps <= 0 ? 1 : Math.Max(1, (int)Math.Round((double)fps / MaskFps));
}

/// <summary>
/// Quanto esta máquina leva para compor um megapixel-quadro e para calcular uma máscara de
/// fundo. Começa com o que foi medido num PC de 4 núcleos (03/11/2026, projeto real de 1 min em
/// 1080p com giro 3D e remoção de fundo) e se ajusta a cada prévia ou exportação concluída,
/// para o tempo estimado acompanhar o computador do usuário.
/// </summary>
public sealed record StudioSpeed(double SecondsPerMegapixelFrame, double SecondsPerMask)
{
    public static readonly StudioSpeed Default = new(.03, .37);

    /// <summary>Ler e decodificar o vídeo de origem custa o mesmo para qualquer tamanho de saída.</summary>
    public const double SecondsPerFrame = .015;

    /// <summary>Mistura a medida nova com a anterior, sem deixar uma medição estranha dominar.</summary>
    public StudioSpeed Learn(double? megapixelFrame, double? mask) => new(
        megapixelFrame is > 0 and < 10 ? (SecondsPerMegapixelFrame * .4) + (megapixelFrame.Value * .6) : SecondsPerMegapixelFrame,
        mask is > 0 and < 30 ? (SecondsPerMask * .4) + (mask.Value * .6) : SecondsPerMask);
}

public static class StudioEstimate
{
    /// <summary>Quadros de vídeo que passam pelo recorte do fundo (o trecho inteiro de cada mídia).</summary>
    public static double RemovalFrames(StudioProject project) =>
        project.Output is "audio" or "texto" ? 0 :
        project.Clips.Where(c => !c.IsImage && c.Transform?.RemoveBackground == true).Sum(c => Math.Ceiling(StudioRules.SourceDuration(c) * project.Fps)) +
        project.Layers.Where(l => l.Visible && l.Kind != "imagem" && l.Transform?.RemoveBackground == true).Sum(l => Math.Ceiling((l.Offset + l.End - l.Start) * project.Fps));

    /// <summary>Peso relativo da composição: cada camada, fundo desfocado e transição pesa mais.</summary>
    public static double CompositionWeight(StudioProject project) =>
        1 + (project.Layers.Count(l => l.Visible) * .35) + (project.Clips.Count(c => c.Fit == "caber-desfocado") * .25 / Math.Max(1, project.Clips.Count))
        + (project.Texts.Count > 0 ? .1 : 0) + (StudioRules.HasTransitions(project) ? .2 : 0);

    /// <summary>Tempo estimado para montar o projeto na qualidade dada (a prévia inclusive).</summary>
    public static TimeSpan Estimate(StudioProject project, StudioQuality quality, StudioSpeed speed)
    {
        var sized = quality.Apply(project);
        if (sized.Output is "audio" or "texto")
        {
            return TimeSpan.FromSeconds(Math.Max(2, StudioRules.TotalDuration(sized) * .05));
        }

        var frames = sized.Output == "imagem" ? 1 : StudioRules.TotalDuration(sized) * sized.Fps;
        var megapixels = sized.Width * (double)sized.Height / 1e6;
        var composition = frames * (StudioSpeed.SecondsPerFrame + (megapixels * speed.SecondsPerMegapixelFrame * CompositionWeight(sized) * quality.EncodeCost));
        // Ler, aplicar a máscara e gravar cada quadro custa pouco perto de calcular a máscara.
        var removed = RemovalFrames(sized);
        var removal = (Math.Ceiling(removed / quality.MaskEvery(sized.Fps)) * speed.SecondsPerMask) + (removed * .01 * Math.Clamp(megapixels / 2.07, .35, 1.5));
        return TimeSpan.FromSeconds(Math.Max(1, composition + removal + 1.5));
    }

    /// <summary>"cerca de 40 s", "cerca de 3 min", "cerca de 1 h 10 min".</summary>
    public static string Readable(TimeSpan time)
    {
        var seconds = Math.Max(1, (int)Math.Round(time.TotalSeconds));
        if (seconds < 60) return $"cerca de {Math.Max(5, (int)Math.Ceiling(seconds / 5.0) * 5)} s";
        var minutes = (int)Math.Ceiling(seconds / 60.0);
        return minutes < 60 ? $"cerca de {minutes} min" : $"cerca de {minutes / 60} h {minutes % 60:00} min";
    }
}
