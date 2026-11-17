using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>Um modelo de separação do pacote e as faixas que ele entrega, na ordem.</summary>
public sealed record MusicStemPackageModel(string Id, string Label, string Summary, string Mode, IReadOnlyList<string> Stems);

/// <summary>Um arquivo do pacote: o nome e as faixas separadas que ele junta.</summary>
public sealed class MusicStemGroup
{
    [JsonPropertyName("nome")] public string Name { get; set; } = "";
    [JsonPropertyName("faixas")] public List<string> Stems { get; set; } = [];
}

public sealed record MusicStemCombination(string Id, string Label, IReadOnlyList<MusicStemGroup> Groups);

/// <summary>O pedido inteiro, como viaja nas opções da conversão (o mesmo JSON do site e do Android).</summary>
/// <summary>
/// Como tratar as faixas antes de misturar, igual ao estúdio musical (lerTratamentoDoPacote em
/// musica-pacote.mjs): refinar agudos, recuperar a bateria do resíduo, reduzir ruído, restaurar
/// por instrumento (0 a 100) e usar a aceleração disponível. Tudo desligado por padrão.
/// </summary>
public sealed class MusicStemTreatment
{
    [JsonPropertyName("refinarAgudos")] public bool RefineHighs { get; set; }
    [JsonPropertyName("recuperarBateria")] public bool RecoverDrums { get; set; }
    [JsonPropertyName("reduzirRuido")] public bool ReduceNoise { get; set; }
    [JsonPropertyName("restauracao")] public int Restoration { get; set; }
    [JsonPropertyName("aceleracao")] public bool Acceleration { get; set; }

    public static MusicStemTreatment Read(JsonNode? node)
    {
        var o = node as JsonObject;
        bool Flag(string k) => o?[k] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
        var restoration = 0;
        if (o?["restauracao"] is JsonValue r && r.TryGetValue<double>(out var d) && double.IsFinite(d))
            restoration = (int)Math.Clamp(Math.Floor(d + 0.5), 0, 100);
        return new MusicStemTreatment
        {
            RefineHighs = Flag("refinarAgudos"), RecoverDrums = Flag("recuperarBateria"), ReduceNoise = Flag("reduzirRuido"),
            Restoration = restoration, Acceleration = Flag("aceleracao"),
        };
    }
}

public sealed class MusicStemPackageRequest
{
    [JsonPropertyName("versao")] public int Version { get; set; } = MusicStemPackage.Version;
    [JsonPropertyName("modelo")] public string Model { get; set; } = "spleeter";
    [JsonPropertyName("grupos")] public List<MusicStemGroup> Groups { get; set; } = [];
    [JsonPropertyName("configuracao")] public MusicProject Configuration { get; set; } = new();
    [JsonPropertyName("formato")] public string Format { get; set; } = "wav-24";
    [JsonPropertyName("master")] public string Master { get; set; } = "sem-alvo";
    [JsonPropertyName("tratamento")] public MusicStemTreatment Treatment { get; set; } = new();
    [JsonPropertyName("manter")] public string? Keep { get; set; }
}

/// <summary>
/// Separar faixas no conversor (experimental): as regras, sem áudio. Espelho de
/// packages/converter-core/musica-pacote.mjs, conferido por musica-pacote-casos.json.
/// </summary>
public static class MusicStemPackage
{
    public const int Version = 1;
    public const int MaxFiles = 12;

    private static readonly Dictionary<string, string> StemLabels = new()
    {
        ["vozes"] = "Voz", ["bateria"] = "Bateria", ["baixo"] = "Baixo",
        ["guitarra"] = "Guitarra", ["piano"] = "Piano", ["outros"] = "Outros",
    };

    public static IReadOnlyList<MusicStemPackageModel> Models { get; } =
    [
        new("spleeter", "Spleeter · 4 faixas", "Voz, bateria, baixo e outros. O mais rápido; guitarra e piano ficam em “outros”.", "4",
            ["vozes", "bateria", "baixo", "outros"]),
        new("roformer", "RoFormer · 6 faixas (experimental)", "Voz, bateria, baixo, guitarra, piano e resíduo. Bem mais lento e pede mais memória.", "6",
            ["vozes", "bateria", "baixo", "guitarra", "piano", "outros"]),
    ];

    public static MusicStemPackageModel? Model(string? id) => Models.FirstOrDefault(m => m.Id == id);

    public static string StemLabel(string id) => StemLabels.TryGetValue(id, out var label) ? label : id;

    private static List<string> Without(MusicStemPackageModel model, params string[] remove) => [.. model.Stems.Where(s => !remove.Contains(s))];

