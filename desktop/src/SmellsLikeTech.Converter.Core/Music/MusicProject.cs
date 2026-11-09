using System.Text.Json;
using System.Text.Json.Serialization;

namespace SmellsLikeTech.Converter.Core.Music;

/// <summary>
/// Projeto da criacao musical (.sltmusic), no mesmo JSON dos quatro clientes
/// (packages/converter-core/musica.mjs). Mutavel de proposito: a tela edita faixa a faixa e o
/// mixer le o projeto inteiro de novo a cada mudanca. Posicoes e duracoes em quadros.
/// </summary>
public sealed class MusicProject
{
    [JsonPropertyName("versao")] public int Version { get; set; } = MusicRules.ProjectVersion;
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("titulo")] public string Title { get; set; } = "Nova música";
    [JsonPropertyName("taxa")] public int SampleRate { get; set; } = MusicRules.DefaultSampleRate;
    [JsonPropertyName("profundidade")] public int BitDepth { get; set; } = 24;
    [JsonPropertyName("bpm")] public double Bpm { get; set; } = 120;
    [JsonPropertyName("compasso")] public int[] Meter { get; set; } = [4, 4];
    [JsonPropertyName("faixas")] public List<MusicTrack> Tracks { get; set; } = [];
    [JsonPropertyName("barramentos")] public List<MusicBus> Buses { get; set; } = [];
    [JsonPropertyName("master")] public MusicMaster Master { get; set; } = new();
    [JsonPropertyName("marcadores")] public List<MusicMarker> Markers { get; set; } = [];
    [JsonPropertyName("midias")] public List<MusicMedia> Media { get; set; } = [];
    [JsonPropertyName("metronomo")] public MusicMetronome Metronome { get; set; } = new();
    [JsonPropertyName("contagem")] public double CountIn { get; set; } = 1;
    [JsonPropertyName("latencia")] public MusicLatency Latency { get; set; } = new();
    [JsonPropertyName("loop")] public MusicLoop Loop { get; set; } = new();
    [JsonPropertyName("punch")] public MusicPunch Punch { get; set; } = new();
    [JsonPropertyName("separacoes")] public List<MusicSeparationRecord> Separations { get; set; } = [];

    public static JsonSerializerOptions Json { get; } = new()
    {
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = false
    };

    public static MusicProject Parse(string json) =>
        JsonSerializer.Deserialize<MusicProject>(json, Json)
        ?? throw new ConversionException("invalid_project", "O projeto musical está vazio.");

    public string ToJson() => JsonSerializer.Serialize(this, Json);

    public MusicProject Clone() => Parse(ToJson());
}

public sealed class MusicTrack
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("nome")] public string Name { get; set; } = "";
    [JsonPropertyName("cor")] public string? Color { get; set; }
    [JsonPropertyName("ganhoDb")] public double GainDb { get; set; }
    [JsonPropertyName("pan")] public double Pan { get; set; }
    [JsonPropertyName("ajusteEsquerdoDb")] public double LeftTrimDb { get; set; }
    [JsonPropertyName("ajusteDireitoDb")] public double RightTrimDb { get; set; }
    [JsonPropertyName("gravar")] public bool Arm { get; set; }
    [JsonPropertyName("monitorar")] public bool Monitor { get; set; }
    [JsonPropertyName("mudo")] public bool Mute { get; set; }
    [JsonPropertyName("solo")] public bool Solo { get; set; }
    [JsonPropertyName("entrada")] public string? Input { get; set; }
    [JsonPropertyName("canaisDeEntrada")] public int InputChannels { get; set; } = 1;
    [JsonPropertyName("saida")] public string Output { get; set; } = "master";
    [JsonPropertyName("efeitos")] public List<MusicEffect> Effects { get; set; } = [];
    [JsonPropertyName("envios")] public List<MusicSend> Sends { get; set; } = [];
    [JsonPropertyName("automacao")] public MusicAutomation Automation { get; set; } = new();
    [JsonPropertyName("clipes")] public List<MusicClip> Clips { get; set; } = [];
    [JsonPropertyName("oculta")] public bool? Hidden { get; set; }
    [JsonPropertyName("congelada")] public bool? Frozen { get; set; }
}

public sealed class MusicClip
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("midia")] public string Media { get; set; } = "";
    [JsonPropertyName("inicio")] public long Start { get; set; }
    [JsonPropertyName("inicioNaMidia")] public long SourceStart { get; set; }
    [JsonPropertyName("duracao")] public long Duration { get; set; }
    [JsonPropertyName("ganhoDb")] public double GainDb { get; set; }
    [JsonPropertyName("entradaSuave")] public long FadeIn { get; set; }
    [JsonPropertyName("saidaSuave")] public long FadeOut { get; set; }
    [JsonPropertyName("mudo")] public bool Mute { get; set; }
    [JsonPropertyName("nome")] public string? Name { get; set; }

    public long End => Start + Duration;
    public MusicClip Copy() => (MusicClip)MemberwiseClone();
}

