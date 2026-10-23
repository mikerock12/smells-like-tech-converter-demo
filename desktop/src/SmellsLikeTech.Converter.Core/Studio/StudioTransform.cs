using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Studio;

public sealed record StudioTransform
{
    [JsonPropertyName("x")] public double X { get; init; } = .5;
    [JsonPropertyName("y")] public double Y { get; init; } = .5;
    [JsonPropertyName("escala")] public double Scale { get; init; } = 1;
    [JsonPropertyName("giro")] public double Rotation { get; init; }
    [JsonPropertyName("giroX")] public double RotationX { get; init; }
    [JsonPropertyName("giroY")] public double RotationY { get; init; }
    [JsonPropertyName("espelharX")] public bool MirrorX { get; init; }
    [JsonPropertyName("espelharY")] public bool MirrorY { get; init; }
    [JsonPropertyName("opacidade")] public double Opacity { get; init; } = 1;
    [JsonPropertyName("mescla")] public string Blend { get; init; } = "normal";
    [JsonPropertyName("filtro")] public string Filter { get; init; } = "original";
    [JsonPropertyName("brilho")] public double Brightness { get; init; }
    [JsonPropertyName("contraste")] public double Contrast { get; init; }
    [JsonPropertyName("saturacao")] public double Saturation { get; init; }
    [JsonPropertyName("removerFundo")] public bool RemoveBackground { get; init; }

    [JsonPropertyName("tempoDaAnimacao")] public double AnimationTime { get; init; }
    [JsonPropertyName("mascara")] public string Mask { get; init; } = "nenhuma";
    [JsonPropertyName("quadrosChave")] public IReadOnlyList<StudioKeyframe> Keyframes { get; init; } = [];

    public string? Problem()
    {
        (string Name, double Value, double Min, double Max)[] limits =
        [
            ("x", X, -2, 3), ("y", Y, -2, 3), ("escala", Scale, .05, 6),
            ("giro", Rotation, -360, 360), ("giroX", RotationX, -180, 180), ("giroY", RotationY, -180, 180),
            ("opacidade", Opacity, 0, 1), ("brilho", Brightness, -1, 1),
            ("contraste", Contrast, -.8, 1), ("saturacao", Saturation, -1, 1)
        ];
        foreach (var (name, value, min, max) in limits)
            if (!double.IsFinite(value) || value < min || value > max) return $"Ajuste inválido da camada: {name}.";
        if (Blend is not ("normal" or "multiplicar" or "clarear" or "sobrepor")) return "Modo de mesclagem desconhecido.";
        if (Filter is not ("original" or "vivido" or "pb" or "sepia" or "vintage" or "frio" or "quente" or "negativo")) return "Filtro desconhecido.";
        return StudioAnimation.Problem(this);
    }
}

public sealed record StudioLayer
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("arquivo")] public string File { get; init; } = "";
    [JsonPropertyName("tipo")] public string Kind { get; init; } = "imagem";
    [JsonPropertyName("inicio")] public double Start { get; init; }
    [JsonPropertyName("fim")] public double End { get; init; } = 3;
    [JsonPropertyName("deslocamento")] public double Offset { get; init; }
    [JsonPropertyName("repetir")] public bool Repeat { get; init; } = true;
    [JsonPropertyName("visivel")] public bool Visible { get; init; } = true;
    [JsonPropertyName("ordem")] public int Order { get; init; } = 1;
    [JsonPropertyName("transformacao")] public StudioTransform? Transform { get; init; }
}

public sealed record StudioBackground
{
    [JsonPropertyName("cor")] public string Color { get; init; } = "#00000000";
    [JsonPropertyName("corFinal")] public string? EndColor { get; init; }
}