    public static IReadOnlyList<MusicStemCombination> Combinations(string modelId)
    {
        var model = Model(modelId);
        if (model is null) return [];
        var list = new List<(string Id, string Label, List<List<string>> Groups)>
        {
            ("separadas", "Cada faixa num arquivo", [.. model.Stems.Select(s => new List<string> { s })]),
            ("karaoke", "Sem a voz (playback)", [Without(model, "vozes")]),
            ("voz", "Só a voz", [["vozes"]]),
            ("voz-e-base", "Voz num arquivo, o resto no outro", [["vozes"], Without(model, "vozes")]),
            ("sem-bateria", "Sem a bateria (para tocar junto)", [Without(model, "bateria")]),
            ("sem-baixo", "Sem o baixo (para tocar junto)", [Without(model, "baixo")]),
            ("voz-baixo-e-bateria", "Voz e baixo juntos, bateria à parte", [["vozes", "baixo"], ["bateria"]]),
        };
        if (model.Stems.Contains("guitarra"))
        {
            list.Add(("sem-guitarra", "Sem a guitarra (para tocar junto)", [Without(model, "guitarra")]));
            list.Add(("sem-piano", "Sem o piano (para tocar junto)", [Without(model, "piano")]));
            list.Add(("cordas-e-ritmo", "Guitarra e baixo juntos, voz e bateria no outro", [["guitarra", "baixo"], ["vozes", "bateria"]]));
        }

        return [.. list.Select(c => new MusicStemCombination(c.Id, c.Label,
            [.. c.Groups.Select(g => new MusicStemGroup { Name = GroupName(g, model), Stems = g })]))];
    }

    public static string GroupName(IReadOnlyCollection<string> stems, MusicStemPackageModel? model)
    {
        var all = model?.Stems ?? [];
        var ordered = all.Count > 0 ? [.. all.Where(stems.Contains)] : stems.ToList();
        if (ordered.Count == 0) return "Vazio";
        if (all.Count > 0 && ordered.Count == all.Count) return "Tudo";
        var missing = all.Where(s => !stems.Contains(s)).ToList();
        if (all.Count > 2 && missing.Count == 1) return $"Sem {StemLabel(missing[0]).ToLowerInvariant()}";
        var names = ordered.Select((s, i) => i == 0 ? StemLabel(s) : StemLabel(s).ToLowerInvariant()).ToList();
        return names.Count == 1 ? names[0] : $"{string.Join(", ", names.Take(names.Count - 1))} e {names[^1]}";
    }

    /// <summary>O projeto dos ajustes: uma faixa por faixa separada, sem clipes; o master vale para todos os arquivos.</summary>
    public static MusicProject Configuration(string modelId, int sampleRate = 44100, string title = "Faixas separadas")
    {
        var model = Model(modelId) ?? throw new ConversionException("invalid_model", $"Modelo desconhecido: {modelId}");
        var project = MusicRules.NewProject("pacote", title, sampleRate);
        project.Metronome.Enabled = false;
        project.Metronome.InExport = false;
        project.Tracks = [.. model.Stems.Select((s, i) => MusicRules.NewTrack(s, StemLabel(s), i))];
        return project;
    }

    public static IReadOnlyList<string> Problems(string? modelId, IReadOnlyList<MusicStemGroup>? groups)
    {
        var model = Model(modelId);
        if (model is null) return ["Escolha o modelo de separação."];
        if (groups is null || groups.Count == 0) return ["Escolha pelo menos um arquivo para gerar."];
        if (groups.Count > MaxFiles) return [$"No máximo {MaxFiles} arquivos por pacote."];
        var problems = new List<string>();
        var names = new HashSet<string>();
        for (var i = 0; i < groups.Count; i++)
        {
            var g = groups[i];
            var label = $"Arquivo {i + 1}";
            if (g?.Stems is not { Count: > 0 }) problems.Add($"{label}: escolha pelo menos uma faixa.");
            else
            {
                var strange = g.Stems.Where(s => !model.Stems.Contains(s)).ToList();
                if (strange.Count > 0) problems.Add($"{label}: {string.Join(", ", strange)} não existe no {model.Label}.");
                if (g.Stems.Distinct().Count() != g.Stems.Count) problems.Add($"{label}: a mesma faixa aparece duas vezes.");
            }

            var name = (g?.Name ?? "").Trim().ToLowerInvariant();
            if (name.Length == 0) problems.Add($"{label}: dê um nome ao arquivo.");
            else if (names.Contains(name)) problems.Add($"{label}: já existe outro arquivo chamado “{g!.Name}”.");
            names.Add(name);
        }

        return problems;
    }

    /// <summary>O projeto que mistura um arquivo: as faixas do grupo, cada uma tocando a faixa separada inteira.</summary>
    public static MusicProject FileProject(MusicProject configuration, MusicStemGroup group, long frames)
    {
        var project = configuration.Clone();
        project.Tracks = [.. project.Tracks.Where(t => group.Stems.Contains(t.Id))];
        foreach (var t in project.Tracks)
        {
            t.Mute = t.Solo = t.Arm = t.Monitor = false;
            t.Output = "master";
            t.Sends = [];
            t.Clips = [new MusicClip { Id = $"clipe-{t.Id}", Media = t.Id, Start = 0, SourceStart = 0, Duration = frames }];
        }

        project.Buses = [];
        project.Media = [.. project.Tracks.Select(t => new MusicMedia { Id = t.Id, Name = $"{t.Id}.wav", Format = "wav", SampleRate = configuration.SampleRate, Channels = 2, Frames = frames, Bits = 32 })];
        project.Metronome.Enabled = false;
        project.Metronome.InExport = false;
        project.Loop = new MusicLoop();
        project.Punch = new MusicPunch();
        project.Markers = [];
        project.Separations = [];
        return project;
    }