public sealed class MusicSend
{
    [JsonPropertyName("barramento")] public string Bus { get; set; } = "";
    [JsonPropertyName("nivelDb")] public double LevelDb { get; set; }
    [JsonPropertyName("preFader")] public bool PreFader { get; set; }
}

public sealed class MusicAutomationPoint
{
    [JsonPropertyName("quadro")] public long Frame { get; set; }
    [JsonPropertyName("valor")] public double Value { get; set; }
}

public sealed class MusicAutomation
{
    [JsonPropertyName("volume")] public List<MusicAutomationPoint> Volume { get; set; } = [];
    [JsonPropertyName("pan")] public List<MusicAutomationPoint> Pan { get; set; } = [];
}

/// <summary>Efeito: tipo, ligado e os parametros numericos pelo nome do catalogo.</summary>
public sealed class MusicEffect
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("tipo")] public string Kind { get; set; } = "";
    [JsonPropertyName("ligado")] public bool? Enabled { get; set; }
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; set; }

    public bool IsEnabled => Enabled == true;

    /// <summary>Parametro numerico presente no JSON (null quando falta ou nao e numero).</summary>
    public double? Raw(string id) =>
        Extra is not null && Extra.TryGetValue(id, out var value) && value.ValueKind == JsonValueKind.Number ? value.GetDouble() : null;

    public bool Has(string id) => Extra is not null && Extra.ContainsKey(id);

    public bool IsNumber(string id) => Extra is not null && Extra.TryGetValue(id, out var value) && value.ValueKind == JsonValueKind.Number;

    public void Set(string id, double value)
    {
        Extra ??= [];
        Extra[id] = JsonSerializer.SerializeToElement(value);
    }

    public MusicEffect Copy() => new() { Id = Id, Kind = Kind, Enabled = Enabled, Extra = Extra is null ? null : new Dictionary<string, JsonElement>(Extra) };
}

public sealed class MusicBus
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("nome")] public string Name { get; set; } = "";
    [JsonPropertyName("ganhoDb")] public double GainDb { get; set; }
    [JsonPropertyName("pan")] public double Pan { get; set; }
    [JsonPropertyName("mudo")] public bool Mute { get; set; }
    [JsonPropertyName("efeitos")] public List<MusicEffect> Effects { get; set; } = [];
}

public sealed class MusicMaster
{
    [JsonPropertyName("ganhoDb")] public double GainDb { get; set; }
    [JsonPropertyName("efeitos")] public List<MusicEffect> Effects { get; set; } = [];
}

public sealed class MusicMarker
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("quadro")] public long Frame { get; set; }
    [JsonPropertyName("nome")] public string Name { get; set; } = "";
}

public sealed class MusicMedia
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("nome")] public string Name { get; set; } = "";
    [JsonPropertyName("formato")] public string Format { get; set; } = "wav";
    [JsonPropertyName("taxa")] public int SampleRate { get; set; }
    [JsonPropertyName("canais")] public int Channels { get; set; }
    [JsonPropertyName("quadros")] public long Frames { get; set; }
    [JsonPropertyName("bits")] public int Bits { get; set; } = 32;
    [JsonPropertyName("sha256")] public string? Sha256 { get; set; }
    [JsonPropertyName("origem")] public string? Origin { get; set; }
    [JsonPropertyName("instrumento")] public string? Instrument { get; set; }
    [JsonPropertyName("modelo")] public string? Model { get; set; }
    [JsonPropertyName("versaoDoModelo")] public string? ModelVersion { get; set; }
}

public sealed class MusicMetronome
{
    [JsonPropertyName("ligado")] public bool Enabled { get; set; } = true;
    [JsonPropertyName("volumeDb")] public double VolumeDb { get; set; } = -6;
    [JsonPropertyName("naExportacao")] public bool InExport { get; set; }
}

public sealed class MusicLatency
{
    [JsonPropertyName("entrada")] public double Input { get; set; }
    [JsonPropertyName("saida")] public double Output { get; set; }
    [JsonPropertyName("medida")] public bool Measured { get; set; }
    [JsonPropertyName("ajuste")] public double? Adjust { get; set; }
}

public sealed class MusicLoop
{
    [JsonPropertyName("ligado")] public bool Enabled { get; set; }
    [JsonPropertyName("inicio")] public long Start { get; set; }
    [JsonPropertyName("fim")] public long End { get; set; }
}

public sealed class MusicPunch
{
    [JsonPropertyName("ligado")] public bool Enabled { get; set; }
    [JsonPropertyName("entrada")] public long In { get; set; }
    [JsonPropertyName("saida")] public long Out { get; set; }
}

public sealed class MusicSeparationRecord
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("midia")] public string Media { get; set; } = "";
    [JsonPropertyName("modo")] public string Mode { get; set; } = "4";
    [JsonPropertyName("modelo")] public string Model { get; set; } = "";
    [JsonPropertyName("faixas")] public List<string> Tracks { get; set; } = [];
}
