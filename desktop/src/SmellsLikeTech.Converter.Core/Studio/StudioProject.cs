using System.Text.Json;
using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Studio;

/// <summary>
/// Projeto do estudio, no mesmo JSON dos quatro clientes (packages/converter-core/estudio.mjs
/// e docs/estudio.md). Os nomes do JSON ficam em portugues; as regras estao em
/// <see cref="StudioRules"/> e a receita do FFmpeg em StudioRecipe.
/// </summary>
public sealed record StudioProject
{
    [JsonPropertyName("versao")] public int Version { get; init; } = StudioRules.ProjectVersion;
    [JsonPropertyName("destino")] public string Destination { get; init; } = "personalizado";
    [JsonPropertyName("largura")] public int Width { get; init; }
    [JsonPropertyName("altura")] public int Height { get; init; }
    [JsonPropertyName("fps")] public int Fps { get; init; } = 30;
    [JsonPropertyName("saida")] public string Output { get; init; } = "video";
    [JsonPropertyName("clipes")] public IReadOnlyList<StudioClip> Clips { get; init; } = [];
    [JsonPropertyName("textos")] public IReadOnlyList<StudioText> Texts { get; init; } = [];
    [JsonPropertyName("audios")] public IReadOnlyList<StudioAudio> Audios { get; init; } = [];
    [JsonPropertyName("camadas")] public IReadOnlyList<StudioLayer> Layers { get; init; } = [];
    [JsonPropertyName("fundo")] public StudioBackground? Background { get; init; }
    /// <summary>Instante exportado quando a saida e imagem.</summary>
    [JsonPropertyName("quadro")] public double Frame { get; init; }

    public static JsonSerializerOptions Json { get; } = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        NumberHandling = JsonNumberHandling.AllowReadingFromString
    };

    public static StudioProject Parse(string json) =>
        JsonSerializer.Deserialize<StudioProject>(json, Json)
        ?? throw new ConversionException("invalid_project", "O projeto do estúdio está vazio.");

    public string ToJson() => JsonSerializer.Serialize(this, Json);
}

/// <summary>Um video ou imagem da faixa principal.</summary>
public sealed record StudioClip
{
    [JsonPropertyName("transformacao")] public StudioTransform? Transform { get; init; }
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    /// <summary>Referencia do arquivo: caminho no aplicativo, nome do envio no plugin.</summary>
    [JsonPropertyName("arquivo")] public string File { get; init; } = "";
    [JsonPropertyName("tipo")] public string Kind { get; init; } = "video";
    [JsonPropertyName("inicio")] public double? Start { get; init; }
    [JsonPropertyName("fim")] public double? End { get; init; }
    [JsonPropertyName("duracao")] public double? Duration { get; init; }
    [JsonPropertyName("ajuste")] public string Fit { get; init; } = "caber";
    [JsonPropertyName("volume")] public double? Volume { get; init; }
    [JsonPropertyName("mudo")] public bool? Muted { get; init; }

    [JsonIgnore] public bool IsImage => Kind == "imagem";
}

/// <summary>Um texto por cima do video, com tempo e posicao.</summary>
public sealed record StudioText
{
    [JsonPropertyName("transformacao")] public StudioTransform? Transform { get; init; }
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("texto")] public string Text { get; init; } = "";
    [JsonPropertyName("inicio")] public double Start { get; init; }
    [JsonPropertyName("fim")] public double End { get; init; }
    [JsonPropertyName("x")] public double X { get; init; } = 0.5;
    [JsonPropertyName("y")] public double Y { get; init; } = 0.5;
    [JsonPropertyName("tamanho")] public double Size { get; init; } = 0.06;
    [JsonPropertyName("cor")] public string Color { get; init; } = "#FFFFFF";
    [JsonPropertyName("contorno")] public string? Outline { get; init; }
    [JsonPropertyName("caixa")] public string? Box { get; init; }
    [JsonPropertyName("fonte")] public string Font { get; init; } = "padrao";
}

/// <summary>Musica ou audio extra: trecho do arquivo e ponto de entrada na linha do tempo.</summary>
public sealed record StudioAudio
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("arquivo")] public string File { get; init; } = "";
    [JsonPropertyName("entrada")] public double? At { get; init; }
    [JsonPropertyName("inicio")] public double Start { get; init; }
    [JsonPropertyName("fim")] public double End { get; init; }
    [JsonPropertyName("volume")] public double? Volume { get; init; }
    [JsonPropertyName("fadeEntrada")] public double? FadeIn { get; init; }
    [JsonPropertyName("fadeSaida")] public double? FadeOut { get; init; }
}

/// <summary>Destino pronto (rede e formato), lido de estudio-presets.json.</summary>
public sealed record StudioPreset
{
    [JsonPropertyName("id")] public string Id { get; init; } = "";
    [JsonPropertyName("rede")] public string Network { get; init; } = "";
    [JsonPropertyName("nome")] public string Name { get; init; } = "";
    [JsonPropertyName("largura")] public int Width { get; init; }
    [JsonPropertyName("altura")] public int Height { get; init; }
    [JsonPropertyName("fps")] public int Fps { get; init; }
    /// <summary>Duracao de referencia da rede, em segundos; nulo quando nao ha limite pratico.</summary>
    [JsonPropertyName("duracaoMaxima")] public double? MaxDuration { get; init; }

    [JsonIgnore] public string Title => $"{Network} · {Name}";
}

public sealed record StudioSegment(
    [property: JsonPropertyName("indice")] int Index,
    [property: JsonPropertyName("id")] string Id,
    [property: JsonPropertyName("inicio")] double Start,
    [property: JsonPropertyName("fim")] double End,
    [property: JsonPropertyName("duracao")] double Duration);

/// <summary>Uma das saidas do estudio (video, GIF, imagem, audio, texto).</summary>
public sealed record StudioOutput(string Id, string Title, string Description, string Extension);

public sealed record StudioFit(string Id, string Title, string Description);

public sealed record StudioFont(string Id, string Name, string? FileName);