    /// <summary>Como o <c>normalize("NFD").replace(/[^\w\s-]/g, "")</c> do JS: sem acento e sem pontuação, com hífens.</summary>
    private static string Slug(string text)
    {
        var clean = new StringBuilder();
        foreach (var ch in text.Normalize(NormalizationForm.FormD))
            if (char.IsAsciiLetterOrDigit(ch) || ch == '_' || ch == '-' || char.IsWhiteSpace(ch)) clean.Append(ch);
        return Regex.Replace(clean.ToString().Trim(), "\\s+", "-").ToLowerInvariant();
    }

    private static string Cut(string text, int max) => text.Length > max ? text[..max] : text;

    public static IReadOnlyList<string> FileNames(string title, IReadOnlyList<MusicStemGroup> groups, string extension)
    {
        var prefix = Cut(Slug(title), 40);
        if (prefix.Length == 0) prefix = "musica";
        var used = new HashSet<string>();
        var names = new List<string>();
        for (var i = 0; i < groups.Count; i++)
        {
            var part = Cut(Slug(groups[i].Name), 40);
            if (part.Length == 0) part = $"arquivo-{i + 1}";
            var name = $"{prefix}-{part}.{extension}";
            for (var n = 2; used.Contains(name); n++) name = $"{prefix}-{part}-{n}.{extension}";
            used.Add(name);
            names.Add(name);
        }

        return names;
    }

    public static MusicStemGroup VideoGroup(string modelId, string keep)
    {
        var model = Model(modelId) ?? throw new ConversionException("invalid_model", $"Modelo desconhecido: {modelId}");
        return keep switch
        {
            "voz" => new MusicStemGroup { Name = "Voz", Stems = ["vozes"] },
            "resto" => new MusicStemGroup { Name = "Sem voz", Stems = Without(model, "vozes") },
            _ => throw new ConversionException("invalid_keep", $"Escolha o que manter no áudio: {keep}")
        };
    }

    /// <summary>Lê e normaliza o pedido; lança <see cref="ConversionException"/> com o problema, em texto para a tela.</summary>
    public static MusicStemPackageRequest ReadRequest(string? json, string defaultModel = "spleeter", string? defaultKeep = null)
    {
        JsonObject root;
        try
        {
            root = (string.IsNullOrWhiteSpace(json) ? new JsonObject() : JsonNode.Parse(json) as JsonObject) ?? new JsonObject();
        }
        catch (JsonException)
        {
            throw new ConversionException("invalid_package", "As opções da separação estão corrompidas. Escolha os arquivos de novo.");
        }

        var modelId = root["modelo"]?.GetValue<string>() ?? defaultModel;
        var model = Model(modelId) ?? throw new ConversionException("invalid_model", "Escolha o modelo de separação.");
        var keep = root["manter"]?.GetValue<string>() ?? defaultKeep;
        var configuration = root["configuracao"] is JsonObject c && c["faixas"] is JsonArray
            ? MusicProject.Parse(c.ToJsonString())
            : Configuration(model.Id);
        if (model.Stems.Any(s => configuration.Tracks.All(t => t.Id != s)))
            throw new ConversionException("invalid_package", "Os ajustes das faixas não correspondem ao modelo escolhido.");
        var groups = root["grupos"] is JsonArray array
            ? array.Deserialize<List<MusicStemGroup>>(MusicProject.Json) ?? []
            : keep is not null ? [VideoGroup(model.Id, keep)] : [.. Combinations(model.Id)[0].Groups];
        var problems = Problems(model.Id, groups);
        if (problems.Count > 0) throw new ConversionException("invalid_package", problems[0]);
        return new MusicStemPackageRequest
        {
            Model = model.Id,
            Groups = [.. groups.Select(g => new MusicStemGroup { Name = g.Name.Trim(), Stems = [.. model.Stems.Where(g.Stems.Contains)] })],
            Configuration = configuration,
            Format = root["formato"]?.GetValue<string>() ?? "wav-24",
            Master = root["master"]?.GetValue<string>() ?? "sem-alvo",
            Treatment = MusicStemTreatment.Read(root["tratamento"]),
            Keep = keep,
        };
    }

    public static string ToJson(MusicStemPackageRequest request) => JsonSerializer.Serialize(request, MusicProject.Json);

    /// <summary>Quanto da barra cada etapa ocupa: a separação é quase tudo.</summary>
    public static double Fraction(string stage, double fraction)
    {
        var f = Math.Clamp(fraction, 0, 1);
        var (from, to) = stage switch
        {
            "lendo" => (0.0, 0.03),
            "modelo" => (0.03, 0.08),
            "separando" => (0.08, 0.8),
            "mixando" => (0.8, 0.96),
            "juntando" => (0.96, 1.0),
            _ => (0.0, 1.0)
        };
        return Math.Round((from + (to - from) * f) * 1e6) / 1e6;
    }
}
